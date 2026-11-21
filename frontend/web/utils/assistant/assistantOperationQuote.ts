import type { AssistantOperationQuote, AssistantSessionSnapshot } from '~/types/assistant-api'
import type { AssistantRunBudgetPolicy } from '~/types/assistant-budget'
import { assistantBudgetUnits } from './assistantBudget'
import { assistantDigest, assistantErrorEnvelope, assistantErrorCode, assistantCommandWasRejected } from './assistantRequestCoordinator'
import { assistantCommandId } from './assistantRequestCoordinator'
import { assistantOperationWithinApprovedScope, assistantOperationStepPending, assistantOperationConfirmationAvailable } from './assistantExecution'
import { assistantBudgetPrepareExplicitApproval } from './assistantBudgetRecovery'
import type { AssistantSessionStore } from './assistantSessionStore'

export interface AssistantOperationQuoteSource {
  sessionId: string
  runId: string
  runRevision: number
  sessionRevision: number
  lastEventSeq: number
  approvalId: string
  approvalRevision: number
  status: 'QUOTED' | 'INVALIDATED'
  stepId: string
  planRevision: number
  quoteId: string
  modelCode: string | null
  quotedUpperBound: string
  expiresAt: string
  referenceRevalidation?: { referenceRevalidationToken: string; expiresAt: string; resources: AssistantOperationReferenceResource[] }
}
export interface AssistantOperationQuoteRecord {
  source: AssistantOperationQuoteSource
  clientRequestId: string
  bodyDigest: string
  result: AssistantOperationQuote
}
export const ASSISTANT_OPERATION_QUOTE_LIMIT = 32
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const integer = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0
const id = (v: unknown): v is string => typeof v === 'string' && /^[1-9]\d{0,18}$/.test(v) && BigInt(v) <= 9223372036854775807n
const text = (v: unknown): v is string => typeof v === 'string' && !!v.trim()
const date = (v: unknown): v is string => typeof v === 'string' && /(?:Z|[+-]\d{2}:\d{2})$/.test(v) && Number.isFinite(Date.parse(v))
const terminal = new Set(['COMPLETED', 'SUCCEEDED', 'FAILED', 'STOPPED', 'CANCELLED', 'CANCELED'])

/** Only this operation's displayed authority is captured, never prompts or lookup metadata. */
export function assistantOperationQuoteSource(snapshot: AssistantSessionSnapshot | null): AssistantOperationQuoteSource | null {
  const run = snapshot?.activeRun, a = snapshot?.approval
  if (!snapshot || !run || !assistantOperationStepPending(snapshot) || !id(snapshot.sessionId) || !id(run.runId) || !integer(snapshot.revision)
    || !integer(snapshot.lastEventSeq) || !integer(run.stateRevision) || run.status !== 'WAITING_APPROVAL'
    || run.stopRequested || run.nextAction !== 'CONFIRM_OPERATION_APPROVAL' || snapshot.inputRequest?.status === 'SUSPENDED'
    || !a || a.scopeKind !== 'PLAN_EXECUTION' || !['QUOTED', 'INVALIDATED'].includes(a.status) || !id(a.approvalId)
    || !integer(a.approvalRevision) || !id(a.stepId) || !integer(a.planRevision)
    || !a.allowedActions.includes('QUOTE_OPERATION') || !text(a.quoteId) || !date(a.expiresAt)
    || a.currencyUnit !== 'BALANCE' || a.hardUpperBoundAvailable !== true
    || assistantBudgetUnits(a.quotedUpperBound) === null || assistantBudgetUnits(a.quotedUpperBound) !== assistantBudgetUnits(a.maxCharge)) return null
  const step = snapshot.steps.find(item => item.stepId === a.stepId)
  if (!step || step.approvalId !== a.approvalId || step.planRevision !== a.planRevision
    || step.status !== 'AWAITING_APPROVAL' || step.providerAcceptanceState !== 'NOT_SUBMITTED' || step.mediaTaskId) return null
  return { sessionId: snapshot.sessionId, runId: run.runId, runRevision: run.stateRevision,
    sessionRevision: snapshot.revision, lastEventSeq: snapshot.lastEventSeq, approvalId: a.approvalId,
    approvalRevision: a.approvalRevision, status: a.status as 'QUOTED' | 'INVALIDATED', stepId: a.stepId, planRevision: a.planRevision,
    quoteId: a.quoteId, modelCode: a.modelCode || null, quotedUpperBound: a.quotedUpperBound, expiresAt: a.expiresAt }
}

