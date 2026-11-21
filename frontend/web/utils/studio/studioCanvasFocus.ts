import type { FitBounds, Rect } from '@xyflow/react'
import type { StudioCanvasNode, StudioGroup } from '@/types/studio'
import { studioNodeDimensions } from '@/utils/studio/studioNodeLod'

/**
 * 双击聚焦：底部多留出 Composer 高度，避免放大后输入框被挤到节点上方。
 * 节点仍可高于视口，用户通过画布平移上下查看内容。
 */
export const STUDIO_NODE_FOCUS_OPTIONS = {
  padding: 0.18,
  duration: 320
} as const

/** 将组内相对坐标转换为画布世界坐标，供 fitBounds 做水平、垂直居中。 */
export function resolveStudioNodeFocusBounds(
  node: StudioCanvasNode,
  groups: StudioGroup[]
): Rect {
  const parent = node.parentId
    ? groups.find((group) => group.id === node.parentId)
    : undefined
  const dimensions = studioNodeDimensions(node)
  return {
    x: node.position.x + (parent?.position.x ?? 0),
    y: node.position.y + (parent?.position.y ?? 0),
    width: dimensions.width,
    height: dimensions.height
  }
}

export function findStudioNodeAtCanvasPosition(
  nodes: StudioCanvasNode[],
  groups: StudioGroup[],
  position: { x: number; y: number }
): StudioCanvasNode | undefined {
  for (let index = nodes.length - 1; index >= 0; index -= 1) {
    const node = nodes[index]
    const bounds = resolveStudioNodeFocusBounds(node, groups)
    if (
      position.x >= bounds.x
      && position.x <= bounds.x + bounds.width
      && position.y >= bounds.y
      && position.y <= bounds.y + bounds.height
    ) return node
  }
}

export function focusStudioNodeInViewport(
  fitBounds: FitBounds,
  node: StudioCanvasNode,
  groups: StudioGroup[]
): Promise<boolean> {
  return fitBounds(
    resolveStudioNodeFocusBounds(node, groups),
    STUDIO_NODE_FOCUS_OPTIONS
  )
}
