type ApiErrorRecord = Record<string, unknown>

function asRecord(value: unknown): ApiErrorRecord | null {
  return value !== null && typeof value === 'object' ? value as ApiErrorRecord : null
}

function nonEmptyText(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

/**
 * 统一读取业务错误、Axios 错误和原生 Error 的可展示文案。
 * 永远不对普通对象执行 String(error)，避免向用户展示 `[object Object]`。
 */
export function apiErrorMessage(error: unknown, fallback = '请求失败'): string {
  if (typeof error === 'string') return error.trim() || fallback

  const detail = asRecord(error)
  if (!detail) return fallback

  const directMessage = nonEmptyText(detail.msg)
  if (directMessage) return directMessage

  const response = asRecord(detail.response)
  const responseData = asRecord(response?.data)
  const responseMessage = nonEmptyText(responseData?.msg) || nonEmptyText(responseData?.message)
  if (responseMessage) return responseMessage

  return nonEmptyText(detail.message) || fallback
}

/**
 * HTTP 200 内的失败业务信封也必须以 Error 形式拒绝。
 * code/msg/data 等原字段继续保留，兼容现有按业务码判断的调用方。
 */
export class ApiBusinessError extends Error {
  readonly payload: ApiErrorRecord
  readonly code?: unknown
  readonly msg: string

  constructor(payload: ApiErrorRecord) {
    const message = apiErrorMessage(payload)
    super(message)
    this.name = 'ApiBusinessError'
    this.payload = payload
    this.code = payload.code
    this.msg = nonEmptyText(payload.msg) || message

    for (const [key, value] of Object.entries(payload)) {
      if (key === 'name' || key === 'message' || key === 'stack' || key === 'payload' || key === 'code' || key === 'msg') continue
      Object.defineProperty(this, key, {
        configurable: true,
        enumerable: true,
        writable: true,
        value
      })
    }
  }
}

export function toApiError(error: unknown): Error {
  if (error instanceof Error) return error
  const payload = asRecord(error)
  if (payload) return new ApiBusinessError(payload)
  return new Error(apiErrorMessage(error))
}
