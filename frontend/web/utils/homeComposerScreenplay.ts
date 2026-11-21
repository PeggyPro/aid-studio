import type { AuthPublicConfigData } from '~/types/business-api'
import { assistantCommandId } from '~/utils/assistant/assistantRequestCoordinator'
import { plainPureTextCharCount } from '~/utils/htmlPlain'
import { assertScriptPlainTextFile, validateScriptUploadFile } from '~/utils/scriptFileUpload'

const REQUEST_PREFIX = 'aid-home-screenplay-request:'
const MAX_SCRIPT_BYTES = 10 * 1024 * 1024
const DIRECT_SCREENPLAY_MIN_CHARS = 500
const EPISODE_HEADING = /^\s*(?:#{1,6}\s+)?第[ \t]*([0-9零一二三四五六七八九十百千两〇]+)[ \t]*[集话](?:[ \t:：—–\-、.。]+(.*?))?[ \t]*$/

export type HomeScreenplayInput =
  | { kind: 'request'; text: string }
  | { kind: 'single-script'; text: string }
  | { kind: 'series-script'; text: string }

export interface HomeScreenplayRequest {
  id: string
  text: string
  attempted: boolean
}

export function resolveHomeScreenplayText(
  input: string,
  limits: AuthPublicConfigData['scriptLimits']
): Extract<HomeScreenplayInput, { kind: 'request' | 'single-script' }> {
  const text = input.trim()
  const length = plainPureTextCharCount(text)
  if (!text) throw new Error('请输入想法或上传剧本')
  if (length < DIRECT_SCREENPLAY_MIN_CHARS) return { kind: 'request', text }
  if (!limits) throw new Error('尚未取得剧本字数限制，请稍候重试')
  if (length > limits.movieMaxChars) {
    throw new Error(`剧本超过 ${limits.movieMaxChars.toLocaleString('zh-CN')} 字`)
  }
  return { kind: 'single-script', text }
}

export async function readHomeScreenplayFile(
  file: File,
  limits: NonNullable<AuthPublicConfigData['scriptLimits']>
): Promise<Exclude<HomeScreenplayInput, { kind: 'request' }>> {
  const formatError = validateScriptUploadFile(file)
  if (formatError) throw new Error(formatError)
  if (file.size > MAX_SCRIPT_BYTES) throw new Error('请选择 10 MB 以内的 TXT 剧本')
  await assertScriptPlainTextFile(file)
  let text: string
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer()).trim()
  } catch {
    throw new Error('无法读取剧本，请将文件另存为 UTF-8 编码的 TXT')
  }
  if (!text) throw new Error('剧本文件没有正文')

  const lines = text.split(/\r?\n/)
  const headings = lines.map(line => EPISODE_HEADING.exec(line))
  const isSeries = headings.filter(Boolean).length > 1
  const limit = isSeries ? limits.seriesMaxChars : limits.movieMaxChars
  if (plainPureTextCharCount(text) > limit) throw new Error(`剧本超过 ${limit.toLocaleString('zh-CN')} 字`)
  if (!isSeries) return { kind: 'single-script', text }

  // Preserve all dialogue and titles while adapting episode markers to the shared server splitter.
  const normalized = lines.map((line, index) => {
    const heading = headings[index]
    if (!heading) return line
    const [, ordinal, title] = heading
    return `第${ordinal}集${title ? `：${title}` : ''}`
  }).join('\n')
  return { kind: 'series-script', text: normalized }
}

export function storeHomeScreenplayRequest(projectId: number, text: string) {
  const request: HomeScreenplayRequest = { id: assistantCommandId(), text, attempted: false }
  sessionStorage.setItem(REQUEST_PREFIX + projectId, JSON.stringify(request))
}

export function readHomeScreenplayRequest(projectId: number): HomeScreenplayRequest | null {
  try {
    const raw = sessionStorage.getItem(REQUEST_PREFIX + projectId)
    if (!raw) return null
    const request = JSON.parse(raw) as HomeScreenplayRequest
    return typeof request?.id === 'string' && typeof request.text === 'string'
      && typeof request.attempted === 'boolean' ? request : null
  } catch {
    return null
  }
}

export function claimHomeScreenplayRequest(projectId: number, id: string): boolean {
  const request = readHomeScreenplayRequest(projectId)
  if (!request || request.id !== id || request.attempted) return false
  sessionStorage.setItem(REQUEST_PREFIX + projectId, JSON.stringify({ ...request, attempted: true }))
  return true
}

export function clearHomeScreenplayRequest(projectId: number, id: string) {
  if (readHomeScreenplayRequest(projectId)?.id === id) sessionStorage.removeItem(REQUEST_PREFIX + projectId)
}
