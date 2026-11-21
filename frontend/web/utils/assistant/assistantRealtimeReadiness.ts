export interface AssistantRealtimeReadiness {
  references: number
  disposed: boolean
  revoked: boolean
  snapshotSessionId: string | null | undefined
  selectedSessionId: string | null | undefined
  eventStream: boolean | undefined
  moduleAvailable: boolean
  websocketAvailable: boolean
  identityCurrent: boolean
  subscriptionSessionId: string | null
  subscriptionExists: boolean
}

/** Read-only lifecycle coordination; execution intent and write ownership are excluded. */
export function assistantRealtimeReadiness(input: AssistantRealtimeReadiness): 'START' | 'KEEP' | 'STOP' {
  if (!Number.isSafeInteger(input.references) || input.references <= 0 || input.disposed || input.revoked
    || !input.snapshotSessionId || input.selectedSessionId !== input.snapshotSessionId
    || input.eventStream !== true || !input.moduleAvailable || !input.websocketAvailable || !input.identityCurrent) return 'STOP'
  return input.subscriptionExists && input.subscriptionSessionId === input.snapshotSessionId ? 'KEEP' : 'START'
}
