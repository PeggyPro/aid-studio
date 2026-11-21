import type { StudioCanvasNode, StudioFlowEdge, StudioFlowStep, StudioMode } from '@/types/studio'
import { filterStudioFlowCanvasVisibleNodes } from '@/utils/studio/studioFlowCanvasProjection'
import { isStudioFlowStoryboardStillNode } from '@/utils/studio/studioFlowStoryboardMedia'
import { packStudioNodesWithoutOverlap } from '@/utils/studio/studioNodePlacement'
import { studioNodeDimensions } from '@/utils/studio/studioNodeLod'

const STEP_LAYOUT: Record<StudioFlowStep, { x: number; width: number }> = {
  'global-setting': { x: 0, width: 330 },
  'story-script': { x: 370, width: 330 },
  'scene-character': { x: 740, width: 620 },
  'storyboard-script': { x: 1400, width: 620 },
  'storyboard-video': { x: 2060, width: 620 },
  dubbing: { x: 2720, width: 330 },
  preview: { x: 3090, width: 330 }
}

const ROW_HEIGHT = 420
const TOP = 72
const COL_INSET = 24
const WIDE_COL_STEP = 320
const FREE_ORIGIN = { x: 80, y: 80 }
const FREE_LAYER_GAP = 96
const FREE_NODE_GAP = 80

const ASSET_TYPE_ORDER: Record<string, number> = {
  character: 0,
  scene: 1,
  prop: 2
}

function stepGridPosition(step: StudioFlowStep, index: number): { x: number; y: number } {
  const layout = STEP_LAYOUT[step]
  const columns = layout.width > 400 ? 2 : 1
  const column = index % columns
  const row = Math.floor(index / columns) + 1
  return {
    x: layout.x + COL_INSET + column * WIDE_COL_STEP,
    y: TOP + row * ROW_HEIGHT
  }
}

function storyboardLanePosition(index: number): {
  script: { x: number; y: number }
  image: { x: number; y: number }
  video: { x: number; y: number }
  dubbing: { x: number; y: number }
} {
  const y = TOP + (index + 1) * ROW_HEIGHT
  return {
    script: { x: STEP_LAYOUT['storyboard-script'].x + COL_INSET, y },
    image: { x: STEP_LAYOUT['storyboard-script'].x + 340, y },
    video: { x: STEP_LAYOUT['storyboard-video'].x + COL_INSET, y },
    dubbing: { x: STEP_LAYOUT.dubbing.x + COL_INSET, y }
  }
}

function storyboardIdOf(node: StudioCanvasNode): string {
  return String(node.data.storyboardId || node.data.flowBinding?.serverId || '').trim()
}

function compareStoryboardIds(a: string, b: string): number {
  const na = Number(a)
  const nb = Number(b)
  if (Number.isFinite(na) && Number.isFinite(nb) && na !== nb) return na - nb
  return a.localeCompare(b, 'zh-CN')
}

function applyPositions(
  nodes: StudioCanvasNode[],
  positions: Map<string, { x: number; y: number }>
): StudioCanvasNode[] {
  let changed = false
  const next = nodes.map((node) => {
    const position = positions.get(node.id)
    if (!position) return node
    if (position.x === node.position.x && position.y === node.position.y) return node
    changed = true
    return { ...node, position }
  })
  return changed ? next : nodes
}

function compareAssetNodes(a: StudioCanvasNode, b: StudioCanvasNode): number {
  const aType = ASSET_TYPE_ORDER[a.data.flowBinding?.assetType ?? ''] ?? 9
  const bType = ASSET_TYPE_ORDER[b.data.flowBinding?.assetType ?? ''] ?? 9
  if (aType !== bType) return aType - bType
  const aId = Number(a.data.flowBinding?.serverId ?? a.data.assetId)
  const bId = Number(b.data.flowBinding?.serverId ?? b.data.assetId)
  if (Number.isFinite(aId) && Number.isFinite(bId) && aId !== bId) return aId - bId
  return a.data.title.localeCompare(b.data.title, 'zh-CN')
}

