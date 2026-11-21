/** 自由画布 API 领域类型。所有 Long ID 在响应适配后统一保留为 string。 */
export type CanvasApiId = string | number
export type CanvasId = string
export type CanvasProjectRole = 'OWNER' | 'EDITOR' | 'VIEWER'
export type CanvasObjectType = 'NODE' | 'EDGE' | 'GROUP'
export type CanvasMutationAction = 'UPSERT' | 'PATCH' | 'DELETE'
export type CanvasMediaType = 'IMAGE' | 'VIDEO' | 'AUDIO' | 'DOCUMENT' | 'MODEL' | 'MODEL_DEP' | 'SCENE_PACKAGE'

export interface CanvasModelBundle {
  format: 'glb' | 'gltf' | 'fbx' | 'obj'
  rootPath: string
  files: Array<{ path: string; resourceId: CanvasId; resourceUrl: string }>
  references: Array<{ ownerPath: string; uri: string; path: string }>
}
export interface CanvasModelUploadResult {
  resourceId: CanvasId; projectId: CanvasId; workspaceId: CanvasId
  bundle: CanvasModelBundle; replayed?: boolean
}
export interface CanvasSceneDocument { schemaVersion: 1 | 2; scenes: Record<string, unknown>[] }
export interface CanvasSceneAdoption { sceneId: string; projectId: string; stepId: string }
export interface CanvasPublicationCard {
  publicationId: CanvasId; projectId: CanvasId; ownerUserId: CanvasId
  contentType: 'CANVAS'; projectName: string; projectDesc: string
  videoUrl: string; coverUrl: string; isPublic: boolean
  visibility: 'PUBLIC' | 'PRIVATE' | 'ADMIN_OFFLINE'; allowCopy: boolean; isDerivative: boolean
}
export interface CanvasPublicationDetail extends CanvasPublicationCard {
  canvas: { schemaVersion: number; projectName: string; objects: CanvasObjectRecord[]; resources: CanvasResource[]
    currentResults: CanvasCurrentResult[]; sceneDocument: CanvasSceneDocument; videoResourceId: CanvasId; coverResourceId: CanvasId }
}
export interface CanvasCopyJob {
  operationId: string; status: 'QUEUED' | 'COPYING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'NOT_FOUND'
  jobId?: CanvasId; projectId?: CanvasId | null; progress?: number
  completedResources?: number; totalResources?: number; completedBytes?: string; totalBytes?: string
  cancelRequested?: boolean; errorCode?: string; replayed?: boolean
}

export interface CanvasPublicationState {
  stateVersion: string | number
  sceneVersion: string | number
  draftRevision: string | number
  visibility: 'PRIVATE' | 'PUBLIC' | 'ADMIN_OFFLINE'
  isPublic: boolean
  allowCopy: boolean
  blocked: boolean
  pendingId?: CanvasId | null
  publishedId?: CanvasId | null
  reviewStatus: 'DRAFT' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'
  reviewReason?: string | null
  hasUnpublishedChanges?: boolean
  hasApprovedVersion?: boolean
  isCurrentDraftApproved?: boolean
  isDerivative?: boolean
  sourcePublicationId?: CanvasId | null
  latestPublicationId?: CanvasId | null
}

export interface CanvasPublicationSubmit {
  projectId: CanvasId
  operationId: string
  expectedRevision: string | number
  expectedSceneVersion: string | number
  projectDesc: string
  videoResourceId: CanvasId
  coverResourceId: CanvasId
  publicCanvas: true
  allowCopy: boolean
  projectName?: string
}

export interface CanvasWorkspaceMeta {
  workspaceId: CanvasId
  projectId: CanvasId
  workspaceType: 'FREE_CANVAS'
  schemaVersion: number
  revision: number
  eventSeq: number
  nodeCount: number
  edgeCount: number
  groupCount: number
  storageBytes: number
}

export interface CanvasProjectSummary {
  projectId: CanvasId
  projectName: string
  projectDesc?: string | null
  coverUrl?: string | null
  /** 画布是否公开：1 公开，0 不公开。 */
  isPublic?: string | null
  publicationState?: CanvasPublicationState
  ownerUserId: CanvasId
  roleCode: CanvasProjectRole
  status: number
  workspaceId: CanvasId
  revision: number
  eventSeq: number
  nodeCount: number
  edgeCount: number
  groupCount: number
  updateTime?: string | null
  createTime?: string | null
}

