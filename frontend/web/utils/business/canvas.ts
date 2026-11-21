import type {
  CanvasApplication,
  CanvasActiveTasks,
  CanvasCollaborationLock,
  CanvasCollaborationState,
  CanvasCollaborationTicket,
  CanvasDraft,
  CanvasEntitlements,
  CanvasId,
  CanvasInvite,
  CanvasImageLayerMergeRequest,
  CanvasImageLayerMergeResult,
  CanvasImageSplitRequest,
  CanvasImageSplitResult,
  CanvasLockItem,
  CanvasMediaType,
  CanvasMember,
  CanvasMutation,
  CanvasMutationResult,
  CanvasOperation,
  CanvasPage,
  CanvasProjectDetail,
  CanvasProjectRole,
  CanvasProjectSetting,
  CanvasProjectSummary,
  CanvasPublicationState,
  CanvasPublicationSubmit,
  CanvasResource,
  CanvasShare,
  CanvasShareDetail,
  CanvasSmartEditComposeJob,
  CanvasSmartEditComposeStatus,
  CanvasSmartEditComposeSubmit,
  CanvasTaskHistory,
  CanvasTaskProjection,
  CanvasWorkspaceLoad,
  CanvasWorkspaceView
} from '@/types/canvas-api'
import { canvasApiPost, canvasApiReadPost } from '@/utils/canvas/canvasApiClient'
import { unwrap } from './shared'
import type { BillingQuoteWire } from '@/types/business-api'
import { CANVAS_QUOTE_TIMEOUT_MS, requestCanvasQuote } from '@/utils/canvas/canvasQuote'
import { quoteWithCanvasMetadataRecovery } from '@/utils/canvas/canvasQuoteMetadataRecovery'

const CANVAS = '/api/user/canvas'

function post<T>(path: string, data?: unknown): Promise<T> {
  return canvasApiPost<T>(`${CANVAS}${path}`, data ?? {})
    .then(unwrap)
    .then(normalizeCanvasPayload)
}

function readPost<T>(path: string, data: unknown): Promise<T> {
  return canvasApiReadPost<T>(`${CANVAS}${path}`, data).then(unwrap).then(normalizeCanvasPayload)
}

const NON_ID_KEYS = new Set(['operationId', 'sessionId', 'pageId', 'localObjectId', 'sourceObjectId', 'targetObjectId', 'objectId'])

/** 服务端兼容期可能返回 JSON number；进入画布领域前将 Long ID 统一转成 string。 */
export function normalizeCanvasPayload<T>(value: T, key = ''): T {
  if (Array.isArray(value)) {
    return value.map((item) => normalizeCanvasPayload(item, key)) as T
  }
  if (value && typeof value === 'object') {
    const output: Record<string, unknown> = {}
    for (const [childKey, child] of Object.entries(value as Record<string, unknown>)) {
      const isLongId = childKey === 'id' || (!NON_ID_KEYS.has(childKey) && /(?:Id|Ids)$/.test(childKey))
      if (isLongId && typeof child === 'number') {
        if (!Number.isSafeInteger(child)) throw new Error(`服务端返回的 ${childKey} 超出 JavaScript 安全整数范围`)
        output[childKey] = String(child)
      } else if (isLongId && typeof child === 'string') {
        output[childKey] = String(child)
      } else if (isLongId && Array.isArray(child)) {
        output[childKey] = child.map((item) => {
          if (typeof item === 'number' && !Number.isSafeInteger(item)) {
            throw new Error(`服务端返回的 ${childKey} 包含超出 JavaScript 安全整数范围的编号`)
          }
          return String(item)
        })
      } else {
        output[childKey] = normalizeCanvasPayload(child, childKey)
      }
    }
    return output as T
  }
  return value
}

export type CanvasProjectListRequest = {
  projectName?: string
  sortBy?: 'UPDATED_AT' | 'CREATED_AT' | 'NAME'
  sortDirection?: 'ASC' | 'DESC'
  pageNum?: number
  pageSize?: number
}

export function canvasProjectList(data: CanvasProjectListRequest = {}) {
  return post<CanvasPage<CanvasProjectSummary>>('/project/list', data)
}

export function canvasProjectCreate(data: {
  projectName: string
  projectDesc?: string
  coverUrl?: string
  operationId: string
}) {
  return post<CanvasProjectDetail>('/project/create', data)
}

export function canvasProjectImport(data: {
  projectName: string
  projectDesc?: string
  coverUrl?: string
  operationId: string
  objects: Array<{ localObjectId: string; objectType: 'NODE' | 'EDGE' | 'GROUP'; payload: Record<string, unknown> }>
}) {
  return post<CanvasProjectDetail & { idMap: Record<string, string> }>('/project/import', data)
}

