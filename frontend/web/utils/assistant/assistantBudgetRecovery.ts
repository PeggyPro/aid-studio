import type { AssistantPlanningApproval, AssistantSessionSnapshot } from '~/types/assistant-api'
import type { AssistantRunBudgetPolicy } from '~/types/assistant-budget'
import type { AssistantSessionStore, AssistantSessionState } from './assistantSessionStore'
import { assistantDigest } from './assistantRequestCoordinator'
import { readAssistantDurable, writeAssistantDurable, removeAssistantDurable } from './assistantDurableStorage'

type RecoveryHold = { sessionId: string; runId: string; renewalKey: string; renewalDigest: string;
  confirmation?: { key: string; digest: string } }
const terminal = new Set(['COMPLETED', 'SUCCEEDED', 'FAILED', 'STOPPED', 'CANCELLED', 'CANCELED'])
export function assistantBudgetExpired(budget: AssistantRunBudgetPolicy | null, now = Date.now()): boolean {
  return !!budget && (budget.status === 'EXPIRED' || !Number.isFinite(Date.parse(budget.expiresAt)) || Date.parse(budget.expiresAt) <= now)
}

export function assistantBudgetDrifted(budget: AssistantRunBudgetPolicy | null, now = Date.now()): boolean {
  return !!budget && (assistantBudgetExpired(budget, now) || budget.scopeState !== 'CURRENT' || budget.status !== 'ACTIVE')
}

/**
 * The composer asks for a fee renewal only when that renewal is the current decision.
 * A paused run that already offers resume keeps that single action; the fee read stays automatic.
 */
