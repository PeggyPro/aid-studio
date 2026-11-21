'use client'

import { useEffect, useRef, useState } from 'react'
import { Button, Modal } from 'antd'
import type { AssistantArtifactChunk, AssistantArtifactSummary, AssistantSourceManifest, AssistantSourceRevision } from '~/types/assistant-artifact'
import type { AssistantApi } from '~/utils/business/assistant'
import type { AssistantSessionStore } from '~/utils/assistant/assistantSessionStore'
import { mergeAssistantSourceContent } from '~/utils/assistant/assistantArtifacts'
import { assistantErrorMessage } from '~/utils/assistant/assistantRequestCoordinator'
import { AssistantArtifactReader } from './AssistantArtifactReader'
import { AssistantSourceCoverage } from './AssistantSourceCoverage'

function sourceTitle(source: AssistantSourceRevision): string {
  return ({ SCRIPT: '剧本原文', DOCUMENT: '参考文档原文', FREE_CANVAS: '画布原文', FLOW_ENTITY: '创作素材原文' } as Record<string, string>)[source.sourceKind] || '引用原文'
}

/** 列表发现与分片读取均按真实 run 的冻结 manifest 授权，不搜索全账号。 */
export function AssistantSourcesPanel({ store, runIds, eventSeq }: { store: AssistantSessionStore; runIds: string[]; eventSeq: number }) {
  const [selected, setSelected] = useState('')
  const runId = runIds.includes(selected) ? selected : runIds[0] || ''
  const [result, setResult] = useState<{ runId: string; items: AssistantSourceRevision[]; cursor: string | null } | null>(null)
  const [manifest, setManifest] = useState<AssistantSourceManifest | null>(null)
  const [manifestError, setManifestError] = useState<string | null>(null)
  const [opened, setOpened] = useState<{ runId: string; source: AssistantSourceRevision } | null>(null)
  const [evidence, setEvidence] = useState<AssistantArtifactSummary | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [reload, setReload] = useState(0)
  const generation = useRef(0)
  const morePending = useRef(false)
  useEffect(() => {
    const current = ++generation.current
    morePending.current = false
    if (!runId) return
    void Promise.allSettled([store.api.sourceManifest(runId), store.api.sourcePage(runId)]).then(([references, sources]) => {
      if (generation.current !== current) return
      if (references.status === 'fulfilled') { setManifest(references.value); setManifestError(null) }
      else { setManifest(null); setManifestError(assistantErrorMessage(references.reason)) }
      if (sources.status === 'fulfilled') { setResult({ runId, items: sources.value.items, cursor: sources.value.nextCursor }); setError(null) }
      else setError(assistantErrorMessage(sources.reason))
      setBusy(false)
    })
    return () => { generation.current += 1 }
  }, [store, runId, eventSeq, reload])
  const more = async () => {
    if (!result?.cursor || result.runId !== runId || morePending.current) return
    const current = generation.current
    morePending.current = true; setBusy(true)
    try {
      const page = await store.api.sourcePage(runId, result.cursor)
      if (generation.current !== current) return
      if (page.nextCursor === result.cursor) throw new Error('原文列表分页未前进')
      setResult({ runId, items: [...new Map([...result.items, ...page.items].map((item) => [item.sourceRevisionId, item])).values()], cursor: page.nextCursor }); setError(null)
    } catch (cause) { if (generation.current === current) setError(assistantErrorMessage(cause)) }
    finally { if (generation.current === current) { morePending.current = false; setBusy(false) } }
  }
  const readEvidence = async (artifactId: string) => {
    const current = generation.current
    try {
      const artifact = await store.api.artifactDetail(artifactId)
      if (generation.current === current) setEvidence(artifact)
    } catch (cause) { if (generation.current === current) setError(assistantErrorMessage(cause)) }
  }
  if (!runId) return null
  const items = result?.runId === runId ? result.items : []
  const references = manifest?.runId === runId ? manifest : null
  if (!items.length && !references?.items.length && !error && !manifestError && runIds.length < 2) return null
  return <section aria-label="引用原文" className="flex min-w-0 flex-col gap-3">
    <div className="flex flex-wrap items-center gap-2"><strong className="text-sm">引用原文</strong><Button size="small" onClick={() => setReload((value) => value + 1)}>刷新原文列表</Button></div>
    {runIds.length > 1 ? <details><summary className="cursor-pointer text-xs">选择原文所属轮次</summary><div className="flex flex-wrap gap-2">{runIds.map((id, index) => <Button key={id} size="small" type={id === runId ? 'primary' : 'default'} onClick={() => { setSelected(id); setOpened(null); setEvidence(null) }}>{index === 0 ? '最近轮次' : '历史轮次 ' + index}</Button>)}</div></details> : null}
    {manifestError ? <p role="alert" className="text-xs">原文引用暂未恢复：{manifestError}</p> : null}
    {references?.items.length ? <section aria-label="固定原文版本" className="flex flex-col gap-2">
      <p className="text-xs">本轮使用以下固定版本。编辑器后续修改不会自动替换本轮引用。</p>
      {references.items.map((item, index) => <div key={(item.documentId || item.scriptId) + ':' + item.version + ':' + index} className="rounded-lg border border-[var(--studio-ui-border,rgba(255,255,255,0.2))] p-3">
        <strong className="text-sm">{item.kind === 'DOCUMENT' ? '参考文档原文' : '剧本原文'} · 版本 {item.version}{item.selection ? ' · 含引用选段' : ''}</strong>
        <p role="status" className="text-xs">{item.snapshotStatus === 'ACTIVE' ? '已保存原文快照，可在下方阅读正文及覆盖进度。' : '已固定引用，等待主助手导入原文。尚不能阅读快照，不表示主助手已读完。'}</p>
      </div>)}
      {references.allowedActions.includes('REFRESH_CONTEXT') && store.getSnapshot().snapshot?.activeRun?.runId === runId ? <p className="text-xs">需要改用当前编辑内容时，请使用上方“更新引用内容”，保存草稿并重新核对预算后再继续。</p> : null}
    </section> : null}
    {error ? <p role="alert" className="text-xs">{error}</p> : null}
    {items.map((source) => <article key={source.sourceRevisionId} className="assistant-card assistant-card-compact flex flex-col gap-2 rounded-xl border border-[var(--studio-ui-border,rgba(255,255,255,0.2))]">
      <strong className="text-sm">{sourceTitle(source)}</strong><p className="text-xs">固定版本 · {Number.isSafeInteger(source.charCount) ? source.charCount.toLocaleString() + ' 字符' : '长度待核验'} · {source.chunkCount} 个片段</p>
      <Button size="small" onClick={() => setOpened({ runId, source })}>阅读原文与覆盖进度</Button>
    </article>)}
    {!items.length && !references?.items.length && !error && !manifestError ? <p className="text-xs">此轮尚无已导入的授权原文。</p> : null}
    {result?.runId === runId && result.cursor ? <Button loading={busy} onClick={() => void more()}>更多原文</Button> : null}
    {opened ? <Modal open title={sourceTitle(opened.source)} width={960} footer={null} onCancel={() => setOpened(null)} destroyOnHidden className="assistant-artifact-dialog" styles={{ body: { maxHeight: '78dvh', overflow: 'auto' } }}>
      <SourceDocument key={opened.runId + ':' + opened.source.sourceRevisionId} runId={opened.runId} source={opened.source} api={store.api} onReadEvidence={(id) => void readEvidence(id)} />
    </Modal> : null}
    {evidence ? <AssistantArtifactReader key={evidence.artifactId} artifact={evidence} api={store.api} store={store} onClose={() => setEvidence(null)} /> : null}
  </section>
}

