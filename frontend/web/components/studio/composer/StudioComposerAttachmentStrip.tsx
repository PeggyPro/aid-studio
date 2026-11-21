'use client'

import { useMemo } from 'react'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioComposerRef, StudioMediaKind } from '@/types/studio'
import { isComposerRefStripVisible } from '@/utils/studio/studioComposerRefs'
import { resolveStudioScriptNodeText } from '@/utils/studio/studioScriptNode'
import {
  StudioComposerReferenceCard
} from './StudioComposerReferenceCards'
import {
  StudioComposerThumb,
  type StudioComposerThumbItem
} from './StudioComposerRefThumbStrip'

function isMediaPreviewRef(
  mediaKind: string | undefined,
  previewUrl: string | undefined
): boolean {
  return Boolean(previewUrl && (mediaKind === 'image' || mediaKind === 'video'))
}

type StudioComposerAttachmentItem =
  | {
      type: 'thumb'
      key: string
      index: number
      thumb: StudioComposerThumbItem
    }
  | {
      type: 'card'
      key: string
      index: number
      card: {
        refItem: StudioComposerRef
        title: string
        summary: string
        mediaKind?: StudioMediaKind
        index: number
      }
    }

/**
 * 图片缩略图与文本/音频卡片同一行按 composerRefs 顺序排布，
 * 避免原先两个独立块级容器导致「图一块、文一块」换行。
 */
export function StudioComposerAttachmentStrip({
  upstream,
  refs,
  onInsert,
  onRemove
}: {
  upstream: StudioComposerThumbItem[]
  refs: StudioComposerRef[] | undefined
  onInsert: (ref: StudioComposerRef) => void
  onRemove: (ref: StudioComposerRef) => void
}) {
  const visibleRefs = useMemo(
    () => (refs ?? []).filter((ref) => isComposerRefStripVisible(ref)),
    [refs]
  )
  const nodeIds = useMemo(
    () => new Set(visibleRefs.flatMap((ref) => (ref.nodeId ? [ref.nodeId] : []))),
    [visibleRefs]
  )
  const sourceSignature = useStudioUiStore((state) => state.nodes
    .filter((node) => nodeIds.has(node.id))
    .map((node) => `${node.id}\0${node.data.title}\0${node.data.mediaKind ?? ''}\0${node.data.mediaUrl ?? ''}\0${resolveStudioScriptNodeText(node.data)}\0${node.data.prompt}`)
    .join('\n'))
  void sourceSignature

  const items = useMemo(() => {
    const nodes = useStudioUiStore.getState().nodes
    const byId = new Map(nodes.map((node) => [node.id, node]))
    return visibleRefs.flatMap<StudioComposerAttachmentItem>((ref, index) => {
      const source = ref.nodeId ? byId.get(ref.nodeId) : undefined
      const mediaKind = source?.data.mediaKind ?? ref.mediaKind
      const previewUrl = source?.data.mediaUrl ?? ref.previewUrl
      const displayIndex = index + 1
      if (isMediaPreviewRef(mediaKind, previewUrl)) {
        return [{
          type: 'thumb' as const,
          key: ref.id,
          index: displayIndex,
          thumb: {
            key: ref.id,
            previewUrl: previewUrl!,
            title: source?.data.title ?? ref.title,
            mediaKind,
            nodeId: ref.nodeId,
            index: displayIndex,
            onInsert: () => onInsert(ref),
            onRemove: () => onRemove(ref)
          } satisfies StudioComposerThumbItem
        }]
      }
      return [{
        type: 'card' as const,
        key: ref.id,
        index: displayIndex,
        card: {
          refItem: ref,
          title: source?.data.title ?? ref.title,
          summary: source ? resolveStudioScriptNodeText(source.data) : ref.textContent || '',
          mediaKind,
          index: displayIndex
        }
      }]
    })
  }, [onInsert, onRemove, visibleRefs, sourceSignature])

  if (!upstream.length && !items.length) return null

  return (
    <div className="studio-composer-attachment-strip" aria-label="引用附件">
      {upstream.map((item) => (
        <StudioComposerThumb key={`up-${item.key}`} item={item} />
      ))}
      {items.map((item) => (
        item.type === 'thumb' ? (
          <StudioComposerThumb key={item.key} item={item.thumb} showRemove />
        ) : (
          <StudioComposerReferenceCard
            key={item.key}
            refItem={item.card.refItem}
            title={item.card.title}
            summary={item.card.summary}
            mediaKind={item.card.mediaKind}
            index={item.card.index}
            onRemove={onRemove}
          />
        )
      ))}
    </div>
  )
}
