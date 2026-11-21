'use client'

import { useEffect, useRef, useState } from 'react'
import type { SeriesCapabilities } from '../../types/assistant-series'

/** System confirmation UI. It never chooses Agent questions, episode order, or creative workflow. */
export function AssistantSeriesPreview({ preview, pending, onConfirm, onRepreview }: {
  preview: SeriesCapabilities
  pending: boolean
  onConfirm: () => Promise<void>
  onRepreview: () => void
}) {
  const [acceptedDigest, setAcceptedDigest] = useState<string | null>(null), [error, setError] = useState<string | null>(null)
  const active = useRef(true), submitting = useRef(false)
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])
  const accepted = acceptedDigest === preview.capabilityDigest
  const canCommit = preview.allowed && preview.items.length > 0 && preview.items.every(item => item.allowed)
  const confirm = async () => {
    if (!accepted || !canCommit || pending || submitting.current) return
    submitting.current = true; setError(null)
    try { await onConfirm() } catch (cause) { if (active.current) setError(cause instanceof Error ? cause.message : '整批采用尚未确认，请核实原操作') }
    finally { submitting.current = false }
  }
  return <section aria-label="整批剧本采用预览" className="assistant-card flex flex-col gap-3">
    <strong>{preview.mode === 'APPEND_NEW' ? '追加新集预览' : '更新现有剧集预览'} · 共 {preview.items.length} 集</strong>
    <p className="text-sm">请核对以下完整批次。确认后由服务端一次事务采用；任一目标版本变化都会拒绝整批，不会部分覆盖。</p>
    <ol className="list-inside list-decimal space-y-3">{preview.items.map(item => <li key={item.itemIndex}>
      <span>第 {item.episodeNo} 集{item.proposedTitle ? ' · ' + item.proposedTitle : ''}</span>
      {item.proposedDescription ? <p className="whitespace-pre-wrap text-xs">{item.proposedDescription}</p> : null}
      <p className="text-xs">{item.effectiveCharCount} 字 · 原文范围 {item.sourceCharStart}–{item.sourceCharEnd}</p>
      {item.currentTargetRevision ? <details><summary>即将更新的已保存版本</summary><code className="break-all text-xs">{item.currentTargetRevision}</code></details> : <p className="text-xs">将新增剧集，不覆盖已有集</p>}
      {!item.allowed ? <p role="alert">当前目标不可采用：{item.reason || '请重新预览'}</p> : null}
    </li>)}</ol>
    {!preview.allowed ? <p role="alert">当前批次不可采用：{preview.reason || '请重新预览'}</p> : null}
    <label className="flex min-h-11 items-center gap-2"><input type="checkbox" checked={accepted} disabled={!canCommit || pending} onChange={event => setAcceptedDigest(event.target.checked ? preview.capabilityDigest : null)} />我已核对全部 {preview.items.length} 集，同意整批采用</label>
    <div className="flex flex-wrap gap-2">
      <button type="button" className="min-h-11 rounded-lg border px-4" disabled={pending} onClick={onRepreview}>重新预览</button>
      <button type="button" className="min-h-11 rounded-lg border px-4" disabled={!canCommit || !accepted || pending} onClick={() => void confirm()}>确认整批采用</button>
    </div>
    {error ? <p role="alert">{error}</p> : null}
  </section>
}
