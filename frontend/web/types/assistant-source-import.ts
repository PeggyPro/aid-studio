import type { AssistantContextRef } from './assistant-api'

export type AssistantSourceImportStatus = 'UPLOADED' | 'DISCOVERING' | 'PARTIAL' | 'PARSING' | 'FINALIZING' | 'FINALIZED' | 'FAILED' | 'CANCELLED'
export interface AssistantSourceImportSummary {
  documentId: string; sessionId: string; projectId: string; originalName: string; mediaType: string
  byteCount: number; byteHash: string; parserCode: string; parserVersion: string; status: AssistantSourceImportStatus
  stateRevision: number; partCount: number; readyPartCount: number; failedPartCount: number
  sourceRevisionId: string | null; version: string | null; contentHash: string | null
  charCount: number | null; wordCount: number | null; errorCode: string | null; allowedActions: string[]
}
export interface AssistantSourceImportPart {
  partIndex: number; partKind: string; locator: Record<string, unknown>; status: string; attemptCount: number
  charStart: number | null; charEnd: number | null; charCount: number | null; wordCount: number | null
  contentHash: string | null; errorCode: string | null
}
export interface AssistantSourceImportDetail extends AssistantSourceImportSummary {
  schemaVersion: 'assistant-source-import-detail/v1'; sourceRevision: string | null
  parts: AssistantSourceImportPart[]; nextAfterPartIndex: number | null; partsComplete: boolean
}
export interface AssistantSourceImportPage {
  schemaVersion: 'assistant-source-import-page/v1'; items: AssistantSourceImportSummary[]
  nextAfterDocumentId: string | null; complete: boolean
}
export interface AssistantSourceImportCommand {
  schemaVersion: 'assistant-source-import-command/v1'; commandId: string; clientRequestId: string
  status: 'PROCESSING' | 'COMPLETED' | 'FAILED'; errorCode: string | null; document: AssistantSourceImportDetail
}
export interface AssistantSourceImportLookup {
  schemaVersion: 'assistant-source-import-lookup/v1'; clientRequestId: string
  observation: 'NOT_OBSERVED' | 'OBSERVED'; command: AssistantSourceImportCommand | null
}
export interface AssistantSourceAdvanceRequest {
  sessionId: string; documentId: string; clientRequestId: string; expectedStateRevision: number
  partIndexes?: number[]; maxParts?: number
}
export type AssistantSourcePending = { kind: 'UPLOAD'; sessionId: string; clientRequestId: string; fileName: string; mediaType: string; fileHash: string; byteCount: number }
  | { kind: 'ADVANCE' | 'CANCEL'; body: AssistantSourceAdvanceRequest }

export function sourceDocumentContext(value: AssistantSourceImportSummary): AssistantContextRef {
  if (value.status !== 'FINALIZED' || !value.version || !value.contentHash || !value.sourceRevisionId) throw new Error('文档尚未解析完成')
  return { kind: 'DOCUMENT', documentId: value.documentId, version: value.version, contentHash: value.contentHash, label: value.originalName }
}
