import type { StudioComposerRef, StudioMediaKind } from '@/types/studio'
import { readStudioCameraMarkAt, studioCameraMarkToken, type StudioCameraMark } from '@/utils/studio/studioCameraMark'

export type StudioPromptDocSpan =
  | { type: 'text'; text: string }
  | { type: 'mention'; ref: StudioComposerRef }
  | { type: 'camera'; mark: StudioCameraMark }

const TOKEN_END = /[\s，。、；：！？,.;:!?(\[{'"\u3000@]/

/** 将 prompt 与 refs 解析为有序片段；同 title 多实例按 refs 出现顺序 FIFO 消费。 */
export function parseStudioPromptToSpans(
  prompt: string,
  refs: StudioComposerRef[]
): StudioPromptDocSpan[] {
  const queues = new Map<string, StudioComposerRef[]>()
  for (const ref of refs) {
    const title = ref.title
    const list = queues.get(title) ?? []
    list.push(ref)
    queues.set(title, list)
  }
  const titles = [...queues.keys()].sort((a, b) => b.length - a.length)
  const spans: StudioPromptDocSpan[] = []
  let i = 0
  let textBuf = ''

  const flushText = () => {
    if (!textBuf) return
    spans.push({ type: 'text', text: textBuf })
    textBuf = ''
  }

  while (i < prompt.length) {
    const camera = readStudioCameraMarkAt(prompt, i)
    if (camera && 'mark' in camera) {
      flushText()
      spans.push({ type: 'camera', mark: camera.mark })
      i += camera.length
      continue
    }
    if (camera && 'raw' in camera) {
      textBuf += camera.raw
      i += camera.raw.length
      continue
    }
    if (prompt[i] === '@') {
      let matched = false
      for (const title of titles) {
        const token = `@${title}`
        if (!prompt.startsWith(token, i)) continue
        const after = prompt[i + token.length]
        // 允许 token 后为文末、空白/标点，或直接接下文（与 AutoLink 空格规则兼容）
        if (after != null && !TOKEN_END.test(after) && after !== '\n') {
          // 例如标题「图」误匹配「图片」——靠最长 title 优先已处理；
          // 若标题不是前缀问题，仍允许非边界后接（中文常见）
        }
        const queue = queues.get(title)
        flushText()
        if (queue && queue.length > 0) {
          spans.push({ type: 'mention', ref: queue.shift()! })
        } else {
          textBuf += token
          flushText()
        }
        i += token.length
        matched = true
        break
      }
      if (matched) continue
    }
    textBuf += prompt[i]
    i += 1
  }
  flushText()
  return spans
}

export function serializeStudioPromptSpans(spans: StudioPromptDocSpan[]): string {
  return spans.map((span) => {
    if (span.type === 'text') return span.text
    if (span.type === 'camera') return studioCameraMarkToken(span.mark)
    return `@${span.ref.title}`
  }).join('')
}

type TiptapTextNode = { type: 'text'; text: string }
type TiptapMentionNode = {
  type: 'studioMention'
  attrs: {
    id: string
    label: string
    previewUrl: string | null
    mediaKind: StudioMediaKind
    sourceKey: string | null
  }
}
type TiptapCameraNode = {
  type: 'studioCameraMark'
  attrs: StudioCameraMark
}
type TiptapInline = TiptapTextNode | TiptapMentionNode | TiptapCameraNode
type TiptapDoc = {
  type: 'doc'
  content: Array<{ type: 'paragraph'; content?: TiptapInline[] }>
}

/** spans → Tiptap JSON（多段按 \\n 拆 paragraph） */
export function studioPromptSpansToTiptapDoc(spans: StudioPromptDocSpan[]): TiptapDoc {
  const paragraphs: TiptapDoc['content'] = []
  let current: TiptapInline[] = []

  const pushParagraph = () => {
    paragraphs.push(current.length ? { type: 'paragraph', content: current } : { type: 'paragraph' })
    current = []
  }

  for (const span of spans) {
    if (span.type === 'camera') {
      current.push({ type: 'studioCameraMark', attrs: span.mark })
      continue
    }
    if (span.type === 'mention') {
      current.push({
        type: 'studioMention',
        attrs: {
          id: span.ref.id,
          label: span.ref.title,
          previewUrl: span.ref.previewUrl ?? null,
          mediaKind: span.ref.mediaKind,
          sourceKey: span.ref.sourceKey ?? null
        }
      })
      continue
    }
    const chunks = span.text.split('\n')
    chunks.forEach((chunk, index) => {
      if (chunk) current.push({ type: 'text', text: chunk })
      if (index < chunks.length - 1) pushParagraph()
    })
  }
  if (current.length || paragraphs.length === 0) pushParagraph()
  return { type: 'doc', content: paragraphs }
}

export function tiptapDocToStudioPromptSpans(
  doc: TiptapDoc | object,
  refsById: Map<string, StudioComposerRef>
): StudioPromptDocSpan[] {
  const root = doc as TiptapDoc
  const spans: StudioPromptDocSpan[] = []
  const paragraphs = root.content ?? []
  paragraphs.forEach((paragraph, pIndex) => {
    if (pIndex > 0) {
      const last = spans[spans.length - 1]
      if (last?.type === 'text') last.text += '\n'
      else spans.push({ type: 'text', text: '\n' })
    }
    for (const node of paragraph.content ?? []) {
      if (node.type === 'text') {
        const last = spans[spans.length - 1]
        if (last?.type === 'text') last.text += node.text
        else spans.push({ type: 'text', text: node.text })
        continue
      }
      if (node.type === 'studioCameraMark') {
        spans.push({ type: 'camera', mark: node.attrs })
        continue
      }
      if (node.type === 'studioMention') {
        const fromMap = refsById.get(node.attrs.id)
        const ref: StudioComposerRef = fromMap ?? {
          id: node.attrs.id,
          kind: 'node',
          title: node.attrs.label,
          mediaKind: node.attrs.mediaKind,
          previewUrl: node.attrs.previewUrl ?? undefined,
          sourceKey: node.attrs.sourceKey ?? undefined
        }
        spans.push({ type: 'mention', ref })
      }
    }
  })
  return spans
}
