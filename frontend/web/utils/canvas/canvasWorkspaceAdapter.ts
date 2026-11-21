import { canvasResourceDisplayUrl } from './canvasPublicationFiles'
import type {
  CanvasCurrentResult,
  CanvasObjectRecord,
  CanvasObjectType,
  CanvasResource,
  CanvasTaskProjection,
  CanvasWorkspaceView
} from '@/types/canvas-api'
import type { CanvasSceneDocument } from '@/types/canvas-api'
import { restoreCanvasScenes } from './canvasSceneDocument'
import { projectCanvasCurrentResult } from './canvasCurrentResultResource'
import type {
  StudioCanvasNode,
  StudioFlowEdge,
  StudioGroup,
  StudioNodeData,
  StudioNodeKind,
  StudioTask,
  StudioTaskStatus,
  StudioWorkspaceData
} from '@/types/studio'
import { createEmptyStudioWorkspace } from '@/utils/studio/mockStudioApi'
import { canvasTaskAwaitingVerification, canvasTaskProgress, canvasVideoFinishEta, canvasTaskState, isCanvasTaskTerminal } from './canvasTaskState'
import { needsCanvasTerminalResult } from './canvasTerminalResult'
import { smartEditMediaClips } from '@/utils/studio/studioSmartEdit'
import { normalizeSmartEditState, invalidateMissingSmartEditSources, seedLegacySmartEditDismissals } from '@/utils/studio/smartEditValidation'
import { STUDIO_NODE_DEFAULT_DIMENSIONS } from '@/utils/studio/studioNodeLod'
import type { StudioSmartEditState } from '@/types/studio'

const FORBIDDEN_KEYS = new Set([
  'taskid', 'remotetaskid', 'taskstatus', 'resulturl', 'outputurl', 'mediaurl',
  'billing', 'balance', 'userid', 'ownerid', 'memberrole', 'permissions',
  'locktoken', 'operationrunid', 'audiorunid', 'storyboardprompttaskid',
  'storyboardpromptstatus', 'errormessage', 'createdat', 'updatedat',
  'modelcostcredits', 'modelisfree', 'status', 'progress', 'selectedresult', 'currentresult'
])

const NODE_KINDS = new Set<StudioNodeKind>([
  'image', 'video', 'frame_breakdown', 'smart_edit', 'text', 'storyboard_script', 'director', 'style_library',
  'official_asset', 'voice', 'note'
])

export type CanvasSerializedObject = {
  objectId: string
  objectType: CanvasObjectType
  payload: Record<string, unknown>
}

export type CanvasDeferredNodePosition = { x: number; y: number }

/** Keep browser-side media work visible while an unrelated server snapshot is applied. */
export function preserveCanvasTransientNodes(
  authoritative: StudioWorkspaceData,
  local: Pick<StudioWorkspaceData, 'scopeKey' | 'nodes' | 'edges'>
): StudioWorkspaceData {
  if (authoritative.scopeKey !== local.scopeKey) return authoritative
  const existing = new Set(authoritative.nodes.map((node) => node.id))
  const pending = local.nodes.filter((node) => node.data.canvasTransient && !existing.has(node.id))
  if (!pending.length) return authoritative
  const pendingIds = new Set(pending.map((node) => node.id))
  for (const node of pending) existing.add(node.id)
  const edgeIds = new Set(authoritative.edges.map((edge) => edge.id))
  const pendingEdges = local.edges.filter((edge) =>
    (pendingIds.has(edge.source) || pendingIds.has(edge.target))
    && existing.has(edge.source) && existing.has(edge.target) && !edgeIds.has(edge.id)
  )
  return {
    ...authoritative,
    nodes: [...authoritative.nodes, ...pending],
    edges: [...authoritative.edges, ...pendingEdges]
  }
}

/**
 * A generation request hands the target node to the server at a fixed version.
 * Keep that exact persisted payload out of later workspace autosaves until the
 * task finishes; otherwise an unrelated save can advance the version between
 * the endpoint's optimistic check and its task-intent transaction.
 */