function tidyFlowCanvasNodes(nodes: StudioCanvasNode[]): StudioCanvasNode[] {
  const positions = new Map<string, { x: number; y: number }>()
  const boundItems = nodes.filter((node) => node.data.flowBinding?.role === 'item')

  const script = boundItems.find((node) => node.data.flowBinding?.entityType === 'story_script')
  if (script) positions.set(script.id, stepGridPosition('story-script', 0))

  const assets = boundItems
    .filter((node) => node.data.flowBinding?.entityType === 'rps_asset')
    .sort(compareAssetNodes)
  assets.forEach((node, index) => {
    positions.set(node.id, stepGridPosition('scene-character', index))
  })

  const storyboardIds = [...new Set(
    boundItems
      .filter((node) => node.data.flowBinding?.entityType === 'storyboard' && !isStudioFlowStoryboardStillNode(node.data))
      .map(storyboardIdOf)
      .filter(Boolean)
  )].sort(compareStoryboardIds)

  storyboardIds.forEach((storyboardId, index) => {
    const lane = storyboardLanePosition(index)
    for (const node of boundItems) {
      if (storyboardIdOf(node) !== storyboardId) continue
      const binding = node.data.flowBinding
      if (!binding) continue
      if (binding.entityType === 'storyboard' && !isStudioFlowStoryboardStillNode(node.data)) {
        positions.set(node.id, lane.script)
      } else if (isStudioFlowStoryboardStillNode(node.data)) {
        positions.set(node.id, lane.image)
      } else if (binding.entityType === 'storyboard_video' || binding.step === 'storyboard-video') {
        positions.set(node.id, lane.video)
      } else if (binding.entityType === 'dubbing' || binding.step === 'dubbing') {
        positions.set(node.id, lane.dubbing)
      }
    }
  })

  const preview = boundItems.find((node) => node.data.flowBinding?.entityType === 'preview')
  if (preview) positions.set(preview.id, stepGridPosition('preview', 0))

  const canvasOnly = nodes.filter((node) => !node.data.flowBinding)
  canvasOnly.forEach((node, index) => {
    positions.set(node.id, {
      x: STEP_LAYOUT.preview.x + 380,
      y: TOP + index * ROW_HEIGHT
    })
  })

  return applyPositions(nodes, positions)
}

function assignFreeCanvasLayers(nodeIds: string[], edges: StudioFlowEdge[]): string[][] {
  const idSet = new Set(nodeIds)
  const layerById = new Map<string, number>()
  for (const id of nodeIds) layerById.set(id, 0)

  const relevantEdges = edges.filter(
    (edge) => idSet.has(edge.source) && idSet.has(edge.target) && edge.source !== edge.target
  )

  let changed = true
  while (changed) {
    changed = false
    for (const edge of relevantEdges) {
      const nextLayer = (layerById.get(edge.source) ?? 0) + 1
      if (nextLayer > (layerById.get(edge.target) ?? 0)) {
        layerById.set(edge.target, nextLayer)
        changed = true
      }
    }
  }

  const maxLayer = Math.max(0, ...layerById.values())
  const layers: string[][] = Array.from({ length: maxLayer + 1 }, () => [])
  for (const id of nodeIds) {
    layers[layerById.get(id) ?? 0]?.push(id)
  }
  return layers.filter((layer) => layer.length)
}

function tidyFreeCanvasNodes(nodes: StudioCanvasNode[], edges: StudioFlowEdge[]): StudioCanvasNode[] {
  const businessNodes = nodes.filter((node) => !node.data.flowBinding)
  if (businessNodes.length <= 1) return nodes

  const nodeById = new Map(businessNodes.map((node) => [node.id, node]))
  const layers = assignFreeCanvasLayers(businessNodes.map((node) => node.id), edges)
  const positions = new Map<string, { x: number; y: number }>()

  let cursorX = FREE_ORIGIN.x
  for (const layer of layers) {
    const sorted = [...layer].sort((a, b) => {
      const na = nodeById.get(a)
      const nb = nodeById.get(b)
      if (!na || !nb) return a.localeCompare(b)
      return na.position.y - nb.position.y || na.position.x - nb.position.x || a.localeCompare(b)
    })
    const maxWidth = Math.max(...sorted.map((id) => studioNodeDimensions(nodeById.get(id)!).width))
    let cursorY = FREE_ORIGIN.y
    for (const id of sorted) {
      positions.set(id, { x: cursorX, y: cursorY })
      cursorY += studioNodeDimensions(nodeById.get(id)!).height + FREE_NODE_GAP
    }
    cursorX += maxWidth + FREE_LAYER_GAP
  }

  const positioned = applyPositions(nodes, positions)
  if (positioned === nodes) return nodes
  const packed = packStudioNodesWithoutOverlap(positioned)
  const moved = packed.some(
    (node, index) =>
      node.position.x !== nodes[index].position.x || node.position.y !== nodes[index].position.y
  )
  return moved ? packed : nodes
}

export function tidyStudioCanvasNodes(
  nodes: StudioCanvasNode[],
  edges: StudioFlowEdge[],
  studioMode: StudioMode
): StudioCanvasNode[] {
  if (!nodes.length) return nodes
  if (studioMode === 'flow') {
    const visibleCount = filterStudioFlowCanvasVisibleNodes(nodes).length
    if (!visibleCount) return nodes
    return tidyFlowCanvasNodes(nodes)
  }
  return tidyFreeCanvasNodes(nodes, edges)
}
