import type { UserGenerationResponse } from '@/types/generation-api'
import { canvasApiStreamPost } from '@/utils/canvas/canvasApiClient'
import { parseSseEventBlock } from '@/utils/sseEventBlock'

type StreamPayload = { taskId?: string | number; sessionId?: string | number | null; messageId?: string | number | null; message?: string }

/** Text alone has a media-task SSE contract. Its delta is only a preview; the canvas snapshot remains authoritative. */
export function startCanvasTextGenerationStream(request: unknown, onDelta: () => void) {
  const controller = new AbortController()
  let accept!: (response: UserGenerationResponse) => void
  let reject!: (error: Error) => void
  let finish!: () => void
  let prepared = false
  let idleTimer: ReturnType<typeof setTimeout> | null = null
  const armIdleTimeout = (milliseconds: number) => {
    if (idleTimer) clearTimeout(idleTimer)
    idleTimer = setTimeout(() => controller.abort(), milliseconds)
  }
  const accepted = new Promise<UserGenerationResponse>((yes, no) => { accept = yes; reject = no })
  const finished = new Promise<void>((yes) => { finish = yes })
  const networkError = (error: unknown) => Object.assign(
    new Error(error instanceof Error ? error.message : '文本生成连接中断'), { code: 'ERR_NETWORK' }
  )

  void (async () => {
    try {
      armIdleTimeout(30000)
      const response = await canvasApiStreamPost('/api/user/generation/text?stream=true', request, controller.signal)
      const reader = response.body?.getReader()
      if (!reader) throw new Error('文本生成未返回事件流')
      const decoder = new TextDecoder()
      let buffer = ''
      let complete = false
      while (!complete) {
        const chunk = await reader.read()
        if (chunk.done) break
        armIdleTimeout(prepared ? 60000 : 30000)
        buffer += decoder.decode(chunk.value, { stream: true })
        if (buffer.length > 1024 * 1024) throw new Error('文本生成事件过大')
        buffer = buffer.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
        let separator: number
        while ((separator = buffer.indexOf('\n\n')) >= 0) {
          const event = parseSseEventBlock(buffer.slice(0, separator))
          buffer = buffer.slice(separator + 2)
          if (!event) continue
          let data: StreamPayload = {}
          try { data = JSON.parse(event.data) as StreamPayload } catch { /* Ignore malformed nonterminal data. */ }
          if (event.event === 'prepared' && !prepared) {
            const taskId = String(data.taskId || '')
            if (!/^\d+$/.test(taskId)) throw new Error('文本生成未返回任务编号')
            prepared = true
            armIdleTimeout(60000)
            accept({
              sessionId: data.sessionId == null ? undefined : String(data.sessionId),
              messageId: data.messageId == null ? undefined : String(data.messageId),
              task: { taskId, mediaType: 'TEXT', status: 'PROCESSING' }
            })
          } else if (event.event === 'delta' && prepared) {
            onDelta()
          } else if (event.event === 'failed') {
            if (!prepared) reject(new Error(data.message || '文本生成失败'))
            complete = true
            break
          } else if (event.event === 'done' || event.event === 'detached') {
            complete = true
            break
          }
        }
      }
      if (!prepared) reject(networkError('文本生成在任务受理前断开'))
    } catch (error) {
      if (!prepared) reject(networkError(error))
    } finally {
      if (idleTimer) clearTimeout(idleTimer)
      finish()
      controller.abort()
    }
  })()

  return { accepted, finished, close: () => controller.abort() }
}
