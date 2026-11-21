import type { StudioNodeData } from '@/types/studio'
import { resolveStudioNodeMediaKind } from '@/utils/studio/studioNodeMedia'

/** 已生成主图的图片节点（含流程素材；形态详情改在下方面板查看） */
export function isStudioImageContentNode(data: StudioNodeData): boolean {
  if (data.kind !== 'image' || !data.mediaUrl?.trim()) return false
  const mediaKind = resolveStudioNodeMediaKind(data.kind, data.mediaKind)
  return mediaKind === 'image' || mediaKind == null
}

/** 图生分镜：脚本节点带主图时按图片卡展示 */
export function isStudioStoryboardImageNode(data: StudioNodeData): boolean {
  if (data.kind !== 'storyboard_script' || !data.mediaUrl?.trim()) return false
  return data.mediaKind === 'image' || data.imagePurpose === 'storyboard'
}

/** 图生分镜尚未出图：脚本卡上标记「待出图」 */
export function needsStudioStoryboardImage(data: StudioNodeData): boolean {
  return data.kind === 'storyboard_script'
    && data.imagePurpose === 'storyboard'
    && !data.mediaUrl?.trim()
}

export function isStudioVideoContentNode(data: StudioNodeData): boolean {
  return data.kind === 'video' && Boolean(data.mediaUrl?.trim())
}

export function isStudioVideoPendingNode(data: StudioNodeData): boolean {
  return data.kind === 'video' && !data.mediaUrl?.trim()
}

/** 视频高清配置节点：先创建派生节点，再在节点下方配置处理参数。 */
export function isStudioVideoHdConfigNode(data: StudioNodeData): boolean {
  return data.kind === 'video' && data.videoPurpose === 'hd' && !data.mediaUrl?.trim()
}

export function isStudioVideoOperationResultNode(data: StudioNodeData): boolean {
  return data.kind === 'video' && [
    'subtitle_removal',
    'trim',
    'crop',
    'depth_capture',
    'audio_separation',
    'vocal_extraction',
    'background_audio_extraction',
    'subject_edit'
  ].includes(data.videoPurpose || '') && !data.mediaUrl?.trim()
}

export function isStudioMediaDerivationNode(data: StudioNodeData): boolean {
  return Boolean(data.mediaDerivation)
}

/** 片段续写尚未提交时，节点保留为不可上传的模型生成占位。 */
export function isStudioVideoClipDraftNode(data: StudioNodeData): boolean {
  return data.kind === 'video'
    && data.videoPurpose === 'continuation'
    && !data.mediaUrl?.trim()
}

/** 导演台已保存构图预览：卡片铺满截图，而不是只显示占位图标 */
export function isStudioDirectorPreviewNode(data: StudioNodeData): boolean {
  return data.kind === 'director' && Boolean(data.mediaUrl?.trim())
}

/** 宫格分镜组节点 */
export function isStudioGridStoryboardNode(data: StudioNodeData): boolean {
  return data.kind === 'image'
    && data.imagePurpose === 'grid_storyboard'
    && Boolean(data.gridStoryboard)
}

/** 外置标题 + 铺满媒体的卡片（图片 / 分镜主图 / 视频 / 导演台预览） */
export function isStudioMediaFillNode(data: StudioNodeData): boolean {
  return (isStudioImageContentNode(data) && !isStudioGridStoryboardNode(data))
    || isStudioStoryboardImageNode(data)
    || isStudioVideoContentNode(data)
    || isStudioDirectorPreviewNode(data)
}

/** 待生图的素材节点：卡片内不堆提示词 */
export function isStudioImagePendingNode(data: StudioNodeData): boolean {
  if (data.kind !== 'image') return false
  if (data.mediaUrl?.trim()) return false
  if (data.flowBinding?.entityType === 'rps_asset') return true
  if (data.flowBinding?.entityType === 'storyboard' && data.imagePurpose === 'storyboard') return true
  return Boolean(data.flowBinding && data.prompt?.trim())
}

/** 高清配置占位节点：尚未出图，卡片内提示配置参数 */
export function isStudioImageHdConfigNode(data: StudioNodeData): boolean {
  return data.kind === 'image' && data.imagePurpose === 'hd' && !data.mediaUrl?.trim()
}

/** 抠图进行中：展示流光粒子动效，尚无结果图 */
export function isStudioImageMattingPendingNode(data: StudioNodeData): boolean {
  return data.kind === 'image'
    && data.imagePurpose === 'matting'
    && !data.mediaUrl?.trim()
    && (data.status === 'queued' || data.status === 'generating' || data.status === 'empty')
}

/** 空节点占位（libtv 式：类型标签在外、卡片内仅水印与操作） */
export function isStudioEmptyPlaceholderNode(input: {
  data: StudioNodeData
  textContent: string
  generating: boolean
  uploading: boolean
  scriptNode: boolean
  mediaFillNode: boolean
  gridStoryboardNode: boolean
  imagePendingNode: boolean
  videoPendingNode: boolean
  imageHdConfigNode: boolean
  imageMattingPendingNode: boolean
  videoHdConfigNode: boolean
}): boolean {
  const { data, textContent } = input
  if (input.generating || input.uploading || input.scriptNode || data.flowBinding) return false
  if (data.textEditingEnabled) return false
  if (input.mediaFillNode || input.gridStoryboardNode || input.imagePendingNode) return false
  if (input.imageHdConfigNode || input.imageMattingPendingNode) return false
  if ((input.videoPendingNode && Boolean(data.videoPurpose)) || input.videoHdConfigNode) return false
  if (data.imagePurpose === 'matting' && data.status === 'failed' && !data.mediaUrl) return false
  if (data.kind === 'text' && textContent.trim()) return false
  if (
    (data.mediaKind === 'image' || data.mediaKind === 'video' || data.mediaKind === 'audio')
    && data.mediaUrl?.trim()
  ) return false
  return true
}

export function parseGenerationAspectRatio(value?: string): number | null {
  if (!value?.trim()) return null
  const normalized = value.trim().toLowerCase()
  if (normalized === 'adaptive') return null
  const match = normalized.match(/^(\d+(?:\.\d+)?)\s*:\s*(\d+(?:\.\d+)?)$/)
  if (!match) return null
  const width = Number(match[1])
  const height = Number(match[2])
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return null
  return width / height
}
