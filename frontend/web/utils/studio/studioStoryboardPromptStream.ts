import type { ApiEnvelope } from '@/types/business-api'
import type { StudioStoryboardAssetRef, StudioStoryboardTableRow } from '@/types/studio'
import { buildUserApiAuthHeaders, redirectToLogin, request, resolveClientApiUrl } from '@/utils/api'
import { parseSseEventBlock } from '@/utils/sseEventBlock'

const ENDPOINT = '/api/user/studio/storyboard-prompt'
const MAX_BUFFER_CHARS = 2 * 1024 * 1024

export interface StudioStoryboardPromptRunRequest {
  projectId: number
  episodeId: number
  nodeId: string
  clientRequestId: string
  rows: Array<Pick<StudioStoryboardTableRow, 'id' | 'shot' | 'duration' | 'description' | 'shotSize' | 'lighting' | 'dialogue' | 'soundEffect' | 'cameraMovement'>>
  assets: Array<Pick<StudioStoryboardAssetRef, 'kind' | 'title'> & { description?: string }>
}

export interface StudioStoryboardPromptTask {
  taskId: number
  mediaType: string
  status: 'QUEUED' | 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  queuePosition?: number | null
  textContent?: string | null
  userMessage?: string | null
  errorMessage?: string | null
}

export type StudioStoryboardPromptStreamResult =
  | { type: 'done'; taskId: number; content: string }
  | { type: 'detached'; taskId: number; status: string }
  | { type: 'failed'; taskId: number | null; message: string }

export interface StudioStoryboardPromptStreamCallbacks {
  onConnected?: () => void
  onPrepared?: (taskId: number) => void
  onDelta?: (content: string) => void
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
}

function parseRecord(raw: string): Record<string, unknown> {
  try { return asRecord(JSON.parse(raw)) } catch { return { message: raw } }
}

function positiveTaskId(value: unknown): number | null {
  const taskId = Number(value)
  return Number.isSafeInteger(taskId) && taskId > 0 ? taskId : null
}

export function parseStudioStoryboardPromptEvent(
  eventName: string,
  raw: string,
  callbacks: StudioStoryboardPromptStreamCallbacks = {}
): StudioStoryboardPromptStreamResult | null {
  const data = parseRecord(raw)
  if (eventName === 'connected') callbacks.onConnected?.()
  if (eventName === 'run_prepared') {
    const taskId = positiveTaskId(data.taskId)
    if (!taskId) throw new Error('任务事件缺少ID')
    callbacks.onPrepared?.(taskId)
  }
  if (eventName === 'delta') {
    const content = String(data.content ?? '')
    if (content) callbacks.onDelta?.(content)
  }
  if (eventName === 'done') {
    const taskId = positiveTaskId(data.taskId)
    if (!taskId) throw new Error('完成事件缺少ID')
    return { type: 'done', taskId, content: String(data.content ?? '') }
  }
  if (eventName === 'detached') {
    const taskId = positiveTaskId(data.taskId)
    if (!taskId) throw new Error('排队事件缺少ID')
    return { type: 'detached', taskId, status: String(data.status ?? 'QUEUED') }
  }
  if (eventName === 'failed') return {
    type: 'failed', taskId: positiveTaskId(data.taskId), message: String(data.message ?? '提示词合成失败')
  }
  return null
}

function responseMessage(raw: string, status: number): string {
  const data = parseRecord(raw)
  return String(data.msg ?? data.message ?? '').trim() || `提示词请求失败（${status}）`
}

function normalizeLineEndings(value: string, flush = false): string {
  const keepCarriageReturn = !flush && value.endsWith('\r')
  const complete = keepCarriageReturn ? value.slice(0, -1) : value
  const normalized = complete.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  return keepCarriageReturn ? `${normalized}\r` : normalized
}

export async function streamStudioStoryboardPrompts(
  body: StudioStoryboardPromptRunRequest,
  callbacks: StudioStoryboardPromptStreamCallbacks,
  signal: AbortSignal
): Promise<StudioStoryboardPromptStreamResult> {
  const auth = buildUserApiAuthHeaders()
  if (!auth.Authorization) {
    redirectToLogin()
    throw new Error('登录状态已失效')
  }
  const response = await fetch(resolveClientApiUrl(`${ENDPOINT}/run`), {
    method: 'POST',
    headers: { Accept: 'text/event-stream', 'Accept-Encoding': 'identity', 'Content-Type': 'application/json', ...auth },
    body: JSON.stringify(body), cache: 'no-store', credentials: 'omit', signal
  })
  if (response.status === 401) { redirectToLogin(); throw new Error('登录状态已失效') }
  if (!response.ok) throw new Error(responseMessage(await response.text(), response.status))
  if (!String(response.headers.get('content-type') || '').toLowerCase().includes('text/event-stream')) {
    throw new Error(responseMessage(await response.text(), response.status))
  }
  const reader = response.body?.getReader()
  if (!reader) throw new Error('提示词响应为空')
  const decoder = new TextDecoder()
  let buffer = ''
  let terminal: StudioStoryboardPromptStreamResult | null = null
  const dispatch = (eventName: string, raw: string) => { terminal = parseStudioStoryboardPromptEvent(eventName, raw, callbacks) }
  try {
    while (!terminal) {
      const chunk = await reader.read()
      if (chunk.done) { buffer += decoder.decode(); buffer = normalizeLineEndings(buffer, true); break }
      if (chunk.value) buffer += decoder.decode(chunk.value, { stream: true })
      if (buffer.length > MAX_BUFFER_CHARS) throw new Error('提示词事件过长')
      buffer = normalizeLineEndings(buffer)
      let separator = buffer.indexOf('\n\n')
      while (separator >= 0) {
        const block = buffer.slice(0, separator)
        buffer = buffer.slice(separator + 2)
        const parsed = parseSseEventBlock(block)
        if (parsed) dispatch(parsed.event, parsed.data)
        if (terminal) break
        separator = buffer.indexOf('\n\n')
      }
    }
    if (!terminal && buffer.trim()) {
      const parsed = parseSseEventBlock(buffer.trim())
      if (parsed) dispatch(parsed.event, parsed.data)
    }
    if (!terminal) throw new Error('提示词流提前断开')
    return terminal
  } finally {
    try { await reader.cancel() } catch { /* response may already be closed */ }
  }
}

function taskFromEnvelope(response: ApiEnvelope<StudioStoryboardPromptTask>): StudioStoryboardPromptTask {
  if (response.code !== 200 || !response.data) throw new Error(response.msg || '任务查询失败')
  return response.data
}

export async function queryStudioStoryboardPromptTask(taskId: number): Promise<StudioStoryboardPromptTask> {
  return taskFromEnvelope(await request.post<ApiEnvelope<StudioStoryboardPromptTask>>(`${ENDPOINT}/status`, { taskId }))
}

export async function cancelStudioStoryboardPromptTask(taskId: number): Promise<StudioStoryboardPromptTask> {
  return taskFromEnvelope(await request.post<ApiEnvelope<StudioStoryboardPromptTask>>(`${ENDPOINT}/cancel`, { taskId }))
}

export async function confirmStudioStoryboardPromptCancellation(
  requestCancellation: () => Promise<StudioStoryboardPromptTask>,
  onCancelled: (task: StudioStoryboardPromptTask) => void
): Promise<StudioStoryboardPromptTask> {
  const task = await requestCancellation()
  if (task.status === 'CANCELLED') onCancelled(task)
  return task
}