export function canvasProjectDetail(projectId: CanvasId) {
  return readPost<CanvasProjectDetail>('/project/detail', { projectId })
}

export function canvasProjectUpdate(data: {
  projectId: CanvasId
  projectName?: string
  projectDesc?: string
  coverUrl?: string
  /** 为空表示不修改公开状态。 */
  publicCanvas?: boolean
}) {
  return post<CanvasProjectDetail>('/project/update', data)
}

export function canvasProjectDelete(projectId: CanvasId) {
  return post<void>('/project/delete', { projectId })
}

export function canvasPublicationPrepare(projectId: CanvasId) {
  return readPost<CanvasPublicationState & { storageReady: boolean; requiresCompleteScenes: boolean }>('/publication/prepare', { projectId })
}

export function canvasPublicationSubmit(data: CanvasPublicationSubmit) {
  return post<{ publicationId: CanvasId; state: CanvasPublicationState }>('/publication/submit', data)
}

export function canvasSmartEditComposeSubmit(data: CanvasSmartEditComposeSubmit) {
  return post<{ jobId: CanvasId; status: CanvasSmartEditComposeStatus }>('/smart-edit/compose', data)
}

export function canvasSmartEditComposeList(projectId: CanvasId) {
  return readPost<{ jobs: CanvasSmartEditComposeJob[] }>('/smart-edit/compose/list', { projectId })
}

export function canvasSmartEditComposeCancel(data: { projectId: CanvasId; nodeId: string; jobId: CanvasId }) {
  return post<{ status: CanvasSmartEditComposeStatus }>('/smart-edit/compose/cancel', data)
}

export function canvasPublicationScenesRead(projectId: CanvasId) {
  return readPost<{ sceneVersion: string | number; document: Record<string, unknown> }>('/publication/scenes/read', { projectId })
}

export function canvasWorkspaceLoad(projectId: CanvasId, sinceSeq?: number) {
  return post<CanvasWorkspaceLoad>('/workspace/load', {
    projectId,
    ...(sinceSeq == null ? {} : { sinceSeq })
  })
}

export function canvasWorkspaceActiveTasks(workspaceId: CanvasId) {
  return readPost<CanvasActiveTasks>('/workspace/tasks/active', { workspaceId })
}

export function canvasWorkspaceMutate(data: {
  workspaceId: CanvasId
  operationId: string
  sessionId: string
  mutations: CanvasMutation[]
  sceneWrite?: { expectedVersion: string | number; document: import('@/types/canvas-api').CanvasSceneDocument; adoption?: import('@/types/canvas-api').CanvasSceneAdoption }
}) {
  return post<CanvasMutationResult>('/workspace/mutate', data)
}

export function canvasWorkspaceChanges(data: { workspaceId: CanvasId; afterSeq: number; limit?: number }) {
  return post<{ items: CanvasOperation[]; currentSeq: number; lastSeq: number; hasMore: boolean }>('/workspace/changes', data)
}

export function canvasWorkspaceCopy(data: {
  workspaceId: CanvasId
  operationId: string
  sources: Array<{ sourceObjectId: string; expectedVersion: number }>
}) {
  return post<CanvasMutationResult & { idMap?: Record<string, string> }>('/workspace/copy', data)
}

export function canvasWorkspaceUndo(data: {
  workspaceId: CanvasId
  operationId: string
  targetOperationId: string
  sessionId: string
  locks: CanvasLockItem[]
}) {
  return post<CanvasMutationResult>('/workspace/undo', data)
}

export function canvasWorkspaceViewLoad(workspaceId: CanvasId) {
  return post<CanvasWorkspaceView>('/workspace/view/load', { workspaceId })
}

export function canvasWorkspaceViewSave(data: {
  workspaceId: CanvasId
  expectedVersion: number
  view: Record<string, unknown>
}) {
  return post<{ viewVersion: number }>('/workspace/view/save', data)
}

export function canvasWorkspaceDraftList(workspaceId: CanvasId) {
  return post<{ items: CanvasDraft[] }>('/workspace/draft/list', { workspaceId })
}

export function canvasWorkspaceDraftSave(data: {
  workspaceId: CanvasId
  objectId: string
  expectedDraftVersion: number
  baseObjectVersion: number
  payload: unknown
}) {
  return post<CanvasDraft>('/workspace/draft/save', data)
}

export function canvasWorkspaceDraftDelete(data: {
  workspaceId: CanvasId
  objectId: string
  expectedDraftVersion: number
}) {
  return post<void>('/workspace/draft/delete', data)
}

export function canvasProjectSettingList(projectId: CanvasId) {
  return post<{ items: CanvasProjectSetting[] }>('/project/setting/list', { projectId })
}

