type ErrorRecord = Record<string, unknown>
const record = (value: unknown): ErrorRecord => value && typeof value === 'object' ? value as ErrorRecord : {}

/** Only transport failures are retried on a timer. Business/configuration refusals wait for a new input or activation. */
export function canRetryCanvasQuote(reason: unknown): boolean {
  const error = record(reason)
  if (['CANVAS_NETWORK_ERROR', 'CANVAS_READ_TIMEOUT', 'CANVAS_QUOTE_TIMEOUT', 'ERR_NETWORK', 'ETIMEDOUT', 'ECONNABORTED'].includes(String(error.code))) return true
  if (error.name === 'TimeoutError') return true
  const status = Number(error.status ?? record(error.response).status)
  return [408, 429, 502, 503, 504].includes(status)
}

export const CANVAS_QUOTE_RETRY_DELAYS = [1000, 3000, 8000] as const
export const CANVAS_QUOTE_ACTIVATION_COOLDOWN = 15_000
