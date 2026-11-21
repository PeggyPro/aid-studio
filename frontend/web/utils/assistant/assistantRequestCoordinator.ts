import { createRandomUuid } from '@/utils/clientId'
/** 请求键包含身份代次与服务，force 不能绕过正在进行的请求。 */
type Slot = { digest: string; promise: Promise<unknown> }
const inflight = new Map<string, Slot>()
const cache = new Map<string, { expires: number; value: unknown }>()

export function assistantDigest(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(assistantDigest).join(',')}]`
  if (value && typeof value === 'object') {
    return `{${Object.entries(value).filter(([, item]) => item !== undefined).sort(([a], [b]) => a.localeCompare(b))
      .map(([key, item]) => `${JSON.stringify(key)}:${assistantDigest(item)}`).join(',')}}`
  }
  return JSON.stringify(value) ?? 'null'
}

export function coordinateAssistantRequest<T>(input: {
  key: string; body: unknown; execute: () => Promise<T>; cacheMs?: number; force?: boolean
}): Promise<T> {
  const digest = assistantDigest(input.body)
  const running = inflight.get(input.key)
  if (running) {
    return running.digest === digest
      ? running.promise as Promise<T>
      : Promise.reject(new Error('同一请求标识不能用于不同内容'))
  }
  const cached = cache.get(input.key)
  if (!input.force && cached && cached.expires > Date.now()) return Promise.resolve(cached.value as T)
  const promise = Promise.resolve().then(input.execute).then((value) => {
    if (input.cacheMs) {
      if (cache.size > 200) cache.clear()
      const entry = { value, expires: Date.now() + input.cacheMs }
      cache.set(input.key, entry)
      setTimeout(() => { if (cache.get(input.key) === entry) cache.delete(input.key) }, input.cacheMs)
    }
    return value
  }).finally(() => {
    if (inflight.get(input.key)?.promise === promise) inflight.delete(input.key)
  })
  inflight.set(input.key, { digest, promise })
  return promise
}

export function assistantCommandId(): string {
  return `asst-${createRandomUuid()}`
}

export function assistantErrorMessage(error: unknown): string {
  if (error && typeof error === 'object') {
    const record = assistantErrorEnvelope(error)
    // Presentation only: preserve the original timeout object and command outcome classification.
    let nativeTimeout = false
    if (record === error && typeof DOMException !== 'undefined') {
      try {
        // Presentation only: use trusted interface accessors, never an exception's own getters/tag.
        // Native accessors accept genuine subclasses and compatible browser realms, rejecting lookalikes.
        nativeTimeout = Object.getOwnPropertyDescriptor(DOMException.prototype, 'name')?.get?.call(error) === 'TimeoutError'
          && Object.getOwnPropertyDescriptor(DOMException.prototype, 'code')?.get?.call(error) === 23
      } catch { /* An incompatible implementation or missing native slots keeps the original presentation. */ }
    }
    if (nativeTimeout && !record.userMessage && !record.msg && record.response == null && record.status == null && record.errorCode == null) {
      return '请求超时，暂未获取结果；请重新同步，已提交的操作需先核实原结果。'
    }
    return String(record.userMessage || record.msg || record.message || '操作未完成，请重试')
  }
  return '操作未完成，请重试'
}

export function assistantErrorEnvelope(error: unknown): Record<string, unknown> {
  if (!error || typeof error !== 'object') return {}
  const value = error as Record<string, unknown>
  const response = value.response as { data?: unknown } | undefined
  return response?.data && typeof response.data === 'object' ? response.data as Record<string, unknown> : value
}

export function assistantErrorCode(error: unknown): string {
  const value = assistantErrorEnvelope(error)
  const data = value.data as Record<string, unknown> | undefined
  return String(data?.errorCode || value.errorCode || value.code || '')
}

export interface AssistantErrorPresentation {
  kind: 'CREATION' | 'ACCESS' | 'CONNECTION' | 'UNKNOWN'
  code?: string
}

/** Presentation only: never decides whether a command was accepted or may be replayed. */
export function assistantErrorPresentation(error: unknown): AssistantErrorPresentation {
  const envelope = assistantErrorEnvelope(error)
  const outer = error && typeof error === 'object' ? error as Record<string, unknown> : {}
  const rawCode = assistantErrorCode(error)
  const code = /^[A-Z][A-Z0-9_]{0,95}$/.test(rawCode) ? rawCode : undefined
  const result = (kind: AssistantErrorPresentation['kind']): AssistantErrorPresentation => ({ kind, ...(code ? { code } : {}) })
  if (outer.apiCryptoDecryptionFailed || outer.apiResponseFormatInvalid || envelope.encrypted === true) return result('UNKNOWN')
  const response = outer.response as { status?: unknown } | undefined
  const statusCode = (value: unknown) => typeof value === 'number' || typeof value === 'string' && /^[1-5]\d{2}$/.test(value) ? Number(value) : 0
  const transportStatus = statusCode(response?.status ?? outer.status)
  const status = transportStatus >= 400 ? transportStatus : statusCode(envelope.code)
  if (status === 401 || status === 403 || ['UNAUTHORIZED', 'FORBIDDEN', 'ACCESS_DENIED', 'AUTHENTICATION_REQUIRED'].includes(code || '')) return result('ACCESS')
  if (status >= 400 && status < 500) return result('CREATION')
  if (status >= 500 && status < 600) return result('UNKNOWN')
  if (code && /^(ASSISTANT_CONTEXT_|ASSISTANT_CAPABILITY_|ASSISTANT_CONFIGURATION_|CAPABILITY_|APPROVAL_|QUOTE_)/.test(code)
    && !/(UNKNOWN|TIMEOUT|INTERNAL)$/.test(code)) return result('CREATION')
  if (['CANVAS_NETWORK_ERROR', 'ERR_NETWORK', 'ECONNABORTED', 'ETIMEDOUT'].includes(code || '')
    || outer.name === 'TimeoutError' || outer.name === 'NetworkError') return result('CONNECTION')
  return result('UNKNOWN')
}

export function assistantErrorState(error: unknown) {
  return { error: assistantErrorMessage(error), errorPresentation: assistantErrorPresentation(error) }
}

/** Losing access cannot establish the outcome of a previously submitted command. */
export function assistantCommandRejectionIsFinal(errorCode: string): boolean {
  return !/^(401|403)$|FORBIDDEN|UNAUTHORIZED|ACCESS_DENIED|AUTH|IDENTITY/.test(errorCode)
}

export function assistantCommandRecoveryAccessUncertain(error: unknown): boolean {
  const envelope = assistantErrorEnvelope(error)
  const outer = error && typeof error === 'object' ? error as { status?: unknown; response?: { status?: unknown } } : undefined
  return [outer?.status, outer?.response?.status, envelope.code].some(value => (typeof value === 'string' || typeof value === 'number') && (Number(value) === 401 || Number(value) === 403))
    || !assistantCommandRejectionIsFinal(assistantErrorCode(error))
}

/** Acknowledged rejection is final; transport errors and 5xx remain uncertain. */
export function assistantCommandWasRejected(error: unknown): boolean {
  const value = assistantErrorEnvelope(error)
  const outer = error && typeof error === 'object' ? error as { status?: number; apiCryptoDecryptionFailed?: boolean; apiResponseFormatInvalid?: boolean; response?: { status?: number } } : undefined
  if (outer?.apiCryptoDecryptionFailed || outer?.apiResponseFormatInvalid || value.encrypted === true) return false
  const transportCode = Number(outer?.response?.status) || Number(outer?.status)
  const bodyCode = Number(value.code)
  if (transportCode >= 500 && transportCode < 600) return false
  if (transportCode >= 400 && transportCode < 500) {
    return !Number.isFinite(bodyCode) || bodyCode === transportCode
  }
  const code = bodyCode
  if (code >= 500 && code < 600) return false
  if (code >= 400 && code < 500) return true
  const businessCode = assistantErrorCode(error)
  return /^(ASSISTANT_|RUNTIME_|INPUT_|RUN_|IDEMPOTENCY_|CONTEXT_|BUDGET_|APPROVAL_|CAPABILITY_)/.test(businessCode)
    && !/(UNKNOWN|TIMEOUT|INTERNAL)$/.test(businessCode)
}
