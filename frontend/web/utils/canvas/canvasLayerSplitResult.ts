import type { LayerSplitData } from '@/types/business-api'
import type { CanvasTaskHistory } from '@/types/canvas-api'

type HistoryResult = CanvasTaskHistory['items'][number]['results'][number]

function positiveDimension(value: unknown): number | undefined {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : undefined
}

function layerRectFromEdges(value: unknown, canvasWidth: number, canvasHeight: number) {
  if (!Array.isArray(value) || value.length !== 4 || !value.every(Number.isInteger)) return null
  const [left, top, right, bottom] = value
  if (left < 0 || top < 0 || right > canvasWidth || bottom > canvasHeight || right <= left || bottom <= top) return null
  return { x: left / canvasWidth, y: top / canvasHeight, w: (right - left) / canvasWidth, h: (bottom - top) / canvasHeight }
}

function layerRect(value: unknown, canvasWidth?: number, canvasHeight?: number) {
  const fullCanvas = { x: 0, y: 0, w: 1, h: 1 }
  if (!value || typeof value !== 'object') return fullCanvas
  const box = value as Record<string, unknown>
  const absoluteRect = canvasWidth && canvasHeight ? layerRectFromEdges(box.absolute, canvasWidth, canvasHeight) : null
  const officialRect = absoluteRect ?? layerRectFromEdges(box.normalized, 1000, 1000)
  if (officialRect) return officialRect
  if (!canvasWidth || !canvasHeight) return fullCanvas
  const x = Number(box.x)
  const y = Number(box.y)
  const width = Number(box.width)
  const height = Number(box.height)
  if (![x, y, width, height].every(Number.isFinite) || width <= 0 || height <= 0) return fullCanvas
  const normalized = x >= 0 && y >= 0 && x + width <= 1 && y + height <= 1
  const rect = normalized
    ? { x, y, w: width, h: height }
    : { x: x / canvasWidth, y: y / canvasHeight, w: width / canvasWidth, h: height / canvasHeight }
  if (rect.x < 0 || rect.y < 0 || rect.x + rect.w > 1.001 || rect.y + rect.h > 1.001) return fullCanvas
  return rect
}

function zIndex(result: HistoryResult): number | null {
  const raw = result.metadata?.zIndex
  if (raw === null || raw === undefined) return null
  const value = Number(raw)
  return Number.isInteger(value) && value >= 0 ? value : null
}

/** Returns null while the server is still publishing the task's protected resources. */
export function canvasLayerSplitResult(history: CanvasTaskHistory, taskId: string): LayerSplitData | null {
  const task = history.items.find((item) => String(item.taskId) === taskId)
  if (!task || String(task.status).toUpperCase() !== 'SUCCEEDED') return null
  if (!task.results.length || task.results.some((result) => result.persisting)) return null
  const ordered = [...task.results].sort((left, right) => (zIndex(left) ?? Infinity) - (zIndex(right) ?? Infinity))
  if (ordered.length < 2 || zIndex(ordered[0]!) !== 0) throw new Error('图层分离结果缺少底图或透明图层')
  const seen = new Set<number>()
  for (const result of ordered) {
    const index = zIndex(result)
    if (index === null || seen.has(index) || !result.resourceId || !result.resultUrl) {
      throw new Error('图层分离结果的顺序、资源或图片地址不完整')
    }
    seen.add(index)
  }
  const background = ordered[0]!
  const canvasWidth = positiveDimension(background.metadata?.width)
  const canvasHeight = positiveDimension(background.metadata?.height)
  return {
    backgroundResourceId: String(background.resourceId),
    backgroundUrl: background.resultUrl!,
    canvasWidth,
    canvasHeight,
    layers: ordered.slice(1).map((result) => ({
      id: `layer-${result.resultId}`,
      resourceId: String(result.resourceId),
      mediaUrl: result.resultUrl!,
      label: String(result.metadata?.name || result.metadata?.description || `图层 ${zIndex(result)}`),
      rect: layerRect(result.metadata?.boundingBox, canvasWidth, canvasHeight)
    }))
  }
}
