import { Position } from '@xyflow/react'
import type {
  StudioCanvasNode,
  StudioFlowNode,
  StudioGroup,
  StudioGroupNode,
  StudioNodeData
} from '@/types/studio'
import { studioNodeDimensions, type StudioNodeLod } from '@/utils/studio/studioNodeLod'

/** store data 对象 → lod → 附带 renderLod 的稳定 data 引用 */
const renderLodDataCache = new WeakMap<StudioNodeData, Map<StudioNodeLod, StudioNodeData>>()
const autoSizeStyleCache = new WeakMap<NonNullable<StudioCanvasNode['style']>, NonNullable<StudioCanvasNode['style']>>()

/** Business size is a saved layout hint. The card owns the live media geometry. */
function autoSizeNodeStyle(style: StudioCanvasNode['style']): StudioCanvasNode['style'] {
  if (!style || !('width' in style) && !('height' in style)) return style
  const cached = autoSizeStyleCache.get(style)
  if (cached) return cached
  const next = { ...style }
  delete next.width
  delete next.height
  autoSizeStyleCache.set(style, next)
  return next
}

/** 同一 store data + 同一 lod 复用同一 data 对象，避免 prepare 打穿 NodeCard memo。 */
export function withStudioNodeRenderLod(
  data: StudioNodeData,
  renderLod: StudioNodeLod
): StudioNodeData {
  if (data.renderLod === renderLod) return data
  let byLod = renderLodDataCache.get(data)
  if (!byLod) {
    byLod = new Map()
    renderLodDataCache.set(data, byLod)
  }
  const cached = byLod.get(renderLod)
  if (cached) return cached
  const next = { ...data, renderLod }
  byLod.set(renderLod, next)
  return next
}

function studioNodeClassName(node: StudioCanvasNode, highlightRefNodeId?: string): string {
  return [node.className, node.id === highlightRefNodeId ? 'studio-node--ref-highlight' : '']
    .filter(Boolean)
    .join(' ')
}

function canReuseBusinessNode(
  previous: StudioCanvasNode | undefined,
  node: StudioCanvasNode,
  data: StudioNodeData,
  className: string,
  group: StudioGroup | undefined,
  style: StudioCanvasNode['style']
): previous is StudioCanvasNode {
  if (!previous) return false
  if (previous.data !== data) return false
  if (previous.selected !== node.selected) return false
  if ((previous.className ?? '') !== className) return false
  // React Flow writes measurements back through onNodesChange. Reusing an older
  // projection here drops measured dimensions and resets its handle bounds.
  if (previous.measured?.width !== node.measured?.width || previous.measured?.height !== node.measured?.height) return false
  if (previous.width !== undefined || previous.height !== undefined) return false
  if (previous.hidden !== node.hidden || previous.draggable !== node.draggable || previous.selectable !== node.selectable) return false
  if (previous.connectable !== node.connectable || previous.style !== style) return false
  if (group) {
    if (previous.parentId !== group.id) return false
    const expectedX = node.position.x - group.position.x
    const expectedY = node.position.y - group.position.y
    return previous.position.x === expectedX && previous.position.y === expectedY
  }
  if (previous.parentId) return false
  return previous.position === node.position
}

function canReuseGroupNode(
  previous: StudioGroupNode | undefined,
  group: StudioGroup,
  selected: boolean
): previous is StudioGroupNode {
  if (!previous || previous.type !== 'studioGroup') return false
  if (previous.selected !== selected) return false
  if (previous.position !== group.position) return false
  if (previous.width !== group.width || previous.height !== group.height) return false
  if (previous.style?.width !== group.width || previous.style?.height !== group.height) return false
  const prevData = previous.data
  return prevData.title === group.title
    && prevData.childIds === group.childIds
    && (prevData.colorTag ?? null) === (group.colorTag ?? null)
    && (prevData.layoutMode ?? 'auto') === (group.layoutMode ?? 'auto')
}

/**
 * 将 store 节点转为 React Flow 节点。
 * 对未变更的业务节点尽量返回上一轮同一对象引用，避免整表重渲染。
 */
