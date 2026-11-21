import type { StudioTouchEditRect } from '@/types/studio'

const MIN_BRUSH_PATH_SPAN = 0.02

export function clamp01(n: number): number {
  if (Number.isNaN(n)) return 0
  if (n < 0) return 0
  if (n > 1) return 1
  return n
}

function roundNorm(n: number): number {
  return Math.round(n * 1e6) / 1e6
}

export function normalizeRect(
  a: { x: number; y: number },
  b: { x: number; y: number }
): StudioTouchEditRect {
  const x1 = Math.min(a.x, b.x)
  const y1 = Math.min(a.y, b.y)
  const x2 = Math.max(a.x, b.x)
  const y2 = Math.max(a.y, b.y)
  const x = clamp01(x1)
  const y = clamp01(y1)
  return {
    x: roundNorm(x),
    y: roundNorm(y),
    w: roundNorm(clamp01(x2) - x),
    h: roundNorm(clamp01(y2) - y)
  }
}

export function pointToDefaultRect(x: number, y: number, size = 0.12): StudioTouchEditRect {
  const s = Math.max(0.02, size)
  return {
    x: x - s / 2,
    y: y - s / 2,
    w: s,
    h: s
  }
}

export function isBrushPathMeaningful(path: { x: number; y: number }[]): boolean {
  if (!path || path.length < 2) return false
  let minX = path[0]!.x
  let maxX = path[0]!.x
  let minY = path[0]!.y
  let maxY = path[0]!.y
  for (let i = 1; i < path.length; i += 1) {
    const p = path[i]!
    minX = Math.min(minX, p.x)
    maxX = Math.max(maxX, p.x)
    minY = Math.min(minY, p.y)
    maxY = Math.max(maxY, p.y)
  }
  return (maxX - minX) >= MIN_BRUSH_PATH_SPAN || (maxY - minY) >= MIN_BRUSH_PATH_SPAN
}

export function brushPathToRect(
  path: { x: number; y: number }[],
  brushSize: number
): StudioTouchEditRect | null {
  if (!isBrushPathMeaningful(path)) return null
  let minX = path[0]!.x
  let maxX = path[0]!.x
  let minY = path[0]!.y
  let maxY = path[0]!.y
  for (let i = 1; i < path.length; i += 1) {
    const p = path[i]!
    minX = Math.min(minX, p.x)
    maxX = Math.max(maxX, p.x)
    minY = Math.min(minY, p.y)
    maxY = Math.max(maxY, p.y)
  }
  const pad = Math.max(0.005, brushSize / 2)
  return clampRectToImage({
    x: minX - pad,
    y: minY - pad,
    w: (maxX - minX) + pad * 2,
    h: (maxY - minY) + pad * 2
  })
}

export function clampRectToImage(rect: StudioTouchEditRect): StudioTouchEditRect {
  const x = clamp01(rect.x)
  const y = clamp01(rect.y)
  const w = Math.max(0, Math.min(rect.w, 1 - x))
  const h = Math.max(0, Math.min(rect.h, 1 - y))
  return { x, y, w, h }
}

export function clientPointToNormalized(
  clientX: number,
  clientY: number,
  imageEl: DOMRect
): { x: number; y: number } {
  if (imageEl.width <= 0 || imageEl.height <= 0) return { x: 0, y: 0 }
  return {
    x: clamp01((clientX - imageEl.left) / imageEl.width),
    y: clamp01((clientY - imageEl.top) / imageEl.height)
  }
}
