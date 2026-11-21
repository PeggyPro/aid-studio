import type {
  StudioCanvasNode,
  StudioChatMessage,
  StudioFlowEdge,
  StudioGroup,
  StudioMode,
  StudioTask,
  StudioWorkspaceData,
  StudioZone
} from '@/types/studio'
import { normalizeSmartEditState, invalidateMissingSmartEditSources, seedLegacySmartEditDismissals } from './smartEditValidation'

const NODE_KINDS = new Set([
  'image', 'video', 'frame_breakdown', 'smart_edit', 'text', 'storyboard_script', 'director',
  'style_library', 'official_asset', 'voice', 'note'
])
const NODE_STATUSES = new Set(['empty', 'queued', 'generating', 'success', 'failed'])
const TASK_STATUSES = new Set(['queued', 'running', 'waiting', 'succeeded', 'failed', 'cancelled'])
const MAX_NODES = 2_000
const MAX_EDGES = 6_000
const MAX_GROUPS = 1_000
const MAX_TASKS = 1_000
const MAX_MESSAGES = 1_000
const MAX_TEXT = 200_000

type UnknownRecord = Record<string, unknown>

function isRecord(value: unknown): value is UnknownRecord {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function finite(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

function text(value: unknown, fallback = '', limit = MAX_TEXT): string {
  return typeof value === 'string' ? value.slice(0, limit) : fallback
}

function validMode(value: unknown, fallback: StudioMode): StudioMode {
  return value === 'flow' || value === 'free' ? value : fallback
}

function normalizeNode(value: unknown): StudioCanvasNode | null {
  if (!isRecord(value) || !isRecord(value.data) || !isRecord(value.position)) return null
  const id = text(value.id, '', 256).trim()
  const kind = text(value.data.kind, '', 64)
  if (!id || !NODE_KINDS.has(kind)) return null
  const status = typeof value.data.status === 'string' && NODE_STATUSES.has(value.data.status)
    ? value.data.status
    : 'empty'
  return {
    ...(value as unknown as StudioCanvasNode),
    id,
    type: 'studioNode',
    position: {
      x: finite(value.position.x, 0),
      y: finite(value.position.y, 0)
    },
    selected: false,
    data: {
      ...(value.data as StudioCanvasNode['data']),
      ...(kind === 'smart_edit' ? { smartEdit: normalizeSmartEditState(value.data.smartEdit) } : {}),
      kind: kind as StudioCanvasNode['data']['kind'],
      title: text(value.data.title, '未命名节点', 500),
      prompt: text(value.data.prompt),
      status: status as StudioCanvasNode['data']['status']
    }
  }
}

function normalizeEdge(value: unknown, nodeIds: Set<string>): StudioFlowEdge | null {
  if (!isRecord(value)) return null
  const id = text(value.id, '', 256).trim()
  const source = text(value.source, '', 256).trim()
  const target = text(value.target, '', 256).trim()
  if (!id || !nodeIds.has(source) || !nodeIds.has(target)) return null
  return { ...(value as unknown as StudioFlowEdge), id, source, target, selected: false }
}

function normalizeGroup(value: unknown, nodeIds: Set<string>): StudioGroup | null {
  if (!isRecord(value) || !isRecord(value.position)) return null
  const id = text(value.id, '', 256).trim()
  if (!id) return null
  const childIds = Array.isArray(value.childIds)
    ? [...new Set(value.childIds.map((item) => text(item, '', 256)).filter((item) => nodeIds.has(item)))]
    : []
  return {
    ...(value as unknown as StudioGroup),
    id,
    title: text(value.title, '分组', 500),
    position: { x: finite(value.position.x, 0), y: finite(value.position.y, 0) },
    width: Math.max(1, finite(value.width, 320)),
    height: Math.max(1, finite(value.height, 240)),
    childIds,
    layoutMode: value.layoutMode === 'manual' ? 'manual' : 'auto'
  }
}

function normalizeTask(value: unknown): StudioTask | null {
  if (!isRecord(value)) return null
  const id = text(value.id, '', 256).trim()
  if (!id) return null
  const status = typeof value.status === 'string' && TASK_STATUSES.has(value.status) ? value.status : 'failed'
  return {
    ...(value as unknown as StudioTask),
    id,
    title: text(value.title, '未命名任务', 500),
    stage: text(value.stage, '', 1_000),
    status: status as StudioTask['status'],
    progress: Math.min(100, Math.max(0, finite(value.progress, 0))),
    createdAt: text(value.createdAt, new Date(0).toISOString(), 64),
    updatedAt: text(value.updatedAt, new Date(0).toISOString(), 64)
  }
}

function normalizeMessage(value: unknown): StudioChatMessage | null {
  if (!isRecord(value)) return null
  const id = text(value.id, '', 256).trim()
  const role = value.role === 'user' || value.role === 'assistant' ? value.role : null
  if (!id || !role) return null
  return {
    ...(value as unknown as StudioChatMessage),
    id,
    role,
    content: text(value.content),
    createdAt: text(value.createdAt, new Date(0).toISOString(), 64)
  }
}

function uniqueById<T extends { id: string }>(items: T[]): T[] {
  const seen = new Set<string>()
  return items.filter((item) => {
    if (seen.has(item.id)) return false
    seen.add(item.id)
    return true
  })
}

function recordValues<T>(value: unknown): Record<string, T> {
  if (!isRecord(value)) return {}
  return Object.fromEntries(Object.entries(value).filter(([, item]) => isRecord(item))) as Record<string, T>
}

export interface StudioWorkspaceValidationResult {
  workspace: StudioWorkspaceData
  repairedEntries: number
}

/**
 * 工作区唯一运行时校验边界。根结构不可信时拒绝加载；单项损坏时隔离该项，
 * 保留其余可恢复内容，避免一个坏节点清空整张画布。
 */
export function validateStudioWorkspace(
  value: unknown,
  expectedScopeKey: string,
  requestedMode: StudioMode | undefined,
  fallback: StudioWorkspaceData
): StudioWorkspaceValidationResult | null {
  if (!isRecord(value) || ![1, 2, 3].includes(Number(value.version))) return null
  if (text(value.scopeKey, '', 1_000) !== expectedScopeKey) return null

  const mode = validMode(value.studioMode, requestedMode ?? 'flow')
  const rawNodes = Array.isArray(value.nodes) ? value.nodes.slice(0, MAX_NODES) : []
  const parsedNodes = invalidateMissingSmartEditSources(uniqueById(rawNodes.map(normalizeNode).filter((item): item is StudioCanvasNode => Boolean(item))))
  const nodeIds = new Set(parsedNodes.map((node) => node.id))
  const rawEdges = Array.isArray(value.edges) ? value.edges.slice(0, MAX_EDGES) : []
  const edges = uniqueById(rawEdges.map((edge) => normalizeEdge(edge, nodeIds)).filter((item): item is StudioFlowEdge => Boolean(item)))
  const nodes = seedLegacySmartEditDismissals(parsedNodes, edges)
  const rawGroups = Array.isArray(value.groups) ? value.groups.slice(0, MAX_GROUPS) : []
  const groups = uniqueById(rawGroups.map((group) => normalizeGroup(group, nodeIds)).filter((item): item is StudioGroup => Boolean(item)))
  const rawTasks = Array.isArray(value.tasks) ? value.tasks.slice(0, MAX_TASKS) : []
  const tasks = uniqueById(rawTasks.map(normalizeTask).filter((item): item is StudioTask => Boolean(item)))
  const rawMessages = Array.isArray(value.messages) ? value.messages.slice(0, MAX_MESSAGES) : []
  const messages = uniqueById(rawMessages.map(normalizeMessage).filter((item): item is StudioChatMessage => Boolean(item)))
  const viewport = isRecord(value.viewport) ? value.viewport : {}
  const zones = Array.isArray(value.zones)
    ? value.zones.filter(isRecord).slice(0, 100) as unknown as StudioZone[]
    : fallback.zones

  const repairedEntries =
    Math.max(0, rawNodes.length - nodes.length) +
    Math.max(0, rawEdges.length - edges.length) +
    Math.max(0, rawGroups.length - groups.length) +
    Math.max(0, rawTasks.length - tasks.length) +
    Math.max(0, rawMessages.length - messages.length)

  return {
    repairedEntries,
    workspace: {
      ...fallback,
      ...(value as unknown as StudioWorkspaceData),
      version: 3,
      scopeKey: expectedScopeKey,
      studioMode: mode,
      title: text(value.title, fallback.title, 500),
      nodes,
      edges,
      zones,
      groups,
      viewport: {
        x: finite(viewport.x, fallback.viewport.x),
        y: finite(viewport.y, fallback.viewport.y),
        zoom: Math.min(4, Math.max(0.1, finite(viewport.zoom, fallback.viewport.zoom)))
      },
      tasks,
      messages,
      conversation: isRecord(value.conversation)
        ? value.conversation as unknown as StudioWorkspaceData['conversation']
        : fallback.conversation,
      savedAssets: Array.isArray(value.savedAssets)
        ? value.savedAssets.filter(isRecord).slice(0, 2_000) as unknown as StudioWorkspaceData['savedAssets']
        : [],
      directorScenes: recordValues(value.directorScenes),
      personalTemplates: Array.isArray(value.personalTemplates)
        ? value.personalTemplates.filter(isRecord).slice(0, 200) as unknown as StudioWorkspaceData['personalTemplates']
        : [],
      personalSkills: Array.isArray(value.personalSkills)
        ? value.personalSkills.filter(isRecord).slice(0, 200) as unknown as StudioWorkspaceData['personalSkills']
        : [],
      reducedMotion: value.reducedMotion === true,
      savedAt: text(value.savedAt, fallback.savedAt, 64)
    }
  }
}
