import type { UserModelListItem } from '@/types/business-api'
import type { StudioNodeData } from '@/types/studio'
import { coerceGenerationSettings, coerceVideoGenerationSettings, resolveCapabilityRecord } from '@/utils/modelCapability'
import { resolveStudioParamsPanelOptions } from '@/utils/studio/studioGenerationOptions'

/** 用户明确选择模型时一次性保存匹配参数；目录加载和查看节点不产生写入。 */
export function studioExplicitModelSelection(
  mode: 'image' | 'video',
  model: UserModelListItem,
  current: NonNullable<StudioNodeData['generationOptions']>,
  allowAutomaticDuration = false
): Partial<StudioNodeData> {
  const panel = resolveStudioParamsPanelOptions(mode, model, allowAutomaticDuration)
  const settings = {
    aspectRatio: current.aspectRatio ?? '',
    count: mode === 'video' ? 1 : current.count ?? 1,
    quality: current.quality ?? ''
  }
  let generationOptions: NonNullable<StudioNodeData['generationOptions']>
  if (mode === 'video') {
    const next = coerceVideoGenerationSettings({
      ...settings,
      duration: String(current.duration ?? panel.snapshot.defaultDurationSeconds),
      audio: current.audioEnabled === true ? 'with_audio' : 'silent'
    }, panel.snapshot)
    const duration = Number(next.duration)
    generationOptions = {
      ...current, aspectRatio: next.aspectRatio, count: 1, quality: next.quality,
      audioEnabled: next.audio === 'with_audio',
      ...(Number.isFinite(duration) && (duration > 0 || duration === -1 && allowAutomaticDuration) ? { duration } : {})
    }
  } else {
    const next = coerceGenerationSettings(settings, panel.snapshot)
    generationOptions = {
      ...current, aspectRatio: next.aspectRatio, count: next.count,
      quality: panel.qualityOptions.length && (model.supportsSizePreset ?? resolveCapabilityRecord(model).supportsSizePreset) !== false ? next.quality : ''
    }
  }
  return {
    model: model.modelCode, modelName: model.modelName,
    modelCostCredits: model.costCredits, modelIsFree: model.isFree, generationOptions
  }
}
