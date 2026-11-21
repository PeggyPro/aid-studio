import type { StudioCanvasNode, StudioGridCellCoord } from '@/types/studio'
import { studioNodeDimensions } from '@/utils/studio/studioNodeLod'

const GRID_PREVIEW_PADDING = 10
const GRID_PREVIEW_GAP = 4

export interface StudioGridStoryboardDropTarget extends StudioGridCellCoord {
  gridNodeId: string
  occupied: boolean
}

export function gridStoryboardContainsCanvasPoint(
  gridNode: StudioCanvasNode,
  point: { x: number; y: number }
): boolean {
  if (!gridNode.data.gridStoryboard || gridNode.data.imagePurpose !== 'grid_storyboard') return false
  const size = studioNodeDimensions(gridNode)
  return point.x >= gridNode.position.x
    && point.y >= gridNode.position.y
    && point.x <= gridNode.position.x + size.width
    && point.y <= gridNode.position.y + size.height
}

/**
 * 宫格预览占满节点媒体区；这里与预览的 padding/gap 共用同一几何模型，
 * 拖回命中不依赖 DOM 缩放后的像素猜测。
 */
export function gridStoryboardCellAtCanvasPoint(
  gridNode: StudioCanvasNode,
  point: { x: number; y: number }
): StudioGridCellCoord | null {
  const storyboard = gridNode.data.gridStoryboard
  if (!storyboard || gridNode.data.imagePurpose !== 'grid_storyboard') return null
  if (!gridStoryboardContainsCanvasPoint(gridNode, point)) return null
  const size = studioNodeDimensions(gridNode)
  const localX = point.x - gridNode.position.x - GRID_PREVIEW_PADDING
  const localY = point.y - gridNode.position.y - GRID_PREVIEW_PADDING
  const contentWidth = size.width - GRID_PREVIEW_PADDING * 2
  const contentHeight = size.height - GRID_PREVIEW_PADDING * 2
  if (localX < 0 || localY < 0 || localX > contentWidth || localY > contentHeight) return null

  const cellWidth = (contentWidth - GRID_PREVIEW_GAP * (storyboard.columns - 1)) / storyboard.columns
  const cellHeight = (contentHeight - GRID_PREVIEW_GAP * (storyboard.rows - 1)) / storyboard.rows
  if (cellWidth <= 0 || cellHeight <= 0) return null
  const pitchX = cellWidth + GRID_PREVIEW_GAP
  const pitchY = cellHeight + GRID_PREVIEW_GAP
  const col = Math.min(storyboard.columns - 1, Math.max(0, Math.floor(localX / pitchX)))
  const row = Math.min(storyboard.rows - 1, Math.max(0, Math.floor(localY / pitchY)))
  const inCellX = localX - col * pitchX
  const inCellY = localY - row * pitchY
  if (inCellX > cellWidth || inCellY > cellHeight) return null
  return { row, col }
}

export function resolveGridStoryboardReturnTarget(
  nodes: StudioCanvasNode[],
  originGridNodeId: string,
  point: { x: number; y: number }
): StudioGridStoryboardDropTarget | null {
  const gridNode = nodes.find((node) => node.id === originGridNodeId)
  if (!gridNode?.data.gridStoryboard) return null
  const cell = gridStoryboardCellAtCanvasPoint(gridNode, point)
  if (!cell) return null
  const occupied = gridNode.data.gridStoryboard.cells.some((item) => (
    item.row === cell.row && item.col === cell.col && Boolean(item.mediaUrl)
  ))
  return { gridNodeId: originGridNodeId, ...cell, occupied }
}
