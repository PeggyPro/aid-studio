import type { AssistantRun } from '~/types/assistant-api'

/** 计划待确认使用快照中的计划；单项操作确认使用当前操作投影，不混用主助手审批；其他恢复状态保留主助手核验。 */
export function assistantMainApprovalReadScope(run: Pick<AssistantRun, 'runId' | 'nextAction'> | null | undefined): string | null {
  if (!run?.runId || ['PLAN_EXECUTION_APPROVAL_REQUIRED', 'CONFIRM_OPERATION_APPROVAL'].includes(run.nextAction || '')) return null
  return `${run.runId}:${run.nextAction || ''}`
}
