import type { CanvasCurrentResult, CanvasTaskProjection } from '@/types/canvas-api'

/** A previous selection on the same node is not the result of the completed task. */
export function isCurrentCanvasTaskResult(
  result: CanvasCurrentResult | undefined,
  taskId: string,
  inputVersion: number
): boolean {
  return Boolean(result?.resultUrl)
    && String(result?.taskId) === taskId
    && Number(result?.inputObjectVersion) === inputVersion
}

export function needsCanvasTerminalResult(
  task: CanvasTaskProjection | undefined,
  result: CanvasCurrentResult | undefined,
  objectVersion: number
): boolean {
  const mediaType = String(task?.mediaType).toUpperCase()
  return Boolean(task?.taskId)
    && String(task?.status).toUpperCase() === 'SUCCEEDED'
    && ['IMAGE', 'VIDEO', 'AUDIO'].includes(mediaType)
    && !(mediaType === 'AUDIO' && !!task?.textContent && !task?.ossUrl && !task?.originUrl)
    && (task?.inputObjectVersion == null || Number(task.inputObjectVersion) === objectVersion)
    && !isCurrentCanvasTaskResult(result, String(task?.taskId), objectVersion)
}
