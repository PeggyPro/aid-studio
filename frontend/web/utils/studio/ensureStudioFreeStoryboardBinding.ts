import { userStoryboardCreate } from '@/utils/businessApi'
import { buildStoryboardCreateRequest } from '@/utils/storyboardCreateRequest'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'

const FREE_BIND_NEED_PROJECT_MSG = '请从「我的作品」打开作品后再进行图片操作'

/**
 * 自由画布没有流程「分镜」业务概念。
 * 这里创建的是底层任务/计费所需的服务端记录（API 字段仍叫 storyboardId），
 * 仅用于归属与报价，不对用户暴露为流程分镜节点。
 */
export async function ensureStudioFreeStoryboardBinding(input: {
  nodeId: string
  projectId: number | null | undefined
  episodeId: number | null | undefined
  title?: string
}): Promise<{ storyboardId: number } | { error: string }> {
  const store = useStudioUiStore.getState()
  const node = store.nodes.find((item) => item.id === input.nodeId)
  if (!node) return { error: '节点不存在' }

  const existing = Number(node.data.storyboardId)
  if (Number.isFinite(existing) && existing > 0) {
    return { storyboardId: existing }
  }

  const projectId = Number(input.projectId)
  const episodeId = Number(input.episodeId)
  if (!Number.isFinite(projectId) || projectId <= 0 || !Number.isFinite(episodeId) || episodeId < 0) {
    return { error: FREE_BIND_NEED_PROJECT_MSG }
  }

  try {
    const created = await userStoryboardCreate(
      buildStoryboardCreateRequest(
        { projectId, episodeId },
        input.title?.trim() || node.data.title || '自由画布图片'
      )
    )
    const storyboardId = Number(created?.id)
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) {
      return { error: '创建任务绑定失败，请稍后重试' }
    }
    useStudioUiStore.getState().updateNodeData(input.nodeId, {
      storyboardId: String(storyboardId)
    })
    return { storyboardId }
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message.trim() : ''
    return { error: message || '创建任务绑定失败，请稍后重试' }
  }
}

export function readNodeStoryboardBindingId(node: StudioCanvasNode): number | null {
  const id = Number(node.data.storyboardId)
  return Number.isFinite(id) && id > 0 ? id : null
}