export function preserveCanvasTaskOwnedObjects(
  current: CanvasSerializedObject[],
  baseline: ReadonlyMap<string, CanvasSerializedObject>,
  taskOwnedObjectIds: ReadonlySet<string>
): CanvasSerializedObject[] {
  if (!taskOwnedObjectIds.size) return current
  const byId = new Map(current.map((item) => [item.objectId, item]))
  for (const objectId of taskOwnedObjectIds) {
    const persisted = baseline.get(objectId)
    if (!persisted) continue
    byId.set(objectId, {
      objectId: persisted.objectId,
      objectType: persisted.objectType,
      payload: persisted.payload
    })
  }
  return [...byId.values()]
}

/**
 * A task owns a fixed node version, so its position cannot be mutated until the
 * server separates layout and generation revisions. Keep a user's drag as
 * session layout and reapply it after authoritative snapshots without changing
 * the task input version.
 */
export function updateCanvasDeferredNodePositions(
  current: CanvasSerializedObject[],
  baseline: ReadonlyMap<string, CanvasSerializedObject>,
  trackedObjectIds: ReadonlySet<string>,
  previous: ReadonlyMap<string, CanvasDeferredNodePosition> = new Map()
): Map<string, CanvasDeferredNodePosition> {
  if (!trackedObjectIds.size) return new Map()
  const next = new Map<string, CanvasDeferredNodePosition>()
  const currentById = new Map(current.map((item) => [item.objectId, item]))
  for (const objectId of trackedObjectIds) {
    const item = currentById.get(objectId)
    const persisted = baseline.get(objectId)
    if (item?.objectType !== 'NODE' || persisted?.objectType !== 'NODE') {
      continue
    }
    const currentPosition = record(item.payload.position)
    const persistedPosition = record(persisted.payload.position)
    const currentX = finite(currentPosition.x, Number.NaN)
    const currentY = finite(currentPosition.y, Number.NaN)
    const persistedX = finite(persistedPosition.x, Number.NaN)
    const persistedY = finite(persistedPosition.y, Number.NaN)
    if (![currentX, currentY, persistedX, persistedY].every(Number.isFinite)) continue
    if (currentX !== persistedX || currentY !== persistedY) {
      next.set(objectId, { x: currentX, y: currentY })
      continue
    }
    const deferred = previous.get(objectId)
    if (deferred) next.set(objectId, deferred)
  }
  return next
}

/** Keep deferred positions out of mutations so a layout-only drag cannot invalidate a selected result. */
export function preserveCanvasDeferredNodePositions(
  current: CanvasSerializedObject[],
  baseline: ReadonlyMap<string, CanvasSerializedObject>,
  deferred: ReadonlyMap<string, CanvasDeferredNodePosition>
): CanvasSerializedObject[] {
  if (!deferred.size) return current
  return current.map((item) => {
    if (item.objectType !== 'NODE' || !deferred.has(item.objectId)) return item
    const persisted = baseline.get(item.objectId)
    if (persisted?.objectType !== 'NODE') return item
    return {
      ...item,
      payload: { ...item.payload, position: persisted.payload.position }
    }
  })
}

export function applyCanvasDeferredNodePositions(
  workspace: StudioWorkspaceData,
  deferred: ReadonlyMap<string, CanvasDeferredNodePosition>
): StudioWorkspaceData {
  if (!deferred.size) return workspace
  let changed = false
  const nodes = workspace.nodes.map((node) => {
    const position = deferred.get(node.id)
    if (!position || (node.position.x === position.x && node.position.y === position.y)) return node
    changed = true
    return { ...node, position }
  })
  return changed ? { ...workspace, nodes } : workspace
}

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {}
}

