'use client'

import type { UserModelListItem } from '@/types/business-api'
import type { StudioCanvasNode, StudioNodeData } from '@/types/studio'
import { useStudioUiStore } from '@/stores/studioUi'
import { pickStudioComposerDefaultModel } from '@/utils/studio/studioComposerDefaultModel'
import { studioExplicitModelSelection } from '@/utils/studio/studioExplicitModelSelection'

/** Default selection is a read-only projection until a parameter edit or submission. */
export function useCanvasComposerModel(
  original: StudioCanvasNode,
  models: UserModelListItem[],
  options: { enabled: boolean; loading: boolean; preferredModelCode?: string; capability?: string }
) {
  const scopeKey = useStudioUiStore((state) => state.scopeKey)
  const missing = !String(original.data.model || '').trim()
  const model = options.enabled && !options.loading && missing
    ? pickStudioComposerDefaultModel(models, [options.preferredModelCode]) : undefined
  const patch: Partial<StudioNodeData> | undefined = model
    ? original.data.kind === 'image' || original.data.kind === 'video'
      ? studioExplicitModelSelection(original.data.kind, model, original.data.generationOptions ?? {}, Boolean(options.capability))
      : { model: model.modelCode, modelName: model.modelName, modelCostCredits: model.costCredits, modelIsFree: model.isFree }
    : undefined
  const node = patch ? { ...original, data: { ...original.data, ...patch } } : original

  const commit = (changes: Partial<StudioNodeData> = {}) => {
    const store = useStudioUiStore.getState()
    const latest = store.nodes.find((item) => item.id === original.id)
    if (store.scopeKey !== scopeKey || !latest) throw new Error('画布或节点已变化，请重新确认')
    if (patch && JSON.stringify([
      latest.data.model, latest.data.generationOptions, latest.data.mediaUrl, latest.data.composerRefs,
      latest.data.videoPurpose, latest.data.imageDescribe
    ]) !== JSON.stringify([
      original.data.model, original.data.generationOptions, original.data.mediaUrl, original.data.composerRefs,
      original.data.videoPurpose, original.data.imageDescribe
    ])) throw new Error('节点配置已变化，请重新确认模型和参数')
    if (patch || Object.keys(changes).length) store.updateNodeData(original.id, { ...patch, ...changes })
  }

  return { node, commit }
}
