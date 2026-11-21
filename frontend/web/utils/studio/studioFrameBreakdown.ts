import type {
  StudioCanvasNode,
  StudioFrameBreakdownDimension
} from '@/types/studio'

export const STUDIO_FRAME_BREAKDOWN_DIMENSIONS: ReadonlyArray<{
  id: StudioFrameBreakdownDimension
  label: string
  description: string
}> = [
  { id: 'storyboard', label: '分镜', description: '识别镜头边界、景别、构图与叙事段落' },
  { id: 'motion', label: '动态', description: '分析主体动作、运镜、速度与转场变化' },
  { id: 'music', label: '音乐', description: '提取节拍、情绪、音效与画面同步关系' }
]

export const DEFAULT_STUDIO_FRAME_BREAKDOWN_DIMENSIONS = STUDIO_FRAME_BREAKDOWN_DIMENSIONS.map(
  (item) => item.id
)

const VALID_DIMENSIONS = new Set<StudioFrameBreakdownDimension>(
  DEFAULT_STUDIO_FRAME_BREAKDOWN_DIMENSIONS
)

export function normalizeStudioFrameBreakdownDimensions(
  value: unknown
): StudioFrameBreakdownDimension[] {
  if (!Array.isArray(value)) return [...DEFAULT_STUDIO_FRAME_BREAKDOWN_DIMENSIONS]
  const normalized = [...new Set(value.filter(
    (item): item is StudioFrameBreakdownDimension => VALID_DIMENSIONS.has(item as StudioFrameBreakdownDimension)
  ))]
  return normalized.length ? normalized : [...DEFAULT_STUDIO_FRAME_BREAKDOWN_DIMENSIONS]
}

/** 至少保留一个拆解维度，避免生成无意义的空分析任务。 */
export function toggleStudioFrameBreakdownDimension(
  current: unknown,
  dimension: StudioFrameBreakdownDimension
): StudioFrameBreakdownDimension[] {
  const normalized = normalizeStudioFrameBreakdownDimensions(current)
  if (!normalized.includes(dimension)) return [...normalized, dimension]
  if (normalized.length === 1) return normalized
  return normalized.filter((item) => item !== dimension)
}

export function isStudioFrameBreakdownVideoCandidate(
  targetNodeId: string,
  candidate: Pick<StudioCanvasNode, 'id' | 'data'>
): boolean {
  if (candidate.id === targetNodeId || candidate.data.kind === 'frame_breakdown') return false
  const isVideo = candidate.data.kind === 'video' || candidate.data.mediaKind === 'video'
  return isVideo && Boolean(candidate.data.mediaUrl?.trim())
}