function finite(value: unknown, fallback: number): number {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

/** 按服务端安全模型剔除运行态、权威字段和裸 URL。 */
export function sanitizeCanvasClientValue(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value
      .map(sanitizeCanvasClientValue)
      .filter((item) => item !== undefined)
  }
  if (value && typeof value === 'object') {
    const result: Record<string, unknown> = {}
    for (const [key, child] of Object.entries(value as Record<string, unknown>)) {
      const normalized = key.toLowerCase()
      if (FORBIDDEN_KEYS.has(normalized) || normalized.endsWith('taskid') || normalized.endsWith('url') || normalized.endsWith('urls')) continue
      const safeChild = sanitizeCanvasClientValue(child)
      if (safeChild !== undefined) result[key] = safeChild
    }
    return result
  }
  if (typeof value === 'string') {
    const normalized = value.trim().toLowerCase()
    if (/^(https?:|data:|blob:|\/\/)/.test(normalized) || normalized.startsWith('/profile/')) return undefined
  }
  if (typeof value === 'function' || typeof value === 'symbol' || typeof value === 'bigint') return undefined
  return value
}

function resourceIdsFromNode(data: StudioNodeData): string[] {
  const ids = [
    ...(data.kind === 'smart_edit' ? data.canvasPrimaryResourceId ? [data.canvasPrimaryResourceId] : [] : Array.isArray(data.canvasResourceIds) ? data.canvasResourceIds : []),
    ...(data.smartEdit ? smartEditMediaClips(data.smartEdit.timeline).flatMap((clip) => clip.sourceResourceId ? [clip.sourceResourceId] : []) : []),
    ...(data.gridStoryboard?.cells || []).map((cell) => cell.resourceId).filter((id): id is string => Boolean(id)),
    ...(data.layerSeparation?.backgroundResourceId ? [data.layerSeparation.backgroundResourceId] : []),
    ...(data.layerSeparation?.layers || []).map((layer) => layer.resourceId).filter((id): id is string => Boolean(id))
  ]
  const unique = [...new Set(ids.map(String).filter((id) => /^\d+$/.test(id) && id !== '0'))]
  if (data.kind === 'smart_edit' && unique.length > 25) throw new Error('剪辑成片最多引用 24 个素材，请移除多余素材后重试')
  return unique.slice(0, 25)
}

function restoreSmartEdit(value: unknown, resources: Map<string, CanvasResource>): StudioSmartEditState {
  const base = normalizeSmartEditState(value)
  const timeline = { ...base.timeline }
  for (const lane of ['videoClips', 'overlayClips', 'audioClips'] as const) {
    Object.assign(timeline, { [lane]: timeline[lane].map((clip) => {
      const resource = resources.get(String(clip.sourceResourceId || ''))
      return { ...clip, sourceUrl: resource ? canvasResourceDisplayUrl(resource) : '', invalid: Boolean(clip.invalid) || !resource }
    }) })
  }
  return { ...base, timeline }
}

function nodeSize(node: StudioCanvasNode): { width: number; height: number } {
  const fallback = STUDIO_NODE_DEFAULT_DIMENSIONS[node.data.kind]
  return {
    width: finite(node.data.displayWidth ?? node.width, fallback.width),
    height: finite(node.data.displayHeight ?? node.height, fallback.height)
  }
}

function serializedNodeData(data: StudioNodeData, nodeId: string) {
  // A source image writes its own tool-panel choices here while the user is
  // editing. They are local interaction state and must not advance a completed
  // node's version. A derived target points to a different source node; retain
  // that relationship and its generation settings so HD/layer results recover.
  const imageToolOptions = data.imageToolOptions?.sourceNodeId === nodeId
    ? undefined
    : data.imageToolOptions
  // Voice result selection and measured playback duration are rebuilt from the current
  // authoritative result. Loading audio must not write another node revision.
  // New nodes and server readback must serialize the same empty resource/text values.
  const persistedData = { ...data, canvasResourceIds: resourceIdsFromNode(data),
    ...(data.kind === 'text' ? { documentContent: data.documentContent || '' } : {}),
    imageToolOptions, canvasCurrentTaskId: undefined,
    canvasCurrentResultId: undefined, canvasLatestTaskStatus: undefined,
    ...(data.kind === 'voice' ? { canvasAudioSelectionVersion: undefined,
      audioDurationSeconds: undefined, mediaKind: undefined } : {}) }
  if (!data.smartEdit) return record(sanitizeCanvasClientValue(persistedData))
  const timeline = { ...data.smartEdit.timeline }
  for (const lane of ['videoClips', 'overlayClips', 'audioClips'] as const) Object.assign(timeline, { [lane]: timeline[lane].map((clip) => ({ ...clip, invalid: undefined })) })
  // invalid 由当前来源/资源重新派生，jobId 是本页任务身份；均不参加服务端基线比较。
  return record(sanitizeCanvasClientValue({ ...persistedData, smartEdit: { ...data.smartEdit,
    timelineInitialized: data.smartEdit.timelineInitialized === true, timeline, jobId: undefined } }))
}

