import type { StudioCanvasNode, StudioGroup } from '@/types/studio'
import { studioId } from './studioGraph'
import { studioNodeDimensions } from './studioNodeLod'

const GROUP_SIDE_PADDING = 28
/** 分组世界坐标只为外置标题留白；画布级动作轨不参与节点/分组几何。 */
const GROUP_HEADER_CLEARANCE = 56
const GROUP_BOTTOM_PADDING = 28
const GROUP_MIN_WIDTH = 120
const GROUP_MIN_HEIGHT = 120

export interface StudioGroupFrame {
  position: { x: number; y: number }
  width: number
  height: number
}

function groupFrameFromBounds(bounds: ReturnType<typeof boundsForNodes>) {
  return {
    position: {
      x: bounds.left - GROUP_SIDE_PADDING,
      y: bounds.top - GROUP_HEADER_CLEARANCE
    },
    width: bounds.right - bounds.left + GROUP_SIDE_PADDING * 2,
    height: bounds.bottom - bounds.top + GROUP_HEADER_CLEARANCE + GROUP_BOTTOM_PADDING
  }
}

function frameUnion(left: StudioGroupFrame, right: StudioGroupFrame): StudioGroupFrame {
  const x = Math.min(left.position.x, right.position.x)
  const y = Math.min(left.position.y, right.position.y)
  const farRight = Math.max(left.position.x + left.width, right.position.x + right.width)
  const bottom = Math.max(left.position.y + left.height, right.position.y + right.height)
  return { position: { x, y }, width: farRight - x, height: bottom - y }
}

function normalizedGroupFrame(frame: StudioGroupFrame, fallback: StudioGroup): StudioGroupFrame {
  const x = Number.isFinite(frame.position.x) ? frame.position.x : fallback.position.x
  const y = Number.isFinite(frame.position.y) ? frame.position.y : fallback.position.y
  const width = Number.isFinite(frame.width) ? Math.max(GROUP_MIN_WIDTH, frame.width) : fallback.width
  const height = Number.isFinite(frame.height) ? Math.max(GROUP_MIN_HEIGHT, frame.height) : fallback.height
  return { position: { x, y }, width, height }
}

export function requiredStudioGroupFrame(nodes: StudioCanvasNode[], group: StudioGroup): StudioGroupFrame | null {
  const children = nodes.filter((node) => group.childIds.includes(node.id))
  return children.length >= 2 ? groupFrameFromBounds(boundsForNodes(children)) : null
}

export function canResizeStudioGroup(
  nodes: StudioCanvasNode[],
  group: StudioGroup,
  frame: StudioGroupFrame
): boolean {
  const required = requiredStudioGroupFrame(nodes, group)
  if (!required) return false
  const normalized = normalizedGroupFrame(frame, group)
  return normalized.position.x <= required.position.x
    && normalized.position.y <= required.position.y
    && normalized.position.x + normalized.width >= required.position.x + required.width
    && normalized.position.y + normalized.height >= required.position.y + required.height
}

export type StudioGroupResizeCorner = 'top-left' | 'top-right'

/**
 * 从角点拖拽增量计算分组框（flow 坐标）。结果会与子节点最小包围盒取并集，避免裁切。
 */
export function projectStudioGroupCornerResize(
  nodes: StudioCanvasNode[],
  group: StudioGroup,
  corner: StudioGroupResizeCorner,
  delta: { x: number; y: number }
): StudioGroupFrame | null {
  const required = requiredStudioGroupFrame(nodes, group)
  if (!required) return null
  const proposed: StudioGroupFrame = corner === 'top-left'
    ? {
      position: {
        x: group.position.x + delta.x,
        y: group.position.y + delta.y
      },
      width: group.width - delta.x,
      height: group.height - delta.y
    }
    : {
      position: {
        x: group.position.x,
        y: group.position.y + delta.y
      },
      width: group.width + delta.x,
      height: group.height - delta.y
    }
  return frameUnion(normalizedGroupFrame(proposed, group), required)
}

export const STUDIO_GROUP_COLOR_PRESETS: ReadonlyArray<{ id: string; color: string | null; label: string }> = [
  { id: 'none', color: null, label: '无色' },
  { id: 'red', color: '#f5222d', label: '红' },
  { id: 'orange', color: '#fa8c16', label: '橙' },
  { id: 'yellow', color: '#fadb14', label: '黄' },
  { id: 'green', color: '#52c41a', label: '绿' },
  { id: 'cyan', color: '#13c2c2', label: '青' },
  { id: 'blue', color: '#1677ff', label: '蓝' },
  { id: 'purple', color: '#722ed1', label: '紫' },
  { id: 'pink', color: '#eb2f96', label: '粉' },
  { id: 'gray', color: '#8c8c8c', label: '灰' }
]

export function formatStudioGroupTitle(childCount: number): string {
  return `分组 ${childCount} 个节点`
}

export function setStudioGroupColor(
  groups: StudioGroup[],
  groupId: string,
  colorTag: string | null
): StudioGroup[] {
  return groups.map((group) => (
    group.id === groupId ? { ...group, colorTag } : group
  ))
}

