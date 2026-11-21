import { studioNodeEmptyNextSteps } from '@/config/studioNodeEmptyNextSteps'
import type { StudioFlowEdge, StudioNodeData } from '@/types/studio'
import {
  isStudioEmptyPlaceholderNode,
  isStudioGridStoryboardNode,
  isStudioImageHdConfigNode,
  isStudioImageMattingPendingNode,
  isStudioImagePendingNode,
  isStudioMediaFillNode,
  isStudioVideoHdConfigNode,
  isStudioVideoPendingNode
} from '@/utils/studio/studioImageNode'
import { isStudioScriptContentNode, resolveStudioScriptNodeText } from '@/utils/studio/studioScriptNode'

/** 连线两端都不再展示空节点的快捷动作轨。 */
export function isStudioNodeConnected(edges: StudioFlowEdge[], nodeId: string): boolean {
  return edges.some((edge) => edge.source === nodeId || edge.target === nodeId)
}

/** 画布级动作轨的唯一可见性判定，避免节点内容与外置 Chrome 对空态产生不同理解。 */
export function isStudioNodeEmptyActionTarget(data: StudioNodeData, connected = false): boolean {
  if (connected) return false
  if (data.emptyNextStepConsumed || !studioNodeEmptyNextSteps(data.kind).length) return false

  return isStudioEmptyPlaceholderNode({
    data,
    textContent: resolveStudioScriptNodeText(data),
    generating: data.status === 'queued' || data.status === 'generating',
    uploading: false,
    scriptNode: isStudioScriptContentNode(data),
    mediaFillNode: isStudioMediaFillNode(data),
    gridStoryboardNode: isStudioGridStoryboardNode(data),
    imagePendingNode: isStudioImagePendingNode(data),
    videoPendingNode: isStudioVideoPendingNode(data),
    imageHdConfigNode: isStudioImageHdConfigNode(data),
    imageMattingPendingNode: isStudioImageMattingPendingNode(data),
    videoHdConfigNode: isStudioVideoHdConfigNode(data)
  })
}