export function serializeCanvasWorkspace(workspace: StudioWorkspaceData): CanvasSerializedObject[] {
  const persistedNodes = workspace.nodes.filter((node) => !node.data.canvasTransient)
  const persistedNodeIds = new Set(persistedNodes.map((node) => node.id))
  const nodes: CanvasSerializedObject[] = persistedNodes.map((node) => ({
    objectId: node.id,
    objectType: 'NODE',
    payload: {
      nodeType: String(node.data.kind || 'note').toUpperCase(),
      title: node.data.title || '未命名节点',
      position: { x: finite(node.position.x, 0), y: finite(node.position.y, 0) },
      size: nodeSize(node),
      prompt: node.data.prompt || '',
      ...(['text', 'voice'].includes(node.data.kind) && node.data.documentContent ? { textContent: node.data.documentContent } : node.data.kind === 'text' ? { textContent: '' } : {}),
      ...(node.data.negativePrompt ? { negativePrompt: node.data.negativePrompt } : {}),
      parameters: record(sanitizeCanvasClientValue(record(node.data.generationOptions))),
      resourceIds: resourceIdsFromNode(node.data),
      inputPorts: [],
      metadata: {
        studioData: serializedNodeData(node.data, node.id),
        hidden: Boolean(node.hidden),
        draggable: node.draggable !== false
      }
    }
  }))

  const edges: CanvasSerializedObject[] = workspace.edges
    .filter((edge) => persistedNodeIds.has(edge.source) && persistedNodeIds.has(edge.target))
    .map((edge) => ({
    objectId: edge.id,
    objectType: 'EDGE',
    payload: {
      sourceObjectId: edge.source,
      targetObjectId: edge.target,
      ...(edge.sourceHandle ? { sourceHandle: edge.sourceHandle } : {}),
      ...(edge.targetHandle ? { targetHandle: edge.targetHandle } : {}),
      label: typeof edge.label === 'string' ? edge.label : edge.data?.label || '',
      style: record(sanitizeCanvasClientValue(edge.style)),
      metadata: { studioData: record(sanitizeCanvasClientValue(edge.data)), type: edge.type || 'studioEdge' }
    }
  }))

  const groups: CanvasSerializedObject[] = workspace.groups.map((group) => ({
    objectId: group.id,
    objectType: 'GROUP',
    payload: {
      title: group.title,
      position: group.position,
      size: { width: group.width, height: group.height },
      childObjectIds: group.childIds.filter((id) => persistedNodeIds.has(id)),
      style: { colorTag: group.colorTag ?? null },
      metadata: { layoutMode: group.layoutMode || 'auto' }
    }
  }))
  return [...nodes, ...edges, ...groups]
}

export function serializeCanvasPersonalView(viewport: { x: number; y: number; zoom: number }) {
  return {
    x: finite(viewport.x, 0),
    y: finite(viewport.y, 0),
    zoom: finite(viewport.zoom, 1)
  }
}

function nodeKind(value: unknown): StudioNodeKind {
  const normalized = String(value || '').toLowerCase() as StudioNodeKind
  return NODE_KINDS.has(normalized) ? normalized : 'note'
}

function taskStatus(value: unknown): StudioTaskStatus {
  const status = String(value || '').toUpperCase()
  if (status === 'SUCCEEDED' || status === 'SUCCESS' || status === 'COMPLETED') return 'succeeded'
  if (status === 'FAILED') return 'failed'
  if (status === 'CANCELLED' || status === 'CANCELED') return 'cancelled'
  if (status === 'PROCESSING' || status === 'RUNNING') return 'running'
  if (status === 'PENDING' || status === 'WAITING') return 'waiting'
  return 'queued'
}

