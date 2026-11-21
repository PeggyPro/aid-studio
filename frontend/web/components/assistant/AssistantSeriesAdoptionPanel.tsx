'use client'

import { useEffect, useRef, useState } from 'react'
import { Button } from 'antd'
import type { AssistantArtifactSummary } from '~/types/assistant-artifact'
import type { AssistantSessionStore } from '~/utils/assistant/assistantSessionStore'
import type { AssistantSeriesEditorAdapter, PendingSeriesAdoption, SeriesCapabilities, SeriesCapabilityRequest, SeriesMode, SeriesReceipt } from '../../types/assistant-series'
import { seriesCommitFromPreview, verifySeriesCapabilities } from '../../utils/assistant/assistantSeriesAdoption'
import { assistantArtifactTitle } from '~/utils/assistant/assistantArtifacts'
import { assistantCommandId, assistantErrorMessage } from '~/utils/assistant/assistantRequestCoordinator'
import { AssistantSeriesPreview } from './AssistantSeriesPreview'

/** A project-scoped write confirmation, independent of the Agent's dynamic creative questions. */
export function AssistantSeriesAdoptionPanel({ store, artifacts, adapter }: {
  store: AssistantSessionStore; artifacts: AssistantArtifactSummary[]; adapter: AssistantSeriesEditorAdapter
}) {
  const [mode, setMode] = useState<SeriesMode>('APPEND_NEW'), [artifactId, setArtifact] = useState(''), [keyword, setKeyword] = useState('')
  const [pairs, setPairs] = useState<{ artifactId: string; episodeId: string }[]>([])
  const [episodes, setEpisodes] = useState<{ episodeId: string; episodeNo: number; title: string }[]>([]), [nextPage, setNextPage] = useState<number | null>(1)
  const [preview, setPreview] = useState<SeriesCapabilities | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null), [receipt, setReceipt] = useState<SeriesReceipt | null>(null)
  const alive = useRef(true), generation = useRef(0), readPending = useRef(false), preparing = useRef(false), confirming = useRef(false)
  const prepared = useRef<{ request: SeriesCapabilityRequest; assertUnchanged: () => void; sessionId: string } | null>(null)
  const applied = useRef('')
  const state = store.getSnapshot(), snapshot = state.snapshot
  const validScope = store.api.transport.kind === 'MAIN' && store.scope.moduleCode === 'SCREENWRITER' && store.scope.episodeId === '0'
    && store.scope.projectId === adapter.projectId && snapshot?.projectId === adapter.projectId && snapshot.episodeId === '0'
  const candidates = artifacts.filter(item => item.status === 'FINALIZED' && item.artifactType === 'SCREENPLAY')
  const blocked = busy || !state.writable || state.busy || !!state.pendingCommand || !validScope
  useEffect(() => { alive.current = true; const requestGeneration = generation; return () => { alive.current = false; requestGeneration.current++; prepared.current = null } }, [store, adapter])
  useEffect(() => {
    const result = state.lastCommandResult
    if (result?.action !== 'adoptSeries' || applied.current === result.body.clientRequestId) return
    applied.current = String(result.body.clientRequestId)
    const value = result.result as SeriesReceipt
    let active = true
    void adapter.applied(value).then(message => { if (active) { setReceipt(value); setNotice(message || '整批采用已保存。'); setPreview(null); prepared.current = null } })
      .catch(cause => { if (active) { setReceipt(value); setError('整批已保存，当前编辑器读取未完成：' + assistantErrorMessage(cause)) } })
    return () => { active = false }
  }, [state.lastCommandResult, adapter])
  const invalidate = () => { generation.current++; prepared.current = null; setPreview(null); setError(null) }
  const loadEpisodes = async (reset = false) => {
    if (readPending.current || !validScope || (!reset && !nextPage)) return
    readPending.current = true
    const current = generation.current, sessionId = snapshot?.sessionId
    try {
      const page = await adapter.episodes(reset ? 1 : nextPage!)
      if (!alive.current || current !== generation.current || store.getSnapshot().snapshot?.sessionId !== sessionId) return
      setEpisodes(previous => [...new Map([...(reset ? [] : previous), ...page.items].map(item => [item.episodeId, item])).values()]); setNextPage(page.nextPage)
    } catch (cause) { if (alive.current && current === generation.current) setError(assistantErrorMessage(cause)) }
    finally { readPending.current = false }
  }
  const requestPreview = async () => {
    if (!snapshot || blocked || preparing.current) return
    preparing.current = true; setBusy(true); setError(null); setPreview(null)
    const current = ++generation.current, sessionId = snapshot.sessionId
    const request: SeriesCapabilityRequest = mode === 'APPEND_NEW' ? { sessionId, mode, artifactId, episodeKeyword: keyword }
      : { sessionId, mode, items: structuredClone(pairs) }
    try {
      // Only artifact identities exposed by the current session's authoritative artifact page are selectable.
      const selected = mode === 'APPEND_NEW' ? [artifactId] : pairs.map(item => item.artifactId)
      if (!selected.length || selected.some(id => !candidates.some(item => item.artifactId === id))) throw new Error('请先选择此会话已完成的剧本产物')
      const editor = await adapter.prepare(request)
      const value = await store.api.seriesCapabilities(request)
      editor.assertUnchanged()
      if (!alive.current || current !== generation.current || store.getSnapshot().snapshot?.sessionId !== sessionId) return
      const scope = { actorId: store.actorId, sessionId, projectId: adapter.projectId, episodeId: '0' as const, moduleCode: 'SCREENWRITER' as const }
      const checked = verifySeriesCapabilities(value, request, scope)
      if (checked.items.some(item => item.episodeId && editor.targetRevisions[item.episodeId] && editor.targetRevisions[item.episodeId] !== item.currentTargetRevision)) throw new Error('当前编辑器的保存版本已变化，请核对后重新预览')
      prepared.current = { request, assertUnchanged: editor.assertUnchanged, sessionId }; setPreview(checked)
    } catch (cause) { if (alive.current && current === generation.current) setError(assistantErrorMessage(cause)) }
    finally { preparing.current = false; if (alive.current) setBusy(false) }
  }
  const confirm = async () => {
    const ready = prepared.current
    if (!preview || !ready || blocked || confirming.current) return
    confirming.current = true
    try {
      if (store.getSnapshot().snapshot?.sessionId !== ready.sessionId) throw new Error('会话已切换')
      ready.assertUnchanged()
      const scope = { actorId: store.actorId, sessionId: ready.sessionId, projectId: adapter.projectId, episodeId: '0' as const, moduleCode: 'SCREENWRITER' as const }
      const operation: PendingSeriesAdoption = { schemaVersion: 1, scope, preview, preparedRequest: ready.request, request: seriesCommitFromPreview(scope, preview, assistantCommandId()) }
      const accepted = await store.execute('adoptSeries', { sessionId: scope.sessionId, clientRequestId: operation.request.clientRequestId, seriesOperation: operation })
      if (!accepted) throw new Error(store.getSnapshot().error || '整批采用结果待核实，请使用原操作核实入口')
    } catch (cause) {
      if (alive.current) setError(assistantErrorMessage(cause))
      throw cause
    } finally { confirming.current = false; if (alive.current) { prepared.current = null; setPreview(null) } }
  }
  if (!validScope) return null
  return <details className="assistant-card flex flex-col gap-3" aria-label="项目剧本整批采用">
    <summary className="min-h-11 cursor-pointer py-3 text-sm font-medium">采用到多个剧集</summary>
    <p className="text-xs leading-5">当前为整个项目的编剧会话。先选择已完成产物并预览，再显式确认整批写入。创作问题仍由助手决定。</p>
    <label className="my-2 flex min-h-11 flex-col gap-1 text-sm">采用方式<select className="assistant-field min-h-11 rounded border bg-transparent p-2" value={mode} disabled={blocked} onChange={event => { invalidate(); setMode(event.target.value as SeriesMode) }}>
      <option value="APPEND_NEW">追加新集（最多 200 集）</option><option value="UPDATE_EXISTING">更新选定剧集（最多 100 集）</option>
    </select></label>
    {!candidates.length ? <p className="text-xs">此轮尚无可采用的已完成剧本产物；可加载更多产物或选择历史轮次。</p> : null}
    {mode === 'APPEND_NEW' ? <div className="flex flex-col gap-2">
      <label className="text-sm">整季剧本<select aria-label="整季剧本产物" className="assistant-field min-h-11 w-full rounded border bg-transparent p-2" value={artifactId} disabled={blocked} onChange={event => { invalidate(); setArtifact(event.target.value) }}><option value="">请选择产物</option>{candidates.map(item => <option key={item.artifactId} value={item.artifactId}>{assistantArtifactTitle(item)} · 版本 {item.revision}</option>)}</select></label>
      <label className="text-sm">原文分集标题标记（可选）<input aria-label="原文分集标题标记" maxLength={100} value={keyword} placeholder="留空按服务端规则识别；也可填写原文标题标记" disabled={blocked} onChange={event => { invalidate(); setKeyword(event.target.value) }} className="assistant-field min-h-11 w-full rounded border bg-transparent p-2" /></label>
    </div> : <div className="flex flex-col gap-2">
      <Button disabled={blocked} onClick={() => void loadEpisodes(true)}>读取当前项目剧集</Button>
      {pairs.map((pair, index) => <div key={index} className="flex flex-col gap-1 rounded border p-2">
        <label className="text-xs">第 {index + 1} 个产物<select aria-label={'更新产物 ' + (index + 1)} className="assistant-field min-h-11 w-full bg-transparent" value={pair.artifactId} disabled={blocked} onChange={event => { invalidate(); setPairs(previous => previous.map((row, at) => at === index ? { ...row, artifactId: event.target.value } : row)) }}><option value="">请选择产物</option>{candidates.map(item => <option key={item.artifactId} value={item.artifactId}>{assistantArtifactTitle(item)} · 版本 {item.revision}</option>)}</select></label>
        <label className="text-xs">明确更新目标<select aria-label={'更新目标 ' + (index + 1)} className="assistant-field min-h-11 w-full bg-transparent" value={pair.episodeId} disabled={blocked} onChange={event => { invalidate(); setPairs(previous => previous.map((row, at) => at === index ? { ...row, episodeId: event.target.value } : row)) }}><option value="">请选择剧集</option>{episodes.map(item => <option key={item.episodeId} value={item.episodeId}>第 {item.episodeNo} 集 · {item.title}</option>)}</select></label>
        <Button disabled={blocked} onClick={() => { invalidate(); setPairs(previous => previous.filter((_, at) => at !== index)) }}>移除此目标</Button>
      </div>)}
      <Button disabled={blocked || pairs.length >= 100} onClick={() => { invalidate(); setPairs(previous => [...previous, { artifactId: '', episodeId: '' }]) }}>添加更新目标</Button>
      {nextPage && episodes.length ? <Button disabled={blocked} onClick={() => void loadEpisodes()}>更多项目剧集</Button> : null}
    </div>}
    <Button className="my-3" disabled={blocked || !candidates.length} loading={busy} onClick={() => void requestPreview()}>预览完整批次</Button>
    {preview ? <AssistantSeriesPreview preview={preview} pending={blocked} onConfirm={confirm} onRepreview={invalidate} /> : null}
    {notice ? <p role="status" className="text-xs">{notice}</p> : null}
    {receipt ? <details><summary>已保存的整批回执 · {receipt.items.length} 集</summary><ol>{receipt.items.map(item => <li key={item.adoptionId}>第 {item.episodeNo} 集 · 已保存新版本</li>)}</ol></details> : null}
    {state.pendingCommand?.action === 'adoptSeries' ? <p role="status" className="text-xs">整批采用待核实。请使用对话底部的原操作核实入口；不会重新拆分或更换操作标识。</p> : null}
    {error ? <p role="alert" className="text-xs">{error}</p> : null}
  </details>
}
