'use client'

import { ReloadOutlined, UploadOutlined } from '@ant-design/icons'
import type { ReactNode } from 'react'
import type { StudioNodeKind } from '@/types/studio'
import { STUDIO_NODE_META } from '@/config/studioMeta'
import { StudioNodeIcon } from './StudioNodeIcon'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'

export function StudioNodeEmptyPreview({
  kind,
  showNextSteps = false,
  uploadable,
  onUpload,
  recoveryAction,
  statusMessage
}: {
  kind: StudioNodeKind
  showNextSteps?: boolean
  uploadable: boolean
  onUpload: () => void
  statusMessage?: string
  recoveryAction?: {
    label: string
    onClick: () => void
  }
}) {
  const meta = STUDIO_NODE_META[kind]
  const uploadLabel = kind === 'video' || kind === 'image' || kind === 'voice'
    ? '上传'
    : kind === 'text'
      ? '上传本地文本'
      : `上传本地${meta.label}`
  const showUpload = !recoveryAction && uploadable && kind !== 'director' && !showNextSteps
  const action: { label: string; title?: string; icon: ReactNode; onClick: () => void } | null = recoveryAction
    ? {
        label: recoveryAction.label,
        title: '在当前节点重新提交上次生成参数',
        icon: <ReloadOutlined aria-hidden />,
        onClick: recoveryAction.onClick
      }
    : showUpload
      ? {
          label: uploadLabel,
          icon: <UploadOutlined aria-hidden />,
          onClick: onUpload
        }
      : null

  return (
    <div className="studio-node-empty-preview">
      <div className="studio-node-empty-preview__stage">
        <span className="studio-node-empty-preview__watermark" aria-hidden>
          <StudioNodeIcon kind={kind} variant="watermark" />
        </span>
        {statusMessage ? (
          <StudioCanvasTooltip title={statusMessage} placement="bottom">
            <span className="studio-node-empty-preview__status nodrag" role="status">{statusMessage}</span>
          </StudioCanvasTooltip>
        ) : null}
        {action ? (
          <StudioCanvasTooltip title={action.title}>
          <button
            type="button"
            className="studio-node-empty-preview__upload nodrag"
            onClick={(event) => {
              event.stopPropagation()
              action.onClick()
            }}
          >
            {action.icon}
            {action.label}
          </button>
          </StudioCanvasTooltip>
        ) : null}
      </div>
    </div>
  )
}