function hydrateTasks(tasks: CanvasTaskProjection[], nodes: StudioCanvasNode[]): StudioTask[] {
  const nodeKinds = new Map(nodes.map((node) => [node.id, node.data.kind]))
  return tasks.filter((task) => (task.taskId != null || task.operationId) && task.objectId != null).map((task) => ({
    id: task.taskId != null ? String(task.taskId) : `canvas-pending:${String(task.operationId)}`,
    nodeId: String(task.objectId),
    targetNodeIds: [String(task.objectId)],
    title: nodeKinds.get(String(task.objectId)) === 'voice' ? '音频生成'
      : task.mediaType === 'TEXT' ? '文本生成' : task.mediaType === 'VIDEO' ? '视频生成' : '图片生成',
    stage: canvasTaskState(task).stage,
    progress: canvasTaskProgress(task) ?? 0,
    canvasProgress: canvasTaskProgress(task),
    canvasVideoFinishEta: canvasVideoFinishEta(task),
    canvasAwaitingVerification: canvasTaskAwaitingVerification(task),
    status: taskStatus(task.status),
    ...(task.taskId != null ? { canvasTaskId: String(task.taskId) } : {}),
    ...(task.operationId ? { canvasOperationId: task.operationId } : {}),
    canCancel: task.canCancel,
    errorMessage: task.errorMessage || undefined,
    createdAt: task.createTime || task.updateTime || new Date(0).toISOString(),
    updatedAt: task.updateTime || task.createTime || new Date(0).toISOString()
  }))
}

/** 进行中任务比工作区快照里的“节点最近任务”更新，按节点覆盖后用于恢复占用状态。 */
export function mergeCanvasTaskProjections(
  snapshotTasks: CanvasTaskProjection[] = [],
  activeTasks: CanvasTaskProjection[] = []
): CanvasTaskProjection[] {
  const byObject = new Map<string, CanvasTaskProjection>()
  const withoutObject: CanvasTaskProjection[] = []
  for (const task of snapshotTasks) {
    if (task.objectId == null) withoutObject.push(task)
    else byObject.set(String(task.objectId), task)
  }
  for (const task of activeTasks) {
    if (task.objectId == null) withoutObject.push(task)
    else {
      const latest = byObject.get(String(task.objectId))
      // Active tasks are read before the workspace: a newer terminal snapshot wins for the same run.
      if (latest && isCanvasTaskTerminal(latest.status)
        && ((task.taskId != null && String(latest.taskId) === String(task.taskId))
          || (task.operationId && task.operationId === latest.operationId))) continue
      byObject.set(String(task.objectId), task)
    }
  }
  return [...byObject.values(), ...withoutObject]
}

