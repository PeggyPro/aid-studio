import { ApiBusinessError } from '@/utils/apiError'

/** Only a request without an HTTP/business response can have an unknown acceptance state. */
export function isCanvasGenerationResponseUnknown(error: unknown): boolean {
  if (error instanceof ApiBusinessError) return false
  if (!error || typeof error !== 'object') return false
  const detail = error as { response?: unknown; code?: unknown }
  if (detail.response != null) return false
  return typeof detail.code === 'string' && [
    'ERR_NETWORK', 'ECONNABORTED', 'ETIMEDOUT'
  ].includes(detail.code)
}
