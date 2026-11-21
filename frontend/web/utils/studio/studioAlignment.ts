export interface StudioAlignmentRect {
  id: string
  x: number
  y: number
  width: number
  height: number
}

export interface StudioAlignmentResult {
  dx: number
  dy: number
  verticalGuide?: number
  horizontalGuide?: number
  verticalGuides: number[]
  horizontalGuides: number[]
  verticalAnchor?: 'left' | 'center' | 'right'
  horizontalAnchor?: 'top' | 'center' | 'bottom'
}

const X_ANCHORS = [
  ['left', (rect: StudioAlignmentRect) => rect.x],
  ['center', (rect: StudioAlignmentRect) => rect.x + rect.width / 2],
  ['right', (rect: StudioAlignmentRect) => rect.x + rect.width]
] as const

const Y_ANCHORS = [
  ['top', (rect: StudioAlignmentRect) => rect.y],
  ['center', (rect: StudioAlignmentRect) => rect.y + rect.height / 2],
  ['bottom', (rect: StudioAlignmentRect) => rect.y + rect.height]
] as const

/** 返回最近的同类锚点吸附偏移，阈值使用画布世界坐标。 */
export function findStudioAlignment(
  moving: StudioAlignmentRect,
  candidates: StudioAlignmentRect[],
  threshold: number
): StudioAlignmentResult {
  let bestX: { delta: number; guide: number; anchor: StudioAlignmentResult['verticalAnchor'] } | null = null
  let bestY: { delta: number; guide: number; anchor: StudioAlignmentResult['horizontalAnchor'] } | null = null

  for (const candidate of candidates) {
    for (const [anchor, read] of X_ANCHORS) {
      const delta = read(candidate) - read(moving)
      if (Math.abs(delta) <= threshold && (!bestX || Math.abs(delta) < Math.abs(bestX.delta))) {
        bestX = { delta, guide: read(candidate), anchor }
      }
    }
    for (const [anchor, read] of Y_ANCHORS) {
      const delta = read(candidate) - read(moving)
      if (Math.abs(delta) <= threshold && (!bestY || Math.abs(delta) < Math.abs(bestY.delta))) {
        bestY = { delta, guide: read(candidate), anchor }
      }
    }
  }

  const verticalGuides = bestX
    ? uniqueGuides(candidates.flatMap((candidate) => X_ANCHORS.flatMap(([, read]) => {
        const delta = read(candidate) - read(moving)
        return Math.abs(delta - bestX.delta) < 0.01 ? [read(candidate)] : []
      })))
    : []
  const horizontalGuides = bestY
    ? uniqueGuides(candidates.flatMap((candidate) => Y_ANCHORS.flatMap(([, read]) => {
        const delta = read(candidate) - read(moving)
        return Math.abs(delta - bestY.delta) < 0.01 ? [read(candidate)] : []
      })))
    : []

  return {
    dx: bestX?.delta ?? 0,
    dy: bestY?.delta ?? 0,
    verticalGuide: bestX?.guide,
    horizontalGuide: bestY?.guide,
    verticalGuides,
    horizontalGuides,
    verticalAnchor: bestX?.anchor,
    horizontalAnchor: bestY?.anchor
  }
}

function uniqueGuides(guides: number[]): number[] {
  return [...new Set(guides.map((guide) => Number(guide.toFixed(4))))].sort((a, b) => a - b)
}

/** 合并多选节点的世界坐标边界，供整组节点统一吸附。 */
export function boundsOfStudioRects(rects: StudioAlignmentRect[]): StudioAlignmentRect | null {
  if (!rects.length) return null
  const left = Math.min(...rects.map((rect) => rect.x))
  const top = Math.min(...rects.map((rect) => rect.y))
  const right = Math.max(...rects.map((rect) => rect.x + rect.width))
  const bottom = Math.max(...rects.map((rect) => rect.y + rect.height))
  return { id: 'selection', x: left, y: top, width: right - left, height: bottom - top }
}