function SourceDocument({ runId, source, api, onReadEvidence }: { runId: string; source: AssistantSourceRevision; api: AssistantApi; onReadEvidence: (id: string) => void }) {
  const [content, setContent] = useState<{ chunks: AssistantArtifactChunk[]; nextAfterChunkIndex: number; complete: boolean }>({ chunks: [], nextAfterChunkIndex: -1, complete: false })
  const [busy, setBusy] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const alive = useRef(true)
  const pending = useRef(false)
  useEffect(() => {
    alive.current = true
    let active = true
    void api.sourceContent(runId, source.sourceRevisionId).then((page) => mergeAssistantSourceContent(source, [], page))
      .then((value) => { if (active) setContent(value) })
      .catch((cause) => { if (active) setError(assistantErrorMessage(cause)) })
      .finally(() => { if (active) setBusy(false) })
    return () => { active = false; alive.current = false }
  }, [api, runId, source])
  const more = async () => {
    if (busy || pending.current || content.complete) return
    pending.current = true; setBusy(true); setError(null)
    try {
      const page = await api.sourceContent(runId, source.sourceRevisionId, content.nextAfterChunkIndex)
      const value = await mergeAssistantSourceContent(source, content.chunks, page)
      if (!alive.current) return
      if (!value.complete && value.nextAfterChunkIndex <= content.nextAfterChunkIndex) throw new Error('尚无新的完整原文分片，请稍后重试')
      setContent(value)
    } catch (cause) { if (alive.current) setError(assistantErrorMessage(cause)) }
    finally { pending.current = false; if (alive.current) setBusy(false) }
  }
  return <div className="assistant-panel assistant-artifact-reader flex min-w-0 flex-col gap-3">
    <p role="status" className="text-sm">{content.complete ? '原文全文已读取并校验通过' : '已读取 ' + content.chunks.length + ' / ' + source.chunkCount + ' 个原文片段'}</p>
    <p className="text-xs">在此阅读原文不会计入主智能体的阅读或采用进度。</p>
    {error ? <p role="alert" className="text-xs">{error}</p> : null}
    <section aria-label="原文正文" tabIndex={0} className="assistant-artifact-body rounded-xl border border-[var(--studio-ui-border,rgba(255,255,255,0.2))]">
      {content.chunks.map((chunk) => <pre key={chunk.chunkIndex} className="whitespace-pre-wrap break-words font-sans text-sm leading-6">{chunk.content}</pre>)}
    </section>
    {!content.complete ? <Button loading={busy} onClick={() => void more()}>{error ? '重试读取原文' : '继续读取原文'}</Button> : null}
    <AssistantSourceCoverage source={source} runId={runId} api={api} onReadEvidence={onReadEvidence} />
  </div>
}