export function assistantOperationQuoteBody(source: AssistantOperationQuoteSource, clientRequestId: string): Record<string, unknown> {
  return { runId: source.runId, approvalId: source.approvalId, expectedRunRevision: source.runRevision,
    expectedApprovalRevision: source.approvalRevision, clientRequestId,
    ...(source.referenceRevalidation ? { referenceRevalidationToken: source.referenceRevalidation.referenceRevalidationToken } : {}) }
}
export function assistantOperationQuoteSourceRecord(value: unknown): AssistantOperationQuoteSource {
  if (!object(value) || !id(value.sessionId) || !id(value.runId) || !id(value.approvalId) || !id(value.stepId)
    || !['runRevision', 'sessionRevision', 'lastEventSeq', 'approvalRevision', 'planRevision'].every(key => integer(value[key]))
    || !['QUOTED', 'INVALIDATED'].includes(String(value.status)) || !text(value.quoteId) || !(value.modelCode === null || text(value.modelCode)) || !date(value.expiresAt)
    || typeof value.quotedUpperBound !== 'string' || assistantBudgetUnits(value.quotedUpperBound) === null
    || value.referenceRevalidation !== undefined && !revalidation(value.referenceRevalidation)
    || Object.keys(value).filter(key => key !== 'referenceRevalidation').sort().join(',') !== ['sessionId', 'runId', 'runRevision', 'sessionRevision', 'lastEventSeq', 'approvalId', 'approvalRevision', 'status', 'stepId', 'planRevision', 'quoteId', 'modelCode', 'quotedUpperBound', 'expiresAt'].sort().join(',')) throw new Error('原操作报价恢复记录不完整，请核实原请求')
  return value as unknown as AssistantOperationQuoteSource
}
/** Full endpoint ACK is mandatory. A safe journal outcome is not an executable quote. */
export function assistantOperationQuoteFromAck(source: AssistantOperationQuoteSource, body: Record<string, unknown>, value: unknown): AssistantOperationQuoteRecord {
  const fail = (): never => { throw new Error('操作报价回执尚不完整，请核实原请求；不会确认费用或重新提交执行') }
  if (!text(body.clientRequestId) || assistantDigest(body) !== assistantDigest(assistantOperationQuoteBody(source, body.clientRequestId))
    || !object(value) || value.runId !== source.runId || !integer(value.runRevision) || !id(value.approvalId)
    || !integer(value.approvalRevision) || value.stepId !== source.stepId || value.planRevision !== source.planRevision
    || value.scopeKind !== 'PLAN_EXECUTION' || value.status !== 'QUOTED' || value.nextAction !== 'CONFIRM_OPERATION_APPROVAL'
    || value.executionSubmitted !== false || !integer(value.lastEventSeq) || !object(value.quote)) return fail()
  const resources = referenceResources(value.referenceResources)
  if (!resources || typeof value.referenceRevalidated !== 'boolean' || value.referenceRevalidated !== !!source.referenceRevalidation
    || assistantDigest(resources) !== assistantDigest(source.referenceRevalidation?.resources ?? [])) return fail()
  const q = value.quote, upper = assistantBudgetUnits(q.quotedUpperBound as string)
  const estimate = q.estimatedAmount === null ? null : assistantBudgetUnits(q.estimatedAmount as string)
  if (!text(q.quoteId) || !(q.modelCode === null || text(q.modelCode)) || q.modelCode !== source.modelCode
    || upper === null || q.estimatedAmount !== null && (estimate === null || estimate > upper)
    || q.currencyUnit !== 'BALANCE' || q.precisionScale !== 4 || q.hardUpperBoundAvailable !== true || !date(q.expiresAt)) return fail()
  if (value.quoteDisposition === 'UNCHANGED') {
    if (source.referenceRevalidation || source.status !== 'QUOTED' || value.previousApprovalId !== null || value.approvalId !== source.approvalId || value.approvalRevision !== source.approvalRevision
      || value.runRevision !== source.runRevision || value.lastEventSeq < source.lastEventSeq || q.quoteId !== source.quoteId
      || upper !== assistantBudgetUnits(source.quotedUpperBound) || Date.parse(q.expiresAt) !== Date.parse(source.expiresAt)) return fail()
  } else if (value.quoteDisposition === 'REPLACED') {
    if (value.previousApprovalId !== source.approvalId || value.approvalId === source.approvalId || value.approvalRevision !== 0
      || value.runRevision !== source.runRevision + 1 || value.lastEventSeq <= source.lastEventSeq) return fail()
  } else return fail()
  // Store only the exact safe contract, not arbitrary endpoint fields.
  const result: AssistantOperationQuote = { runId: value.runId as string, runRevision: value.runRevision,
    approvalId: value.approvalId, approvalRevision: value.approvalRevision, previousApprovalId: value.previousApprovalId as string | null,
    stepId: source.stepId, planRevision: source.planRevision, scopeKind: 'PLAN_EXECUTION', status: 'QUOTED',
    nextAction: 'CONFIRM_OPERATION_APPROVAL', quoteDisposition: value.quoteDisposition,
    referenceRevalidated: value.referenceRevalidated, referenceResources: resources,
    quote: { quoteId: q.quoteId, modelCode: q.modelCode, estimatedAmount: q.estimatedAmount as string | null,
      quotedUpperBound: q.quotedUpperBound as string, currencyUnit: 'BALANCE', precisionScale: 4,
      expiresAt: q.expiresAt, hardUpperBoundAvailable: true }, lastEventSeq: value.lastEventSeq, executionSubmitted: false }
  return { source, clientRequestId: body.clientRequestId, bodyDigest: assistantDigest(body), result }
}
export function assistantOperationQuoteRecords(value: unknown): AssistantOperationQuoteRecord[] {
  if (value === undefined) return []
  if (!Array.isArray(value) || value.length > ASSISTANT_OPERATION_QUOTE_LIMIT) throw new Error('操作报价恢复记录容量或格式异常')
  return value.map(row => {
    if (!object(row) || Object.keys(row).sort().join(',') !== ['source', 'clientRequestId', 'bodyDigest', 'result'].sort().join(',')) throw new Error('操作报价恢复记录不完整')
    const source = assistantOperationQuoteSourceRecord(row.source)
    if (!text(row.clientRequestId)) throw new Error('操作报价恢复标识不完整')
    const projected = assistantOperationQuoteFromAck(source, assistantOperationQuoteBody(source, row.clientRequestId), row.result)
    if (projected.bodyDigest !== row.bodyDigest || assistantDigest(projected.result) !== assistantDigest(row.result)) throw new Error('操作报价恢复回执不一致')
    return projected
  })
}
export function assistantOperationQuoteAdd(records: AssistantOperationQuoteRecord[], record: AssistantOperationQuoteRecord): AssistantOperationQuoteRecord[] {
  const same = records.find(row => row.source.sessionId === record.source.sessionId && row.clientRequestId === record.clientRequestId)
  if (same) {
    if (assistantDigest(same) !== assistantDigest(record)) throw new Error('原操作报价回执不一致，请核实原请求')
    return records
  }
  if (records.length >= ASSISTANT_OPERATION_QUOTE_LIMIT) throw new Error('操作报价恢复记录已满，请先同步原操作')
  return [...records, record]
}
export function assistantOperationQuoteCurrent(records: AssistantOperationQuoteRecord[], snapshot: AssistantSessionSnapshot): AssistantOperationQuoteRecord | null {
  const run = snapshot.activeRun
  return run ? [...records].reverse().find(row => row.source.sessionId === snapshot.sessionId && row.result.runId === run.runId) ?? null : null
}
export function assistantOperationQuoteCaughtUp(record: AssistantOperationQuoteRecord, snapshot: AssistantSessionSnapshot): boolean {
  const current = assistantOperationQuoteSource(snapshot), q = record.result
  return !!current && current.status === 'QUOTED' && current.runId === q.runId && current.runRevision >= q.runRevision && current.lastEventSeq >= q.lastEventSeq
    && current.approvalId === q.approvalId && current.approvalRevision === q.approvalRevision
    && current.stepId === q.stepId && current.planRevision === q.planRevision && current.quoteId === q.quote.quoteId
    && current.modelCode === q.quote.modelCode && assistantBudgetUnits(current.quotedUpperBound) === assistantBudgetUnits(q.quote.quotedUpperBound)
    && Date.parse(current.expiresAt) === Date.parse(q.quote.expiresAt)
}
export function assistantOperationQuoteObserve(records: AssistantOperationQuoteRecord[], snapshot: AssistantSessionSnapshot): AssistantOperationQuoteRecord[] {
  const run = snapshot.activeRun
  if (!run) return records
  const next = records.filter(row => {
    const q = row.result, a = snapshot.approval
    if (row.source.sessionId !== snapshot.sessionId || snapshot.lastEventSeq < q.lastEventSeq) return true
    if (run.runId !== q.runId || terminal.has(run.status)) return false
    if (run.stateRevision <= q.runRevision) return true
    // A newer authoritative phase or this exact consumed approval closes the fee display,
    // without interpreting it as a fresh execution grant or a replayable command ACK.
    if (run.nextAction !== 'CONFIRM_OPERATION_APPROVAL') return false
    if (a?.scopeKind === 'PLAN_EXECUTION' && a.status === 'ACCEPTED' && a.approvalId === q.approvalId
      && a.approvalRevision === q.approvalRevision + 1 && a.stepId === q.stepId && a.planRevision === q.planRevision) return false
    const current = assistantOperationQuoteSource(snapshot)
    return !current || current.status === 'QUOTED' && current.stepId === q.stepId
      && current.approvalId === q.approvalId && current.approvalRevision === q.approvalRevision
  })
  return next.length === records.length ? records : next
}
/** Quote-only may read fresh pricing during a renewal hold, but never spend or clear it. */
export function assistantOperationQuoteAvailable(snapshot: AssistantSessionSnapshot, budget: AssistantRunBudgetPolicy | null, now: number): boolean {
  const source = assistantOperationQuoteSource(snapshot)
  const approval = snapshot.approval?.scopeKind === 'PLAN_EXECUTION' ? snapshot.approval : null
  return !!source && !!approval && !!budget && budget.runId === source.runId && budget.runRevision === source.runRevision
    && assistantOperationWithinApprovedScope(approval, budget, now)
}

