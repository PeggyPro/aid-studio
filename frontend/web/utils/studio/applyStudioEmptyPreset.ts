import { STUDIO_EMPTY_PRESETS } from '@/config/studioEmptyPresets'
import type { StudioEmptyPresetId } from '@/config/studioNodeEmptyNextSteps'
import type { StudioCanvasNode, StudioNodeData, StudioWorkspaceSnapshot } from '@/types/studio'
import { appendComposerRefInstance } from '@/utils/studio/studioComposerRefs'
import { createStudioEdge, createStudioNode } from '@/utils/studio/studioGraph'
import { groupStudioNodes, recalculateStudioGroups } from '@/utils/studio/studioGroup'
import { studioNodeDimensions } from '@/utils/studio/studioNodeLod'
import { placeStudioNodeWithoutOverlap, toStudioNodeRect } from '@/utils/studio/studioNodePlacement'
import { studioNodeToComposerRef, withStudioVideoReference } from '@/utils/studio/studioReferencePicker'

export type StudioEmptyPresetRoleOverrides = Record<string, Partial<StudioNodeData>>

export interface AppliedStudioEmptyPreset {
  snapshot: StudioWorkspaceSnapshot
  selectedNodeId: string
  childIds: string[]
}

function mergeNodePatch(data: StudioNodeData, patch: Partial<StudioNodeData>): StudioNodeData {
  const now = new Date().toISOString()
  return {
    ...data,
    ...patch,
    generationOptions: patch.generationOptions
      ? { ...data.generationOptions, ...patch.generationOptions }
      : data.generationOptions,
    emptyNextStepConsumed: true,
    updatedAt: now
  }
}

function detachAnchorFromExistingGroup(
  nodes: StudioCanvasNode[],
  groups: StudioWorkspaceSnapshot['groups'],
  anchorId: string
): Pick<StudioWorkspaceSnapshot, 'nodes' | 'groups'> {
  const oldGroupId = nodes.find((node) => node.id === anchorId)?.data.groupId
  if (!oldGroupId) return { nodes, groups }

  const trimmed = groups
    .map((group) => group.id === oldGroupId
      ? { ...group, childIds: group.childIds.filter((id) => id !== anchorId) }
      : group)
    .filter((group) => group.childIds.length >= 2)
  const validIds = new Set(trimmed.map((group) => group.id))
  const detached = nodes.map((node) => {
    if (node.id === anchorId) return { ...node, data: { ...node.data, groupId: undefined } }
    if (node.data.groupId && !validIds.has(node.data.groupId)) {
      return { ...node, data: { ...node.data, groupId: undefined } }
    }
    return node
  })
  return { nodes: detached, groups: recalculateStudioGroups(detached, trimmed) }
}

function addEdgeReference(
  nodes: StudioCanvasNode[],
  sourceId: string,
  targetId: string
): StudioCanvasNode[] {
  const source = nodes.find((node) => node.id === sourceId)
  const target = nodes.find((node) => node.id === targetId)
  if (!source || !target) return nodes

  const base = studioNodeToComposerRef(source)
  const appended = appendComposerRefInstance(target.data.composerRefs, {
    kind: base.kind,
    nodeId: base.nodeId,
    title: base.title,
    mediaKind: base.mediaKind,
    previewUrl: base.previewUrl,
    sourceKey: base.sourceKey,
    channel: 'attachment'
  })
  const generationOptions = target.data.kind === 'video'
    ? withStudioVideoReference(target.data.generationOptions ?? {}, appended.ref)
    : target.data.generationOptions
  return nodes.map((node) => node.id === targetId
    ? {
        ...node,
        data: {
          ...node.data,
          composerRefs: appended.refs,
          generationOptions,
          updatedAt: new Date().toISOString()
        }
      }
    : node)
}

export function applyStudioEmptyPreset(input: {
  snapshot: StudioWorkspaceSnapshot
  anchorNodeId: string
  presetId: StudioEmptyPresetId
  roleOverrides?: StudioEmptyPresetRoleOverrides
}): AppliedStudioEmptyPreset | null {
  const preset = STUDIO_EMPTY_PRESETS[input.presetId]
  const anchor = input.snapshot.nodes.find((node) => node.id === input.anchorNodeId)
  if (!anchor || anchor.data.kind !== preset.anchorKind || anchor.data.flowBinding || anchor.data.emptyNextStepConsumed) {
    return null
  }

  const detached = detachAnchorFromExistingGroup(input.snapshot.nodes, input.snapshot.groups, anchor.id)
  let nodes = detached.nodes.map((node) => node.id === anchor.id
    ? { ...node, data: mergeNodePatch(node.data, preset.anchorPatch ?? {}) }
    : node)
  const roles = new Map<string, string>([[preset.anchorRole, anchor.id]])
  const occupied = nodes.map(toStudioNodeRect)

  for (const definition of preset.nodes) {
    const desired = {
      x: anchor.position.x + definition.offset.x,
      y: anchor.position.y + definition.offset.y
    }
    const override = input.roleOverrides?.[definition.role] ?? {}
    const draft = createStudioNode(definition.kind, desired, {
      ...definition.data,
      ...override,
      generationOptions: override.generationOptions
        ? { ...definition.data.generationOptions, ...override.generationOptions }
        : definition.data.generationOptions,
      zoneId: override.zoneId ?? definition.data.zoneId ?? anchor.data.zoneId,
      emptyNextStepConsumed: true
    })
    const size = studioNodeDimensions(draft)
    const position = placeStudioNodeWithoutOverlap(desired, size, occupied)
    const created = { ...draft, position }
    occupied.push({ ...position, ...size })
    roles.set(definition.role, created.id)
    nodes.push(created)
  }

  const edges = [...input.snapshot.edges]
  for (const definition of preset.edges) {
    const sourceId = roles.get(definition.sourceRole)
    const targetId = roles.get(definition.targetRole)
    if (!sourceId || !targetId) return null
    edges.push(createStudioEdge(sourceId, targetId, 'reference'))
    nodes = addEdgeReference(nodes, sourceId, targetId)
  }

  if (preset.sourceScriptRole) {
    const sourceScriptNodeId = roles.get(preset.sourceScriptRole)
    nodes = nodes.map((node) => node.id === anchor.id
      ? { ...node, data: { ...node.data, sourceScriptNodeId } }
      : node)
  }

  const childIds = [...roles.values()]
  const grouped = groupStudioNodes(nodes, detached.groups, childIds)
  const selectedNodeId = roles.get(preset.selectRole)
  if (!grouped.groupId || !selectedNodeId) return null

  return {
    snapshot: {
      nodes: grouped.nodes.map((node) => ({ ...node, selected: node.id === selectedNodeId })),
      edges,
      zones: input.snapshot.zones,
      groups: grouped.groups
    },
    selectedNodeId,
    childIds
  }
}
