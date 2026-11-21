'use client'

import { useEffect, useRef, useState } from 'react'
import { Button } from 'antd'
import type { AssistantApi } from '~/utils/business/assistant'
import type { AssistantSourceCoverage as Coverage, AssistantSourceRef } from '~/types/assistant-artifact'
import { assistantErrorMessage } from '~/utils/assistant/assistantRequestCoordinator'

export function AssistantSourceCoverage({ source, runId, api, onReadEvidence }: {
  source: Pick<AssistantSourceRef, 'sourceRevisionId'> & { purpose?: string };  runId: string; api: AssistantApi; onReadEvidence: (artifactId: string) => void
}) {
  const alive = useRef(true)
  const pending = useRef(false)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  const [value, setValue] = useState<Coverage | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const read = async (more = false) => {
    if (pending.current) return
    pending.current = true; setBusy(true); setError(null)
    try {
      const page = await api.artifactCoverage(runId, source.sourceRevisionId, more ? value?.nextCursor || undefined : undefined)
      if (!alive.current) return
      if (page.sourceRevisionId !== source.sourceRevisionId || !Number.isSafeInteger(page.coveredChunks) || !Number.isSafeInteger(page.totalChunks)
        || page.coveredChunks < 0 || page.totalChunks < page.coveredChunks) throw new Error('原文覆盖进度返回无效')
      if (more && page.nextCursor && page.nextCursor === value?.nextCursor) throw new Error('覆盖记录分页未前进')
      if (page.items.some((item) => item.sourceRevisionId !== source.sourceRevisionId)) throw new Error('覆盖记录不属于此原文版本')
      setValue({ ...page, items: more && value ? [...new Map([...value.items, ...page.items].map((item) => [item.coverageId, item])).values()] : page.items })
    } catch (cause) { if (alive.current) setError(assistantErrorMessage(cause)) }
    finally { pending.current = false; if (alive.current) setBusy(false) }
  }
  return <section className="assistant-card assistant-card-compact flex flex-col gap-2 rounded-lg border border-[var(--studio-ui-border,rgba(255,255,255,0.15))]" aria-label="原文覆盖进度">
    <span className="text-xs">固定原文版本{source.purpose ? ' · ' + source.purpose : ''}</span>
    <Button size="small" loading={busy} onClick={() => void read()}>{value ? '刷新原文覆盖进度' : '查看原文覆盖进度'}</Button>
    {value ? <>
      <p role="status" className="text-xs">已读取或采用 {value.coveredChunks} / {value.totalChunks} 个原文片段；未覆盖片段不视为已经审阅。</p>
      <progress value={value.coveredChunks} max={Math.max(1, value.totalChunks)} aria-label="原文覆盖比例" />
      {value.items.map((item) => <article key={item.coverageId} className="text-xs leading-5"><p>片段 {item.chunkIndex + 1} · {item.purpose}{item.notes ? ' · ' + item.notes : ''}</p>{item.evidenceArtifactId ? <Button size="small" onClick={() => onReadEvidence(item.evidenceArtifactId!)}>阅读覆盖证据</Button> : null}</article>)}
      {value.nextCursor ? <Button size="small" disabled={busy} onClick={() => void read(true)}>更多覆盖记录</Button> : null}
    </> : null}
    {error ? <p role="alert" className="text-xs">{error}</p> : null}
  </section>
}
