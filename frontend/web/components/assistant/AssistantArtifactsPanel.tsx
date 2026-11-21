'use client'

import { useEffect, useRef, useState } from 'react'
import { Button } from 'antd'
import type { AssistantSessionStore } from '~/utils/assistant/assistantSessionStore'
import type { AssistantAdoptionAdapter, AssistantArtifactSummary } from '~/types/assistant-artifact'
import { assistantArtifactLabel, assistantArtifactTitle, isAssistantMediaArtifact } from '~/utils/assistant/assistantArtifacts'
import { assistantErrorMessage } from '~/utils/assistant/assistantRequestCoordinator'
import { assistantArtifactIsDeliverable } from '~/utils/assistant/assistantArtifactPresentation'
import { AssistantArtifactReader } from './AssistantArtifactReader'
import type { AssistantEffectHandler } from '~/types/assistant-effects'
import { AssistantEffectAction } from './AssistantEffectAction'
import type { AssistantSeriesEditorAdapter } from '~/types/assistant-series'
import { AssistantSeriesAdoptionPanel } from './AssistantSeriesAdoptionPanel'

/** 仅查询本会话中已出现的真实runId，不能无范围读取整账号产物冒充当前对话。 */
export function AssistantArtifactsPanel({ store, runIds, eventSeq, adoptionAdapter, seriesAdapter, onLocateTarget }: { store: AssistantSessionStore; runIds: string[]; eventSeq: number; adoptionAdapter?: AssistantAdoptionAdapter; seriesAdapter?: AssistantSeriesEditorAdapter; onLocateTarget?: AssistantEffectHandler }) {
  const [selected, setSelected] = useState('')
  const selectedRun = runIds.includes(selected) ? selected : runIds[0] || ''
  const [result, setResult] = useState<{ runId: string; items: AssistantArtifactSummary[]; cursor: string | null } | null>(null)
  const [opened, setOpened] = useState<AssistantArtifactSummary | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [reload, setReload] = useState(0)
  const generation = useRef(0)
  const morePending = useRef(false)
  useEffect(() => {
    const current = ++generation.current
    morePending.current = false
    if (!selectedRun) return
    void store.api.artifactPage({ runId: selectedRun }, true).then((page) => {
      if (generation.current !== current) return
      if (page.items.some((item) => item.producerRunId !== selectedRun)) throw new Error('产物不属于当前轮次')
      setResult((previous) => ({ runId: selectedRun,
        items: previous?.runId === selectedRun ? [...new Map([...previous.items, ...page.items].map((item) => [item.artifactId, item])).values()] : page.items,
        cursor: previous?.runId === selectedRun && previous.items.length > page.items.length ? previous.cursor : page.nextCursor }))
      setError(null)
    }).catch((cause) => { if (generation.current === current) setError(assistantErrorMessage(cause)) })
      .finally(() => { if (generation.current === current) setLoading(false) })
    return () => { generation.current += 1 }
  }, [store, selectedRun, eventSeq, reload])
  const loadMore = async () => {
    if (!result?.cursor || result.runId !== selectedRun || morePending.current) return
    morePending.current = true; setLoading(true)
    const current = generation.current
    try {
      const page = await store.api.artifactPage({ runId: selectedRun, cursor: result.cursor })
      if (generation.current !== current) return
      if (page.nextCursor === result.cursor) throw new Error('产物分页游标未前进')
      if (page.items.some((item) => item.producerRunId !== selectedRun)) throw new Error('产物不属于当前轮次')
      setResult({ runId: selectedRun, items: [...new Map([...result.items, ...page.items].map((item) => [item.artifactId, item])).values()], cursor: page.nextCursor })
    } catch (cause) { if (generation.current === current) setError(assistantErrorMessage(cause)) }
    finally { if (generation.current === current) { morePending.current = false; setLoading(false) } }
  }
  if (!selectedRun) return null
  const items = result?.runId === selectedRun ? result.items.filter(assistantArtifactIsDeliverable) : []
  if (!items.length && !error && runIds.length < 2 && !(result?.runId === selectedRun && result.cursor)) return null
  return <section className="flex min-w-0 flex-col gap-3" aria-label="对话产物">
    <div className="flex flex-wrap items-center gap-2"><strong className="text-sm">产物</strong><Button size="small" onClick={() => setReload((value) => value + 1)}>刷新产物</Button></div>
    {runIds.length > 1 ? <details><summary className="cursor-pointer text-xs">选择当前或历史轮次</summary><div className="flex flex-wrap gap-2">{runIds.map((id, index) => <Button key={id} size="small" type={id === selectedRun ? 'primary' : 'default'} onClick={() => setSelected(id)}>{index === 0 ? '最近轮次' : '历史轮次 ' + index}</Button>)}</div></details> : null}
    {error ? <p role="alert" className="text-xs">{error}</p> : null}
    {items.map((artifact) => <article key={artifact.artifactId} className="assistant-card assistant-card-compact flex flex-col gap-2 rounded-xl border border-[var(--studio-ui-border,rgba(255,255,255,0.2))]">
      <strong className="break-words text-sm">{assistantArtifactTitle(artifact)}</strong>
      <span className="text-xs">{assistantArtifactLabel(artifact.artifactType)} · 版本 {artifact.revision} · {assistantArtifactLabel(artifact.status)} · 评审 {assistantArtifactLabel(artifact.reviewStatus)}</span>
      {artifact.summary ? <p className="whitespace-pre-wrap break-words text-sm leading-6">{artifact.summary}</p> : null}
      <Button size="small" onClick={() => setOpened(artifact)}>{isAssistantMediaArtifact(artifact) ? '查看媒体与评审' : '阅读全文与评审'}</Button>
      <AssistantEffectAction key={artifact.latestTargetEffect?.effectId || 'no-effect'} effect={artifact.latestTargetEffect} onLocateTarget={onLocateTarget} />
    </article>)}
    {!items.length && !error ? <p className="text-xs">{result?.runId === selectedRun && result.cursor ? '本页暂无可展示成果，可继续查看。' : '此轮暂无已保存产物。'}</p> : null}
    {result?.runId === selectedRun && result.cursor ? <Button loading={loading} onClick={() => void loadMore()}>更多产物</Button> : null}
    {seriesAdapter ? <AssistantSeriesAdoptionPanel key={'series:' + selectedRun} store={store} artifacts={items} adapter={seriesAdapter} /> : null}
    {opened ? <AssistantArtifactReader key={opened.artifactId} artifact={opened} api={store.api} store={store} adoptionAdapter={adoptionAdapter} onClose={() => setOpened(null)} /> : null}
  </section>
}
