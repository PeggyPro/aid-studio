'use client'

import { PictureOutlined, ReloadOutlined, VideoCameraOutlined } from '@ant-design/icons'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'

export function StudioNodeMediaPlaceholder({ kind, failed, onRetry }: {
  kind: 'image' | 'video'
  failed: boolean
  onRetry?: () => void
}) {
  const label = kind === 'image' ? '图片' : '视频'
  return (
    <div className={`studio-node-media-placeholder${failed ? ' is-failed nodrag' : ''}`} role="status">
      <span className="studio-node-media-placeholder__icon" aria-hidden>
        {kind === 'image' ? <PictureOutlined /> : <VideoCameraOutlined />}
      </span>
      <span>{failed ? `${label}预览加载失败` : `正在加载${label}预览…`}</span>
      {failed && onRetry ? (
        <StudioCanvasTooltip title="重新读取媒体预览">
          <button type="button" onClick={event => { event.stopPropagation(); onRetry() }}>
            <ReloadOutlined aria-hidden />重新加载
          </button>
        </StudioCanvasTooltip>
      ) : null}
    </div>
  )
}
