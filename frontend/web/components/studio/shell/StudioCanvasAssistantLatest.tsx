'use client'

import { useEffect, useRef, useState } from 'react'
import { ArrowDownOutlined, DownOutlined } from '@ant-design/icons'

/** Canvas-only control; the existing viewport continues to own scroll following. */
export function StudioCanvasAssistantLatest({ sessionId, onJumpToLatest }: {
  sessionId: string
  onJumpToLatest?: () => void
}) {
  const host = useRef<HTMLDivElement>(null)
  const [visible, setVisible] = useState(false)
  useEffect(() => {
    const viewport = host.current?.closest('.canvas-assistant-panel')?.querySelector<HTMLElement>('.agent-conversation-viewport')
    if (!viewport) return
    const update = () => setVisible(viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight > 96)
    viewport.addEventListener('scroll', update, { passive: true })
    const observer = new ResizeObserver(update)
    observer.observe(viewport)
    const content = viewport.querySelector('.agent-conversation-viewport__content')
    if (content) observer.observe(content)
    update()
    return () => { viewport.removeEventListener('scroll', update); observer.disconnect() }
  }, [sessionId])
  return <div ref={host} className={onJumpToLatest ? "canvas-assistant-latest-anchor is-compact" : "canvas-assistant-latest-anchor"}>{visible ? <button type="button" aria-label="返回最新消息" title="返回最新消息" onClick={() => {
    if (onJumpToLatest) {
      onJumpToLatest()
      return
    }
    const viewport = host.current?.closest('.canvas-assistant-panel')?.querySelector<HTMLElement>('.agent-conversation-viewport')
    viewport?.scrollTo({ top: viewport.scrollHeight, behavior: 'auto' })
  }}>{onJumpToLatest ? <DownOutlined aria-hidden /> : <ArrowDownOutlined aria-hidden />}</button> : null}</div>
}
