import type { AssistantSourceImportCommand, AssistantSourceImportDetail, AssistantSourceImportSummary, AssistantSourceImportPage, AssistantSourceImportLookup, AssistantSourceAdvanceRequest } from '~/types/assistant-source-import'
import type { AssistantTransport } from '~/utils/business/assistant'
import type { AssistantSourceRevision, AssistantSourceContent } from '~/types/assistant-artifact'
import { assistantDigest, coordinateAssistantRequest } from './assistantRequestCoordinator'

const positiveId = (value: unknown): value is string => typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value)
const count = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 0
const digest = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
const statuses = new Set(['UPLOADED','DISCOVERING','PARTIAL','PARSING','FINALIZING','FINALIZED','FAILED','CANCELLED'])
function summary(value: AssistantSourceImportSummary, sessionId: string): AssistantSourceImportSummary {
  if (!value || !positiveId(value.documentId) || value.sessionId !== sessionId || !statuses.has(value.status)
    || !count(value.stateRevision) || !count(value.partCount) || !count(value.readyPartCount) || !count(value.failedPartCount)
    || value.partCount > 10_000 || value.readyPartCount > value.partCount || value.failedPartCount > value.partCount
    || !count(value.byteCount) || value.byteCount > 10 * 1024 * 1024 || !digest(value.byteHash)
    || typeof value.originalName !== 'string' || value.originalName.length > 255
    || typeof value.parserCode !== 'string' || typeof value.parserVersion !== 'string'
    || !Array.isArray(value.allowedActions) || value.allowedActions.some(action => !['ADVANCE','RETRY','CANCEL','PIN_DOCUMENT'].includes(action)))
    throw new Error('文档状态与当前会话不一致，请重新恢复')
  if (value.status === 'FINALIZED' && (!positiveId(value.sourceRevisionId) || !positiveId(value.version)
    || !digest(value.contentHash) || !count(value.charCount) || value.charCount > 5_000_000 || value.readyPartCount !== value.partCount))
    throw new Error('文档版本尚未完整，请重新恢复')
  return value
}
export function verifySourceImportDetail(value: AssistantSourceImportDetail, sessionId: string): AssistantSourceImportDetail {
  summary(value, sessionId)
  if (value.schemaVersion !== 'assistant-source-import-detail/v1' || !Array.isArray(value.parts) || value.parts.length > 100
    || typeof value.partsComplete !== 'boolean' || value.nextAfterPartIndex !== null && !count(value.nextAfterPartIndex)) throw new Error('文档分片状态无效')
  let previous = -1
  for (const part of value.parts) {
    if (!count(part.partIndex) || part.partIndex <= previous || part.partIndex >= value.partCount || !count(part.attemptCount)
      || part.attemptCount > 3 || !['PENDING','PARSING','READY','FAILED','CANCELLED'].includes(part.status)) throw new Error('文档分片状态无效')
    previous = part.partIndex
  }
  return value
}

/** Bind reading to the immutable source identity returned by the detail endpoint. */
export function sourceImportRevisionForReading(row: AssistantSourceImportSummary, detail: AssistantSourceImportDetail): AssistantSourceRevision {
  verifySourceImportDetail(detail, row.sessionId)
  if (row.status !== 'FINALIZED' || detail.status !== 'FINALIZED' || detail.documentId !== row.documentId
    || detail.sourceRevisionId !== row.sourceRevisionId || detail.version !== row.version || detail.contentHash !== row.contentHash
    || detail.charCount !== row.charCount || detail.wordCount !== row.wordCount
    || !positiveId(detail.sourceRevisionId) || !digest(detail.contentHash)
    || typeof detail.sourceRevision !== 'string' || !detail.sourceRevision.trim() || detail.sourceRevision.length > 128)
    throw new Error('原文版本或来源已变化，请重新打开')
  return { sourceRevisionId: detail.sourceRevisionId, sourceKind: 'DOCUMENT', sourceObjectId: detail.documentId,
    sourceRevision: detail.sourceRevision, contentHash: detail.contentHash, charCount: detail.charCount || 0,
    wordCount: detail.wordCount || 0, chunkCount: 0, status: 'ACTIVE' }
}

