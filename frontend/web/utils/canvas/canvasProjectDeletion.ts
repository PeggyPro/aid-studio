import type { CanvasId, CanvasTaskProjection } from '@/types/canvas-api'
import { canvasProjectDelete, canvasWorkspaceActiveTasks } from '@/utils/business/canvas'
import { userTaskCancel } from '@/utils/business/task'

function taskLabel(task: CanvasTaskProjection): string {
  if (task.sourceType === 'RUNTIME_RUN') return 'Agent 或 Skill 运行'
  const status = String(task.status || '').toUpperCase()
  if (status === 'QUEUED') return '排队任务'
  if (status === 'PENDING') return '待处理任务'
  return '正在执行的生成任务'
}

/**
 * 项目删除的服务端契约要求工作区没有活跃任务。删除前只自动取消当前用户
 * 仍可取消的媒体排队任务；已经受理、由他人发起或 Runtime 管理的任务保留
 * 服务端权威状态，并向用户说明阻塞原因。
 */
export async function deleteCanvasProjectAfterCancellingQueuedTasks(
  projectId: CanvasId,
  workspaceId: CanvasId
): Promise<{ cancelledTaskCount: number }> {
  const active = await canvasWorkspaceActiveTasks(workspaceId)
  const blocked = active.items.find((task) => task.sourceType === 'RUNTIME_RUN'
    || String(task.status || '').toUpperCase() !== 'QUEUED'
    || task.taskId == null
    || task.canCancel !== true)
  if (blocked) {
    throw new Error(`项目仍有${taskLabel(blocked)}，请先停止或等待任务结束后再删除`)
  }

  for (const task of active.items) {
    await userTaskCancel({ taskId: String(task.taskId), taskSource: 'MEDIA' })
  }
  if (active.items.length > 0) {
    const refreshed = await canvasWorkspaceActiveTasks(workspaceId)
    if (refreshed.items.length > 0) {
      throw new Error('排队任务的取消状态尚未同步，请稍后重试删除项目')
    }
  }

  await canvasProjectDelete(projectId)
  return { cancelledTaskCount: active.items.length }
}
