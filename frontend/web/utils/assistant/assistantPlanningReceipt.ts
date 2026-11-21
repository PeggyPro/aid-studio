import type { AssistantPlanningApproval, AssistantSessionSnapshot } from '~/types/assistant-api'

/** A mutation receipt can precede the next snapshot; it cannot override newer run state. */
export function assistantPlanningReceipt(snapshot: AssistantSessionSnapshot,
  command: { action: string; body: Record<string, unknown>; result: unknown } | null): AssistantPlanningApproval | null {
  const run = snapshot.activeRun
  if (!run || run.status !== 'WAITING_APPROVAL'
    || !['INITIAL_PLANNING_REQUIRED', 'REPLANNING_REQUIRED', 'CONTINUE_AGENT_REQUIRED'].includes(run.nextAction || '')
    || command?.action !== 'planningQuote' || command.body.runId !== run.runId) return null
  const approval = command.result as AssistantPlanningApproval | undefined
  if (!approval || approval.runId !== run.runId || approval.scopeKind !== 'ASSISTANT_ITERATION'
    || approval.status !== 'QUOTED' || !approval.approvalId || !Number.isSafeInteger(approval.approvalRevision)
    || approval.approvalRevision < 0 || !Number.isSafeInteger(approval.runRevision)
    || approval.runRevision < run.stateRevision || !Number.isSafeInteger(approval.lastEventSeq)
    || approval.lastEventSeq < snapshot.lastEventSeq
    || typeof command.body.expectedRunRevision !== 'number' || !Number.isSafeInteger(command.body.expectedRunRevision)
    || command.body.expectedRunRevision < 0
    || command.body.expectedRunRevision > approval.runRevision) return null
  return approval
}
