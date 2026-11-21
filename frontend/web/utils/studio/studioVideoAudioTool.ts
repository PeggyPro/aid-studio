import type { StudioVideoAudioOperation } from '@/types/business-api'
import type { StudioVideoAudioTool, StudioVideoPurpose } from '@/types/studio'

export interface StudioVideoAudioToolDefinition {
  label: string
  videoTitleSuffix: string
  audioTitleSuffix: string
  videoPurpose: Extract<StudioVideoPurpose, 'audio_separation' | 'vocal_extraction' | 'background_audio_extraction'>
  businessOperation: StudioVideoAudioOperation
}

/** 工具菜单、节点命名和 API operation 共用同一注册表，避免三处映射漂移。 */
export const STUDIO_VIDEO_AUDIO_TOOLS: Record<StudioVideoAudioTool, StudioVideoAudioToolDefinition> = {
  separate: {
    label: '音视频分离',
    videoTitleSuffix: '无声视频',
    audioTitleSuffix: '音轨',
    videoPurpose: 'audio_separation',
    businessOperation: 'SEPARATE_AUDIO_VIDEO'
  },
  extract_vocals: {
    label: '人声提取',
    videoTitleSuffix: '人声分离视频',
    audioTitleSuffix: '人声',
    videoPurpose: 'vocal_extraction',
    businessOperation: 'EXTRACT_VOCALS'
  },
  extract_background_audio: {
    label: '背景音提取',
    videoTitleSuffix: '背景音分离视频',
    audioTitleSuffix: '背景音',
    videoPurpose: 'background_audio_extraction',
    businessOperation: 'EXTRACT_BACKGROUND_AUDIO'
  }
}

export function studioVideoAudioToolDefinition(operation: StudioVideoAudioTool): StudioVideoAudioToolDefinition {
  return STUDIO_VIDEO_AUDIO_TOOLS[operation]
}
