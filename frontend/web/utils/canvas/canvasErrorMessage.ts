import { apiErrorMessage } from '@/utils/apiError'

/** 业务拒绝时保留服务端的字段和允许值，不替客户端猜测或自动改写参数。 */
export function canvasErrorMessage(error: unknown, fallback = '请求失败'): string {
  const record = error && typeof error === 'object' ? error as Record<string, unknown> : null
  const payload = record?.payload && typeof record.payload === 'object'
    ? record.payload as Record<string, unknown>
    : record
  const data = payload?.data && typeof payload.data === 'object'
    ? payload.data as Record<string, unknown>
    : null
  const reason = typeof data?.reason === 'string' ? data.reason.trim() : ''
  const field = typeof data?.field === 'string' ? data.field.trim() : ''
  const allowed = Array.isArray(data?.allowedValues)
    ? data.allowedValues.filter((item): item is string => typeof item === 'string')
    : []
  return [apiErrorMessage(error, fallback), reason, field ? `字段：${field}` : '',
    allowed.length ? `可选值：${allowed.join('、')}` : ''].filter(Boolean).join('；')
}
