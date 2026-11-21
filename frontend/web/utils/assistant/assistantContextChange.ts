import { assistantErrorCode, assistantErrorMessage } from './assistantRequestCoordinator'

const contextChangeCodes = new Set([
  'ASSISTANT_CONTEXT_STALE',
  'CREATIVE_CONTEXT_INVALID',
  'TOOL_PREPARATION_REFERENCE_CONTEXT_INVALID'
])

/** Server copy for a stale canvas/document/reference scope. Not a user action. */
export function assistantContextChangeText(message: string | null | undefined): boolean {
  return typeof message === 'string' && message.includes('上下文已变更')
}

/** Context drift is repaired by the session sync, never by a manual resync button. */
export function assistantContextChangeSignal(error: unknown): boolean {
  return contextChangeCodes.has(assistantErrorCode(error)) || assistantContextChangeText(assistantErrorMessage(error))
}
