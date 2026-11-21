import type { StudioCanvasNode, StudioFlowEdge, StudioNodeKind } from '@/types/studio'

const RECOMMENDED_TARGETS: Partial<Record<StudioNodeKind, readonly StudioNodeKind[] | 'any'>> = {
  text: ['storyboard_script'],
  storyboard_script: ['image', 'voice'],
  image: ['director', 'video'],
  voice: ['video'],
  style_library: ['image'],
  official_asset: ['image'],
  note: 'any',
  director: ['video']
}

export const STUDIO_RECOMMENDATION_EDGE_LIMIT = 5

export function isStudioFlowRecommendation(source: StudioNodeKind, target: StudioNodeKind): boolean {
  const allowed = RECOMMENDED_TARGETS[source]
  return allowed === 'any' || Boolean(allowed?.includes(target))
}

export function createStudioRecommendationEdges(input: {
  sourceId: string | null
  nodes: StudioCanvasNode[]
  edges: StudioFlowEdge[]
  maxEdges?: number
}): StudioFlowEdge[] {
  const source = input.nodes.find((node) => node.id === input.sourceId)
  if (!source || !RECOMMENDED_TARGETS[source.data.kind]) return []
  if (source.data.flowBinding?.entityType === 'story_script' || source.data.flowBinding?.entityType === 'rps_asset') {
    return []
  }
  const existingTargets = new Set(input.edges.filter((edge) => edge.source === source.id).map((edge) => edge.target))
  const maxEdges = Math.max(0, input.maxEdges ?? STUDIO_RECOMMENDATION_EDGE_LIMIT)
  return input.nodes
    .filter((target) => target.id !== source.id && !target.hidden && !existingTargets.has(target.id) && isStudioFlowRecommendation(source.data.kind, target.data.kind))
    .map((target) => ({ target, distance: distanceSquared(source, target) }))
    .sort((a, b) => a.distance - b.distance || a.target.id.localeCompare(b.target.id))
    .slice(0, maxEdges)
    .map(({ target }) => {
      // 节点左=target handle、右=source handle。推荐边按画布从左到右落锚，
      // 避免「下游悬停、上游在左」时从当前节点右侧连到上游左侧（背对背）。
      const leftToRight = target.position.x >= source.position.x
      const edgeSource = leftToRight ? source : target
      const edgeTarget = leftToRight ? target : source
      return {
        id: `studio-recommendation:${source.id}:${target.id}`,
        source: edgeSource.id,
        target: edgeTarget.id,
        type: 'studioEnergy',
        selectable: false,
        focusable: false,
        deletable: false,
        animated: false,
        zIndex: 0,
        className: 'studio-edge--recommendation',
        style: { stroke: '#aebbd0', strokeWidth: 1.15, opacity: 0.34 },
        data: { kind: 'flow', label: '推荐下一步' }
      }
    })
}

function distanceSquared(source: StudioCanvasNode, target: StudioCanvasNode): number {
  const dx = target.position.x - source.position.x
  const dy = target.position.y - source.position.y
  return dx * dx + dy * dy
}