export function prepareStudioFlowNodes(
  nodes: StudioCanvasNode[],
  groups: StudioGroup[],
  nodeFilter: 'all' | 'rerun',
  renderLod: StudioNodeLod,
  highlightRefNodeId?: string,
  previous: StudioFlowNode[] = [],
  selectedNodeIds: string[] = [],
  seedViewportGeometry = false
): StudioFlowNode[] {
  const filtered = nodeFilter === 'rerun'
    ? nodes.filter((node) => node.data.status === 'failed' || node.data.status === 'empty')
    : nodes
  const visibleIds = new Set(filtered.map((node) => node.id))
  const selectedIds = new Set(selectedNodeIds)
  const visibleGroups = groups.filter((group) => group.childIds.some((id) => visibleIds.has(id)))
  const groupById = new Map(visibleGroups.map((group) => [group.id, group]))

  const previousBusiness = new Map<string, StudioCanvasNode>()
  const previousGroups = new Map<string, StudioGroupNode>()
  for (const item of previous) {
    if (item.type === 'studioGroup') previousGroups.set(item.id, item as StudioGroupNode)
    else if (item.type === 'studioNode') previousBusiness.set(item.id, item as StudioCanvasNode)
  }

  const groupNodes = visibleGroups.map((group): StudioFlowNode => {
    const selected = group.childIds.length >= 2
      && group.childIds.every((id) => selectedIds.has(id))
    const prev = previousGroups.get(group.id)
    if (prev && canReuseGroupNode(prev, group, selected)) return prev
    return {
      id: group.id,
      type: 'studioGroup',
      position: group.position,
      // Parent 节点必须同时带 width/height 与 style，NodeResizeControl 才能稳定改尺寸。
      ...(seedViewportGeometry ? { handles: [] } : {}),
      width: group.width,
      height: group.height,
      data: {
        title: group.title,
        childIds: group.childIds,
        colorTag: group.colorTag ?? null,
        layoutMode: group.layoutMode ?? 'auto'
      },
      style: { width: group.width, height: group.height },
      zIndex: 0,
      selectable: true,
      draggable: true,
      selected
    } satisfies StudioGroupNode
  })

  const businessNodes: StudioFlowNode[] = filtered.map((node) => {
    const group = node.data.groupId ? groupById.get(node.data.groupId) : undefined
    const data = withStudioNodeRenderLod(node.data, renderLod)
    const className = studioNodeClassName(node, highlightRefNodeId)
    const style = autoSizeNodeStyle(node.style)
    const prev = previousBusiness.get(node.id)
    if (canReuseBusinessNode(prev, node, data, className, group, style)) return prev

    const geometry = seedViewportGeometry ? studioInitialViewportGeometry(node) : undefined
    if (!group) {
      return {
        ...node,
        ...geometry,
        width: undefined,
        height: undefined,
        style,
        className,
        data
      }
    }
    return {
      ...node,
      ...geometry,
      width: undefined,
      height: undefined,
      style,
      className,
      data,
      parentId: group.id,
      extent: 'parent' as const,
      expandParent: false,
      position: {
        x: node.position.x - group.position.x,
        y: node.position.y - group.position.y
      }
    }
  })

  return [...groupNodes, ...businessNodes]
}

/** React Flow otherwise mounts every unmeasured node before it can cull the graph. */
function studioInitialViewportGeometry(node: StudioCanvasNode): Partial<StudioCanvasNode> {
  const { width, height } = studioNodeDimensions(node)
  const y = (height - 1) / 2
  return {
    initialWidth: width,
    initialHeight: height,
    // Match StudioNodeCard's center-left and center-right handles until ResizeObserver measures them.
    handles: [
      { id: 'target-hit', x: -.5, y, type: 'target', position: Position.Left },
      { id: 'target-left', x: -10.5, y, type: 'target', position: Position.Left },
      { x: width - .5, y, type: 'source', position: Position.Right }
    ]
  }
}

/** Keep the last projection per canvas instance so unchanged React Flow nodes retain identity. */
export function createStudioFlowNodeProjector(seedViewportGeometry = false) {
  let previous: StudioFlowNode[] = []
  return (
    nodes: StudioCanvasNode[],
    groups: StudioGroup[],
    nodeFilter: 'all' | 'rerun',
    renderLod: StudioNodeLod,
    highlightRefNodeId?: string,
    selectedNodeIds: string[] = []
  ): StudioFlowNode[] => {
    previous = prepareStudioFlowNodes(
      nodes, groups, nodeFilter, renderLod, highlightRefNodeId, previous, selectedNodeIds, seedViewportGeometry
    )
    return previous
  }
}