export interface CanvasProjectDetail extends Omit<CanvasProjectSummary, 'workspaceId' | 'revision' | 'eventSeq' | 'nodeCount' | 'edgeCount' | 'groupCount'> {
  workspace: CanvasWorkspaceMeta
  settings?: CanvasProjectSetting[]
}

export interface CanvasObjectRecord {
  objectId: CanvasId
  objectType: CanvasObjectType
  objectVersion: number
  deleted: boolean
  content: Record<string, unknown> | null
  updateTime?: string | null
}

export interface CanvasResource {
  /** In-memory bytes for the modeling editor. Never sent to the server. */
  sceneDataUrl?: string
  /** Browser-only authenticated preview; never persisted as a resource URL. */
  displayUrl?: string
  resourceId: CanvasId
  workspaceId?: CanvasId
  mediaType: CanvasMediaType
  resourceUrl: string
  fileName?: string | null
  fileSize?: number | null
  mimeType?: string | null
  sourceType?: string | null
  sourceId?: CanvasId | null
  status?: string | null
  createTime?: string | null
  bundle?: CanvasModelBundle
}

/** Server-side smart edit compose: media is read from registered resources, never uploaded again. */
export interface CanvasSmartEditComposeClip {
  clipId: string
  track: 'video' | 'overlay' | 'audio'
  sourceResourceId: CanvasId
  timelineStartSeconds: number
  rangeStartSeconds: number
  rangeEndSeconds: number
  volume: number
  overlayRect?: { x: number; y: number; w: number; h: number }
  transitionOut?: { type: 'cut' | 'fade' | 'dissolve' | 'wipe'; durationSeconds: number }
}
export interface CanvasSmartEditComposeSubmit {
  projectId: CanvasId
  nodeId: string
  operationId: string
  fingerprint: string
  aspectRatio: string
  width: number
  height: number
  durationSeconds: number
  hiddenTracks: string[]
  clips: CanvasSmartEditComposeClip[]
  subtitles: Array<{ id: string; timelineStartSeconds: number; durationSeconds: number; text: string }>
  texts: Array<{ id: string; timelineStartSeconds: number; durationSeconds: number; text: string; fontSize: number; color: string; rect: { x: number; y: number; w: number; h: number }; animation: 'none' | 'fade' | 'slide' }>
}
export type CanvasSmartEditComposeStatus = 'queued' | 'running' | 'succeeded' | 'failed' | 'cancelled'
export interface CanvasSmartEditComposeJob {
  jobId: CanvasId
  projectId: CanvasId
  nodeId: string
  status: CanvasSmartEditComposeStatus
  progress?: number | null
  fingerprint: string
  resourceId?: CanvasId | null
  resourceUrl?: string | null
  errorMessage?: string | null
}

/** Deterministic image splitting creates registered workspace resources without a model task. */
export interface CanvasImageSplitRequest {
  workspaceId: CanvasId
  sourceObjectId: string
  sourceResourceId: CanvasId
  expectedVersion: number
  clientRequestId: string
  mode: 'GRID' | 'REGIONS'
  rows?: number
  columns?: number
  selectedCells?: Array<{ row: number; col: number }>
  regions?: Array<{ x: number; y: number; width: number; height: number }>
}

export interface CanvasImageSplitOutput {
  index: number
  row?: number
  col?: number
  x: number
  y: number
  width: number
  height: number
  resourceId: CanvasId
  resourceUrl: string
}

export interface CanvasImageSplitResult {
  sourceWidth: number
  sourceHeight: number
  outputs: CanvasImageSplitOutput[]
}

export interface CanvasImageLayerMergeRequest {
  workspaceId: CanvasId
  sourceObjectId: string
  expectedVersion: number
  clientRequestId: string
  canvas: { width: number; height: number }
  backgroundResourceId?: CanvasId
  layers: Array<{
    layerId: string
    resourceId: CanvasId
    rect: { x: number; y: number; width: number; height: number }
    rotationDeg: number
    flipX: boolean
    flipY: boolean
    opacity: number
    visible: boolean
  }>
}

