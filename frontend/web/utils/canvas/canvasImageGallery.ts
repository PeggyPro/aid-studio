import type { CanvasTaskHistory } from '@/types/canvas-api'

export type CanvasImageGalleryResult = {
  resultId: string
  resultIndex: number
  resultUrl: string
}

/** Results belong to one generation run; older node history is never part of its gallery. */
export function canvasImageGalleryResults(history: CanvasTaskHistory, taskId: string): CanvasImageGalleryResult[] {
  const task = history.items.find((item) => String(item.taskId) === taskId && item.mediaType === 'IMAGE')
  if (!task || String(task.status).toUpperCase() !== 'SUCCEEDED') return []
  return task.results
    .map((result, index) => ({
      resultId: result.resultId == null ? '' : String(result.resultId),
      resultIndex: Number.isInteger(result.resultIndex) ? Number(result.resultIndex) : index,
      resultUrl: result.resultUrl?.trim() || '',
      persisting: result.persisting
    }))
    .filter((result) => result.resultId && result.resultUrl && !result.persisting)
    .sort((left, right) => left.resultIndex - right.resultIndex)
    .map(({ resultId, resultIndex, resultUrl }) => ({ resultId, resultIndex, resultUrl }))
}