export function groupStudioNodes(
  nodes: StudioCanvasNode[],
  groups: StudioGroup[],
  selectedIds: string[]
): { nodes: StudioCanvasNode[]; groups: StudioGroup[]; groupId: string | null } {
  const selected = new Set(selectedIds)
  const children = nodes.filter((node) => selected.has(node.id))
  if (children.length < 2) return { nodes, groups, groupId: null }
  const bounds = boundsForNodes(children)
  const frame = groupFrameFromBounds(bounds)
  const groupId = studioId('group')
  const group: StudioGroup = {
    id: groupId,
    title: formatStudioGroupTitle(children.length),
    ...frame,
    childIds: children.map((node) => node.id)
  }
  return {
    nodes: nodes.map((node) => selected.has(node.id)
      ? { ...node, data: { ...node.data, groupId } }
      : node),
    groups: [...groups, group],
    groupId
  }
}

export function ungroupStudioNodes(
  nodes: StudioCanvasNode[],
  groups: StudioGroup[],
  selectedIds: string[]
): { nodes: StudioCanvasNode[]; groups: StudioGroup[]; removedGroupIds: string[] } {
  const selected = new Set(selectedIds)
  const removing = new Set<string>()
  for (const group of groups) {
    if (selected.has(group.id) || group.childIds.some((id) => selected.has(id))) removing.add(group.id)
  }
  if (!removing.size) return { nodes, groups, removedGroupIds: [] }
  return {
    nodes: nodes.map((node) => node.data.groupId && removing.has(node.data.groupId)
      ? { ...node, data: { ...node.data, groupId: undefined } }
      : node),
    groups: groups.filter((group) => !removing.has(group.id)),
    removedGroupIds: [...removing]
  }
}

export function moveStudioGroup(
  nodes: StudioCanvasNode[],
  groups: StudioGroup[],
  groupId: string,
  position: { x: number; y: number }
): { nodes: StudioCanvasNode[]; groups: StudioGroup[] } {
  const group = groups.find((item) => item.id === groupId)
  if (!group) return { nodes, groups }
  const dx = position.x - group.position.x
  const dy = position.y - group.position.y
  if (!dx && !dy) return { nodes, groups }
  const children = new Set(group.childIds)
  return {
    nodes: nodes.map((node) => children.has(node.id)
      ? { ...node, position: { x: node.position.x + dx, y: node.position.y + dy } }
      : node),
    groups: groups.map((item) => item.id === groupId ? { ...item, position } : item)
  }
}

/**
 * Persist a user-sized group without moving any child world coordinates. The
 * required child frame is always included, so later relayouts cannot clip nodes.
 */
export function resizeStudioGroup(
  nodes: StudioCanvasNode[],
  groups: StudioGroup[],
  groupId: string,
  frame: StudioGroupFrame
): StudioGroup[] {
  const group = groups.find((item) => item.id === groupId)
  if (!group) return groups
  const required = requiredStudioGroupFrame(nodes, group)
  if (!required) return groups
  const resized = frameUnion(normalizedGroupFrame(frame, group), required)
  if (
    resized.position.x === group.position.x
    && resized.position.y === group.position.y
    && resized.width === group.width
    && resized.height === group.height
  ) return groups
  return groups.map((item) => item.id === groupId
    ? { ...item, ...resized, layoutMode: 'manual' }
    : item)
}

export function recalculateStudioGroups(
  nodes: StudioCanvasNode[],
  groups: StudioGroup[]
): StudioGroup[] {
  const byId = new Map(nodes.map((node) => [node.id, node]))
  return groups.flatMap((group) => {
    const children = group.childIds.flatMap((id) => {
      const child = byId.get(id)
      return child ? [child] : []
    })
    if (children.length < 2) return []
    const required = groupFrameFromBounds(boundsForNodes(children))
    const frame = group.layoutMode === 'manual'
      ? frameUnion(group, required)
      : required
    return [{
      ...group,
      ...frame,
      childIds: children.map((node) => node.id)
    }]
  })
}

/**
 * 让节点 groupId 与组成员保持一致，并裁剪已删除节点。少于两个存活成员的组自动解散。
 * 流程快照刷新和 Undo/Redo 共同复用，避免旧业务实体从历史组中复活。
 */
export function reconcileStudioGroupsForNodes(
  nodes: StudioCanvasNode[],
  groups: StudioGroup[]
): { nodes: StudioCanvasNode[]; groups: StudioGroup[] } {
  const survivingIds = new Set(nodes.map((node) => node.id))
  const candidates = groups.map((group) => ({
    ...group,
    childIds: group.childIds.filter((id) => survivingIds.has(id))
  }))
  const validGroups = candidates.filter((group) => group.childIds.length >= 2)
  const validGroupIds = new Set(validGroups.map((group) => group.id))
  const membersByGroup = new Map(validGroups.map((group) => [group.id, new Set(group.childIds)]))
  const normalizedNodes = nodes.map((node) => {
    const groupId = node.data.groupId
    if (!groupId) return node
    const members = membersByGroup.get(groupId)
    if (validGroupIds.has(groupId) && members?.has(node.id)) return node
    return { ...node, data: { ...node.data, groupId: undefined } }
  })
  return {
    nodes: normalizedNodes,
    groups: recalculateStudioGroups(normalizedNodes, validGroups)
  }
}

function boundsForNodes(nodes: StudioCanvasNode[]) {
  return {
    left: Math.min(...nodes.map((node) => node.position.x)),
    top: Math.min(...nodes.map((node) => node.position.y)),
    right: Math.max(...nodes.map((node) => node.position.x + studioNodeDimensions(node).width)),
    bottom: Math.max(...nodes.map((node) => node.position.y + studioNodeDimensions(node).height))
  }
}
