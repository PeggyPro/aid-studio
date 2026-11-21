import { canvasApiPost } from '@/utils/canvas/canvasApiClient'
import { canvasApiStreamPost } from '@/utils/canvas/canvasApiClient'
import { parseSseEventBlock } from '@/utils/sseEventBlock'
import { normalizeCanvasPayload } from './canvas'
import { unwrap } from './shared'

const ROOT = '/api/user/canvas/conversation'
const post = <T>(path: string, data: unknown): Promise<T> =>
  canvasApiPost<T>(`${ROOT}${path}`, data).then(unwrap).then(normalizeCanvasPayload)

export type CanvasConversationReference = { objectId: string; expectedVersion: number }
export type CanvasConversationTurn = {
  id: string
  prompt: string
  modelCode: string
  references?: CanvasConversationReference[]
  replyText?: string | null
  status: 'CHAT_SUBMITTING' | 'CHAT_RUNNING' | 'PAUSED_CHAT' | 'PLAN_READY' | 'AWAITING_STEP' | 'ACTION_SUBMITTING' | 'ACTION_RUNNING' | 'PAUSED' | 'PAUSED_RESULT' | 'DONE' | 'FAILED' | 'CANCELLED'
  chatQuote?: number | null
  planPrompt?: string | null
  planModel?: string | null
  planQuote?: number | null
  planVersion: number
  mode?: 'MANUAL' | 'AUTO' | null
  budgetCap?: number | null
  spentCredits?: number | null
  resultNodeId?: string | null
  resultText?: string | null
  errorMessage?: string | null
  chatTaskId?: string | null
  chatTaskStatus?: string | null
  actionTaskId?: string | null
  actionTaskStatus?: string | null
}
export type CanvasConversationSession = { sessionId: string; workspaceId: string; title: string; turns: CanvasConversationTurn[] }
export type CanvasConversationQuote = { modelCode: string; preHoldCredits: number; displayText: string }

export const canvasConversationOpen = (workspaceId: string) => post<CanvasConversationSession>('/open', { workspaceId })
export const canvasConversationList = (workspaceId: string) => post<{ items: Array<{ sessionId: string; title: string; updatedAt: string }> }>('/list', { workspaceId })
export const canvasConversationDetail = (sessionId: string, workspaceId: string) => post<CanvasConversationSession>('/detail', { sessionId, workspaceId })
export const canvasConversationQuoteMessage = (data: { workspaceId: string; sessionId: string; prompt: string; modelCode: string; references: CanvasConversationReference[] }) => post<CanvasConversationQuote>('/message/quote', data)
export const canvasConversationSend = (data: { workspaceId: string; sessionId: string; prompt: string; modelCode: string; references: CanvasConversationReference[]; clientRequestId: string; authorizedCredits: number }) => post<CanvasConversationSession>('/message/send', data)
export const canvasConversationRequoteMessage = (sessionId: string, workspaceId: string) => post<CanvasConversationQuote>('/message/requote', { sessionId, workspaceId })
export const canvasConversationRetryMessage = (data: { workspaceId: string; sessionId: string; authorizedCredits: number }) => post<CanvasConversationSession>('/message/retry', data)
export const canvasConversationUpdatePlan = (data: { workspaceId: string; sessionId: string; prompt: string; modelCode: string; planVersion: number }) => post<CanvasConversationSession>('/plan/update', data)
export const canvasConversationQuotePlan = (sessionId: string, workspaceId: string) => post<CanvasConversationQuote>('/plan/quote', { sessionId, workspaceId })
export const canvasConversationConfirmPlan = (data: { workspaceId: string; sessionId: string; planVersion: number; mode: 'MANUAL' | 'AUTO'; quotedCredits: number; budgetCap: number }) => post<CanvasConversationSession>('/plan/confirm', data)
export const canvasConversationConfirmStep = (data: { workspaceId: string; sessionId: string; planVersion: number; quotedCredits: number }) => post<CanvasConversationSession>('/step/confirm', data)
export const canvasConversationCancel = (sessionId: string, workspaceId: string) => post<CanvasConversationSession>('/run/cancel', { sessionId, workspaceId })

/** A reconnect receives a fresh authoritative snapshot, so terminal states are never lost offline. */
export function subscribeCanvasConversation(sessionId: string, workspaceId: string, onState: (state: CanvasConversationSession) => void) {
  const controller = new AbortController()
  const deliver = (state: CanvasConversationSession) => {
    if (!controller.signal.aborted && String(state.sessionId) === sessionId && String(state.workspaceId) === workspaceId) onState(state)
  }
  // A proxy may keep an SSE connection open without flushing events. Re-read
  // authoritative state as a fallback; never repeat a send or generation command.
  let polling = false
  const poll = async () => {
    if (polling || controller.signal.aborted) return
    polling = true
    try { deliver(await canvasConversationDetail(sessionId, workspaceId)) }
    catch { /* Existing authentication handling applies; the next read can recover. */ }
    finally { polling = false }
  }
  const pollTimer = window.setInterval(() => { void poll() }, 5000)
  void (async () => {
    while (!controller.signal.aborted) {
      try {
        const response = await canvasApiStreamPost(`${ROOT}/run/events`, { sessionId, workspaceId }, controller.signal)
        const reader = response.body?.getReader()
        if (!reader) throw new Error('对话事件流不可用')
        const decoder = new TextDecoder()
        let buffer = ''
        while (!controller.signal.aborted) {
          const chunk = await reader.read()
          if (chunk.done) break
          buffer += decoder.decode(chunk.value, { stream: true })
          if (buffer.length > 1024 * 1024) throw new Error('对话事件过大')
          buffer = buffer.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
          let separator: number
          while ((separator = buffer.indexOf('\n\n')) >= 0) {
            const event = parseSseEventBlock(buffer.slice(0, separator))
            buffer = buffer.slice(separator + 2)
            if (event?.event !== 'state') continue
            const state = normalizeCanvasPayload(JSON.parse(event.data) as CanvasConversationSession)
            deliver(state)
          }
        }
      } catch { /* The next connection replays the latest state. */ }
      if (!controller.signal.aborted) await new Promise<void>((resolve) => {
        const timer = window.setTimeout(resolve, 2000)
        controller.signal.addEventListener('abort', () => { window.clearTimeout(timer); resolve() }, { once: true })
      })
    }
  })()
  return () => { window.clearInterval(pollTimer); controller.abort() }
}
