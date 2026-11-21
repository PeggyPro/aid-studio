import type { StudioAgentDockRect } from '@/utils/studio/studioNodeAgentDockLayout'
import { studioNodeVisualElement } from './studioNodeVisualBounds'

export interface StudioMaskSurfaceLayout {
  left: number
  top: number
  width: number
  height: number
}

/**
 * Image edit frames may overflow the React Flow node (outpainting is the main
 * case). DOM node bounds do not include overflowing descendants, so the bottom
 * toolbar must use the visible card and active edit frame. React Flow's wrapper
 * can retain a wider measured box after a portrait image loads; it is only a fallback.
 */
export function measureStudioImageEditBoundsInCanvas(
  canvas: HTMLElement,
  nodeId: string
): StudioAgentDockRect | null {
  const safeId = typeof CSS !== 'undefined' && typeof CSS.escape === 'function'
    ? CSS.escape(nodeId)
    : nodeId.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
  const node = canvas.querySelector(`.react-flow__node[data-id="${safeId}"]`)
  if (!(node instanceof HTMLElement)) return null
  const canvasRect = canvas.getBoundingClientRect()
  const visible = studioNodeVisualElement(node)
  const elements = [
    visible,
    node.querySelector('.studio-image-crop-overlay__frame, .studio-image-expand-overlay__frame')
  ].filter((item): item is HTMLElement => item instanceof HTMLElement)
  const rects = elements.map((item) => item.getBoundingClientRect())
    .filter((rect) => rect.width >= 1 && rect.height >= 1)
  if (!rects.length) return null
  const left = Math.min(...rects.map((rect) => rect.left)) - canvasRect.left
  const top = Math.min(...rects.map((rect) => rect.top)) - canvasRect.top
  const right = Math.max(...rects.map((rect) => rect.right)) - canvasRect.left
  const bottom = Math.max(...rects.map((rect) => rect.bottom)) - canvasRect.top
  return { left, top, right, bottom, width: right - left, height: bottom - top }
}

/** 量取节点内图片预览区在 canvas 容器中的位置，供擦除/重绘蒙版屏幕空间定位。 */
export function measureStudioNodePreviewInCanvas(
  canvas: HTMLElement,
  nodeId: string
): StudioAgentDockRect | null {
  const safeId = typeof CSS !== 'undefined' && typeof CSS.escape === 'function'
    ? CSS.escape(nodeId)
    : nodeId.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
  const node = canvas.querySelector(`.react-flow__node[data-id="${safeId}"]`)
  if (!(node instanceof HTMLElement)) return null
  const preview = node.querySelector('.studio-image-node-preview, .studio-node-card__preview')
  if (!(preview instanceof HTMLElement)) return null
  const canvasRect = canvas.getBoundingClientRect()
  const rect = preview.getBoundingClientRect()
  if (rect.width < 1 || rect.height < 1) return null
  const left = rect.left - canvasRect.left
  const top = rect.top - canvasRect.top
  const right = rect.right - canvasRect.left
  const bottom = rect.bottom - canvasRect.top
  return {
    left,
    top,
    right,
    bottom,
    width: right - left,
    height: bottom - top
  }
}

export function toMaskSurfaceLayout(rect: StudioAgentDockRect): StudioMaskSurfaceLayout {
  return {
    left: rect.left,
    top: rect.top,
    width: rect.width,
    height: rect.height
  }
}

/** 蒙版逻辑分辨率：与屏幕 zoom 解耦，导出时缩放至原图像素。 */
export function resolveStudioMaskCanvasSize(
  screenWidth: number,
  screenHeight: number,
  maxEdge = 1024
): { width: number; height: number } {
  const w = Math.max(1, Math.round(screenWidth))
  const h = Math.max(1, Math.round(screenHeight))
  const scale = maxEdge / Math.max(w, h)
  if (scale >= 1) return { width: w, height: h }
  return {
    width: Math.max(1, Math.round(w * scale)),
    height: Math.max(1, Math.round(h * scale))
  }
}

export function maskLayoutsEqual(
  a: StudioMaskSurfaceLayout | null | undefined,
  b: StudioMaskSurfaceLayout | null | undefined,
  epsilon = 0.5
): boolean {
  if (!a || !b) return false
  return Math.abs(a.left - b.left) < epsilon
    && Math.abs(a.top - b.top) < epsilon
    && Math.abs(a.width - b.width) < epsilon
    && Math.abs(a.height - b.height) < epsilon
}
