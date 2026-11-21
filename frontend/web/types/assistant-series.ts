/** Wire contract: SeriesAdoptionRequests / SeriesAdoptionViews. */
export type SeriesMode = 'UPDATE_EXISTING' | 'APPEND_NEW'
export type SeriesCapabilityRequest =
  | { sessionId: string; mode: 'UPDATE_EXISTING'; items: { artifactId: string; episodeId: string }[] }
  | { sessionId: string; mode: 'APPEND_NEW'; artifactId: string; episodeKeyword?: string }

export interface SeriesCapabilityItem {
  itemIndex: number
  artifactId: string
  artifactRef: string
  artifactContentHash: string
  segmentContentHash: string
  episodeId: string | null
  episodeNo: number
  currentTargetRevision: string | null
  proposedTitle: string | null
  proposedDescription: string | null
  sourceCharStart: number
  sourceCharEnd: number
  effectiveCharCount: number
  allowed: boolean
  reason: string | null
}
export interface SeriesCapabilities {
  schemaVersion: 'assistant-series-adoption-capabilities/v1'
  mode: SeriesMode
  projectId: string
  projectEpisodeSetDigest: string
  splitterContractVersion: string | null
  capabilityDigest: string
  /** Opaque candidate snapshot, not authority. Never decode into targets or modify it. */
  splitCapabilitySnapshot: string | null
  allowed: boolean
  reason: string | null
  items: SeriesCapabilityItem[]
}
interface SeriesCommitBase {
  sessionId: string
  capabilityDigest: string
  expectedProjectEpisodeSetDigest: string
  clientRequestId: string
}
export type SeriesCommit = SeriesCommitBase & (
  | { mode: 'UPDATE_EXISTING'; items: { artifactId: string; artifactContentHash: string; episodeId: string; expectedTargetRevision: string }[] }
  | { mode: 'APPEND_NEW'; artifactId: string; artifactContentHash: string; splitCapabilitySnapshot: string }
)
export interface SeriesReceiptItem {
  itemIndex: number
  adoptionId: string
  artifactId: string
  artifactRef: string
  artifactContentHash: string
  segmentContentHash: string
  targetObjectId: string
  episodeId: string
  episodeNo: number
  previousTargetRevision: string | null
  targetRevision: string
  createdEpisodeId: string | null
  createdScriptId: string | null
  sourceCharStart: number
  sourceCharEnd: number
}
export interface SeriesReceipt {
  schemaVersion: 'assistant-series-adoption-receipt/v1'
  commandId: string
  /** The receipt must identify the same session as the original durable command. */
  sessionId: string
  clientRequestId: string
  mode: SeriesMode
  projectId: string
  beforeEpisodeSetDigest: string
  afterEpisodeSetDigest: string
  items: SeriesReceiptItem[]
}
export interface SeriesScope {
  actorId: string
  sessionId: string
  projectId: string
  episodeId: '0'
  moduleCode: 'SCREENWRITER'
}
export interface PendingSeriesAdoption {
  schemaVersion: 1
  scope: SeriesScope
  request: SeriesCommit
  preparedRequest: SeriesCapabilityRequest
  /** Safe preview identity/ranges only, needed to verify a replayed receipt after restart. */
  preview: SeriesCapabilities
}

export interface AssistantSeriesEditorAdapter {
  projectId: string
  episodes: (page: number) => Promise<{ items: { episodeId: string; episodeNo: number; title: string }[]; nextPage: number | null }>
  prepare: (request: SeriesCapabilityRequest) => Promise<{ assertUnchanged: () => void; targetRevisions: Record<string, string> }>
  applied: (receipt: SeriesReceipt) => Promise<string | void>
}
