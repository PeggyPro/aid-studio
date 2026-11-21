export interface ComputeResourceStatus {
  errorCode?: string
  safeMessage?: string
  providerAcceptanceState?: string
}

/** Queue positions belong to the server; never infer them from client timing or retries. */
export function computeResourceStatusMessage(step?: ComputeResourceStatus): string | undefined {
  if (step?.providerAcceptanceState !== 'NOT_SUBMITTED') return undefined
  if (step.errorCode === 'COMPUTE_RESOURCE_MAINTENANCE') return '构建资源维护中，请稍后重试'
  if (step.errorCode === 'TOOL_WAITING_RESOURCE') return step.safeMessage?.trim() || '正在核对排队状态'
  return undefined
}