export function canvasProjectSettingUpdate(data: {
  projectId: CanvasId
  settingKey: string
  expectedVersion: number
  value: unknown
}) {
  return post<CanvasProjectSetting>('/project/setting/update', data)
}

export function canvasMemberList(projectId: CanvasId) {
  return post<{ items: CanvasMember[] }>('/member/list', { projectId })
}

export function canvasMemberRole(data: {
  projectId: CanvasId
  userId: CanvasId
  roleCode: Exclude<CanvasProjectRole, 'OWNER'>
}) {
  return post<void>('/member/role', data)
}

export function canvasMemberRemove(projectId: CanvasId, userId: CanvasId) {
  return post<void>('/member/remove', { projectId, userId })
}

export function canvasMemberExit(projectId: CanvasId) {
  return post<void>('/member/exit', { projectId })
}

export function canvasInviteCreate(data: {
  projectId: CanvasId
  roleCode: Exclude<CanvasProjectRole, 'OWNER'>
  validDays?: number
  maxUses?: number
}) {
  return post<CanvasInvite>('/invite/create', data)
}

export function canvasInviteList(projectId: CanvasId) {
  return post<{ items: CanvasInvite[] }>('/invite/list', { projectId })
}

export function canvasInviteRevoke(projectId: CanvasId, id: CanvasId) {
  return post<void>('/invite/revoke', { projectId, id })
}

export function canvasApplicationApply(data: { inviteToken: string; message?: string }) {
  return post<CanvasApplication>('/application/apply', data)
}

export function canvasApplicationList(projectId: CanvasId) {
  return post<{ items: CanvasApplication[] }>('/application/list', { projectId })
}

export function canvasApplicationReview(data: {
  id: CanvasId
  decision: 'APPROVE' | 'REJECT'
  remark?: string
}) {
  return post<void>('/application/review', data)
}

export function canvasShareCreate(data: { projectId: CanvasId; validDays?: number }) {
  return post<CanvasShare>('/share/create', data)
}

export function canvasShareDetail(shareToken: string) {
  return post<CanvasShareDetail>('/share/detail', { shareToken })
}

export function canvasShareList(projectId: CanvasId) {
  return post<{ items: CanvasShare[] }>('/share/list', { projectId })
}

export function canvasShareUpdate(projectId: CanvasId, id: CanvasId) {
  return post<CanvasShare>('/share/update', { projectId, id })
}

export function canvasShareRevoke(projectId: CanvasId, id: CanvasId) {
  return post<void>('/share/revoke', { projectId, id })
}

export function canvasEntitlementEffective(projectId?: CanvasId) {
  return post<CanvasEntitlements>('/entitlement/effective', projectId ? { projectId } : {})
}

export function canvasResourceRegister(data: {
  workspaceId: CanvasId
  sourceType: 'USER_ASSET' | 'OFFICIAL_ASSET' | 'AUDIO_ASSET' | 'MEDIA_TASK' | 'MEDIA_RESULT' | 'ASSET_CENTER'
  sourceId: CanvasId
  sourceCategoryCode?: string
}) {
  return post<CanvasResource>('/resource/register', data)
}

export function canvasImageSplit(data: CanvasImageSplitRequest) {
  return post<CanvasImageSplitResult>('/image/split', data)
}

export function canvasImageLayerMerge(data: CanvasImageLayerMergeRequest) {
  return post<CanvasImageLayerMergeResult>('/image/layer/merge', data)
}

export function canvasResourceTransfer(data: {
  workspaceId: CanvasId
  resourceId: CanvasId
  operationId: string
  targetCategory?: string
  assetName: string
  remark?: string
}) {
  return post<Record<string, unknown>>('/resource/transfer', data)
}

export function canvasResourceUpload(workspaceId: CanvasId, items: Array<{ file: File; mediaType: CanvasMediaType }>) {
  const form = new FormData()
  form.append('workspaceId', workspaceId)
  for (const item of items) form.append('mediaTypes', item.mediaType)
  for (const item of items) form.append('files', item.file)
  return canvasApiPost<CanvasResource[]>(`${CANVAS}/resource/upload`, form).then(unwrap).then(normalizeCanvasPayload)
}

export function canvasCollaborationTicket(workspaceId: CanvasId, pageId: string) {
  return post<CanvasCollaborationTicket>('/collaboration/ticket', { workspaceId, pageId })
}

export function canvasCollaborationState(workspaceId: CanvasId) {
  return post<CanvasCollaborationState>('/collaboration/state', { workspaceId })
}

export function canvasLockAcquire(workspaceId: CanvasId, sessionId: string, objectIds: string[]) {
  return post<{ locks: CanvasCollaborationLock[] }>('/collaboration/lock/acquire', { workspaceId, sessionId, objectIds })
}

