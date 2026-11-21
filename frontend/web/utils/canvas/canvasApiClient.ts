import type { ApiEnvelope, AuthCryptoPublicConfig } from '@/types/business-api'
import { buildUserApiAuthHeaders, handleSseErrorRecharge, redirectToLogin, request, resolveClientApiUrl } from '@/utils/api'
import { maybeDecryptApiPayload, prepareEncryptedRequestForPublicConfig } from '@/utils/apiCrypto'
import { toApiError } from '@/utils/apiError'
import { parseCanvasApiJson } from '@/utils/canvas/parseCanvasApiJson'
import { assertCanvasApiEnvelope, parseCanvasApiResponseBody } from '@/utils/canvas/canvasApiResponse'
import { runStudioReadSingleFlight } from '@/utils/studio/studioRequestSingleFlight'

type PublicConfig = { crypto?: AuthCryptoPublicConfig | null; serverTime?: number }
type ConfigSnapshot = { value: PublicConfig; offsetMs: number; expiresAt: number }

const configCache = new Map<string, ConfigSnapshot>()
const configInflight = new Map<string, Promise<ConfigSnapshot>>()
export const CANVAS_AUTH_CHANGED_EVENT = 'aid:canvas-auth-changed'

/** Canvas is part of the system: API routing never creates a second login identity. */
export function hasCanvasGatewaySession(mainToken: string): boolean {
  return typeof window !== 'undefined' && Boolean(mainToken) && localStorage.getItem('token') === mainToken
}

function requestCanvasAuthentication(expectedToken?: string): boolean {
  if (typeof window === 'undefined') return false
  const currentToken = localStorage.getItem('token') || ''
  // An old request must not sign out an account that logged in after it was dispatched.
  if (expectedToken && currentToken !== expectedToken) return false
  redirectToLogin()
  return true
}

export function canvasApiAuthHeaders(): Record<string, string> {
  const headers = buildUserApiAuthHeaders()
  if (!headers.Authorization) throw Object.assign(new Error('请先登录'), { status: 401 })
  return headers
}

/** CryptoIgnore text stream accepts plain JSON and keeps the gateway Bearer identity. */
export async function canvasApiStreamPost(path: string, data: unknown, signal: AbortSignal): Promise<Response> {
  const headers = canvasApiAuthHeaders()
  const response = await fetchCanvas(resolveCanvasApiUrl(path), {
    method: 'POST',
    headers: { ...headers, Accept: 'text/event-stream', 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
    cache: 'no-store',
    signal
  })
  if (response.status === 401) {
    requestCanvasAuthentication(headers.Authorization?.replace(/^Bearer\s+/i, ''))
    throw Object.assign(new Error('登录已失效，请重新登录'), { status: 401 })
  }
  if (!response.ok) throw new Error(`文本生成连接失败 (${response.status})`)
  return response
}

/** 默认复用主接口地址；显式覆盖只决定路由，认证始终使用同一系统登录态。 */
export function canvasApiBaseUrl(): string {
  const configured = process.env.NEXT_PUBLIC_CANVAS_API_BASE_URL?.trim().replace(/\/$/, '')
  if (configured) return configured
  return resolveClientApiUrl('').replace(/\/$/, '')
}

export function resolveCanvasApiUrl(path: string): string {
  return `${canvasApiBaseUrl()}${path.startsWith('/') ? path : `/${path}`}`
}

/** Shared by independent read callers; identity is rechecked before dispatch and delivery. */
export function canvasApiReadPost<T>(path: string, data: unknown = {}): Promise<ApiEnvelope<T>> {
  const identity = () => {
    const mainToken = typeof window === 'undefined' ? '' : localStorage.getItem('token') || ''
    return JSON.stringify([canvasApiBaseUrl(), mainToken])
  }
  return runStudioReadSingleFlight(identity, path, data,
    snapshot => canvasApiPost<T>(path, snapshot, { requestTimeoutMs: 30_000 }))
    .catch(error => {
      if (error instanceof Error && error.name === 'TimeoutError') {
        throw Object.assign(new Error('画布读取超时，请重试', { cause: error }), { code: 'CANVAS_READ_TIMEOUT' })
      }
      throw error
    })
}

/** Only a rejection from fetch itself is a network failure; decoding and local errors stay distinct. */
async function fetchCanvas(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  try { return await fetch(input, init) }
  catch (cause) {
    if (!(cause instanceof TypeError)) throw cause
    throw Object.assign(new Error('画布网络连接失败，请检查网络后重试', { cause }), { code: 'CANVAS_NETWORK_ERROR' })
  }
}

async function publicConfig(base: string): Promise<ConfigSnapshot> {
  const cached = configCache.get(base)
  if (cached && cached.expiresAt > Date.now()) return cached
  const running = configInflight.get(base)
  if (running) return running
  const abort = new AbortController()
  const timeout = setTimeout(() => abort.abort(), 30000)
  const promise = (async () => {
    const started = Date.now()
    const response = await fetchCanvas(`${base}/auth/public-config`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}', signal: abort.signal
    })
    const envelope = parseCanvasApiResponseBody(await response.text(), response.status) as ApiEnvelope<PublicConfig>
    assertCanvasApiEnvelope(envelope, response.status)
    if (!response.ok || envelope.code !== 200) throw Object.assign(toApiError(envelope), { status: response.status })
    const value = envelope.data ?? {}
    const offsetMs = typeof value.serverTime === 'number' ? value.serverTime - started : 0
    const snapshot = { value, offsetMs, expiresAt: Date.now() + 5 * 60_000 }
    configCache.set(base, snapshot)
    return snapshot
  })().finally(() => {
    clearTimeout(timeout)
    configInflight.delete(base)
  })
  configInflight.set(base, promise)
  return promise
}

