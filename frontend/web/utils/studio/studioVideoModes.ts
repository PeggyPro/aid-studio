import type { StudioMode, StudioVideoGenMode, StudioVideoPurpose } from '@/types/studio'

const ALL_MODES: StudioVideoGenMode[] = ['imageToVideo', 'multiParam', 'gridVideo']

export function studioVideoModes(input: {
  studioMode: StudioMode
  creationMode?: 'i2v' | 'multi' | 'pro' | 'grid' | string
}): StudioVideoGenMode[] {
  if (input.studioMode === 'free' || !input.creationMode) return [...ALL_MODES]
  if (input.creationMode === 'i2v') return ['imageToVideo']
  if (input.creationMode === 'grid' || input.creationMode === 'auto_grid') return ['gridVideo']
  if (input.creationMode === 'multi' || input.creationMode === 'pro') return ['multiParam']
  return [...ALL_MODES]
}

/**
 * 模式栏目只在「这次生成还要用户选图生 / 多参 / 宫格」时出现。
 * 头部工具派生的视频节点已经绑了具体功能，没有这道选择。
 * 当前功能只剩一种模式时也不展示，避免一个无法切换的栏目。
 */
export function studioVideoModeChoices(input: {
  studioMode: StudioMode
  creationMode?: 'i2v' | 'multi' | 'pro' | 'grid' | string
  videoPurpose?: StudioVideoPurpose | null
}): StudioVideoGenMode[] {
  if (input.videoPurpose) return []
  const modes = studioVideoModes(input)
  return modes.length > 1 ? modes : []
}
