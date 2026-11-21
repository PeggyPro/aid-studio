import type { AssistantSessionSnapshot } from '@/types/assistant-api'

const terminalStatuses = new Set(['COMPLETED', 'SUCCEEDED', 'FAILED', 'STOPPED', 'CANCELLED'])

/** Canvas presentation follows the run lifecycle, independently of button permissions. */
export function canvasAssistantComposerState(snapshot: AssistantSessionSnapshot | null | undefined, settling: boolean) {
  const run = snapshot?.activeRun
  const input = snapshot?.inputRequest
  const currentInput = run && !run.stopRequested && input?.runId === run.runId && input.questions.length > 0
    && (run.status === 'WAITING_INPUT' && ['OPEN', 'DRAFTING'].includes(input.status)
      || run.status === 'PAUSED' && input.status === 'SUSPENDED') ? input : null
  const active = Boolean(run && !terminalStatuses.has(run.status)) || settling
  return {
    input: currentInput,
    active,
    idle: Boolean(snapshot) && !active,
    phase: currentInput && currentInput.status !== 'SUSPENDED' ? 'question:' + currentInput.requestId + ':' + currentInput.schemaDigest
      : run && !run.stopRequested && ['WAITING_APPROVAL', 'PAUSED'].includes(run.status) ? 'approval:' + run.runId : 'message'
  }
}
