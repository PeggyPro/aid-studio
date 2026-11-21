import type { StudioCanvasNode, StudioNodeKind } from '@/types/studio'
import { parseGenerationAspectRatio } from '@/utils/studio/studioImageNode'
import { isStudioScriptContentNode, resolveStudioScriptNodeText } from '@/utils/studio/studioScriptNode'

export type StudioNodeLod = 'ghost' | 'compact' | 'full'

const STUDIO_AUDIO_NODE_DIMENSIONS = { width: 300, height: 112 } as const
/** 100% 缩放下图片、视频卡片的默认占位。16:9，比旧的 320×180 大约一号半。 */
const STUDIO_EMPTY_VISUAL_NODE_DIMENSIONS = { width: 480, height: 270 } as const
const STUDIO_EMPTY_DOCUMENT_NODE_DIMENSIONS = { width: 320, height: 228 } as const

export function studioNodeLod(zoom: number): StudioNodeLod {
  if (zoom < 0.4) return 'ghost'
  if (zoom < 0.75) return 'compact'
  return 'full'
}

export function shouldUseCanvasLod(nodeCount: number, zoom: number): boolean {
  if (nodeCount >= 1200) return zoom < 0.62
  if (nodeCount >= 600) return zoom < 0.48
  return nodeCount >= 240 && zoom < 0.32
}

/** 各业务节点保留独立的默认画布占位，避免不同信息密度被压成同一种卡片。 */
export const STUDIO_NODE_DEFAULT_DIMENSIONS: Record<StudioNodeKind, {
  width: number
  height: number
}> = {
  image: STUDIO_EMPTY_VISUAL_NODE_DIMENSIONS,
  video: STUDIO_EMPTY_VISUAL_NODE_DIMENSIONS,
  frame_breakdown: { width: 304, height: 382 },
  smart_edit: { width: 304, height: 382 },
  text: STUDIO_EMPTY_DOCUMENT_NODE_DIMENSIONS,
  storyboard_script: STUDIO_EMPTY_DOCUMENT_NODE_DIMENSIONS,
  director: { width: 272, height: 168 },
  style_library: { width: 280, height: 196 },
  official_asset: { width: 280, height: 196 },
  voice: STUDIO_AUDIO_NODE_DIMENSIONS,
  note: { width: 232, height: 152 }
}

const STUDIO_MEDIA_LAYOUT_AREA = 129_600
const STUDIO_MEDIA_MIN_WIDTH = 280
const STUDIO_MEDIA_MAX_WIDTH = 640
const STUDIO_MEDIA_MIN_HEIGHT = 200
const STUDIO_MEDIA_MAX_HEIGHT = 640
const STUDIO_MEDIA_RATIO_CACHE_LIMIT = 400
export const STUDIO_GRID_STORYBOARD_MIN_HEIGHT = 220
export const STUDIO_GRID_STORYBOARD_MAX_HEIGHT = 1_600
const studioMediaAspectRatioCache = new Map<string, number>()

