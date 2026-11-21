import type {
  StudioCanvasNode,
  StudioWorkspaceData,
  StudioWorkspaceSnapshot
} from '@/types/studio'
import { DEFAULT_STUDIO_CONVERSATION } from './studioConversation'
import { reconcileStudioGroupsForNodes } from './studioGroup'

export function isStudioFlowWorkspaceScopeReady(
  state: { hydrated: boolean; scopeKey: string },
  expectedScopeKey: string
): boolean {
  return state.hydrated && state.scopeKey === expectedScopeKey
}

/**
 * 流程画布只持久化绑定键和布局；标题、正文、媒体、任务状态等业务字段由服务端快照恢复。
 * 不带 flowBinding 的画布专属节点仍完整保存。
 */
export function serializeStudioFlowLayoutWorkspace(workspace: StudioWorkspaceData): StudioWorkspaceData {
  if (workspace.studioMode !== 'flow') return workspace
  const nodes = workspace.nodes.map((node) => node.data.flowBinding
    ? toFlowLayoutNode(node)
    : structuredClone(node))
  const survivingCanvasNodeIds = new Set(nodes
    .filter((node) => !node.data.flowBinding)
    .map((node) => node.id))
  return {
    ...workspace,
    nodes,
    edges: workspace.edges
      .filter((edge) => !edge.data?.flowManaged)
      .map((edge) => structuredClone(edge)),
    tasks: [],
    messages: [],
    conversation: structuredClone(DEFAULT_STUDIO_CONVERSATION),
    savedAssets: (workspace.savedAssets ?? []).filter((asset) => survivingCanvasNodeIds.has(asset.sourceNodeId))
  }
}

/**
 * 流程画布撤销/重做只采用历史布局与画布专属节点；绑定实体及其业务数据始终使用当前快照。
 */
export function mergeStudioFlowBusinessForHistory(
  current: StudioWorkspaceSnapshot,
  target: StudioWorkspaceSnapshot
): StudioWorkspaceSnapshot {
  const targetLayoutByBinding = new Map(target.nodes.flatMap((node) => node.data.flowBinding
    ? [[node.data.flowBinding.bindingKey, node] as const]
    : []))
  const canvasOnly = target.nodes.filter((node) => !node.data.flowBinding)
  const boundNodes = current.nodes.filter((node) => node.data.flowBinding).map((node) => {
    const layout = targetLayoutByBinding.get(node.data.flowBinding!.bindingKey)
    if (!layout) return structuredClone(node)
    return {
      ...structuredClone(node),
      position: structuredClone(layout.position),
      width: layout.width,
      height: layout.height,
      measured: layout.measured ? structuredClone(layout.measured) : undefined,
      hidden: layout.hidden,
      zIndex: layout.zIndex,
      selected: false
    }
  })
  const ungroupedNodes = [...structuredClone(canvasOnly), ...boundNodes]
  const targetGroupByChildId = new Map(target.groups.flatMap((group) =>
    group.childIds.map((id) => [id, group.id] as const)))
  const nodesWithTargetMembership = ungroupedNodes.map((node) => ({
    ...node,
    data: { ...node.data, groupId: targetGroupByChildId.get(node.id) }
  }))
  const reconciledGroups = reconcileStudioGroupsForNodes(nodesWithTargetMembership, structuredClone(target.groups))
  const nodes = reconciledGroups.nodes
  const survivingIds = new Set(nodes.map((node) => node.id))
  const userEdges = target.edges.filter((edge) =>
    !edge.data?.flowManaged && survivingIds.has(edge.source) && survivingIds.has(edge.target)
  )
  const managedEdges = current.edges.filter((edge) =>
    edge.data?.flowManaged && survivingIds.has(edge.source) && survivingIds.has(edge.target)
  )
  return {
    nodes,
    edges: structuredClone([...userEdges, ...managedEdges]),
    zones: structuredClone(target.zones),
    groups: reconciledGroups.groups
  }
}

function toFlowLayoutNode(node: StudioCanvasNode): StudioCanvasNode {
  const binding = structuredClone(node.data.flowBinding!)
  return {
    ...structuredClone(node),
    selected: false,
    data: {
      kind: node.data.kind,
      title: '',
      status: 'empty',
      prompt: '',
      zoneId: node.data.zoneId,
      groupId: node.data.groupId,
      flowBinding: binding,
      flowLayoutOnly: true,
      createdAt: node.data.createdAt,
      updatedAt: node.data.updatedAt
    }
  }
}
