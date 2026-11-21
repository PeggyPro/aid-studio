import type { StudioCanvasNode, StudioTask } from '@/types/studio'

/** 普通节点可撤销删除；运行中的远端工作及结构化分镜需要用户确认。 */
export function studioDeleteReason(nodes: StudioCanvasNode[], tasks: StudioTask[]): string | null {
  const ids = new Set(nodes.map((node) => node.id))
  const activeTask = tasks.some((task) =>
    ['queued', 'running', 'waiting'].includes(task.status)
    && [task.nodeId, ...(task.targetNodeIds ?? [])].some((id) => id && ids.has(id)))
  if (activeTask || nodes.some(({ data }) =>
    ['queued', 'generating'].includes(data.status)
    || ['QUEUED', 'PENDING', 'PROCESSING'].includes(data.storyboardPromptTaskStatus ?? ''))) {
    return '这些节点关联尚未完成的任务。可取消的排队任务会先停止；已开始执行或不可取消的任务需等待结束后再删除。'
  }
  if (nodes.some(({ data }) => (data.storyboardRows?.length ?? 0) > 0
    || data.gridStoryboard?.cells.some((cell) => cell.mediaUrl || cell.status === 'generating' || cell.status === 'uploading')
    || data.layerSeparation?.dirty)) {
    return '这些节点包含分镜内容、宫格素材或未保存的图层编辑，删除会同时移除这些编辑数据。'
  }
  if (nodes.some(({ data }) => data.mediaUrl?.startsWith('blob:'))) {
    return '节点包含尚未持久化的本地媒体，删除后撤销可能无法恢复该临时文件，请先保存素材。'
  }
  return null
}
