'use client'

import {
  CameraOutlined,
  DownloadOutlined,
  EditOutlined,
  ExpandAltOutlined,
  FastBackwardOutlined,
  FastForwardOutlined
} from '@ant-design/icons'
import type { StudioMediaKind, StudioNodeStatus } from '@/types/studio'
import { StudioChromeIcon } from '@/components/studio/theme/StudioChromeIcon'
import { StudioStatusBadge } from './StudioStatusBadge'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'
import { StudioInlineNodeTitle } from './StudioInlineNodeTitle'

export interface StudioNodeContentHeaderProps {
  title: string
  status: StudioNodeStatus
  mediaKind?: StudioMediaKind
  videoPlayable?: boolean
  captureBusy?: boolean
  titleEditable?: boolean
  onSelect: () => void
  onTitleChange?: (title: string) => void
  onOpenImageTools?: () => void
  onPreview?: () => void
  onDownload?: () => void
  onCaptureFirst?: () => void
  onCaptureCurrent?: () => void
  onCaptureLast?: () => void
}

export function StudioNodeContentHeader({
  title,
  status,
  mediaKind,
  videoPlayable,
  captureBusy,
  titleEditable,
  onSelect,
  onTitleChange,
  onOpenImageTools,
  onPreview,
  onDownload,
  onCaptureFirst,
  onCaptureCurrent,
  onCaptureLast
}: StudioNodeContentHeaderProps) {
  const action = (label: string, icon: React.ReactNode, onClick?: () => void, disabled = false) => (
    <StudioCanvasTooltip title={disabled ? `${label}暂不可用` : label}>
      <span className="studio-node-content-header__action-tooltip nodrag">
        <button
          type="button"
          className="studio-node-content-header__action nodrag"
          aria-label={label}
          disabled={disabled}
          onClick={(event) => {
            event.stopPropagation()
            onClick?.()
          }}
        >
          {icon}
        </button>
      </span>
    </StudioCanvasTooltip>
  )

  return (
    <header className="studio-node-card__header studio-node-content-header">
      <div className="studio-node-content-header__identity nodrag">
        <span className="studio-node-card__heading">
          <StudioInlineNodeTitle
            title={title}
            editable={titleEditable}
            onActivate={onSelect}
            onCommit={onTitleChange}
          />
        </span>
        <StudioStatusBadge status={status} />
      </div>
      <div className="studio-node-content-header__actions nodrag">
        {onOpenImageTools ? action('图片编辑工具', <StudioChromeIcon name="sparkle" fallback={<EditOutlined />} />, onOpenImageTools) : null}
        {onPreview ? action('全屏预览', <StudioChromeIcon name="expand" fallback={<ExpandAltOutlined />} />, onPreview) : null}
        {onDownload ? action('下载', <StudioChromeIcon name="download" fallback={<DownloadOutlined />} />, onDownload) : null}
        {mediaKind === 'video' && (onCaptureFirst || onCaptureCurrent || onCaptureLast) ? (
          <>
            {onCaptureFirst
              ? action('截取首帧', <FastBackwardOutlined />, onCaptureFirst, !videoPlayable || captureBusy)
              : null}
            {onCaptureCurrent
              ? action('截取当前帧', <CameraOutlined />, onCaptureCurrent, !videoPlayable || captureBusy)
              : null}
            {onCaptureLast
              ? action('截取尾帧', <FastForwardOutlined />, onCaptureLast, !videoPlayable || captureBusy)
              : null}
          </>
        ) : null}
      </div>
    </header>
  )
}
