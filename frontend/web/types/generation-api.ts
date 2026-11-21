import type { CanvasApiId, CanvasId } from '@/types/canvas-api'

export type UserGenerationKind = 'text' | 'image' | 'video' | 'audio'
export type UserGenerationStatus =
  | 'QUEUED'
  | 'PENDING'
  | 'PROCESSING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'

export type UserGenerationReferenceRole = 'REFERENCE' | 'FIRST_FRAME' | 'LAST_FRAME'

export interface UserGenerationReference {
  resourceId: CanvasId
  mention: string
  role: UserGenerationReferenceRole
}

export interface UserGenerationNodeReference {
  objectId: string
  expectedVersion: number
  mention: string
  selectionStart?: number
  selectionEnd?: number
}

export interface UserGenerationBaseRequest {
  modelName?: string
  capabilityCode?: string
  projectId?: CanvasId
  episodeId?: CanvasId | number
  workspaceId?: CanvasId
  clientRequestId: string
  session?: { sessionId?: CanvasId; title?: string }
  canvasNode?: {
    objectId: string
    expectedVersion: number
    editSessionId: string
    lockToken: string
  }
  parameters?: Record<string, unknown>
}

export interface UserTextGenerationRequest extends UserGenerationBaseRequest {
  prompt: string
  references?: UserGenerationReference[]
  nodeReferences?: UserGenerationNodeReference[]
  resultFormat?: 'TEXT' | 'MARKDOWN' | 'HTML'
  reasoningEnabled?: boolean
  includeReasoning?: boolean
}

export interface UserImageGenerationRequest extends UserGenerationBaseRequest {
  funcCode?: string
  prompt: string
  negativePrompt?: string
  size?: string
  expectedImageCount?: number
  references?: UserGenerationReference[]
  nodeReferences?: UserGenerationNodeReference[]
  imageEdit?: {
    operation: 'REDRAW' | 'ERASE' | 'EXPAND' | 'RELIGHT' | 'LAYER_SPLIT' | 'TOUCH_EDIT' | 'EMOTION' | 'PORTRAIT_TEXTURE' | 'MULTI_VIEW' | 'STYLE_APPLY'
    sourceResourceId: CanvasId
    sourceObjectId?: string
    sourceExpectedVersion?: number
    maskResourceId?: CanvasId
    maskEncoding?: 'BLACK_WHITE' | 'ALPHA'
    expand?: { targetWidth: number; targetHeight: number; sourceX: number; sourceY: number }
    lighting?: {
      brightness?: number
      colorTemperature?: number
      keyLightAzimuth?: number
      keyLightElevation?: number
      rimLightEnabled?: boolean
      rimLightAzimuth?: number
      rimLightElevation?: number
    }
    selections?: Array<{ x: number; y: number; width: number; height: number; instruction: string }>
    faces?: Array<{ region: { x: number; y: number; width: number; height: number }; expression: string }>
    portrait?: { skinTexture?: number; surfaceTexture?: number; sharpness?: number }
    camera?: { yaw: number; pitch: number; framing: 'WIDE' | 'MEDIUM' | 'CLOSE' }
    style?: { styleSource: 'official' | 'custom'; styleAssetId: CanvasId }
  }
}

export interface UserVideoGenerationRequest extends UserGenerationBaseRequest {
  prompt: string
  durationSeconds?: number
  aspectRatio?: string
  audio?: boolean
  bgm?: boolean
  audioType?: string
  voiceId?: string
  references?: UserGenerationReference[]
  nodeReferences?: UserGenerationNodeReference[]
}

export interface UserGenerationTask {
  taskId: CanvasId
  mediaType: 'TEXT' | 'IMAGE' | 'VIDEO' | string
  protocol?: string | null
  modelName?: string | null
  status: UserGenerationStatus | string
  queuePosition?: number | null
  eta?: Record<string, unknown> | null
  providerTaskId?: string | null
  originUrl?: string | null
  ossUrl?: string | null
  textContent?: string | null
  errorMessage?: string | null
  errorCode?: string | null
  errorType?: string | null
  errorSource?: string | null
  userMessage?: string | null
  needRecharge?: boolean
  rechargeOwner?: string | null
  retryable?: boolean
  refundStatus?: string | null
  billingStatus?: string | null
  preHoldAmount?: number | null
  actualCost?: number | null
  billingMode?: string | null
  outputDurationSeconds?: number | null
}

export interface UserGenerationResponse {
  sessionId?: CanvasApiId | null
  messageId?: CanvasApiId | null
  task: UserGenerationTask
}
