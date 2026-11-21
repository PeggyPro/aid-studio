import type { UserModelListItem } from '@/types/business-api'
import {
  buildAspectRatioSelectOptions,
  buildCountSelectOptions,
  buildDurationSelectOptions,
  buildQualitySelectOptions,
  buildVideoCountSelectOptions,
  buildVideoQualitySelectOptions,
  parseModelCapability,
  type ModelCapabilitySnapshot,
  type SelectOption
} from '@/utils/modelCapability'

/** @deprecated 自由画布旧节点控件兜底；多功能参数面板请用 resolveStudioParamsPanelOptions */
export const STUDIO_FREE_ASPECT_RATIO_VALUES = ['16:9', '9:16', '1:1', '4:3'] as const

/** @deprecated 见 resolveStudioParamsPanelOptions */
export const STUDIO_ASPECT_RATIO_OPTIONS = [
  '1:1', '1:2', '2:1', '9:16', '16:9', '3:4', '4:3', '3:2', '2:3', '4:5', '5:4', '21:9', '9:21'
].map((value) => ({
  value,
  label: value
}))

/** @deprecated 见 resolveStudioParamsPanelOptions */
export function studioAspectRatioOptions(mode: 'free' | 'flow') {
  return mode === 'flow'
    ? STUDIO_ASPECT_RATIO_OPTIONS
    : STUDIO_FREE_ASPECT_RATIO_VALUES.map((value) => ({ value, label: value }))
}

export const STUDIO_VIDEO_DURATION_OPTIONS = [3, 5, 8].map((value) => ({
  value,
  label: `${value} 秒`
}))

export const STUDIO_VOICE_OPTIONS = ['自然叙事', '克制电影感', '轻快对白'].map((value) => ({
  value,
  label: value
}))

const VIDEO_FALLBACK_SIZES = ['720p', '1080p'] as const

/**
 * 多功能输入框参数面板：按模型 capability 解析比例 / 清晰度 / 数量 / 秒数。
 * 与编辑分镜视频 / 分镜图弹窗同源（modelCapability），禁止写死全量比例与清晰度。
 */
export function resolveStudioParamsPanelOptions(
  mode: 'image' | 'video',
  model?: UserModelListItem | null,
  allowAutomaticDuration = false
): {
  snapshot: ModelCapabilitySnapshot
  ratioOptions: SelectOption<string>[]
  qualityOptions: SelectOption<string>[]
  countOptions: SelectOption<number>[]
  durationOptions: SelectOption<string>[]
  supportsDuration: boolean
} {
  const parsed = parseModelCapability(model, undefined, allowAutomaticDuration)
  // parseModelCapability(null) 会填入图片清晰度默认档；视频无模型时改用视频档
  const snapshot: ModelCapabilitySnapshot = mode === 'video' && !model
    ? {
        ...parsed,
        sizeOptions: [...VIDEO_FALLBACK_SIZES],
        defaultSize: '1080p'
      }
    : parsed

  return {
    snapshot,
    ratioOptions: buildAspectRatioSelectOptions(snapshot),
    qualityOptions: mode === 'video'
      ? buildVideoQualitySelectOptions(snapshot)
      : buildQualitySelectOptions(snapshot),
    countOptions: mode === 'video'
      ? buildVideoCountSelectOptions(snapshot)
      : buildCountSelectOptions(snapshot),
    durationOptions: buildDurationSelectOptions(snapshot),
    supportsDuration: mode === 'video' && snapshot.supportsDuration && snapshot.durationOptions.length > 0
  }
}
