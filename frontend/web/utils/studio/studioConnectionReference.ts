import type { StudioCanvasNode, StudioComposerRef, StudioNodeData } from '@/types/studio'
import {
  appendComposerRefInstance,
  isComposerRefStripVisible,
  removeComposerRef,
  removeStudioVideoReference,
  sourceKeyOfRef
} from '@/utils/studio/studioComposerRefs'
import { libraryAutoRef } from '@/utils/studio/studioLibraryLink'
import {
  studioNodeToComposerRef,
  studioReferencePickDisabledReason,
  withStudioVideoReference
} from '@/utils/studio/studioReferencePicker'

function hasAttachmentSourceKey(
  refs: StudioComposerRef[] | undefined,
  sourceKey: string
): boolean {
  return (refs ?? []).some(
    (item) => isComposerRefStripVisible(item) && sourceKeyOfRef(item) === sourceKey
  )
}

/** 连线引用的同源键（多实例共享；剪断时按此清理全部 attachment 实例）。 */
export function studioConnectionReferenceSourceKey(source: Pick<StudioCanvasNode, 'id' | 'data'>): string {
  if (source.data.kind === 'style_library' || source.data.kind === 'official_asset') {
    return `library:${source.id}`
  }
  return `node:${source.id}`
}

/** @deprecated 兼容旧单例 id；新逻辑请用 studioConnectionReferenceSourceKey */
export function studioConnectionReferenceId(source: Pick<StudioCanvasNode, 'id' | 'data'>): string {
  return studioConnectionReferenceSourceKey(source)
}

/** 连线成功后，把上游可引用内容写入下游 composerRefs（同源 attachment 已有则跳过）。 */
export function applyStudioConnectionReference(
  source: StudioCanvasNode,
  target: StudioCanvasNode
): StudioNodeData | null {
  if (source.data.kind === 'style_library' || source.data.kind === 'official_asset') {
    const ref = libraryAutoRef(source)
    const sourceKey = studioConnectionReferenceSourceKey(source)
    if (hasAttachmentSourceKey(target.data.composerRefs, sourceKey)) {
      return source.data.kind === 'style_library'
        ? {
            ...target.data,
            generationOptions: {
              ...target.data.generationOptions,
              styleId: source.data.generationOptions?.styleId,
              styleName: source.data.generationOptions?.styleName ?? source.data.title,
              stylePreviewUrl: source.data.generationOptions?.stylePreviewUrl ?? source.data.mediaUrl,
              stylePrompt: source.data.generationOptions?.stylePrompt
            },
            updatedAt: new Date().toISOString()
          }
        : null
    }
    const appended = appendComposerRefInstance(target.data.composerRefs, {
      ...ref,
      sourceKey,
      channel: 'attachment'
    })
    return {
      ...target.data,
      composerRefs: appended.refs,
      generationOptions: source.data.kind === 'style_library'
        ? {
            ...target.data.generationOptions,
            styleId: source.data.generationOptions?.styleId,
            styleName: source.data.generationOptions?.styleName ?? source.data.title,
            stylePreviewUrl: source.data.generationOptions?.stylePreviewUrl ?? source.data.mediaUrl,
            stylePrompt: source.data.generationOptions?.stylePrompt
          }
        : target.data.generationOptions,
      updatedAt: new Date().toISOString()
    }
  }

  if (target.data.kind !== 'image' && target.data.kind !== 'video' && target.data.kind !== 'smart_edit') return null
  if (studioReferencePickDisabledReason(target, source)) return null

  const sourceKey = studioConnectionReferenceSourceKey(source)
  if (hasAttachmentSourceKey(target.data.composerRefs, sourceKey)) return null

  const base = studioNodeToComposerRef(source)
  const appended = appendComposerRefInstance(target.data.composerRefs, {
    kind: base.kind,
    nodeId: base.nodeId,
    title: base.title,
    mediaKind: base.mediaKind,
    previewUrl: base.previewUrl,
    sourceKey,
    channel: 'attachment'
  })
  return {
    ...target.data,
    composerRefs: appended.refs,
    generationOptions: target.data.kind === 'video'
      ? withStudioVideoReference(target.data.generationOptions ?? {}, appended.ref)
      : target.data.generationOptions,
    updatedAt: new Date().toISOString()
  }
}

/**
 * 剪断连线时清理下游由该上游写入的全部同源 attachment 实例（不影响正文 mention）。
 */
export function clearStudioConnectionReference(
  source: StudioCanvasNode,
  target: StudioCanvasNode
): StudioNodeData | null {
  const sourceKey = studioConnectionReferenceSourceKey(source)
  const refs = target.data.composerRefs ?? []
  const matched = refs.filter(
    (item) => isComposerRefStripVisible(item) && sourceKeyOfRef(item) === sourceKey
  )
  const styleId = source.data.generationOptions?.styleId
  const hadStyle = source.data.kind === 'style_library'
    && Boolean(styleId)
    && target.data.generationOptions?.styleId === styleId

  if (!matched.length && !hadStyle) return null

  const now = new Date().toISOString()
  let nextOptions = target.data.generationOptions ?? {}
  let nextRefs = refs

  for (const item of matched) {
    if (target.data.kind === 'video') {
      const cleared = removeStudioVideoReference(nextRefs, nextOptions, item.id)
      nextRefs = cleared.composerRefs
      nextOptions = cleared.generationOptions
    } else {
      nextRefs = removeComposerRef(nextRefs, item.id)
    }
  }

  if (hadStyle) {
    const { styleId: _styleId, styleName: _styleName, stylePreviewUrl: _stylePreviewUrl, stylePrompt: _stylePrompt, ...rest } = nextOptions
    nextOptions = rest
  }

  return {
    ...target.data,
    composerRefs: nextRefs,
    generationOptions: nextOptions,
    updatedAt: now
  }
}

export function studioComposerRefFromAsset(asset: {
  id: string
  title: string
  mediaUrl: string
  mediaKind?: StudioComposerRef['mediaKind']
  kind?: 'image' | 'video'
}): StudioComposerRef {
  const mediaKind = asset.mediaKind
    ?? (asset.kind === 'video' ? 'video' : 'image')
  return {
    id: `asset:${asset.id}`,
    kind: 'asset',
    assetId: asset.id,
    title: asset.title,
    mediaKind,
    previewUrl: asset.mediaUrl,
    sourceKey: `asset:${asset.id}`
  }
}
