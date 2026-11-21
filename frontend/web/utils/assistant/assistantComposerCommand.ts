import { assistantDigest } from './assistantRequestCoordinator'
import type { AssistantDispatchReceipt, AssistantSessionState } from './assistantSessionStore'

/** The editor identity and the immutable request belong to one direct submission. */
export interface AssistantComposerCommand {
  sessionId: string
  revision: number
  content: string
  clientRequestId: string
  bodyDigest: string
  everDispatched: boolean
}

export function assistantComposerReceiptMatches(command: AssistantComposerCommand, receipt: AssistantDispatchReceipt): boolean {
  return receipt.action === 'send' && receipt.sessionId === command.sessionId
    && receipt.clientRequestId === command.clientRequestId && receipt.bodyDigest === command.bodyDigest
}

export function assistantComposerSettlement(command: AssistantComposerCommand,
  state: Pick<AssistantSessionState, 'lastCommandResult' | 'lastRejectedCommand'>,
  receipt?: AssistantDispatchReceipt): 'ACCEPTED' | 'REJECTED' | 'NOT_DISPATCHED' | null {
  const accepted = state.lastCommandResult
  if (accepted?.action === 'send' && accepted.body.sessionId === command.sessionId
    && accepted.body.clientRequestId === command.clientRequestId && assistantDigest(accepted.body) === command.bodyDigest) return 'ACCEPTED'
  const rejected = state.lastRejectedCommand
  if (rejected?.action === 'send' && rejected.sessionId === command.sessionId
    && rejected.clientRequestId === command.clientRequestId && rejected.bodyDigest === command.bodyDigest) {
    if (rejected.source === 'ACKNOWLEDGED_REJECTION') return 'REJECTED'
    if (rejected.source === 'LOCAL_NOT_DISPATCHED' && !command.everDispatched) return 'NOT_DISPATCHED'
  }
  if (receipt && assistantComposerReceiptMatches(command, receipt) && receipt.dispatchState === 'NOT_DISPATCHED'
    && !receipt.earlierUnresolvedSameKey && !command.everDispatched) return 'NOT_DISPATCHED'
  return null
}