/** A separate user confirmation binds the currently displayed operation, never its price ACK. */
export function assistantConfirmOperation(store: AssistantSessionStore, rendered: AssistantSessionSnapshot, budget: AssistantRunBudgetPolicy | null): Promise<boolean> {
  const state = store.getSnapshot(), current = state.snapshot, run = current?.activeRun, a = current?.approval
  if (!current || !run || !a || !budget || !state.writable || state.busy || state.pendingCommand
    || current.sessionId !== rendered.sessionId || current.revision !== rendered.revision || current.lastEventSeq !== rendered.lastEventSeq
    || run.runId !== rendered.activeRun?.runId || run.stateRevision !== rendered.activeRun?.stateRevision
    || a.approvalId !== rendered.approval?.approvalId || a.approvalRevision !== rendered.approval?.approvalRevision
    || a.scopeKind !== 'PLAN_EXECUTION' || !['QUOTED', 'INVALIDATED'].includes(a.status) || run.status !== 'WAITING_APPROVAL' || run.stopRequested
    || current.inputRequest?.status === 'SUSPENDED' || !state.capabilities?.executionAvailable
    || !a.allowedActions.includes('CONFIRM_APPROVAL') || store.operationApprovalAccepted(current) || store.operationQuoteSynchronizing(current)
    || store.operationReferenceChallenge(current) || !assistantOperationConfirmationAvailable(current) || !assistantOperationWithinApprovedScope(a, budget, Date.now())
    || budget.runId !== run.runId || budget.runRevision !== run.stateRevision) return Promise.resolve(false)
  const body = { runId: run.runId, expectedRunRevision: run.stateRevision, approvalId: a.approvalId,
    expectedApprovalRevision: a.approvalRevision, clientRequestId: assistantCommandId(),
    ...(budget.renewalNeedsConfirmation === true ? { confirmRenewedExecution: true } : {}) }
  try { assistantBudgetPrepareExplicitApproval(store, current, budget, body) }
  catch (error) { store.reportError(error); return Promise.resolve(false) }
  return store.execute('approveOperation', body)
}