export interface CanvasImageLayerMergeResult {
  resourceId: CanvasId
  resourceUrl: string
  mimeType: 'image/png' | string
  width: number
  height: number
  sourceObjectId: string
  clientRequestId: string
}

export interface CanvasTaskProjection {
  taskId: CanvasId | null
  workspaceId?: CanvasId
  runId?: CanvasId
  sourceType?: string
  objectId?: CanvasId
  mediaType?: CanvasMediaType | string
  status: string
  operationId?: string
  actorUserId?: CanvasId
  inputObjectVersion?: number
  canCancel?: boolean
  progress?: number | null
  eta?: { displayProgress?: number | null; phase?: string | null; waitReasonCode?: string | null } | null
  executionPhase?: 'WAITING_FOR_CAPACITY' | 'SUBMITTING' | 'UPSTREAM_PROCESSING' | 'SAVING_MEDIA' | null
  queueWaitReason?: 'MODEL_CAPACITY' | 'PROVIDER_CAPACITY' | 'ACCOUNT_CAPACITY' | 'PLATFORM_CAPACITY' | null
  resultUrl?: string | null
  originUrl?: string | null
  ossUrl?: string | null
  textContent?: string | null
  errorMessage?: string | null
  errorCode?: string | null
  errorType?: string | null
  errorSource?: string | null
  userMessage?: string | null
  queuePosition?: number | null
  needRecharge?: boolean
  rechargeOwner?: string | null
  retryable?: boolean
  billingStatus?: string | null
  preHoldAmount?: number | null
  actualCost?: number | null
  createTime?: string | null
  updateTime?: string | null
}

export interface CanvasActiveTasks {
  workspaceId: CanvasId
  items: CanvasTaskProjection[]
  total: number
}

export interface CanvasCurrentResult {
  objectId: CanvasId
  taskId: CanvasId
  resultId: CanvasId
  resourceId?: CanvasId | null
  inputObjectVersion: number
  selectionVersion?: number
  mediaType?: CanvasMediaType | string
  resultUrl?: string | null
}

export interface CanvasWorkspaceLoad {
  mode: 'SNAPSHOT' | 'CHANGES'
  workspace: CanvasWorkspaceMeta
  objects?: CanvasObjectRecord[]
  changes?: CanvasOperation[]
  tasks: CanvasTaskProjection[]
  resources: CanvasResource[]
  currentResults?: CanvasCurrentResult[]
  sceneVersion?: string | number
  sceneDocument?: CanvasSceneDocument
  atomicSceneCommit?: boolean
  lastSeq?: number
  currentSeq?: number
  hasMore?: boolean
}

export interface CanvasOperation {
  operationId: string
  actorUserId: CanvasId
  revision: number
  eventSeq: number
  operationType: string
  data: Record<string, unknown>
  createTime?: string | null
}

export interface CanvasWorkspaceView {
  viewVersion: number
  view: Record<string, unknown> | null
}

export interface CanvasMember {
  id: CanvasId
  projectId: CanvasId
  userId: CanvasId
  userName?: string | null
  nickName?: string | null
  avatarUrl?: string | null
  roleCode: CanvasProjectRole
  status: 'ACTIVE'
  joinedTime?: string | null
}

export interface CanvasInvite {
  id: CanvasId
  projectId: CanvasId
  roleCode: Exclude<CanvasProjectRole, 'OWNER'>
  maxUses: number
  usedCount: number
  remainingUses: number
  expireTime?: string | null
  status: 'ACTIVE' | 'REVOKED' | 'EXPIRED'
  inviteToken?: string
}

export interface CanvasApplication {
  id: CanvasId
  projectId: CanvasId
  inviteId?: CanvasId
  applicantUserId?: CanvasId
  userName?: string | null
  nickName?: string | null
  requestedRole: Exclude<CanvasProjectRole, 'OWNER'>
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  applyMessage?: string | null
  reviewRemark?: string | null
  createTime?: string | null
  reviewTime?: string | null
}

