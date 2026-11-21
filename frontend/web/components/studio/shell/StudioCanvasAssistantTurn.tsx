'use client'

import type { ReactNode } from 'react'
import { BulbOutlined } from '@ant-design/icons'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'

export function StudioCanvasAssistantTurn({ children, label, className = '', author = '画布助手' }: { children: ReactNode; label: string; className?: string; author?: string }) {
  return <article className={`canvas-assistant-turn ${className}`} aria-label={label}>
    <div className="canvas-assistant-message__author"><span><BulbOutlined aria-hidden /></span>{author}</div>
    <div className="canvas-assistant-turn__body">{children}</div>
  </article>
}

export function StudioCanvasAssistantAction({ children, onClick, disabled, reason, primary = false, label }: {
  children: ReactNode; onClick: () => void; disabled?: boolean; reason?: string; primary?: boolean; label?: string
}) {
  return <StudioCanvasTooltip title={reason}><button type="button" aria-label={label}
    className={`canvas-assistant-action${primary ? ' is-primary' : ''}`} disabled={disabled} onClick={onClick}>{children}</button></StudioCanvasTooltip>
}
