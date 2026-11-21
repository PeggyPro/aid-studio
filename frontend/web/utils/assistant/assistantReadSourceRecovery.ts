import type { AssistantSessionSnapshot } from '~/types/assistant-api'

/** A completed read denial can replace its source scope; unknown operations cannot. */
export function assistantReadSourceScopeBlocked(snapshot: AssistantSessionSnapshot): boolean {
  const run = snapshot.activeRun
  const plan = snapshot.plan
  const latest = snapshot.steps.at(-1)
  return !!run && run.status === 'PAUSED' && !run.stopRequested
    && run.nextAction === 'CONTINUE_AGENT_REQUIRED' && plan?.status === 'APPROVED'
    && latest?.planRevision === plan.revision && latest.stepKind === 'TOOL'
    && latest.status === 'FAILED' && latest.providerAcceptanceState === 'ACCEPTED'
    && !latest.mediaTaskId && ['CREATIVE_SOURCE_SCOPE_REQUIRED', 'CREATIVE_CONTEXT_INVALID'].includes(latest.errorCode || '')
}
