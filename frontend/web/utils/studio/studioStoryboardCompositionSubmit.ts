import type { StudioCanvasNode, StudioImagePurpose } from '@/types/studio'
import {
  buildStudioStoryboardCompositionPrompt,
  getStudioStoryboardComposition,
  resolveStudioStoryboardCompositionReferenceUrl,
  type StudioStoryboardCompositionItem,
  type StudioStoryboardCompositionRoute
} from '@/utils/studio/studioStoryboardComposition'

export type StudioStoryboardCompositionSubmitPlan = {
  item: StudioStoryboardCompositionItem
  finalPrompt: string
  referenceImage: string
  route: StudioStoryboardCompositionRoute
  label: string
  purpose: StudioImagePurpose
  writeToSelf: boolean
}

/**
 * 分镜构图提交前规划：解析功能、最终 prompt、参考图与是否写回当前空下游节点。
 * 编排层（hooks）只消费本结果，避免在 UI/hook 里散落分支。
 */
export function planStudioStoryboardCompositionSubmit(input: {
  node: StudioCanvasNode
  userPrompt: string
}): StudioStoryboardCompositionSubmitPlan | { error: string } {
  const compositionId = input.node.data.storyboardCompositionId
  const item = getStudioStoryboardComposition(compositionId)
  if (!item) {
    return { error: '请先选择分镜构图功能' }
  }

  const referenceImage = resolveStudioStoryboardCompositionReferenceUrl(input.node.data)
  if (!referenceImage) {
    return { error: '请保留参考图后再生成' }
  }

  const hasMedia = Boolean(String(input.node.data.mediaUrl || '').trim())
  return {
    item,
    finalPrompt: buildStudioStoryboardCompositionPrompt(item, input.userPrompt),
    referenceImage,
    route: item.generateRoute,
    label: item.label,
    purpose: item.imagePurpose,
    writeToSelf: Boolean(compositionId) && !hasMedia
  }
}
