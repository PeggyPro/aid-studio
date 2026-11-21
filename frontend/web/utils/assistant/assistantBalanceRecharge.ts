import type { AssistantRunStep, AssistantSessionSnapshot } from '@/types/assistant-api'
import { isUserBalanceErrorCode } from '@/utils/insufficientBalanceRecharge'

function failedBalanceSteps(snapshot: AssistantSessionSnapshot) {
  return snapshot.steps.filter(step => isUserBalanceErrorCode(step.errorCode)
    && ['FAILED', 'FAILED_PRETASK'].includes(step.status)
    && !['UNKNOWN', 'RECONCILING', 'SUBMITTING'].includes(step.providerAcceptanceState || ''))
}

function failureKey(snapshot: AssistantSessionSnapshot, step: AssistantRunStep) {
  return JSON.stringify([snapshot.sessionId, snapshot.activeRun?.runId, step.stepId, step.attemptNo, step.errorCode])
}

/** Observe accepted live snapshots, never historical pages or cached transcript rendering. */
export class AssistantBalanceRechargeObserver {
  private seen = new Set<string>()

  observe(previous: AssistantSessionSnapshot | null, snapshot: AssistantSessionSnapshot): boolean {
    const failures = failedBalanceSteps(snapshot)
    const before = new Set(previous ? failedBalanceSteps(previous).map(step => failureKey(previous, step)) : [])
    const actionable = !!previous && previous.sessionId === snapshot.sessionId
      && previous.actorId === snapshot.actorId
      && ['RUNNING', 'PAUSED', 'WAITING_APPROVAL', 'FAILED'].includes(snapshot.activeRun?.status || '')
    let notify = false
    for (const step of failures) {
      const key = failureKey(snapshot, step)
      if (actionable && !this.seen.has(key) && !before.has(key)) notify = true
      this.seen.add(key)
    }
    // Bounded, per-session-store memory; current failures are also compared with the prior snapshot.
    while (this.seen.size > 256) this.seen.delete(this.seen.values().next().value!)
    return notify
  }
}
