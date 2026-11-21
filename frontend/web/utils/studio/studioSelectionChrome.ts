export type StudioSelectionChromeMode =
  | 'none'
  | 'singleNode'
  | 'multiSelect'
  | 'group'
  | 'gridStoryboard'

export interface StudioSelectionChromeInput {
  selectedNodeIds: string[]
  nodes: Array<{ id: string; data: { kind: string; imagePurpose?: string; gridStoryboard?: unknown } }>
  groups: Array<{ id: string; childIds: string[] }>
  /** 编辑会话等外部门闩：为 true 时强制 none（调用方传入） */
  chromeLocked?: boolean
}

function sameIdSet(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false
  const set = new Set(a)
  if (set.size !== a.length) return false
  return b.every((id) => set.has(id))
}

export function findStudioGroupBySelection<T extends { id: string; childIds: string[] }>(
  groups: T[],
  selectedNodeIds: string[]
): T | undefined {
  if (selectedNodeIds.length < 2) return undefined
  return groups.find((group) => group.childIds.length >= 2 && sameIdSet(selectedNodeIds, group.childIds))
}

function isGridStoryboardNode(
  node: StudioSelectionChromeInput['nodes'][number] | undefined
): boolean {
  return Boolean(
    node
    && node.data.imagePurpose === 'grid_storyboard'
    && node.data.gridStoryboard
  )
}

export function resolveStudioSelectionChrome(
  input: StudioSelectionChromeInput
): StudioSelectionChromeMode {
  if (input.chromeLocked) return 'none'
  const selectedIds = input.selectedNodeIds
  if (!selectedIds.length) return 'none'

  const byId = new Map(input.nodes.map((node) => [node.id, node]))

  if (selectedIds.length === 1) {
    const only = byId.get(selectedIds[0])
    if (isGridStoryboardNode(only)) return 'gridStoryboard'
    if (only) return 'singleNode'
    return 'none'
  }

  if (selectedIds.length >= 2) {
    const matchesGroup = Boolean(findStudioGroupBySelection(input.groups, selectedIds))
    if (matchesGroup) return 'group'
    return 'multiSelect'
  }

  return 'none'
}
