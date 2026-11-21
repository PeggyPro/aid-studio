import type { AssistantTargetEffect } from './assistant-effects'

/** 服务端不可变产物投影；metadata仅供展示，不能据此授权写入或猜测媒体URL。 */
export interface AssistantArtifactSummary {
  presentationRole?: 'DELIVERABLE' | 'INTERNAL_STATE'
  latestTargetEffect?: AssistantTargetEffect
  artifactId: string
  logicalArtifactId: string
  revision: number
  artifactType: string
  schemaVersion: string
  contentHash: string
  reviewStatus: string
  summary?: string | null
  status: string
  producerRunId: string
  producerStepId?: string | null
  metadata: Record<string, unknown>
  createdAt?: string | null
}
export interface AssistantArtifactDetail extends AssistantArtifactSummary {
  parentRevision?: number | null
  sourceContextDigest?: string | null
  sourceContextRefs?: AssistantSourceRef[] | null
  resourceId?: string | null
  fileId?: string | null
  resourceRef?: Record<string, unknown> | null
  chunkCount: number
  charCount: number | null
  wordCount: number | null
}
export interface AssistantArtifactChunk {
  chunkIndex: number
  charStart: number
  charEnd: number
  charCount: number
  wordCount: number
  contentHash: string
  content: string
}
export interface AssistantArtifactContent {
  artifactId: string
  contentHash: string
  chunks: AssistantArtifactChunk[]
  nextAfterChunkIndex: number | null
  complete: boolean
}
export interface AssistantArtifactReview {
  reviewId: string
  reviewArtifactId: string
  verdict: string
  severityCounts: Record<string, number>
  contentHash: string
  createdAt?: string | null
}
export interface AssistantArtifactPage<T> { items: T[]; nextCursor: string | null }

export interface AssistantSourceRef {
  ref: string
  sourceRevisionId: string
  sourceKind: string
  sourceObjectId: string
  sourceRevision: string
  contentHash: string
  purpose: string
}
export interface AssistantSourceCoverage {
  sourceRevisionId: string
  totalChunks: number
  coveredChunks: number
  items: { coverageId: string; sourceRevisionId: string; chunkIndex: number; purpose: string; coverageStatus: string; evidenceArtifactId?: string | null; revision: number; notes?: string | null; updatedAt?: string | null }[]
  nextCursor: string | null
}
export interface AssistantAdoptionTarget {
  targetKind: string
  targetObjectId: string
  currentTargetRevision: string
  allowed: boolean
  reasonCode?: string | null
  displayName: string
  requiredContext: Record<string, unknown>
}
export interface AssistantAdoptionCapabilities { artifactId: string; targets: AssistantAdoptionTarget[] }
export interface AssistantAdoptionReceipt {
  adoptionId: string
  artifactId: string
  targetKind: string
  targetObjectId: string
  targetRevision: string
  status: 'SUCCEEDED'
  receipt: Record<string, unknown>
}
export interface AssistantAdoptionCapabilityContext { targetKind?: string; targetObjectId?: string; workspaceId?: string }
export interface AssistantAdoptionAdapter {
  capabilityContext?: () => AssistantAdoptionCapabilityContext

  supports: (target: AssistantAdoptionTarget) => boolean
  prepare: (target: AssistantAdoptionTarget) => Promise<{ context: Record<string, unknown>; targetRevision?: string; assertUnchanged: () => void; release?: () => Promise<void> }>
  applied: (receipt: AssistantAdoptionReceipt, command: Record<string, unknown>) => Promise<string | void>
}

/** 经产物服务重新鉴权的归档媒体投影；hash 验证技术回执，不是二进制或创作质量。 */
export interface AssistantArtifactMedia {
  artifactId: string
  artifactType: 'IMAGE' | 'VIDEO' | 'AUDIO' | 'MUSIC'
  mediaTaskId: string
  resultIndex: number | null
  resourceUrl: string
  mimeType: string | null
  fileSize: number | null
  durationSeconds: number | null
  archiveStatus: 'ARCHIVED'
  contentHash: string
  contentHashKind: 'MEDIA_ARCHIVE_RECEIPT_SHA256'
  creativeQualityVerified: false
}

export interface AssistantSourceRevision {
  sourceRevisionId: string
  sourceKind: string
  sourceObjectId: string
  sourceRevision: string
  contentHash: string
  charCount: number
  wordCount: number
  chunkCount: number
  status: string
  createdAt?: string | null
}
export interface AssistantSourceContent {
  sourceRevisionId: string
  sourceKind: string
  sourceObjectId: string
  sourceRevision: string
  contentHash: string
  chunkCount: number
  nextAfterChunkIndex: number | null
  complete: boolean
  chunks: (AssistantArtifactChunk & { locator?: unknown })[]
}

/** 当前运行冻结的读取引用；未入库不等于没有引用，浏览器不得代替 Agent 导入。 */
export interface AssistantSourceManifestItem {
  kind: 'SCRIPT' | 'DOCUMENT'
  scriptId?: string
  documentId?: string
  projectId: string
  episodeId?: string
  version: string
  contentHash: string
  selection: Record<string, unknown> | null
  access: 'READ'
  sourceRevisionId: string | null
  snapshotStatus: 'ACTIVE' | 'NOT_INGESTED'
  nextAction: 'READ_SOURCE' | 'WAIT_FOR_AGENT_INGEST'
}
export interface AssistantSourceManifest {
  runId: string
  contextDigest: string
  runRevision: number
  runStatus: string
  allowedActions: string[]
  items: AssistantSourceManifestItem[]
}