export interface CanvasShare {
  id: CanvasId
  projectId: CanvasId
  workspaceId: CanvasId
  snapshotRevision: number
  expireTime?: string | null
  status: 'ACTIVE' | 'REVOKED' | 'EXPIRED'
  shareToken?: string
}

export interface CanvasShareDetail extends CanvasShare {
  projectName?: string
  snapshot: Omit<CanvasWorkspaceLoad, 'mode'>
}

export interface CanvasProjectSetting {
  settingKey: string
  value: unknown
  version: number
}

export interface CanvasDraft {
  objectId: CanvasId
  draftVersion: number
  baseObjectVersion: number
  currentObjectVersion?: number
  stale?: boolean
  payload: unknown
  updateTime?: string | null
}

export interface CanvasOnlineUser {
  userId: CanvasId
  nickName?: string | null
  avatarUrl?: string | null
  sessionCount: number
  lastHeartbeatAt?: string | null
}

export interface CanvasCollaborationLock {
  objectId: CanvasId
  lockType?: 'EDIT' | 'TASK'
  lockToken?: string
  lockFence?: number
  sessionId?: string | null
  taskId?: CanvasId | null
  userId?: CanvasId
  nickName?: string | null
  avatarUrl?: string | null
  acquiredAt?: string | null
  expireAt?: string | null
}

export interface CanvasCollaborationState {
  workspaceId: CanvasId
  serverTime?: string | null
  onlineUsers: CanvasOnlineUser[]
  sessions: Array<Record<string, unknown>>
  locks: CanvasCollaborationLock[]
}

export interface CanvasCollaborationTicket {
  sessionId: string
  ticket: string
  expireAt: string
  websocketPath: string
  lockLeaseSeconds: number
  lockRenewSeconds: number
  textMergeMillis: number
  heartbeatSeconds: number
  runtimeConfig?: Record<string, unknown>
  effectiveLimits?: Record<string, number>
}

export interface CanvasEntitlements {
  items?: Array<{ entitlementCode: string; name?: string; enabled: boolean; value?: string; source?: string }>
  projectCapacityItems?: Array<{ entitlementCode: string; name?: string; enabled: boolean; value?: string; source?: string }>
  entitlements?: Record<string, unknown> | Array<Record<string, unknown>>
  runtimeConfig?: Record<string, unknown>
  effectiveLimits?: Record<string, number>
  [key: string]: unknown
}

export interface CanvasMutation {
  objectId: CanvasId
  objectType: CanvasObjectType
  action: CanvasMutationAction
  expectedVersion: number
  lockToken: string
  payload?: Record<string, unknown>
}

export interface CanvasMutationResult {
  workspace?: CanvasWorkspaceMeta
  objects?: CanvasObjectRecord[]
  operationId?: string
  revision?: number
  eventSeq?: number
  sceneVersion?: CanvasId
  [key: string]: unknown
}

export interface CanvasTaskHistoryItem extends CanvasTaskProjection {
  results: Array<{
    resultId: CanvasId | null
    taskId: CanvasId
    resultIndex?: number
    resourceId?: CanvasId | null
    metadata?: Record<string, unknown> | null
    mediaType?: string
    resultUrl?: string | null
    textContent?: string | null
    selected: boolean
    persisting?: boolean
  }>
}

export interface CanvasTaskHistory {
  items: CanvasTaskHistoryItem[]
  total: number
  currentResult?: CanvasCurrentResult | null
  runtimeRuns?: Array<{
    sourceType: 'RUNTIME_RUN'
    runId: CanvasId
    memberType: 'AGENT' | 'SKILL'
    actorUserId?: CanvasId
    mediaType: string
    inputObjectVersion?: number | null
    status: string
    taskId?: CanvasId | null
    createTime?: string | null
    updateTime?: string | null
    resultPreview?: { textContent?: string | null } | null
    steps?: Array<{ stepIndex?: number; stepKind?: string; status?: string; taskId?: CanvasId | null }>
  }>
  runtimeTotal?: number
}

export interface CanvasPage<T> {
  items: T[]
  total: number
}

export interface CanvasLockItem {
  objectId: CanvasId
  lockToken: string
}