export function assistantComposerShowsBudgetRenewal(snapshot: AssistantSessionSnapshot, budget: AssistantRunBudgetPolicy | null, now = Date.now()): boolean {
  const run = snapshot.activeRun
  if (!assistantBudgetDrifted(budget, now) || !run || run.stopRequested) return false
  if (run.status === 'PAUSED' && run.allowedActions.includes('RESUME')) return false
  return run.status === 'WAITING_APPROVAL' || run.status === 'PAUSED'
}
export function assistantBudgetCanRenew(budget: AssistantRunBudgetPolicy | null, now = Date.now()): boolean {
  return assistantBudgetExpired(budget, now) && budget?.canRenew === true
}
export function assistantBudgetSubmissionCurrent(rendered: AssistantSessionSnapshot, budget: AssistantRunBudgetPolicy,
  latest: AssistantSessionState): boolean {
  const run = rendered.activeRun, current = latest.snapshot?.activeRun
  return !!run && !!current && latest.writable && !latest.busy && !latest.pendingCommand
    && latest.snapshot?.sessionId === rendered.sessionId && current.runId === run.runId
    && current.stateRevision === run.stateRevision && budget.runId === run.runId && budget.runRevision === run.stateRevision
    && !current.stopRequested && !terminal.has(current.status)
}
function key(store: AssistantSessionStore, snapshot: AssistantSessionSnapshot): string {
  return store.recoveryScopeKey + ':budget-confirmation:' + encodeURIComponent(snapshot.sessionId) + ':' + encodeURIComponent(snapshot.activeRun!.runId)
}
function hold(store: AssistantSessionStore, snapshot: AssistantSessionSnapshot): RecoveryHold | null {
  const value = readAssistantDurable<RecoveryHold>(key(store, snapshot))
  if (value && (value.sessionId !== snapshot.sessionId || value.runId !== snapshot.activeRun?.runId
    || typeof value.renewalKey !== 'string' || typeof value.renewalDigest !== 'string')) throw new Error('授权恢复记录待核实；原对话仍保留')
  return value
}
/** An acknowledged approval is consumed even while a later read still projects QUOTED. */
export function assistantBudgetApprovalAccepted(store: AssistantSessionStore, approval: AssistantPlanningApproval | null): boolean {
  const current = store.getSnapshot(), accepted = current.lastCommandResult
  return !!approval && current.snapshot?.activeRun?.runId === approval.runId
    && accepted?.action === 'approve' && accepted.body.approvalId === approval.approvalId
    && accepted.body.expectedApprovalRevision === approval.approvalRevision
    && accepted.body.expectedRunRevision === approval.runRevision
}
export function assistantBudgetRecoveryHeld(store: AssistantSessionStore, snapshot: AssistantSessionSnapshot,
  budget: AssistantRunBudgetPolicy | null): boolean {
  if (!snapshot.activeRun) return false
  try { return budget?.renewalNeedsConfirmation === true || !!hold(store, snapshot) }
  catch { return true } // Storage uncertainty may block automation, never grant authority.
}
export function assistantBudgetBeginRenewal(store: AssistantSessionStore, snapshot: AssistantSessionSnapshot, body: Record<string, unknown>) {
  writeAssistantDurable(key(store, snapshot), { sessionId: snapshot.sessionId, runId: snapshot.activeRun!.runId,
    renewalKey: String(body.clientRequestId), renewalDigest: assistantDigest(body) } satisfies RecoveryHold)
}
export function assistantBudgetPrepareExplicitApproval(store: AssistantSessionStore, snapshot: AssistantSessionSnapshot,
  budget: AssistantRunBudgetPolicy | null, body: Record<string, unknown>) {
  if (!assistantBudgetRecoveryHeld(store, snapshot, budget)) return
  const original = hold(store, snapshot)
  writeAssistantDurable(key(store, snapshot), { sessionId: snapshot.sessionId, runId: snapshot.activeRun!.runId,
    renewalKey: original?.renewalKey ?? 'SERVER_RENEWAL', renewalDigest: original?.renewalDigest ?? 'SERVER_RENEWAL',
    confirmation: { key: String(body.clientRequestId), digest: assistantDigest(body) } } satisfies RecoveryHold)
}
export function assistantBudgetSettleExplicitApproval(store: AssistantSessionStore, snapshot: AssistantSessionSnapshot) {
  if (!snapshot.activeRun) return
  const saved = hold(store, snapshot), accepted = store.getSnapshot().lastCommandResult
  if (saved?.confirmation && (accepted?.action === 'approve' || accepted?.action === 'approveOperation')
    && accepted.body.clientRequestId === saved.confirmation.key && assistantDigest(accepted.body) === saved.confirmation.digest)
    removeAssistantDurable(key(store, snapshot))
}
export function assistantBudgetRenewalAccepted(store: AssistantSessionStore, snapshot: AssistantSessionSnapshot): boolean {
  if (!snapshot.activeRun) return false
  const saved = hold(store, snapshot), accepted = store.getSnapshot().lastCommandResult
  const result = accepted?.result as { renewedExpired?: boolean; budgetPolicy?: AssistantRunBudgetPolicy } | undefined
  return !!saved && accepted?.action === 'reviseBudget' && accepted.body.renewExpired === true
    && accepted.body.clientRequestId === saved.renewalKey && assistantDigest(accepted.body) === saved.renewalDigest
    && result?.renewedExpired === true && result.budgetPolicy?.runId === saved.runId
}

export function assistantBudgetRenewalBlockReason(budget: AssistantRunBudgetPolicy | null): string {
  switch (budget?.renewBlockReason) {
    case 'NOT_EXPIRED': return assistantBudgetExpired(budget)
      ? '费用确认期限已到，请重新同步核实当前状态；对话与历史仍保留。'
      : '消费授权尚未到期，可按当前范围修改授权。'
    case 'RUN_TERMINAL': return '本轮已结束，可以在当前对话发送新的需求；历史不会重放。'
    case 'RUN_NOT_ACTIVE': return '当前运行已变化，请重新同步当前对话。'
    case 'STOP_REQUESTED': return '正在核实停止请求与原任务，请先确认其权威状态。'
    case 'RUN_BUSY': case 'RESERVATION_PENDING': case 'RUN_RECOVERY_REQUIRED':
      return '原操作或费用仍待核实，请先使用原请求核实结果；对话与草稿仍保留。'
    case 'POLICY_INACTIVE': case 'AUTHORIZATION_INACTIVE':
      return '当前消费授权已关闭或撤销，不能续用；历史仍保留，请核对当前授权权限。'
    default: return '消费授权状态暂不能确认，请重新同步并核实权限或原操作；对话与草稿仍保留。'
  }
}
