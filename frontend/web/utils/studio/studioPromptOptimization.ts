import type { UserTextGenerationRequest } from '@/types/generation-api'
import { canvasApiStreamPost } from '@/utils/canvas/canvasApiClient'
import { parseSseEventBlock } from '@/utils/sseEventBlock'

/** One-shot request: never attach a conversation or a node writeback target. */
export function promptOptimizationRequest(modelName: string, prompt: string, mediaKind: 'image' | 'video' | 'text'): UserTextGenerationRequest {
  return {
    modelName,
    clientRequestId: 'prompt-optimization-quote',
    prompt: `请优化以下${mediaKind === 'text' ? '文本' : mediaKind === 'video' ? '视频' : '图片'}生成提示词，保留原意、主体和明确约束，${mediaKind === 'text' ? '改善表达、结构和输出要求' : '改善构图、光线、风格'}${mediaKind === 'video' ? '与镜头运动' : ''}。保留原文中的素材引用标记，不虚构素材内容。只返回可直接使用的完整提示词，不要解释，不要前后引号。\n\n原始提示词：\n${prompt.trim()}`,
    reasoningEnabled: false,
    includeReasoning: false,
    parameters: { max_tokens: 2000 },
    resultFormat: 'TEXT'
  }
}

/** Deltas are previews. Only the authoritative done payload can be applied. */
export async function streamPromptOptimization(request: UserTextGenerationRequest, signal: AbortSignal, onPreview: (text: string) => void): Promise<string> {
  const controller = new AbortController()
  const abort = () => controller.abort(signal.reason)
  signal.throwIfAborted()
  signal.addEventListener('abort', abort, { once: true })
  let timer: ReturnType<typeof setTimeout> | undefined
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
  const armTimeout = () => {
    clearTimeout(timer)
    timer = setTimeout(() => controller.abort(new Error('优化连接超时，结果未确认，请勿重复提交')), 60000)
  }
  try {
    armTimeout()
    const response = await canvasApiStreamPost('/api/user/generation/text?stream=true', request, controller.signal)
    if (!response.headers.get('content-type')?.includes('text/event-stream')) throw new Error('服务端未返回文本事件流')
    reader = response.body?.getReader()
    if (!reader) throw new Error('文本优化未返回事件流')
    const decoder = new TextDecoder()
    let buffer = '', preview = ''
    while (true) {
      const chunk = await reader.read()
      signal.throwIfAborted()
      if (chunk.done) throw new Error('优化连接已中断，结果未确认，请勿重复提交')
      armTimeout()
      buffer += decoder.decode(chunk.value, { stream: true })
      if (buffer.length > 1024 * 1024) throw new Error('文本事件过大')
      let boundary: RegExpExecArray | null
      while ((boundary = /\r?\n\r?\n/.exec(buffer))) {
        const event = parseSseEventBlock(buffer.slice(0, boundary.index))
        buffer = buffer.slice(boundary.index + boundary[0].length)
        if (!event || !['delta', 'done', 'failed', 'detached'].includes(event.event)) continue
        const data = JSON.parse(event.data) as { content?: string; message?: string }
        if (event.event === 'failed') throw new Error(data.message || '提示词优化失败')
        if (event.event === 'detached') throw new Error('任务已转入后台，本次弹窗未取得完整结果，请到任务记录查看')
        if (typeof data.content !== 'string') throw new Error('文本优化响应不完整')
        if (event.event === 'done') {
          if (!data.content.trim()) throw new Error('模型返回了空提示词')
          return data.content
        }
        preview += data.content
        if (preview.length > 1024 * 1024) throw new Error('优化文本过长')
        onPreview(preview)
      }
    }
  } finally {
    clearTimeout(timer)
    signal.removeEventListener('abort', abort)
    controller.abort()
    await reader?.cancel().catch(() => undefined)
  }
}
