import type { AssistantSessionSnapshot } from '~/types/assistant-api'

/** Only explicit failed-operation pauses are errors; user/budget/context pauses keep their meaning. */
export function assistantOperationFailedPause(snapshot: AssistantSessionSnapshot | null): boolean {
  return snapshot?.activeRun?.status === 'PAUSED'
    && ['OPERATION_FAILED', 'OPERATION_PREPARATION_REJECTED'].includes(snapshot.activeRun.pauseReason || '')
}

export function assistantCanResume(snapshot: AssistantSessionSnapshot | null, contextBlocked = false): boolean {
  if (!snapshot) return false
  const run = snapshot.activeRun
  return !!run && run.status === 'PAUSED' && run.allowedActions.includes('RESUME') && !run.stopRequested
    && snapshot.inputRequest?.status !== 'SUSPENDED' && !contextBlocked
}

export function assistantResumeCopy(nextAction?: string): string {
  return nextAction === 'REPLANNING_REQUIRED' ? '本次流程已暂停，确认继续后重新规划。'
    : nextAction === 'CONTINUE_AGENT_REQUIRED' ? '本次流程已暂停，确认继续后进入下一步确认。'
      : '本次流程已暂停，可由你确认继续处理。'
}

/** Compare with the rendered control before calling the store's ordinary resume command. */
export function assistantResumeStillCurrent(rendered: AssistantSessionSnapshot | null, latest: {
  snapshot: AssistantSessionSnapshot | null; writable: boolean; busy: boolean; pendingCommand: unknown
}, contextBlocked = false): boolean {
  const run = rendered?.activeRun, current = latest.snapshot?.activeRun
  return !!run && !!current && latest.snapshot?.sessionId === rendered?.sessionId
    && current.runId === run.runId && current.stateRevision === run.stateRevision
    && latest.writable && !latest.busy && !latest.pendingCommand && assistantCanResume(latest.snapshot, contextBlocked)
}