export interface AssistantOperationReferenceResource { resourceId: string; identityHash: string; mediaType: 'IMAGE' | 'VIDEO' | 'AUDIO' }
export interface AssistantOperationReferenceChallenge {
  source: AssistantOperationQuoteSource
  referenceRevalidationToken: string
  expiresAt: string
  resources: AssistantOperationReferenceResource[]
}
const token = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/.test(value)
function referenceResources(value: unknown): AssistantOperationReferenceResource[] | null {
  if (!Array.isArray(value)) return null
  const seen = new Set<string>(), rows: AssistantOperationReferenceResource[] = []
  for (const r of value) {
    if (!object(r) || !id(r.resourceId) || typeof r.identityHash !== 'string' || !/^[a-fA-F0-9]{64}$/.test(r.identityHash)
      || !['IMAGE', 'VIDEO', 'AUDIO'].includes(String(r.mediaType)) || seen.has(r.resourceId)
      || Object.keys(r).sort().join(',') !== 'identityHash,mediaType,resourceId') return null
    seen.add(r.resourceId); rows.push({ resourceId: r.resourceId, identityHash: r.identityHash, mediaType: r.mediaType as AssistantOperationReferenceResource['mediaType'] })
  }
  return rows
}
function revalidation(value: unknown): AssistantOperationQuoteSource['referenceRevalidation'] | null {
  if (!object(value) || Object.keys(value).sort().join(',') !== 'expiresAt,referenceRevalidationToken,resources' || !token(value.referenceRevalidationToken) || !date(value.expiresAt)) return null
  const resources = referenceResources(value.resources)
  return resources?.length ? { referenceRevalidationToken: value.referenceRevalidationToken, expiresAt: value.expiresAt, resources } : null
}
function originalSource(source: AssistantOperationQuoteSource): AssistantOperationQuoteSource {
  const original = { ...source }; delete original.referenceRevalidation
  return original
}
export function assistantOperationReferenceRecords(value: unknown): AssistantOperationReferenceChallenge[] {
  if (value === undefined) return []
  if (!Array.isArray(value) || value.length > ASSISTANT_OPERATION_QUOTE_LIMIT) throw new Error('素材核对恢复记录容量或格式异常')
  return value.map(row => {
    if (!object(row) || Object.keys(row).sort().join(',') !== 'expiresAt,referenceRevalidationToken,resources,source'
      || !token(row.referenceRevalidationToken) || !date(row.expiresAt)) throw new Error('素材核对恢复记录不完整')
    const source = assistantOperationQuoteSourceRecord(row.source), resources = referenceResources(row.resources)
    if (source.referenceRevalidation || !resources?.length) throw new Error('素材核对恢复范围不完整')
    return { source, referenceRevalidationToken: row.referenceRevalidationToken, expiresAt: row.expiresAt, resources }
  })
}
export function assistantOperationReferenceCurrent(records: AssistantOperationReferenceChallenge[], snapshot: AssistantSessionSnapshot): AssistantOperationReferenceChallenge | null {
  const source = assistantOperationQuoteSource(snapshot)
  return source ? [...records].reverse().find(row => assistantDigest(row.source) === assistantDigest(source)) ?? null : null
}
export function assistantOperationReferenceSource(source: AssistantOperationQuoteSource | null, body: Record<string, unknown>, records: AssistantOperationReferenceChallenge[]): AssistantOperationQuoteSource | null {
  if (!source || body.referenceRevalidationToken === undefined) return source
  const challenge = records.find(row => row.referenceRevalidationToken === body.referenceRevalidationToken
    && assistantDigest(row.source) === assistantDigest(source) && Date.parse(row.expiresAt) > Date.now())
  return challenge ? { ...source, referenceRevalidation: { referenceRevalidationToken: challenge.referenceRevalidationToken, expiresAt: challenge.expiresAt, resources: challenge.resources } } : null
}
export function assistantOperationReferenceFromError(error: unknown, source: AssistantOperationQuoteSource, body: Record<string, unknown>): AssistantOperationReferenceChallenge | null {
  const e = assistantErrorEnvelope(error), outer = object(error) ? error : {}, response = object(outer.response) ? outer.response : {}
  const status = response.status ?? outer.status, transport = Number(status)
  if (outer.apiCryptoDecryptionFailed || outer.apiResponseFormatInvalid || e.encrypted === true
    || status != null && transport !== 409 && !(transport >= 200 && transport < 300 && String(e.code) === '409')
    || e.code != null && String(e.code) !== '409' || status == null && String(e.code) !== '409'
    || !assistantCommandWasRejected(error) || assistantErrorCode(error) !== 'OPERATION_QUOTE_REFERENCE_REVALIDATION_REQUIRED') return null
  const d = object(e.data) && object(e.data.details) ? e.data.details : null
  if (!d || Object.keys(d).sort().join(',') !== 'approvalId,approvalRevision,executionSubmitted,expiresAt,referenceRevalidationToken,resources,runId,runRevision'
    || d.runId !== source.runId || d.runRevision !== source.runRevision || d.approvalId !== source.approvalId
    || d.approvalRevision !== source.approvalRevision || d.executionSubmitted !== false
    || d.referenceRevalidationToken !== body.clientRequestId || !token(d.referenceRevalidationToken) || !date(d.expiresAt)) return null
  const resources = referenceResources(d.resources)
  return resources?.length ? { source: originalSource(source), referenceRevalidationToken: d.referenceRevalidationToken, expiresAt: d.expiresAt, resources } : null
}
export function assistantOperationReferenceAdd(records: AssistantOperationReferenceChallenge[], record: AssistantOperationReferenceChallenge): AssistantOperationReferenceChallenge[] {
  const next = records.filter(row => assistantDigest(row.source) !== assistantDigest(record.source))
  if (next.length >= ASSISTANT_OPERATION_QUOTE_LIMIT) throw new Error('素材核对恢复记录已满，请先同步原操作')
  return [...next, record]
}
export function assistantOperationReferenceObserve(records: AssistantOperationReferenceChallenge[], snapshot: AssistantSessionSnapshot): AssistantOperationReferenceChallenge[] {
  const run = snapshot.activeRun
  return records.filter(row => row.source.sessionId !== snapshot.sessionId || !run || snapshot.lastEventSeq < row.source.lastEventSeq
    || run.runId === row.source.runId && !terminal.has(run.status) && run.stateRevision <= row.source.runRevision)
}
