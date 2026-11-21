export interface AssistantRecoveryScope {
  sessionId: string
  sessionRevision: number
  lastEventSeq: number
  activeRunId?: string | null
  activeRunRevision?: number | null
}

export interface AssistantCommandReceipt {
  commandId: string
  clientRequestId: string
  commandType: string
  status: string
  payloadDigest: string | null
  errorCode: string | null
  /** Metadata only. Never reconstruct an operation or editor receipt from these IDs. */
  outcome: Record<string, string>
}

export interface AssistantCommandLookup {
  scope: AssistantRecoveryScope
  observation: 'OBSERVED' | 'NOT_OBSERVED'
  recoveryDirective: 'REFRESH_AUTHORITATIVE_STATE' | 'UNKNOWN_REPLAY_SAME_KEY_ONLY'
  replacementKeyAllowed: false
  receipt: AssistantCommandReceipt | null
}

export interface AssistantCommandPageRequest {
  sessionId: string
  limit?: number
  afterCommandId?: string
  throughCommandId?: string
  expectedSessionRevision?: number
  expectedLastEventSeq?: number
}

export interface AssistantCommandPage {
  scope: AssistantRecoveryScope
  items: AssistantCommandReceipt[]
  hasMore: boolean
  nextCommandId: string | null
  throughCommandId: string
}
