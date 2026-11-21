'use client'

import type { CanvasCollaborationConnectionStatus } from '@/hooks/studio/useCanvasProjectWorkspace'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { StudioIconGlyph } from '@/components/studio/theme/StudioIconGlyph'
import './studio-canvas-assistant-connection.css'

export function canvasAssistantConnectionReason(status: CanvasCollaborationConnectionStatus, error?: string | null) {
  return status === 'paused'
    ? `${error || '画布连接已暂停'}。正在重新连接，输入内容会保留。`
    : '正在恢复画布连接，可以继续编辑，连接恢复后再发送。'
}

/** 协作恢复由工作区完成。这里只说明状态，不提供手动重连。 */
export function StudioCanvasAssistantConnectionStatus({ status, error, recovering = true }: {
  status: CanvasCollaborationConnectionStatus
  error?: string | null
  recovering?: boolean
}) {
  const held = status === 'paused' && !recovering
  const title = held
    ? (error || '画布连接已暂停')
    : status === 'reconnecting' || status === 'paused'
      ? '正在重新连接画布'
      : status === 'connected'
        ? '正在同步画布'
        : '正在连接画布'
  return <section className={`canvas-assistant-connection${held ? ' is-held' : ' is-connecting'}`} aria-label="画布连接状态">
    {held ? <StudioCanvasTooltip trigger={['hover', 'focus', 'click']} title={<span className="canvas-assistant-connection__reason" tabIndex={0}>{error || '画布连接已暂停'}</span>}>
      <button type="button" className="canvas-assistant-connection__details" aria-label={`连接详情：${error || '画布连接已暂停'}`}>
        <StudioIconGlyph name="connection" />
      </button>
    </StudioCanvasTooltip> : <span className="canvas-assistant-connection__indicator" aria-hidden="true"><StudioIconGlyph name="reconnect" /></span>}
    <div className="canvas-assistant-connection__copy" role="status" aria-live="polite" aria-atomic="true">
      <p className={held ? undefined : 'canvas-assistant-status-shimmer'}>{title}</p>
      <small>{held ? '输入内容会保留' : '可以继续编辑，连接恢复后再发送'}</small>
    </div>
  </section>
}