export function hydrateCanvasWorkspace(input: {
  scopeKey: string
  projectName: string
  objects: CanvasObjectRecord[]
  tasks?: CanvasTaskProjection[]
  /**
   * Only these task identities may drive a node's live status. `tasks` also
   * contains the latest historical binding per node, so using it directly
   * would resurrect completed work whenever a result selection is unavailable.
   * Omit this field only for legacy/read-only callers that do not have the
   * active-task projection.
   */
  runtimeTaskIds?: ReadonlySet<string>
  /** Terminal media tasks whose result selection is still settling in this session. */
  pendingResultTaskIds?: ReadonlySet<string>
  /** Locally created generation targets that are still in preflight or submission. */
  reservedNodeIds?: ReadonlySet<string>
  resources?: CanvasResource[]
  currentResults?: CanvasCurrentResult[]
  sceneDocument?: CanvasSceneDocument
  view?: CanvasWorkspaceView | null
}): StudioWorkspaceData {
  const empty = createEmptyStudioWorkspace(input.scopeKey, 'free')
  const resources = new Map((input.resources || []).map((item) => [String(item.resourceId), item]))
  const currentResults = new Map((input.currentResults || []).map((item) => [String(item.objectId), item]))
  const taskByObject = new Map((input.tasks || [])
    .filter((item) => item.objectId != null)
    .map((item) => [String(item.objectId), item]))
  const runtimeTaskByObject = new Map((input.tasks || [])
    .filter((item) => item.objectId != null
      && (input.runtimeTaskIds == null
        || (item.taskId != null && input.runtimeTaskIds.has(String(item.taskId)))
        || (item.operationId != null && input.runtimeTaskIds.has(`operation:${String(item.operationId)}`))))
    .map((item) => [String(item.objectId), item]))
  const nodes: StudioCanvasNode[] = []
  const edges: StudioFlowEdge[] = []
  const groups: StudioGroup[] = []

  for (const object of input.objects || []) {
    if (object.deleted || !object.content) continue
    const content = record(object.content)
    const metadata = record(content.metadata)
    if (object.objectType === 'NODE') {
      const stored = record(metadata.studioData)
      const position = record(content.position)
      const size = record(content.size)
      const current = currentResults.get(String(object.objectId))
      const latestTask = taskByObject.get(String(object.objectId))
      const runtimeTask = runtimeTaskByObject.get(String(object.objectId))
      const locallyReserved = input.reservedNodeIds?.has(String(object.objectId)) === true
      const awaitingCurrentResult = runtimeTask?.taskId != null
        && input.pendingResultTaskIds?.has(String(runtimeTask.taskId)) === true
        && needsCanvasTerminalResult(runtimeTask, current, object.objectVersion)
      const kind = nodeKind(stored.kind || content.nodeType)
      const expectedMediaType = kind === 'video' || kind === 'frame_breakdown' || kind === 'smart_edit' ? 'VIDEO' : kind === 'voice' ? 'AUDIO' : kind === 'image' || kind === 'official_asset' || kind === 'style_library' ? 'IMAGE' : null
      const currentProjection = projectCanvasCurrentResult(current, resources, expectedMediaType)
      const persistedResourceIds = Array.isArray(content.resourceIds) ? content.resourceIds.map(String) : []
      const resourceIds = [...new Set([
        ...(currentProjection?.resourceId ? [currentProjection.resourceId] : []),
        ...persistedResourceIds
      ])]
      const primaryResourceId = currentProjection?.resourceId
        || (stored.canvasPrimaryResourceId == null ? '' : String(stored.canvasPrimaryResourceId))
      const audioInputIds = new Set(kind === 'voice' && Array.isArray(record(content.parameters).audioResourceIds)
        ? (record(content.parameters).audioResourceIds as unknown[]).map(String) : [])
      const firstResource = resourceIds
        .filter(id => !audioInputIds.has(id) || id === currentProjection?.resourceId)
        .map((id) => resources.get(id))
        .find((item) => item && (!expectedMediaType || item.mediaType === expectedMediaType) && (!primaryResourceId || String(item.resourceId) === primaryResourceId))
      const audioTextVersion = Number(stored.audioTextSelectionVersion)
      const audioTextCurrent = kind === 'voice' && !!content.textContent && Number.isSafeInteger(audioTextVersion) && audioTextVersion >= 0
        && Number(current?.selectionVersion ?? 0) <= Number(stored.audioTextSelectionVersion ?? 0)
      const mediaUrl = audioTextCurrent ? undefined : currentProjection?.mediaUrl || (stored.imagePurpose === 'grid_storyboard' || (kind === 'smart_edit' && !primaryResourceId) ? undefined : firstResource?.displayUrl || firstResource?.resourceUrl) || undefined
      const smartEdit = kind === 'smart_edit' ? restoreSmartEdit(stored.smartEdit, resources) : undefined
      const storedGrid = record(stored.gridStoryboard)
      const gridStoryboard = Array.isArray(storedGrid.cells)
        ? {
            ...storedGrid,
            cells: storedGrid.cells.map((value) => {
              const cell = record(value)
              const resourceId = cell.resourceId == null ? '' : String(cell.resourceId)
              const resource = resources.get(resourceId)
              return resource ? { ...cell, mediaUrl: canvasResourceDisplayUrl(resource) } : cell
            })
          }
        : undefined
      const storedLayers = record(stored.layerSeparation)
      const layerSeparation = Array.isArray(storedLayers.layers)
        ? {
            ...storedLayers,
            backgroundUrl: storedLayers.backgroundResourceId == null
              ? ''
              : (resources.get(String(storedLayers.backgroundResourceId)) ? canvasResourceDisplayUrl(resources.get(String(storedLayers.backgroundResourceId))!) : ''),
            layers: storedLayers.layers.map((value) => {
              const layer = record(value)
              const resourceId = layer.resourceId == null ? '' : String(layer.resourceId)
              const resource = resources.get(resourceId)
              return { ...layer, mediaUrl: resource ? canvasResourceDisplayUrl(resource) : '' }
            })
          }
        : undefined
      const now = object.updateTime || new Date(0).toISOString()
      const composerRefs = Array.isArray(stored.composerRefs)
        ? stored.composerRefs.map((value) => {
            const ref = record(value)
            const resource = ref.assetId == null ? undefined : resources.get(String(ref.assetId))
            return resource ? { ...ref, previewUrl: canvasResourceDisplayUrl(resource) } : ref
          })
        : undefined
      const data: StudioNodeData = {
        ...stored,
        ...(smartEdit ? { smartEdit } : {}),
        ...(gridStoryboard ? { gridStoryboard: gridStoryboard as unknown as StudioNodeData['gridStoryboard'] } : {}),
        ...(layerSeparation ? { layerSeparation: layerSeparation as unknown as StudioNodeData['layerSeparation'] } : {}),
        ...(composerRefs ? { composerRefs: composerRefs as unknown as StudioNodeData['composerRefs'] } : {}),
        kind,
        ...(kind === 'voice' ? { mediaKind: 'audio' as const,
          canvasAudioSelectionVersion: Number(current?.selectionVersion ?? 0) } : {}),
        title: String(content.title || stored.title || '未命名节点'),
        prompt: String(content.prompt ?? stored.prompt ?? ''),
        ...(kind === 'text' ? { documentContent: String(content.textContent ?? stored.documentContent ?? '') } : kind === 'voice' ? { documentContent: audioTextCurrent ? String(content.textContent ?? '') : undefined } : {}),
        negativePrompt: content.negativePrompt == null ? undefined : String(content.negativePrompt),
        status: awaitingCurrentResult ? 'generating' : runtimeTask && !isCanvasTaskTerminal(runtimeTask.status)
          ? canvasTaskState(runtimeTask).nodeStatus
          : locallyReserved ? 'queued'
          : latestTask && !mediaUrl && ['CANCELLED', 'CANCELED'].includes(latestTask.status.toUpperCase())
            ? 'empty'
            : latestTask && !mediaUrl && latestTask.status.toUpperCase() === 'FAILED'
            ? 'failed'
            : kind === 'smart_edit' && stored.status === 'failed' ? 'failed' : (mediaUrl || smartEdit?.timeline.videoClips.length || smartEdit?.timeline.texts.length || gridStoryboard?.cells.some((cell) => Boolean(record(cell).mediaUrl)) || content.textContent || stored.documentContent) ? 'success' : 'empty',
        ...(awaitingCurrentResult
          ? { errorMessage: '任务已完成，正在同步画布结果' }
          : !mediaUrl && (latestTask?.errorMessage || latestTask?.userMessage)
            ? { errorMessage: latestTask.userMessage || latestTask.errorMessage || undefined }
            : {}),
        canvasLatestTaskStatus: latestTask?.status ? latestTask.status.toUpperCase() : undefined,
        createdAt: now,
        updatedAt: now,
        canvasResourceIds: resourceIds,
        ...(primaryResourceId ? { canvasPrimaryResourceId: primaryResourceId } : {}),
        ...((currentProjection?.result.taskId || runtimeTask?.taskId) ? { canvasCurrentTaskId: String(currentProjection?.result.taskId || runtimeTask?.taskId) } : {}),
        ...(currentProjection?.result.resultId ? { canvasCurrentResultId: String(currentProjection.result.resultId) } : {}),
        ...(mediaUrl ? {
          mediaUrl,
          ...(kind === 'official_asset' || kind === 'style_library' ? { mediaKind: 'image' as const } : {})
        } : {})
      }
      nodes.push({
        id: String(object.objectId),
        type: 'studioNode',
        position: { x: finite(position.x, 0), y: finite(position.y, 0) },
        width: finite(size.width, finite(stored.displayWidth, 320)),
        height: finite(size.height, finite(stored.displayHeight, 220)),
        hidden: Boolean(metadata.hidden),
        draggable: metadata.draggable !== false,
        data
      })
      continue
    }
    if (object.objectType === 'EDGE') {
      const stored = record(metadata.studioData)
      const kind = ['sequence', 'reference', 'flow'].includes(String(stored.kind))
        ? String(stored.kind) as 'sequence' | 'reference' | 'flow'
        : 'reference'
      edges.push({
        id: String(object.objectId),
        type: String(metadata.type || 'studioEdge'),
        source: String(content.sourceObjectId || ''),
        target: String(content.targetObjectId || ''),
        sourceHandle: content.sourceHandle == null ? null : String(content.sourceHandle),
        targetHandle: content.targetHandle == null ? null : String(content.targetHandle),
        data: { ...stored, kind, label: String(content.label || stored.label || '') },
        style: record(content.style)
      })
      continue
    }
    if (object.objectType === 'GROUP') {
      const position = record(content.position)
      const size = record(content.size)
      const style = record(content.style)
      groups.push({
        id: String(object.objectId),
        title: String(content.title || '分组'),
        position: { x: finite(position.x, 0), y: finite(position.y, 0) },
        width: finite(size.width, 640),
        height: finite(size.height, 420),
        childIds: Array.isArray(content.childObjectIds) ? content.childObjectIds.map(String) : [],
        layoutMode: metadata.layoutMode === 'manual' ? 'manual' : 'auto',
        colorTag: typeof style.colorTag === 'string' ? style.colorTag : null
      })
    }
  }

  const personalView = record(input.view?.view)
  // 现行契约把 x/y/zoom 直接放在 view 中；保留读取旧版 view.viewport，避免历史记录跳回原点。
  const legacyViewport = record(personalView.viewport)
  const nodeById = new Map(nodes.map((node) => [node.id, node]))
  const hydratedNodes = nodes.map((node) => {
    if (!node.data.composerRefs?.length) return node
    const composerRefs = node.data.composerRefs.map((ref) => {
      if (!ref.nodeId) return ref
      const source = nodeById.get(ref.nodeId)
      if (source?.data.kind === 'style_library') {
        return { ...ref, kind: 'library' as const, mediaKind: 'text' as const, previewUrl: source.data.mediaUrl }
      }
      if (ref.previewUrl || !source?.data.mediaUrl) return ref
      return {
        ...ref,
        previewUrl: source.data.mediaUrl,
        mediaKind: ref.mediaKind || source.data.mediaKind
      }
    })
    return { ...node, data: { ...node.data, composerRefs } }
  })
  return {
    ...empty,
    title: input.projectName || '未命名画布',
    nodes: seedLegacySmartEditDismissals(invalidateMissingSmartEditSources(hydratedNodes), edges),
    edges,
    groups,
    directorScenes: restoreCanvasScenes(input.sceneDocument, input.resources || []),
    viewport: {
      x: finite(personalView.x, finite(legacyViewport.x, empty.viewport.x)),
      y: finite(personalView.y, finite(legacyViewport.y, empty.viewport.y)),
      zoom: finite(personalView.zoom, finite(legacyViewport.zoom, empty.viewport.zoom))
    },
    tasks: hydrateTasks(input.tasks || [], nodes),
    savedAt: new Date().toISOString()
  }
}

export function stableCanvasJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stableCanvasJson).join(',')}]`
  if (value && typeof value === 'object') {
    return `{${Object.entries(value as Record<string, unknown>)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([key, child]) => `${JSON.stringify(key)}:${stableCanvasJson(child)}`)
      .join(',')}}`
  }
  return JSON.stringify(value)
}
