'use client'

import type { StudioNodeKind } from '@/types/studio'
import { STUDIO_NODE_META } from '@/config/studioMeta'
import { StudioNodeIcon } from './StudioNodeIcon'
import { StudioInlineNodeTitle } from './StudioInlineNodeTitle'

export function StudioNodeExternalTitle({
  kind,
  title,
  editable = false,
  plain = false,
  readOnly = false,
  disabledReason,
  onTitleChange
}: {
  kind: StudioNodeKind
  title: string
  editable?: boolean
  plain?: boolean
  readOnly?: boolean
  disabledReason?: string
  onTitleChange?: (title: string) => void
}) {
  const meta = STUDIO_NODE_META[kind]
  const storedTitle = title.trim()
  const numberedDefault = storedTitle.startsWith(meta.label) && /^(?:节点\s*)?\d+$/.test(storedTitle.slice(meta.label.length))
  const heading = !storedTitle || !plain && (numberedDefault || storedTitle === meta.label + "节点") ? meta.label : storedTitle
  return (
    <div className={`studio-node-external-title${editable ? ' is-editable' : ''}`}>
      {!plain ? <>
        <span className="studio-node-external-title__chip" aria-hidden>
          <StudioNodeIcon kind={kind} variant="chip" />
        </span>
      </> : null}
        {readOnly ? <strong className="studio-inline-node-title__display">{heading}</strong> : <StudioInlineNodeTitle
          className="studio-node-external-title__editor"
          title={heading}
          editable={editable}
          disabledReason={disabledReason}
          onCommit={onTitleChange}
        />}
    </div>
  )
}

export function StudioImagePendingPreview({
  kind = 'image',
  title,
  actionLabel,
  onAction
}: {
  kind?: StudioNodeKind
  title?: string
  actionLabel?: string
  onAction?: () => void
}) {
  const meta = STUDIO_NODE_META[kind]
  const heading = title ?? `尚未生成${meta.label}`
  return (
    <div className="studio-image-pending-preview">
      <div className="studio-node-empty-preview__stage studio-image-pending-preview__stage">
        <span className="studio-node-empty-preview__watermark" aria-hidden>
          <StudioNodeIcon kind={kind} variant="watermark" />
        </span>
        <span className="studio-image-pending-preview__title">{heading}</span>
      </div>
      {actionLabel && onAction ? (
        <button
          type="button"
          className="studio-image-pending-preview__action nodrag"
          onClick={(event) => {
            event.stopPropagation()
            onAction()
          }}
        >
          {actionLabel}
        </button>
      ) : null}
    </div>
  )
}
