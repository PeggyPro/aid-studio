import type { CanvasId } from './canvas-api'
import type { UserGenerationNodeReference, UserGenerationReference, UserGenerationTask } from './generation-api'
import type { UserModelListItem } from './business-api'

export type CanvasRuntimeMemberType = 'SKILL' | 'AGENT'
export type CanvasRuntimeStatus = 'CREATED' | 'RUNNING' | 'WAITING_INPUT' | 'SUCCEEDED' | 'FAILED' | 'CANCELED'
export type CanvasRuntimeOutputKind = 'TEXT' | 'IMAGE' | 'VIDEO'

export interface CanvasRuntimeGroup {
  id: CanvasId
  groupCode: string
  parentId?: CanvasId | null
  name: string
  description?: string | null
  iconUrl?: string | null
  sortOrder?: number
}

export interface CanvasRuntimeModel extends Omit<UserModelListItem, 'id' | 'modelType'> {
  defaultModel?: boolean
  modelLogo?: string | null
}

export interface CanvasRuntimeMember {
  memberType: CanvasRuntimeMemberType
  memberId: CanvasId
  memberCode: string
  groupCode: string
  name: string
  description?: string | null
  iconUrl?: string | null
  outputKind: CanvasRuntimeOutputKind
  versionCode?: string | null
  versionId?: CanvasId
  defaultModelCode?: string | null
  models: CanvasRuntimeModel[]
  sortOrder?: number
}

export interface CanvasRuntimePage<T> {
  total: number
  data: T[]
}

export interface CanvasRuntimeErrorDetails {
  errorCode?: string | null
  field?: string | null
  reason?: string | null
  allowedValues?: string[] | null
  retryable?: boolean | null
}

export interface CanvasRuntimeRun {
  runId: CanvasId
  memberType: CanvasRuntimeMemberType
  memberId?: CanvasId
  memberCode?: string
  versionId?: CanvasId
  modelCode?: string | null
  outputKind: CanvasRuntimeOutputKind
  projectId?: CanvasId | null
  workspaceId?: CanvasId | null
  nodeObjectId?: string | null
  status: CanvasRuntimeStatus
  inputPreview?: Record<string, unknown> | null
  resultPreview?: { textContent?: string | null; resultUrl?: string | null } | null
  errorMessage?: string | null
  errorDetails?: CanvasRuntimeErrorDetails | null
  actualCost?: number | null
  task?: UserGenerationTask | null
  steps?: Array<{ stepIndex?: number; stepKind?: string; status?: string; taskId?: CanvasId | null }>
}

export interface CanvasRuntimeInvokeRequest {
  memberType: CanvasRuntimeMemberType
  memberCode: string
  clientRequestId: string
  prompt: string
  modelCode: string
  capabilityCode: string
  projectId?: CanvasId
  workspaceId?: CanvasId
  canvasNode?: { objectId: string; expectedVersion: number; editSessionId: string; lockToken: string }
  references?: UserGenerationReference[]
  nodeReferences?: UserGenerationNodeReference[]
  size?: string
  expectedImageCount?: number
  durationSeconds?: number
  aspectRatio?: string
  parameters?: Record<string, unknown>
}

export interface CanvasRuntimeRunHistory {
  items: CanvasRuntimeRun[]
  total: number
  pageNum: number
  pageSize: number
}
