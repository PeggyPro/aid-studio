import { MarkerType, type Connection, type XYPosition } from '@xyflow/react'
import { STUDIO_NODE_META } from '@/config/studioMeta'
import type {
  StudioCanvasNode,
  StudioDiagnostic,
  StudioEdgeKind,
  StudioFlowEdge,
  StudioNodeData,
  StudioNodeKind,
  StudioTemplate,
  StudioWorkspaceSnapshot
} from '@/types/studio'
import { DEFAULT_STUDIO_FRAME_BREAKDOWN_DIMENSIONS } from './studioFrameBreakdown'
import { defaultStudioSmartEditState } from './studioSmartEdit'
import { normalizeSmartEditState } from './smartEditValidation'

let fallbackId = 0

export function studioId(prefix: string): string {
  const randomId = globalThis.crypto?.randomUUID?.()
  if (randomId) return `${prefix}-${randomId}`
  fallbackId += 1
  return `${prefix}-${Date.now()}-${fallbackId}`
}

const STUDIO_MEDIA_SEQUENCE_LABEL = { image: '图片', video: '视频' } as const

function studioMediaSequenceNumber(title: string, label: string): number | null {
  const match = new RegExp(`^${label}(\\d+)$`).exec(title)
  return match ? Number(match[1]) : null
}

/** 图片 / 视频节点按已有序号顺延，已有「图片」「视频」这类无序号标题会补成图片1、视频1。 */
export function assignStudioMediaSequenceTitles<T extends StudioCanvasNode>(nodes: T[]): T[] {
  let changed = false
  const next = nodes.slice()
  for (const kind of ['image', 'video'] as const) {
    const label = STUDIO_MEDIA_SEQUENCE_LABEL[kind]
    const used = new Set<number>()
    for (const node of next) {
      if (node.data.kind !== kind) continue
      const number = studioMediaSequenceNumber(node.data.title, label)
      if (number) used.add(number)
    }
    const bare = next
      .map((node, index) => ({ node, index }))
      .filter(({ node }) => node.data.kind === kind && node.data.title === label)
      .sort((a, b) => (a.node.data.createdAt ?? '').localeCompare(b.node.data.createdAt ?? '') || a.index - b.index)
    let number = 1
    for (const item of bare) {
      while (used.has(number)) number += 1
      next[item.index] = {
        ...item.node,
        data: { ...item.node.data, title: `${label}${number}` }
      }
      used.add(number)
      changed = true
      number += 1
    }
  }
  return changed ? next : nodes
}

export function nextStudioMediaTitle(kind: 'image' | 'video', nodes: StudioCanvasNode[]): string {
  const label = STUDIO_MEDIA_SEQUENCE_LABEL[kind]
  let max = 0
  for (const node of nodes) {
    if (node.data.kind !== kind) continue
    const number = studioMediaSequenceNumber(node.data.title, label)
    if (number) max = Math.max(max, number)
  }
  return `${label}${max + 1}`
}

export function createStudioNode(
  kind: StudioNodeKind,
  position: XYPosition,
  options: Partial<StudioNodeData> = {}
): StudioCanvasNode {
  const meta = STUDIO_NODE_META[kind]
  const now = new Date().toISOString()
  // 默认值与 options 合并：调用方传入的字段（含 gridStoryboard / layerSeparation 等）必须保留，
  // 禁止再手写白名单字段列表——漏字段会导致分镜组等功能静默丢数据。
  const defaultGenerationOptions: NonNullable<StudioNodeData['generationOptions']> = {
    aspectRatio: '16:9',
    duration: 5,
    voice: '自然叙事',
    quality: kind === 'video' ? '1080p' : '2k',
    count: 1,
    videoMode: 'imageToVideo',
    endFrameEnabled: false
  }
  const data: StudioNodeData = {
    ...options,
    kind,
    title: options.title ?? meta.label,
    status: options.status ?? 'empty',
    prompt: options.prompt ?? meta.defaultPrompt,
    imagePurpose: options.imagePurpose ?? (kind === 'image' ? 'generic' : undefined),
    mediaKind: options.mediaKind ?? defaultMediaKindForKind(kind),
    smartEdit: options.smartEdit ?? (kind === 'smart_edit' ? defaultStudioSmartEditState() : undefined),
    frameBreakdown: options.frameBreakdown ?? (kind === 'frame_breakdown'
      ? { dimensions: [...DEFAULT_STUDIO_FRAME_BREAKDOWN_DIMENSIONS] }
      : undefined),
    composerHidden: options.composerHidden ?? (kind === 'frame_breakdown' ? true : undefined),
    generationOptions: options.generationOptions ?? defaultGenerationOptions,
    createdAt: options.createdAt ?? now,
    updatedAt: options.updatedAt ?? now
  }
  return {
    id: studioId(kind),
    type: 'studioNode',
    position,
    zIndex: 2,
    data
  }
}