function command(value: AssistantSourceImportCommand, sessionId: string, key: string): AssistantSourceImportCommand {
  if (!value || value.schemaVersion !== 'assistant-source-import-command/v1' || value.clientRequestId !== key
    || !positiveId(value.commandId) || !['PROCESSING','COMPLETED','FAILED'].includes(value.status)) throw new Error('文档操作回执无效')
  verifySourceImportDetail(value.document, sessionId)
  return value
}
export function createAssistantSourceImportApi(transport: AssistantTransport, identity = transport.identityKey()) {
  const root = '/api/user/assistant/source/import'
  async function post<T>(path: string, body: unknown): Promise<T> {
    if (transport.identityKey() !== identity) throw new Error('登录状态已变化，请重新打开助手')
    const value = await transport.post<T>(root + path, body)
    if (transport.identityKey() !== identity) throw new Error('登录状态已变化，请重新打开助手')
    if ((value.code !== 0 && value.code !== 200) || value.data == null) throw value
    return value.data
  }
  function read<T>(path: string, body: Record<string, unknown>) {
    return coordinateAssistantRequest<T>({ key: `${transport.kind}:${identity}:source:${path}:${assistantDigest(body)}`, body, force: true, cacheMs: 0, execute: () => post<T>(path,body) })
  }
  function mutate(path: string, body: AssistantSourceAdvanceRequest) {
    return coordinateAssistantRequest<AssistantSourceImportCommand>({ key: `${transport.kind}:${identity}:source:${body.clientRequestId}`, body, execute: () => post<AssistantSourceImportCommand>(path,body) })
      .then(value => command(value,body.sessionId,body.clientRequestId))
  }
  return {
    sourceImportContent: (sessionId: string, documentId: string, afterChunkIndex = -1) => read<AssistantSourceContent>('/content',{sessionId,documentId,afterChunkIndex,limit:20}),
    sourceImportPage: (sessionId: string, afterDocumentId?: string) => read<AssistantSourceImportPage>('/page',{sessionId,afterDocumentId,limit:20}).then(value => {
      if (value.schemaVersion !== 'assistant-source-import-page/v1' || !Array.isArray(value.items) || value.items.length > 20) throw new Error('文档列表无效')
      for (const item of value.items) summary(item,sessionId)
      return value
    }),
    sourceImportDetail: (sessionId: string, documentId: string, afterPartIndex = -1) => read<AssistantSourceImportDetail>('/detail',{sessionId,documentId,afterPartIndex,partLimit:100}).then(value => verifySourceImportDetail(value,sessionId)),
    sourceImportLookup: (sessionId: string, clientRequestId: string) => read<AssistantSourceImportLookup>('/command',{sessionId,clientRequestId}).then(value => {
      if (!value || value.schemaVersion !== 'assistant-source-import-lookup/v1' || value.clientRequestId !== clientRequestId || !['NOT_OBSERVED','OBSERVED'].includes(value.observation)) throw new Error('文档操作查询无效')
      if (value.observation === 'OBSERVED') { if (!value.command) throw new Error('文档回执缺失'); command(value.command,sessionId,clientRequestId) }
      else if (value.command !== null) throw new Error('文档查询状态无效')
      return value
    }),
    sourceImportUpload: (sessionId: string, clientRequestId: string, file: File, fileHash: string) => {
      if (!digest(fileHash) || file.size < 1 || file.size > 10 * 1024 * 1024) return Promise.reject(new Error('文档大小或指纹无效'))
      const body = {sessionId,clientRequestId,fileName:file.name,mediaType:file.type || 'application/octet-stream',byteCount:file.size,fileHash}
      const form = new FormData(); form.set('sessionId',sessionId); form.set('clientRequestId',clientRequestId); form.set('file',file)
      return coordinateAssistantRequest<AssistantSourceImportCommand>({key:`${transport.kind}:${identity}:source:${clientRequestId}`,body,execute:() => post<AssistantSourceImportCommand>('/upload',form)})
        .then(value => {
          command(value,sessionId,clientRequestId)
          if (value.document.byteHash !== fileHash || value.document.byteCount !== file.size || value.document.originalName !== file.name) throw new Error('上传文档指纹不一致，请核实原操作')
          return value
        })
    },
    sourceImportAdvance: (body: AssistantSourceAdvanceRequest) => mutate('/advance',body),
    sourceImportCancel: (body: AssistantSourceAdvanceRequest) => mutate('/cancel',body)
  }
}
