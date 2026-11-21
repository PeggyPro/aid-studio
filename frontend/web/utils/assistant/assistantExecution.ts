import type { AssistantOperationApproval, AssistantSessionSnapshot } from '~/types/assistant-api'
import type { AssistantRunBudgetPolicy } from '~/types/assistant-budget'
import { assistantBudgetUnits } from './assistantBudget'

const statuses: Record<string, string> = { PROPOSED: '待确认', APPROVED: '已确认', SUPERSEDED: '已被新计划替代', REVOKED: '已撤回', PREPARING: '准备中', AWAITING_APPROVAL: '等待确认', WAITING_APPROVAL: '等待确认', PAUSING: '正在暂停', STOPPING: '正在停止', PROCESSING: '处理中', RETRY_WAIT: '等待重试', READY: '等待执行', SUBMITTING: '提交中', INFLIGHT: '已受理', RUNNING: '运行中', RECONCILING: '正在核实结果', DECISION_READY: '决策就绪，待执行', SUCCEEDED: '已完成', COMPLETED: '已完成', FAILED: '执行失败', FAILED_PRETASK: '准备失败（未生成）', CANCELLED_PRETASK: '已取消（未提交）', CANCELLED: '已取消', PAUSED: '已暂停', STOPPED: '已停止', WAITING_TASK: '等待生成任务', WAITING_SKILL: '等待创作方法', QUOTED: '等待确认', ACCEPTED: '已授权', INVALIDATED: '已失效', PENDING: '等待处理', QUEUED: '排队中' }
export function assistantExecutionStatus(status: string): string { return statuses[status] || '状态待确认' }
/** 旧步骤字段可能包含节点目标引用；产物提示只统计 Runtime Artifact 引用。 */
export function assistantSavedArtifactCount(refs: readonly string[]): number {
  return new Set(refs.filter((ref) => /^artifact:.+:[1-9][0-9]*$/.test(ref))).size
}
export function assistantOperationTitle(operationKind?: string): string {
  return ({ ASSISTANT_ITERATION: '助手分析', ASSISTANT_DECISION: '助手分析与决策', MAIN_AGENT: '助手分析与决策', ASSISTANT_TOOL: '工具操作', ASSISTANT_SKILL: '创作方法', TOOL: '工具操作', TOOL_EXECUTE: '工具操作', SKILL: '创作方法', SKILL_INVOKE: '创作方法', TEXT_GENERATION: '生成文本', TEXT_GENERATE: '生成文本', IMAGE_GENERATE: '生成图片', VIDEO_GENERATE: '生成视频', AUDIO_GENERATE: '生成音频', MUSIC_GENERATE: '生成音乐', ARTIFACT_ADOPT: '采用产物' } as Record<string, string>)[operationKind || ''] || '计划操作'
}
/** Budget, model and capability scope. A local quote deadline is not part of this check. */
export function assistantOperationWithinApprovedScope(approval: AssistantOperationApproval, budget: AssistantRunBudgetPolicy | null, now: number): boolean {
  const charge = assistantBudgetUnits(approval.maxCharge), remaining = budget ? assistantBudgetUnits(budget.remaining) : null, perCall = budget ? assistantBudgetUnits(budget.perCallMaxCharge) : null
  return approval.hardUpperBoundAvailable && assistantBudgetUnits(approval.quotedUpperBound) === charge
    && !!budget && budget.status === 'ACTIVE' && budget.scopeState === 'CURRENT'
    && budget.currencyUnit === 'BALANCE' && budget.precisionScale === 4 && approval.currencyUnit === 'BALANCE'
    && Date.parse(budget.expiresAt) > now
    && charge !== null && remaining !== null && perCall !== null && charge <= remaining && charge <= perCall
    && (!approval.modelCode || budget.allowedModelCodes.includes(approval.modelCode))
    && (!approval.toolCode || budget.allowedToolCodes.includes(approval.toolCode))
    && (approval.capabilityCodes || []).every((code) => budget.allowedCapabilityCodes.includes(code))
    && (approval.mediaTypes || []).every((type) => budget.allowedMediaTypes.includes(type))
    && budget.usedOperations < budget.maxOperations
}
export function assistantOperationBudgetAvailable(approval: AssistantOperationApproval, budget: AssistantRunBudgetPolicy | null, now: number): boolean {
  return assistantOperationWithinApprovedScope(approval, budget, now)
    && !!approval.expiresAt && Date.parse(approval.expiresAt) > now
}

export function assistantOperationNeedsMediaConfirmation(snapshot: AssistantSessionSnapshot): boolean {
  return snapshot.activeRun?.automaticTextExecution === true
    && snapshot.activeRun.executionPreference !== 'AUTO'
    && (snapshot.approval?.mediaTypes || []).some(type => type === 'IMAGE' || type === 'VIDEO')
}

/** Public projection guard only; the server still proves the complete task/funding graph. */
export function assistantOperationStepPending(snapshot: AssistantSessionSnapshot): boolean {
  const run = snapshot.activeRun, a = snapshot.approval, plan = snapshot.plan
  if (!run || run.status !== 'WAITING_APPROVAL' || run.stopRequested || run.nextAction !== 'CONFIRM_OPERATION_APPROVAL'
    || snapshot.inputRequest?.status === 'SUSPENDED' || !a || a.scopeKind !== 'PLAN_EXECUTION'
    || typeof a.stepId !== 'string' || !/^[1-9]\d{0,18}$/.test(a.stepId)
    || !Number.isSafeInteger(a.approvalRevision) || a.approvalRevision < 0
    || !Number.isSafeInteger(a.planRevision) || a.planRevision < 0 || !plan || plan.status !== 'APPROVED' || plan.replanRequired
    || plan.revision !== a.planRevision) return false
  const steps = snapshot.steps.filter(step => step.stepId === a.stepId)
  if (steps.length !== 1) return false
  const step = steps[0]
  return step.approvalId === a.approvalId && step.planRevision === a.planRevision
    && step.status === 'AWAITING_APPROVAL' && step.providerAcceptanceState === 'NOT_SUBMITTED'
    && !step.mediaTaskId && !(step.outputArtifactRefs || []).length
}
export function assistantOperationConfirmationAvailable(snapshot: AssistantSessionSnapshot): boolean {
  return assistantOperationStepPending(snapshot) && snapshot.approval?.status === 'QUOTED'
    && snapshot.approval.allowedActions.includes('CONFIRM_APPROVAL')
}
