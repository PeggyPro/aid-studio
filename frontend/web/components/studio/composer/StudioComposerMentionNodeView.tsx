'use client'

/* eslint-disable @next/next/no-img-element -- mention chip 预览含 blob/cdn URL */
import type { MouseEvent } from 'react'
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/react'
import { resolveStudioMentionPreview } from '@/utils/studio/studioMentionPreview'
import type { StudioComposerMentionAttrs, StudioComposerMentionOptions } from './studioComposerMentionExtension'

export function StudioComposerMentionNodeView(props: NodeViewProps) {
  const label = String(props.node.attrs.label ?? '')
  const preview = resolveStudioMentionPreview(
    props.node.attrs.mediaKind as string | null | undefined,
    props.node.attrs.previewUrl as string | null | undefined
  )
  const options = props.extension.options as StudioComposerMentionOptions

  return (
    <NodeViewWrapper
      as="span"
      className="studio-composer-mention"
      contentEditable={false}
      onClick={(event: MouseEvent<HTMLElement>) => {
        event.preventDefault()
        event.stopPropagation()
        const rect = event.currentTarget.getBoundingClientRect()
        options.onMentionClick?.(props.node.attrs as StudioComposerMentionAttrs, rect)
      }}
    >
      {preview.kind === 'video' ? (
        <video src={preview.src} muted playsInline preload="metadata" tabIndex={-1} />
      ) : preview.kind === 'image' ? (
        <img src={preview.src} alt="" />
      ) : (
        <i aria-hidden />
      )}
      <span className="studio-composer-mention__label">{label}</span>
    </NodeViewWrapper>
  )
}