export function canvasLockRenew(workspaceId: CanvasId, sessionId: string, locks: CanvasLockItem[]) {
  return post<{ locks: CanvasCollaborationLock[] }>('/collaboration/lock/renew', { workspaceId, sessionId, locks })
}

export function canvasLockRelease(workspaceId: CanvasId, sessionId: string, locks: CanvasLockItem[]) {
  return post<void>('/collaboration/lock/release', { workspaceId, sessionId, locks })
}

export type CanvasGenerateRequest<TRequest extends Record<string, unknown>> = {
  workspaceId: CanvasId
  objectId: string
  expectedVersion: number
  sessionId: string
  lockToken: string
  operationId: string
  resourceIds?: CanvasId[]
  request: TRequest
}

export type CanvasAudioRequest = { modelCode: string; capabilityCode: string; parameters: Record<string, unknown> }

export function canvasGenerateAudio(data: CanvasGenerateRequest<CanvasAudioRequest>) {
  return post<CanvasTaskProjection>('/generate/audio', data)
}

export function canvasQuoteAudio(data: { workspaceId: CanvasId; resourceIds: CanvasId[]; request: CanvasAudioRequest }, ownerSignal?: AbortSignal) {
  const snapshot = structuredClone(data)
  return requestCanvasQuote('canvas-audio', snapshot, signal => canvasApiPost<BillingQuoteWire>(
    `${CANVAS}/generate/audio/quote`, snapshot, { signal, requestTimeoutMs: CANVAS_QUOTE_TIMEOUT_MS }
  ).then(unwrap), ownerSignal)
}

export function canvasPrepareAudioAttachment(data: { workspaceId: CanvasId; modelCode: string; capabilityCode: string; field: string; resourceId: CanvasId }) {
  return post<{ referenceId: string; resourceId: string }>('/generate/audio/attachment', data)
}

export function canvasGenerateImage<T extends Record<string, unknown>>(data: CanvasGenerateRequest<T>) {
  return post<CanvasTaskProjection>('/generate/image', data)
}

export function canvasGenerateVideo<T extends Record<string, unknown>>(data: CanvasGenerateRequest<T>) {
  return post<CanvasTaskProjection>('/generate/video', data)
}

export function canvasQuoteVideoTool<T extends Record<string, unknown>>(data: {
  workspaceId: CanvasId
  resourceIds: CanvasId[]
  videoTool: { operation: string; sourceObjectId: string; sourceExpectedVersion: number; sourceResourceId: CanvasId }
  request: T
}, ownerSignal?: AbortSignal) {
  const request = structuredClone(data)
  return requestCanvasQuote('video-tool', request, signal => quoteWithCanvasMetadataRecovery(request,
    async quoteSignal => unwrap(await canvasApiPost<BillingQuoteWire>(
      `${CANVAS}/generate/video/quote`, request, { signal: quoteSignal, requestTimeoutMs: CANVAS_QUOTE_TIMEOUT_MS }
    )), signal), ownerSignal)
}

export function canvasGenerateTask(workspaceId: CanvasId, taskId: CanvasId) {
  return post<CanvasTaskProjection>('/generate/task', { workspaceId, taskId })
}

export function canvasGenerateHistory(data: {
  workspaceId: CanvasId
  objectId: string
  pageNum?: number
  pageSize?: number
}) {
  return post<CanvasTaskHistory>('/generate/history', data)
}

export function canvasTextImport(workspaceId: CanvasId, file: File) {
  const form = new FormData()
  form.append('workspaceId', workspaceId)
  form.append('file', file)
  return canvasApiPost<{ textContent: string; textFormat: 'TEXT'; characterCount: number }>(`${CANVAS}/text/import`, form)
    .then(unwrap)
}

export function canvasTextSessions(data: { workspaceId: CanvasId; objectId: string; pageNum?: number; pageSize?: number }) {
  return post<{ items: Array<{ sessionId: CanvasId; title?: string | null; lastMessageSeq?: CanvasId; lastActiveTime?: string | null }>; total: number; pageNum: number; pageSize: number }>('/text/sessions', data)
}

export function canvasTextResultAdopt(data: {
  workspaceId: CanvasId
  runId: CanvasId
  mode: 'REPLACE' | 'APPEND' | 'CREATE_NODE'
  objectId: string
  expectedVersion: number
  sessionId: string
  lockToken: string
  operationId: string
  title?: string
}) {
  return post<CanvasMutationResult>('/text/result/adopt', data)
}

export function canvasGenerateResultSelect(data: {
  workspaceId: CanvasId
  objectId: string
  expectedVersion: number
  resultId: CanvasId
  sessionId: string
  lockToken: string
  operationId: string
}) {
  return post<Record<string, unknown>>('/generate/result/select', data)
}