/** Guest and host camera requests share the canvas gateway's bootstrap, never the main site's cached key. */
export async function prepareCanvasApiPayload(data: unknown) {
  const config = await publicConfig(canvasApiBaseUrl())
  if (config.value.crypto?.enabled) {
    if (!config.value.crypto.publicKey) throw new Error('画布加密公钥不可用，请检查接口加密配置')
    return prepareEncryptedRequestForPublicConfig({
      body: data,
      publicKey: config.value.crypto.publicKey,
      serverTimeOffsetMs: config.offsetMs
    })
  }
  return { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data ?? {}), aesKey: undefined }
}

/** 画布项目、目录和生成使用同一后端的认证与加密规则，保留Long无损解析。 */
export async function canvasApiPost<T>(path: string, data: unknown = {}, options: { anonymous?: boolean; requestTimeoutMs?: number; signal?: AbortSignal; keepSessionOnUnauthorized?: boolean } = {}): Promise<ApiEnvelope<T>> {
  options.signal?.throwIfAborted()
  if (process.env.NODE_ENV === 'test') {
    return request.post<ApiEnvelope<T>>(path, data, options.signal ? { signal: options.signal } : undefined)
  }
  const base = canvasApiBaseUrl()
  const useLocalProxy = process.env.NODE_ENV === 'development' && !process.env.NEXT_PUBLIC_CANVAS_API_BASE_URL
  const headers: Record<string, string> = options.anonymous ? { 'X-Requested-With': 'XMLHttpRequest' } : canvasApiAuthHeaders()
  const multipart = typeof FormData !== 'undefined' && data instanceof FormData
  let body: BodyInit
  let aesKey: CryptoKey | undefined
  if (multipart) {
    body = data
  } else {
    const config = useLocalProxy ? null : await publicConfig(base)
    options.signal?.throwIfAborted()
    if (config?.value.crypto?.enabled) {
      if (!config.value.crypto.publicKey) throw new Error('画布加密公钥不可用')
      const encrypted = await prepareEncryptedRequestForPublicConfig({
        body: data,
        publicKey: config.value.crypto.publicKey,
        serverTimeOffsetMs: config.offsetMs
      })
      Object.assign(headers, encrypted.headers)
      body = encrypted.body ?? ''
      aesKey = encrypted.aesKey
    } else {
      headers['Content-Type'] = 'application/json'
      body = JSON.stringify(data)
    }
  }
  options.signal?.throwIfAborted()
  const timeout = options.requestTimeoutMs ? AbortSignal.timeout(options.requestTimeoutMs) : undefined
  const signal = options.signal && timeout ? AbortSignal.any([options.signal, timeout]) : options.signal || timeout
  const response = await fetchCanvas(resolveCanvasApiUrl(path), { method: 'POST', headers, body, ...(signal ? { signal } : {}) })
  if (!options.anonymous && response.status === 401) {
    if (!options.keepSessionOnUnauthorized) requestCanvasAuthentication(headers.Authorization?.replace(/^Bearer\s+/i, ''))
    throw Object.assign(new Error('登录已失效，请重新登录'), { status: 401 })
  }
  const raw = parseCanvasApiResponseBody(await response.text(), response.status)
  const envelope = await maybeDecryptApiPayload<ApiEnvelope<T>>(raw, aesKey, parseCanvasApiJson)
  assertCanvasApiEnvelope(envelope, response.status)
  if (!options.anonymous && (response.status === 401 || envelope.code === 401)) {
    if (!options.keepSessionOnUnauthorized) requestCanvasAuthentication(headers.Authorization?.replace(/^Bearer\s+/i, ''))
    throw Object.assign(new Error('登录已失效，请重新登录'), { status: 401 })
  }
  if (!response.ok || (envelope.code !== 200 && envelope.code !== 0)) {
    const recharge = envelope as ApiEnvelope<T> & {
      needRecharge?: boolean
      rechargeOwner?: string
      userMessage?: string
      errorMessage?: string
    }
    handleSseErrorRecharge(recharge, envelope.msg)
    throw Object.assign(toApiError(envelope), { status: response.status })
  }
  return envelope
}
