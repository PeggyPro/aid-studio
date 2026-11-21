import { buildUserApiAuthHeaders, resolveClientApiUrl } from '@/utils/api'
import { parseSseEventBlock } from '@/utils/sseEventBlock'

const MAX_BUFFER_CHARS = 1024 * 1024

export function parseStudioProjectScope(scopeKey: string): { projectId: number; episodeId: number } | null {
  const match = /^project-(\d+):episode-(\d+)$/.exec(scopeKey.trim())
  if (!match) return null
  const projectId = Number(match[1])
  const episodeId = Number(match[2])
  if (!Number.isSafeInteger(projectId) || projectId <= 0 || !Number.isSafeInteger(episodeId) || episodeId < 0) return null
  return { projectId, episodeId }
}

export function isStudioTaskScopeCurrent(expectedScopeKey: string, currentScopeKey: string): boolean {
  return Boolean(expectedScopeKey) && expectedScopeKey === currentScopeKey
}

export interface StudioConversationRunRequest {
  projectId: number
  episodeId: number
  sessionId?: number
  clientMessageId: string
  message: string
  selectedNodeIds: string[]
  confirmedParameters?: Record<string, unknown>
}

export type StudioConversationStreamEvent =
  | { type: 'session'; sessionId: number; runId: number }
  | { type: 'message_delta'; messageId: number; delta: string }
  | { type: 'clarification'; field: string; question: string; options: Array<{ label: string; value: string }> }
  | { type: 'plan'; steps: Array<Record<string, unknown>>; estimatedCredits: number }
  | { type: 'step'; stepId: string; status: string; taskId?: number }
  | { type: 'done'; runId: number }
  | { type: 'error'; message: string; code?: string }

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
}

export function parseStudioConversationEvent(event: string, raw: string): StudioConversationStreamEvent | null {
  let value: unknown
  try {
    value = JSON.parse(raw)
  } catch {
    value = { message: raw }
  }
  const data = asRecord(value)
  if (event === 'session') return { type: 'session', sessionId: Number(data.sessionId), runId: Number(data.runId) }
  if (event === 'message_delta') return { type: 'message_delta', messageId: Number(data.messageId), delta: String(data.delta ?? '') }
  if (event === 'clarification') return {
    type: 'clarification',
    field: String(data.field ?? ''),
    question: String(data.question ?? ''),
    options: Array.isArray(data.options) ? data.options.map((item) => {
      const option = asRecord(item)
      return { label: String(option.label ?? ''), value: String(option.value ?? '') }
    }) : []
  }
  if (event === 'plan') return {
    type: 'plan',
    steps: Array.isArray(data.steps) ? data.steps.map(asRecord) : [],
    estimatedCredits: Number(data.estimatedCredits ?? 0)
  }
  if (event === 'step') return {
    type: 'step',
    stepId: String(data.stepId ?? ''),
    status: String(data.status ?? ''),
    taskId: data.taskId == null ? undefined : Number(data.taskId)
  }
  if (event === 'done') return { type: 'done', runId: Number(data.runId) }
  if (event === 'error') return { type: 'error', message: String(data.message ?? '对话任务失败'), code: data.code == null ? undefined : String(data.code) }
  return null
}

/** 对话编排后端上线后直接启用；使用 fetch 是为了给 SSE 请求携带现有 Bearer Token。 */
export async function streamStudioConversation(
  request: StudioConversationRunRequest,
  onEvent: (event: StudioConversationStreamEvent) => void,
  signal?: AbortSignal
): Promise<void> {
  const response = await fetch(resolveClientApiUrl('/api/user/studio/conversation/run'), {
    method: 'POST',
    headers: { ...buildUserApiAuthHeaders(), 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(request),
    signal
  })
  if (!response.ok || !response.body) throw new Error(`对话连接失败（${response.status}）`)
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let completed = false
  try {
    while (true) {
      const { done, value } = await reader.read()
      buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, '\n')
      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const block = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        const parsed = parseSseEventBlock(block)
        const event = parsed ? parseStudioConversationEvent(parsed.event, parsed.data) : null
        if (event) onEvent(event)
        boundary = buffer.indexOf('\n\n')
      }
      if (buffer.length > MAX_BUFFER_CHARS) throw new Error('单条对话事件过长')
      if (done) {
        completed = true
        break
      }
    }
    const trailing = parseSseEventBlock(buffer.trim())
    const event = trailing ? parseStudioConversationEvent(trailing.event, trailing.data) : null
    if (event) onEvent(event)
  } finally {
    if (!completed) await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}