/** 仅缓存浏览器解码出的媒体比例，用于画布布局；不得作为模型生成参数。 */
export function rememberStudioMediaAspectRatio(mediaUrl: string, width: number, height: number): number | null {
  const key = mediaUrl.trim()
  if (!key || !Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return null
  const ratio = width / height
  if (!Number.isFinite(ratio) || ratio <= 0) return null
  if (!studioMediaAspectRatioCache.has(key) && studioMediaAspectRatioCache.size >= STUDIO_MEDIA_RATIO_CACHE_LIMIT) {
    const oldest = studioMediaAspectRatioCache.keys().next().value
    if (typeof oldest === 'string') studioMediaAspectRatioCache.delete(oldest)
  }
  studioMediaAspectRatioCache.set(key, ratio)
  return ratio
}

export function studioNodeDimensions(node: Pick<StudioCanvasNode, 'data'>, intrinsicMediaAspectRatio?: number | null): {
  width: number
  height: number
} {
  const base = STUDIO_NODE_DEFAULT_DIMENSIONS[node.data.kind]
  // 分析节点内的视频是输入素材，不决定整张配置卡片的宽高比。
  if (node.data.kind === 'frame_breakdown' || node.data.kind === 'smart_edit') return base
  const originWidth = node.data.gridStoryboardOrigin?.canvasWidth
  const originHeight = node.data.gridStoryboardOrigin?.canvasHeight
  if (
    (node.data.kind === 'image' || node.data.kind === 'video')
    && typeof originWidth === 'number'
    && typeof originHeight === 'number'
    && Number.isFinite(originWidth)
    && Number.isFinite(originHeight)
    && originWidth > 0
    && originHeight > 0
  ) {
    return { width: Math.round(originWidth), height: Math.round(originHeight) }
  }
  if (node.data.imagePurpose === 'grid_storyboard' && node.data.gridStoryboard) {
    const gridSize = studioGridStoryboardDimensions(
      node.data.gridStoryboard.rows,
      node.data.gridStoryboard.columns
    )
    const width = typeof node.data.displayWidth === 'number' && Number.isFinite(node.data.displayWidth)
      ? Math.max(280, Math.min(960, Math.round(node.data.displayWidth)))
      : gridSize.width
    const height = typeof node.data.displayHeight === 'number' && Number.isFinite(node.data.displayHeight)
      ? Math.max(STUDIO_GRID_STORYBOARD_MIN_HEIGHT, Math.min(STUDIO_GRID_STORYBOARD_MAX_HEIGHT, Math.round(node.data.displayHeight)))
      : gridSize.height
    return { width, height }
  }
  if (node.data.kind === 'voice' && !node.data.flowBinding && (node.data.mediaUrl || node.data.audioEdit || node.data.mediaDerivation)) {
    return node.data.mediaUrl ? base : { ...base, height: node.data.audioEdit ? 240 : 136 }
  }
  if (node.data.kind === 'text' || isStudioScriptContentNode(node.data)) {
    if (
      node.data.kind === 'text'
      && node.data.textEditingEnabled
      && typeof node.data.displayWidth === 'number'
      && typeof node.data.displayHeight === 'number'
      && Number.isFinite(node.data.displayWidth)
      && Number.isFinite(node.data.displayHeight)
    ) {
      return {
        width: Math.max(240, Math.min(960, Math.round(node.data.displayWidth))),
        height: Math.max(220, Math.min(900, Math.round(node.data.displayHeight)))
      }
    }
    if (node.data.kind === 'text' && node.data.textEditingEnabled) return { width: 300, height: 380 }
    const hasScript = Boolean(resolveStudioScriptNodeText(node.data))
    if (hasScript) return { width: 300, height: 380 }
    return STUDIO_EMPTY_DOCUMENT_NODE_DIMENSIONS
  }
  if (!node.data.mediaUrl) {
    if (node.data.kind === 'image' || node.data.kind === 'video') return STUDIO_EMPTY_VISUAL_NODE_DIMENSIONS
    return base
  }
  const cachedRatio = studioMediaAspectRatioCache.get(node.data.mediaUrl.trim())
  const persistedRatio = typeof node.data.mediaAspectRatio === 'number' && node.data.mediaAspectRatio > 0
    ? node.data.mediaAspectRatio
    : null
  const optionRatio = parseGenerationAspectRatio(node.data.generationOptions?.aspectRatio)
  const mediaRatio = intrinsicMediaAspectRatio ?? cachedRatio ?? persistedRatio ?? optionRatio
  if ((node.data.kind === 'image' || node.data.kind === 'video' || node.data.mediaKind === 'image' || node.data.mediaKind === 'video') && mediaRatio) {
    return studioMediaNodeDimensions(mediaRatio)
  }
  if (node.data.kind === 'image') {
    return studioMediaNodeDimensions(1)
  }
  const mediaHeight = node.data.mediaKind === 'video'
    ? 232
    : node.data.mediaKind === 'audio'
      ? 320
      : 224
  return { width: base.width, height: Math.max(base.height, mediaHeight) }
}

export function studioMediaNodeDimensions(mediaAspectRatio: number): { width: number; height: number } {
  const minimumSafeRatio = STUDIO_MEDIA_MIN_WIDTH / STUDIO_MEDIA_MAX_HEIGHT
  const maximumSafeRatio = STUDIO_MEDIA_MAX_WIDTH / STUDIO_MEDIA_MIN_HEIGHT
  const ratio = Math.min(maximumSafeRatio, Math.max(minimumSafeRatio, mediaAspectRatio))
  let width = Math.sqrt(STUDIO_MEDIA_LAYOUT_AREA * ratio)
  let height = width / ratio
  const enlarge = Math.max(1, STUDIO_MEDIA_MIN_WIDTH / width, STUDIO_MEDIA_MIN_HEIGHT / height)
  width *= enlarge
  height *= enlarge
  const shrink = Math.min(1, STUDIO_MEDIA_MAX_WIDTH / width, STUDIO_MEDIA_MAX_HEIGHT / height)
  return {
    width: Math.round(width * shrink),
    height: Math.round(height * shrink)
  }
}

function studioGridStoryboardDimensions(rows: number, columns: number): { width: number; height: number } {
  const safeRows = Math.max(1, rows)
  const safeCols = Math.max(1, columns)
  const cell = 96
  const gap = 4
  const pad = 16
  const width = Math.min(STUDIO_MEDIA_MAX_WIDTH, Math.max(280, safeCols * cell + (safeCols - 1) * gap + pad * 2))
  const height = Math.min(STUDIO_MEDIA_MAX_HEIGHT + 40, Math.max(220, safeRows * cell + (safeRows - 1) * gap + pad * 2 + 28))
  return { width: Math.round(width), height: Math.round(height) }
}
