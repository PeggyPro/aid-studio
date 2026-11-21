import { parseCanvasApiJson } from '@/utils/canvas/parseCanvasApiJson'

/** 无协议回执时只保留 HTTP 状态；不展示网关 HTML，也不把命令判为已拒绝。 */
export class CanvasResponseFormatError extends Error {
  readonly apiResponseFormatInvalid = true
  constructor(readonly status: number) {
    super(status === 413
      ? '上传或提交内容超过服务端大小限制，请缩小后重试'
      : status >= 500
      ? '画布服务暂时不可用，请稍后核实上次操作结果'
      : '画布服务返回异常，请核实上次操作结果')
    this.name = 'CanvasResponseFormatError'
  }
}

export function parseCanvasApiResponseBody(source: string, status: number): unknown {
  try {
    const value = parseCanvasApiJson<unknown>(source)
    if (value === null || typeof value !== 'object' || Array.isArray(value)) {
      throw new CanvasResponseFormatError(status)
    }
    return value
  } catch {
    throw new CanvasResponseFormatError(status)
  }
}

export function assertCanvasApiEnvelope(value: unknown, status: number): void {
  if (!value || typeof value !== 'object' || Array.isArray(value)
    || typeof (value as { code?: unknown }).code !== 'number') {
    throw new CanvasResponseFormatError(status)
  }
}
