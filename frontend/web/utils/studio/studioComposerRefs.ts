import type { StudioComposerRef, StudioComposerRefChannel, StudioNodeData } from '@/types/studio'
import { revokeObjectUrl } from '@/utils/objectUrl'

type StudioGenerationOptions = NonNullable<StudioNodeData['generationOptions']>

export interface StudioVideoReferenceUpdate {
  composerRefs: StudioComposerRef[]
  generationOptions: StudioGenerationOptions
  previousRef?: StudioComposerRef
}

export function newComposerRefInstanceId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `i-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

export function sourceKeyOfRef(
  ref: Pick<StudioComposerRef, 'kind' | 'nodeId' | 'assetId' | 'id' | 'sourceKey'>
): string {
  if (ref.sourceKey) return ref.sourceKey
  if (ref.nodeId) return `node:${ref.nodeId}`
  if (ref.assetId) return `asset:${ref.assetId}`
  const parts = ref.id.split(':')
  if (parts.length >= 2 && (parts[0] === 'node' || parts[0] === 'asset' || parts[0] === 'library')) {
    return `${parts[0]}:${parts[1]}`
  }
  return ref.id
}

/** 通道缺省视为 attachment，兼容历史数据。 */
export function composerRefChannelOf(
  ref: Pick<StudioComposerRef, 'channel'>
): StudioComposerRefChannel {
  return ref.channel === 'mention' ? 'mention' : 'attachment'
}

/** 上方缩略图条只展示 attachment（含缺省）。 */
export function isComposerRefStripVisible(
  ref: Pick<StudioComposerRef, 'channel'>
): boolean {
  return composerRefChannelOf(ref) === 'attachment'
}

/** 仅 attachment 可驱动视频首尾帧。 */
export function isComposerRefFrameEligible(
  ref: Pick<StudioComposerRef, 'channel' | 'mediaKind' | 'previewUrl'>
): boolean {
  return isComposerRefStripVisible(ref) && ref.mediaKind === 'image' && Boolean(ref.previewUrl)
}

export function hasComposerRefSourceKey(
  refs: StudioComposerRef[] | undefined,
  sourceKey: string
): boolean {
  return (refs ?? []).some((item) => sourceKeyOfRef(item) === sourceKey)
}

/** 追加引用实例：每次新 id，允许同源重复。 */
export function appendComposerRefInstance(
  refs: StudioComposerRef[] | undefined,
  base: Omit<StudioComposerRef, 'id'> & { id?: string }
): { refs: StudioComposerRef[]; ref: StudioComposerRef } {
  const instanceId = newComposerRefInstanceId()
  const sourceKey = base.sourceKey ?? sourceKeyOfRef({
    ...base,
    id: base.id ?? `${base.kind}:${instanceId}`
  })
  let id: string
  if (base.kind === 'node' && base.nodeId) id = `node:${base.nodeId}:${instanceId}`
  else if (base.kind === 'asset' && base.assetId) id = `asset:${base.assetId}:${instanceId}`
  else if (base.kind === 'library') id = `library:${sourceKey.split(':')[1] ?? 'x'}:${instanceId}`
  else id = `${base.kind}:${instanceId}`

  const ref: StudioComposerRef = {
    ...base,
    id,
    sourceKey,
    channel: base.channel ?? 'attachment'
  }
  return { refs: [...(refs ?? []), ref], ref }
}

export function upsertComposerRef(
  refs: StudioComposerRef[] | undefined,
  next: StudioComposerRef
): StudioComposerRef[] {
  const current = refs ?? []
  const index = current.findIndex((item) => item.id === next.id)
  if (index < 0) return [...current, next]
  return current.map((item, itemIndex) => itemIndex === index ? next : item)
}

export function removeComposerRef(refs: StudioComposerRef[] | undefined, id: string): StudioComposerRef[] {
  return (refs ?? []).filter((item) => item.id !== id)
}

/** 原子替换引用及其关联首尾帧，避免帧参数继续指向即将回收的 Blob URL。 */
export function replaceStudioVideoReference(
  refs: StudioComposerRef[] | undefined,
  generationOptions: StudioGenerationOptions,
  next: StudioComposerRef
): StudioVideoReferenceUpdate {
  const previousRef = refs?.find((item) => item.id === next.id)
  const previousUrl = previousRef?.previewUrl
  const canDriveFrames = isComposerRefFrameEligible(next)
  const replacementUrl = canDriveFrames ? next.previewUrl : undefined
  let nextOptions = generationOptions

  if (previousUrl && previousUrl !== next.previewUrl) {
    nextOptions = {
      ...generationOptions,
      startFrameUrl: generationOptions.startFrameUrl === previousUrl
        ? replacementUrl
        : generationOptions.startFrameUrl,
      endFrameUrl: generationOptions.endFrameUrl === previousUrl
        ? replacementUrl
        : generationOptions.endFrameUrl
    }
  } else if (!previousRef && replacementUrl) {
    if (!generationOptions.startFrameUrl) {
      nextOptions = { ...generationOptions, startFrameUrl: replacementUrl }
    } else if (generationOptions.endFrameEnabled && !generationOptions.endFrameUrl) {
      nextOptions = { ...generationOptions, endFrameUrl: replacementUrl }
    }
  }

  return {
    composerRefs: upsertComposerRef(refs, next),
    generationOptions: nextOptions,
    previousRef
  }
}

/** 移除引用时仅清理与该预览 URL 精确匹配的首尾帧。 */
export function removeStudioVideoReference(
  refs: StudioComposerRef[] | undefined,
  generationOptions: StudioGenerationOptions,
  id: string
): StudioVideoReferenceUpdate {
  const previousRef = refs?.find((item) => item.id === id)
  const previousUrl = previousRef?.previewUrl
  return {
    composerRefs: removeComposerRef(refs, id),
    generationOptions: previousUrl ? {
      ...generationOptions,
      startFrameUrl: generationOptions.startFrameUrl === previousUrl
        ? undefined
        : generationOptions.startFrameUrl,
      endFrameUrl: generationOptions.endFrameUrl === previousUrl
        ? undefined
        : generationOptions.endFrameUrl
    } : generationOptions,
    previousRef
  }
}

export function revokeStudioComposerRefPreview(
  ref: StudioComposerRef,
  revoke = revokeObjectUrl
): void {
  if (ref.kind === 'upload' && ref.previewUrl?.startsWith('blob:')) revoke(ref.previewUrl)
  else if (ref.previewUrl?.startsWith('blob:')) revoke(ref.previewUrl)
}
