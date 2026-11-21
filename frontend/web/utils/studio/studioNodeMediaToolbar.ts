import type { StudioCanvasNode, StudioMode } from '@/types/studio'
import { studioNodeHasLabelContent } from './studioNodeLabels'
import { studioNodeDimensions } from './studioNodeLod'

export interface StudioMediaToolbarRect {
  left: number
  top: number
  right: number
  bottom: number
  width: number
  height: number
}

export interface StudioMediaToolbarPosition {
  left: number
  top: number
  placement: 'top' | 'bottom'
}

const VIEWPORT_MARGIN = 16
/** 工具栏与节点顶部的间距（含头部文字留白） */
export const TOOLBAR_ABOVE_GAP = 28

export function shouldShowStudioNodeMediaToolbar(
  node: StudioCanvasNode | undefined,
  studioMode: StudioMode,
  selectedCount: number
): boolean {
  // 图片顶栏工具条仅属自由画布；流程画布不展示。
  if (studioMode === 'flow') return false
  if (selectedCount !== 1 || !node) return false
  if (node.data.status === 'queued' || node.data.status === 'generating') return false
  if (node.data.kind === 'style_library' || node.data.kind === 'frame_breakdown' || node.data.kind === 'smart_edit') return false
  if (node.data.kind === 'text' && node.data.textEditingEnabled) return false
  if (studioNodeHasLabelContent(node.data)) return true
  if (node.data.kind === 'voice') return Boolean(node.data.mediaUrl) && !node.data.flowBinding
  if (node.data.kind === 'image' || node.data.kind === 'video') return Boolean(node.data.mediaUrl)
  return false
}

export function isStudioNodeMediaToolbarTarget(node: StudioCanvasNode): boolean {
  if (node.data.status === 'queued' || node.data.status === 'generating') return false
  if (node.data.kind === 'style_library' || node.data.kind === 'frame_breakdown' || node.data.kind === 'smart_edit') return false
  if (studioNodeHasLabelContent(node.data)) return true
  if (node.data.kind === 'voice') return Boolean(node.data.mediaUrl) && !node.data.flowBinding
  if (node.data.kind === 'image' || node.data.kind === 'video') return Boolean(node.data.mediaUrl)
  return false
}

/**
 * Build overlay selection from store/world node geometry.
 * Grouped RF children use parent-relative DOM boxes; world→canvas is the stable
 * source that matches the agent dock and avoids flash on group selection.
 */
export function selectionRectFromWorldNode(
  node: Pick<StudioCanvasNode, 'position' | 'data'>,
  toCanvas: (position: { x: number; y: number }) => { x: number; y: number }
): StudioMediaToolbarRect {
  const size = studioNodeDimensions(node)
  const topLeft = toCanvas(node.position)
  const bottomRight = toCanvas({
    x: node.position.x + size.width,
    y: node.position.y + size.height
  })
  return {
    left: topLeft.x,
    top: topLeft.y,
    right: bottomRight.x,
    bottom: bottomRight.y,
    width: bottomRight.x - topLeft.x,
    height: bottomRight.y - topLeft.y
  }
}

export function placeStudioNodeMediaToolbar(
  selection: StudioMediaToolbarRect,
  viewport: StudioMediaToolbarRect,
  size: { width: number; height: number }
): StudioMediaToolbarPosition {
  const centeredLeft = selection.left + (selection.width - size.width) / 2
  const left = clamp(
    centeredLeft,
    viewport.left + VIEWPORT_MARGIN,
    viewport.right - size.width - VIEWPORT_MARGIN
  )
  // 节点滚到视口顶部时允许工具栏随节点离开可视区，不能翻到底部遮挡节点输入框。
  const top = selection.top - TOOLBAR_ABOVE_GAP - size.height
  return { left, top, placement: 'top' }
}

/**
 * 空节点下一步条定位。
 * 外框必须等于可见卡片宽度，标签在这张外框里居中。
 * 不能用节点外层测量值或 shrink-to-fit：卡片变宽后外层会停在旧宽度，标签就偏到左侧。
 */
export function placeStudioNodeEmptyNextSteps(
  selection: StudioMediaToolbarRect,
  viewport: StudioMediaToolbarRect,
  railSize: { width: number; height: number }
): StudioMediaToolbarPosition & { width: number; bridgeHeight: number } {
  const width = Math.max(railSize.width, selection.width)
  const position = placeStudioNodeMediaToolbar(selection, viewport, {
    width,
    height: railSize.height
  })
  return {
    ...position,
    width,
    bridgeHeight: TOOLBAR_ABOVE_GAP
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(value, Math.max(min, max)))
}