export function createStudioEdge(
  source: string,
  target: string,
  kind: StudioEdgeKind = 'flow',
  connection?: Partial<Connection>
): StudioFlowEdge {
  const color = edgeColor(kind)
  return {
    id: studioId('edge'),
    source,
    target,
    sourceHandle: connection?.sourceHandle ?? undefined,
    targetHandle: connection?.targetHandle ?? undefined,
    type: 'bezier',
    label: edgeLabel(kind),
    labelStyle: { fill: '#aeb4c1', fontSize: 10, fontWeight: 600 },
    labelBgStyle: { fill: '#11131a', fillOpacity: 0.92 },
    labelBgPadding: [6, 3],
    labelBgBorderRadius: 8,
    markerEnd: { type: MarkerType.ArrowClosed, color },
    style: { stroke: color, strokeWidth: 1.35, opacity: 0.78 },
    data: { kind, label: edgeLabel(kind) }
  }
}

export function applyTemplateToSnapshot(
  current: StudioWorkspaceSnapshot,
  template: StudioTemplate,
  offset: XYPosition = { x: 0, y: 0 }
): StudioWorkspaceSnapshot {
  const storyboardId = studioId('storyboard')
  const inserted = template.nodes.map((item) =>
    createStudioNode(item.kind, {
      x: item.position.x + offset.x,
      y: item.position.y + offset.y
    }, {
      title: item.title,
      zoneId: item.zoneId,
      mediaKind: item.mediaKind,
      imagePurpose: item.imagePurpose,
      storyboardId: item.imagePurpose === 'storyboard' || item.kind === 'video' ? storyboardId : undefined
    })
  )
  const edges = template.edges.map((item) =>
    createStudioEdge(inserted[item.sourceIndex].id, inserted[item.targetIndex].id, item.kind)
  )
  return {
    nodes: [...current.nodes, ...inserted],
    edges: [...current.edges, ...edges],
    zones: current.zones,
    groups: current.groups
  }
}

export function duplicateStudioNodes(
  nodes: StudioCanvasNode[],
  edges: StudioFlowEdge[],
  selectedIds: string[]
): { nodes: StudioCanvasNode[]; edges: StudioFlowEdge[]; newIds: string[] } {
  const selected = new Set(selectedIds)
  const idMap = new Map<string, string>()
  const copies = nodes
    .filter((node) => selected.has(node.id))
    .map((node) => {
      const id = studioId(node.data.kind)
      idMap.set(node.id, id)
      return {
        ...node,
        id,
        selected: true,
        position: { x: node.position.x + 40, y: node.position.y + 40 },
        data: {
          ...node.data,
          groupId: undefined,
          assetId: undefined,
          bindingKey: undefined,
          flowBinding: undefined,
          storyboardId: node.data.imagePurpose === 'storyboard' ? studioId('storyboard') : node.data.storyboardId,
          title: `${node.data.title} 副本`,
          status: node.data.mediaUrl ? node.data.status : 'empty',
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString()
        }
      } satisfies StudioCanvasNode
    })

  for (const node of copies) {
    if (node.data.smartEditOrigin) {
      const origin = node.data.smartEditOrigin
      node.data = { ...node.data, smartEditOrigin: { ...origin, nodeId: idMap.get(origin.nodeId) || origin.nodeId, sourceNodeId: origin.sourceNodeId ? idMap.get(origin.sourceNodeId) || origin.sourceNodeId : undefined } }
    }
    if (!node.data.smartEdit) continue
    const edit = normalizeSmartEditState(node.data.smartEdit)
    for (const lane of ['videoClips', 'overlayClips', 'audioClips'] as const) Object.assign(edit.timeline, { [lane]: edit.timeline[lane].map((clip) => ({ ...clip, sourceNodeId: idMap.get(clip.sourceNodeId) || clip.sourceNodeId })) })
    if (edit.dismissedSourceIds) edit.dismissedSourceIds = edit.dismissedSourceIds.map((id) => idMap.get(id) || id)
    node.data = { ...node.data, smartEdit: edit, composerRefs: node.data.composerRefs?.map((ref) => ({ ...ref, nodeId: ref.nodeId ? idMap.get(ref.nodeId) || ref.nodeId : undefined })) }
  }
  const copiedEdges = edges.flatMap((edge) => {
    const source = idMap.get(edge.source)
    const target = idMap.get(edge.target)
    if (!source || !target) return []
    return [createStudioEdge(source, target, edge.data?.kind ?? 'flow')]
  })

  return { nodes: copies, edges: copiedEdges, newIds: copies.map((node) => node.id) }
}

