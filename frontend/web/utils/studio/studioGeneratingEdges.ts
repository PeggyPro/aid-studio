import type { StudioCanvasNode, StudioFlowEdge } from '@/types/studio'

/** 按节点生成态刷新边 animated；无变化时返回原 edges 引用，避免无意义订阅抖动。 */
export function patchStudioGeneratingEdges(
  edges: StudioFlowEdge[],
  nodes: StudioCanvasNode[]
): StudioFlowEdge[] {
  const generating = new Set(
    nodes
      .filter((node) => node.data.status === 'generating' || node.data.status === 'queued')
      .map((node) => node.id)
  )
  let changed = false
  const next = edges.map((edge) => {
    const animated = generating.has(edge.target)
    if (Boolean(edge.animated) === animated) return edge
    changed = true
    return { ...edge, animated }
  })
  return changed ? next : edges
}
