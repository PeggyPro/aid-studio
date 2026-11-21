import type {
  UserGenerationResponse,
  UserGenerationKind,
  UserGenerationTask,
  UserImageGenerationRequest,
  UserTextGenerationRequest,
  UserVideoGenerationRequest
} from '@/types/generation-api'
import type { BillingQuoteWire } from '@/types/business-api'
import { canvasApiPost } from '@/utils/canvas/canvasApiClient'
import { unwrap } from './shared'
import { CANVAS_QUOTE_TIMEOUT_MS, requestCanvasQuote } from '@/utils/canvas/canvasQuote'
import { quoteWithCanvasMetadataRecovery } from '@/utils/canvas/canvasQuoteMetadataRecovery'

const GENERATION = '/api/user/generation'

function post<T>(path: string, data: unknown): Promise<T> {
  return canvasApiPost<T>(`${GENERATION}${path}`, data).then(unwrap)
}

export function userGenerationText(data: UserTextGenerationRequest) {
  return post<UserGenerationResponse>('/text', data)
}

export function userGenerationImage(data: UserImageGenerationRequest) {
  return post<UserGenerationResponse>('/image', data)
}

export function userGenerationImageQuote(data: UserImageGenerationRequest, ownerSignal?: AbortSignal) {
  return userGenerationQuote('image', data, ownerSignal)
}

/** 正式授权输入只读报价；指定视频缺信息时独立准备后最多重报一次。 */
export function userGenerationQuote(kind: UserGenerationKind, data: UserTextGenerationRequest | UserImageGenerationRequest | UserVideoGenerationRequest, ownerSignal?: AbortSignal) {
  const request = structuredClone(data)
  return requestCanvasQuote(kind, request, signal => quoteWithCanvasMetadataRecovery(request,
    async quoteSignal => unwrap(await canvasApiPost<BillingQuoteWire>(
      `${GENERATION}/${kind}/quote`, request, { signal: quoteSignal, requestTimeoutMs: CANVAS_QUOTE_TIMEOUT_MS }
    )), signal), ownerSignal)
}

export function userGenerationVideo(data: UserVideoGenerationRequest) {
  return post<UserGenerationResponse>('/video', data)
}

export function userGenerationTaskStatus(taskId: string) {
  return post<UserGenerationTask>('/task/status', { taskId })
}

export interface UserGenerationSessionMessage {
  messageId: string
  seqNo: number
  role: string
  status: string
  mediaType?: string | null
  content?: string | null
  mediaTaskId?: string | null
  parts?: Array<{ partId: string; partSeq: number; type: string; text?: string | null; resourceId?: string | null }>
}

export function userGenerationSessionMessages(sessionId: string, beforeSeq?: string, limit = 20) {
  return post<{ sessionId: string; items: UserGenerationSessionMessage[]; nextCursor?: string | null; hasMore: boolean }>(
    '/sessions/messages', { sessionId, ...(beforeSeq ? { beforeSeq } : {}), limit }
  )
}

export function userGenerationSessionList(pageNum = 1, pageSize = 20) {
  return post<{ items: Array<{ sessionId: string; workspaceId?: string | null; nodeObjectId?: string | null; title?: string | null; lastMessageSeq?: string | null; lastActiveTime?: string | null }>; total: number }>(
    '/sessions/list', { pageNum, pageSize }
  )
}