export function diagnoseStudioGraph(
  nodes: StudioCanvasNode[],
  edges: StudioFlowEdge[]
): StudioDiagnostic[] {
  const diagnostics: StudioDiagnostic[] = []
  const linkedIds = new Set(edges.flatMap((edge) => [edge.source, edge.target]))

  for (const node of nodes) {
    if (node.data.status === 'failed') {
      diagnostics.push({
        id: `failed-${node.id}`,
        severity: 'error',
        nodeId: node.id,
        title: `${node.data.title} 生成失败`,
        reason: node.data.errorMessage || 'Mock 生成任务未得到有效结果',
        action: '保留上游内容，仅重跑当前节点'
      })
      continue
    }
    if (STUDIO_NODE_META[node.data.kind].generatable && !node.data.prompt.trim()) {
      diagnostics.push({
        id: `prompt-${node.id}`,
        severity: 'warning',
        nodeId: node.id,
        title: `${node.data.title} 缺少生成描述`,
        reason: '节点没有可执行的 Prompt',
        action: '填入该类型默认描述'
      })
    }
    if ((node.data.kind === 'image' || node.data.kind === 'video') &&
      node.data.status === 'success' && !node.data.mediaUrl) {
      diagnostics.push({
        id: `result-${node.id}`,
        severity: 'warning',
        nodeId: node.id,
        title: `${node.data.title} 没有结果`,
        reason: '节点状态与结果内容不一致',
        action: '将该节点标记为待重跑'
      })
    }
    if (nodes.length > 1 && !linkedIds.has(node.id) && node.data.kind !== 'note') {
      diagnostics.push({
        id: `orphan-${node.id}`,
        severity: 'info',
        nodeId: node.id,
        title: `${node.data.title} 尚未连线`,
        reason: '该节点不在任何生产或引用链路中',
        action: '连接到相关上游或下游节点'
      })
    }
  }

  return diagnostics
}

export function repairStudioDiagnostics(
  snapshot: StudioWorkspaceSnapshot,
  diagnostics: StudioDiagnostic[],
  selectedIds: string[]
): StudioWorkspaceSnapshot {
  const selected = new Set(selectedIds)
  const byNode = new Map(
    diagnostics
      .filter((item) => item.nodeId && selected.has(item.id))
      .map((item) => [item.nodeId as string, item])
  )
  const nodes = snapshot.nodes.map((node) => {
    const diagnostic = byNode.get(node.id)
    if (!diagnostic) return node
    const meta = STUDIO_NODE_META[node.data.kind]
    if (diagnostic.id.startsWith('prompt-')) {
      return { ...node, data: { ...node.data, prompt: meta.defaultPrompt, updatedAt: new Date().toISOString() } }
    }
    if (diagnostic.id.startsWith('result-') || diagnostic.id.startsWith('failed-')) {
      return {
        ...node,
        data: {
          ...node.data,
          status: 'empty' as const,
          errorMessage: undefined,
          updatedAt: new Date().toISOString()
        }
      }
    }
    return node
  })
  const edges = [...snapshot.edges]
  for (const diagnostic of diagnostics) {
    if (!selected.has(diagnostic.id) || !diagnostic.id.startsWith('orphan-') || !diagnostic.nodeId) {
      continue
    }
    const nodeIndex = nodes.findIndex((node) => node.id === diagnostic.nodeId)
    const neighbor = nodeIndex > 0 ? nodes[nodeIndex - 1] : nodes[nodeIndex + 1]
    if (!neighbor) continue
    const [source, target] = nodeIndex > 0
      ? [neighbor.id, diagnostic.nodeId]
      : [diagnostic.nodeId, neighbor.id]
    if (!edges.some((edge) => edge.source === source && edge.target === target)) {
      edges.push(createStudioEdge(source, target, 'reference'))
    }
  }
  return { ...snapshot, nodes, edges }
}

export function edgeColor(kind: StudioEdgeKind): string {
  if (kind === 'reference') return '#85809f'
  if (kind === 'sequence') return '#9a8d73'
  return '#718a9c'
}

export function edgeLabel(kind: StudioEdgeKind): string {
  if (kind === 'reference') return '引用'
  if (kind === 'sequence') return '顺序'
  return '流程'
}

export function defaultMediaKindForKind(kind: StudioNodeKind) {
  if (kind === 'video' || kind === 'frame_breakdown' || kind === 'smart_edit') return 'video' as const
  if (kind === 'voice') return 'audio' as const
  if (kind === 'text' || kind === 'storyboard_script' || kind === 'note') return 'text' as const
  if (kind === 'image' || kind === 'style_library' || kind === 'official_asset' || kind === 'director') {
    return 'image' as const
  }
  return undefined
}

