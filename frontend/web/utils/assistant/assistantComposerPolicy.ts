/** Drafting and queuing are local; dispatch still uses the command journal and write lease. */
export function assistantComposerPolicy(input: {
  conversational: boolean; hasShell: boolean; hasSession: boolean; runStatus?: string; hasDraftReferences?: boolean
  disabledReason?: string; messageDisabledReason?: string; canSend: boolean
  mutationBlocked: boolean; pendingCommand: boolean; awaitingMessageSync: boolean; sending: boolean
}) {
  const conversation = input.conversational && input.hasShell
  const activeRun = !!input.runStatus && !['WAITING_APPROVAL', 'PAUSED', 'COMPLETED', 'SUCCEEDED', 'FAILED', 'STOPPED', 'CANCELLED'].includes(input.runStatus)
  const queueing = conversation && (activeRun || input.hasDraftReferences && ['WAITING_APPROVAL', 'PAUSED'].includes(input.runStatus || '') || input.pendingCommand || input.awaitingMessageSync || input.sending)
  const editorDisabled = !input.hasSession || (conversation ? !!input.disabledReason
    : !input.canSend || input.mutationBlocked || !!input.messageDisabledReason || input.pendingCommand)
  const submitDisabled = queueing ? editorDisabled : !input.hasSession || !input.canSend
    || input.mutationBlocked || !!input.messageDisabledReason || input.pendingCommand
  return { queueing, editorDisabled, submitDisabled }
}
