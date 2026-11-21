import type { StudioCanvasNode, StudioGridStoryboard } from '@/types/studio'
import { createEmptyGridStoryboard } from '@/utils/studio/studioImageGridSession'
import { studioNodeDimensions } from '@/utils/studio/studioNodeLod'

export const STUDIO_GRID_STORYBOARD_MERGE_MAX = 25
export const STUDIO_GRID_STORYBOARD_MERGE_TOOLTIP =
  '分镜组仅支持图片节点，且组内节点数量不可超过25个'

export function canMergeNodesToGridStoryboard(
  nodes: StudioCanvasNode[]
): { ok: true } | { ok: false; reason: typeof STUDIO_GRID_STORYBOARD_MERGE_TOOLTIP } {
  const count = nodes.length
  if (count < 2 || count > STUDIO_GRID_STORYBOARD_MERGE_MAX) {
    return { ok: false, reason: STUDIO_GRID_STORYBOARD_MERGE_TOOLTIP }
  }
  if (!nodes.every((node) => node.data.kind === 'image')) {
    return { ok: false, reason: STUDIO_GRID_STORYBOARD_MERGE_TOOLTIP }
  }
  return { ok: true }
}

/** 能覆盖 count 的最小 n×n，n∈[2,5]；count<2 仍返回 2×2 */
export function resolveMergeGridDimensions(count: number): { rows: number; columns: number } {
  const safe = Math.max(0, count)
  if (safe <= 4) return { rows: 2, columns: 2 }
  if (safe <= 9) return { rows: 3, columns: 3 }
  if (safe <= 16) return { rows: 4, columns: 4 }
  return { rows: 5, columns: 5 }
}

export function sortNodesForGridFill(nodes: StudioCanvasNode[]): StudioCanvasNode[] {
  return [...nodes].sort((a, b) => {
    if (a.position.y !== b.position.y) return a.position.y - b.position.y
    return a.position.x - b.position.x
  })
}

export function buildMergedGridStoryboardFromNodes(nodes: StudioCanvasNode[]): {
  rows: number
  columns: number
  gridStoryboard: StudioGridStoryboard
  title: string
  position: { x: number; y: number }
  removeNodeIds: string[]
} | null {
  if (!canMergeNodesToGridStoryboard(nodes).ok) return null
  const ordered = sortNodesForGridFill(nodes)
  const { rows, columns } = resolveMergeGridDimensions(ordered.length)
  // 按排序后的稳定下标落入格槽；无图节点仍占位为空格，禁止 flatMap 跳过导致错位。
  const filled = ordered.flatMap((node, index) => {
    const mediaUrl = node.data.mediaUrl?.trim()
    if (!mediaUrl) return []
    const row = Math.floor(index / columns)
    const col = index % columns
    const size = studioNodeDimensions(node)
    return [{
      row,
      col,
      mediaUrl,
      mediaAspectRatio: node.data.mediaAspectRatio,
      canvasWidth: size.width,
      canvasHeight: size.height,
      title: node.data.title || `${row + 1}-${col + 1}`
    }]
  })
  const gridStoryboard = createEmptyGridStoryboard({
    rows,
    columns,
    aspectRatio: ordered[0]?.data.generationOptions?.aspectRatio ?? 'original',
    sourceNodeId: ordered[0]?.id,
    filled
  })
  const left = Math.min(...ordered.map((node) => node.position.x))
  const top = Math.min(...ordered.map((node) => node.position.y))
  return {
    rows,
    columns,
    gridStoryboard,
    title: `分镜组 ${ordered.length} 个节点`,
    position: { x: left, y: top },
    removeNodeIds: ordered.map((node) => node.id)
  }
}

/** 选区包围盒尺寸参考（可选辅助） */
export function boundsForMergeNodes(nodes: StudioCanvasNode[]) {
  return {
    left: Math.min(...nodes.map((node) => node.position.x)),
    top: Math.min(...nodes.map((node) => node.position.y)),
    right: Math.max(...nodes.map((node) => node.position.x + studioNodeDimensions(node).width)),
    bottom: Math.max(...nodes.map((node) => node.position.y + studioNodeDimensions(node).height))
  }
}
