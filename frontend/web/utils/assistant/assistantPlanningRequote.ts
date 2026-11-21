import { assistantCommandWasRejected, assistantErrorCode, assistantErrorEnvelope } from './assistantRequestCoordinator'
import { assistantBudgetUnits } from './assistantBudget'
import type { AssistantPlanningApproval, AssistantSessionSnapshot } from '~/types/assistant-api'

/** 只保存该确定未受理拒绝的专用字段，不保存任意错误详情或重建报价。 */
export interface AssistantPlanningRequote {
  sessionId: string
  runId: string
  runRevision: string
  approvalId: string
  approvalRevision: string
  nextAction: 'REQUOTE'
  retryQuote: true
  retryMessage: false
  planningSubmitted: false
}
type Snapshot = { sessionId: string; activeRun?: { runId: string; stateRevision: number; status: string } | null }
type Approval = { approvalId: unknown; approvalRevision: unknown; runId: unknown; runRevision: unknown }
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const integer = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const identity = (value: unknown): value is string => typeof value === 'string' && /^[1-9]\d*$/.test(value)
function completeScope(value: unknown): boolean {
  if (!object(value) || typeof value.scopeDigest !== 'string' || !/^[a-fA-F0-9]{64}$/.test(value.scopeDigest)
    || value.amountScale !== 4 || !integer(value.maxTtlSeconds) || value.maxTtlSeconds < 1
    || !integer(value.maxIterations) || value.maxIterations < 1 || !integer(value.maxOperations) || value.maxOperations < 1
    || !Array.isArray(value.availableModes) || !value.availableModes.length || value.availableModes.some(mode => mode !== 'MANUAL' && mode !== 'AUTO')) return false
  for (const key of ['allowedToolCodes', 'allowedModelCodes', 'allowedCapabilityCodes', 'allowedMediaTypes', 'automaticForbiddenOperationKinds']) {
    if (!Array.isArray(value[key]) || value[key].some(item => typeof item !== 'string' || !item.trim())) return false
  }
  return Array.isArray(value.targetRefs) && value.targetRefs.every(ref => object(ref)
    && (ref.kind === 'FREE_CANVAS' && typeof ref.workspaceId === 'string' && !!ref.workspaceId
      || (ref.kind === 'PREVIZ_IMAGE' || ref.kind === 'PREVIZ_EDITOR') && typeof ref.previzProjectId === 'string'
        && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(ref.previzProjectId)
        && typeof ref.objectId === 'string' && /^[a-f0-9]{64}$/.test(ref.objectId) && ref.contentHash === ref.objectId
      || ref.kind === 'PREVIZ_PROJECT' && typeof ref.previzProjectId === 'string'
        && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(ref.previzProjectId)
        && (ref.sourceVersion == null || typeof ref.sourceVersion === 'string' && /^[a-f0-9]{64}$/.test(ref.sourceVersion))
      || ref.kind === 'FLOW_ENTITY' && typeof ref.projectId === 'string' && !!ref.projectId && typeof ref.entityType === 'string' && !!ref.entityType && typeof ref.entityId === 'string' && !!ref.entityId
      || ref.kind === 'DOCUMENT' && identity(ref.documentId) && identity(ref.documentSessionId)
        && identity(ref.version) && typeof ref.contentHash === 'string' && /^[a-fA-F0-9]{64}$/.test(ref.contentHash)
      || ref.kind === 'CANVAS_RESOURCE' && identity(ref.projectId) && identity(ref.workspaceId) && identity(ref.resourceId)
        && typeof ref.contentHash === 'string' && /^[a-fA-F0-9]{64}$/.test(ref.contentHash)
      || ref.kind === 'SCRIPT' && typeof ref.scriptId === 'string' && !!ref.scriptId))
}
/** A different numeric ID is not enough to make a malformed read into an executable quote. */
export function assistantPlanningApprovalProjection(value: unknown, runId: string, runRevision: number, requireBudgetScope = false): AssistantPlanningApproval | null {
  if (!object(value) || !identity(value.approvalId) || !identity(value.runId) || value.runId !== runId
    || !integer(value.approvalRevision) || !integer(value.runRevision) || value.runRevision !== runRevision
    || value.scopeKind !== 'ASSISTANT_ITERATION' || !['QUOTED', 'ACCEPTED', 'INVALIDATED'].includes(String(value.status))
    || typeof value.nextAction !== 'string' || !value.nextAction.trim() || value.status === 'QUOTED' && value.nextAction !== 'CONFIRM_APPROVAL'
    || !integer(value.lastEventSeq) || !object(value.quote)) return null
  const q = value.quote
  const bound = assistantBudgetUnits(q.quotedUpperBound as string), estimate = assistantBudgetUnits(q.estimatedAmount as string)
  if (typeof q.quoteId !== 'string' || !q.quoteId.trim() || typeof q.modelCode !== 'string' || !q.modelCode.trim() || q.currencyUnit !== 'BALANCE' || q.precisionScale !== 4
    || bound === null || estimate === null || estimate > bound || q.hardUpperBoundAvailable !== true
    || typeof q.expiresAt !== 'string' || !Number.isFinite(Date.parse(q.expiresAt))
    || q.runBudgetScope !== undefined && !completeScope(q.runBudgetScope) || requireBudgetScope && q.runBudgetScope === undefined) return null
  return value as unknown as AssistantPlanningApproval
}
/** A complete success for the original approve is stronger than a later malformed read. Never build a quote from it. */
export function assistantPlanningApprovalAcceptedReceipt(command: { action: string; body: Record<string, unknown>; result: unknown } | null,
  snapshot: AssistantSessionSnapshot): boolean {
  const run = snapshot.activeRun
  if (!run || ['SUCCEEDED', 'COMPLETED', 'FAILED', 'STOPPED'].includes(run.status) || command?.action !== 'approve' || !object(command.result)) return false
  const b = command.body, r = command.result
  return typeof b.clientRequestId === 'string' && !!b.clientRequestId && identity(b.approvalId)
    && integer(b.expectedApprovalRevision) && integer(b.expectedRunRevision) && r.approvalId === b.approvalId
    && integer(r.approvalRevision) && r.approvalRevision >= b.expectedApprovalRevision
    && r.runId === run.runId && integer(r.runRevision) && r.runRevision >= b.expectedRunRevision
    && run.stateRevision <= r.runRevision && identity(r.authorizationId) && integer(r.authorizationRevision)
    && r.status === 'READY' && r.nextAction === 'ADVANCE_AGENT' && integer(r.lastEventSeq)
}
const keys = ['sessionId', 'runId', 'runRevision', 'approvalId', 'approvalRevision', 'nextAction', 'retryQuote', 'retryMessage', 'planningSubmitted']
const decimal = (value: unknown): string | null => {
  if (typeof value === 'number' && (!Number.isSafeInteger(value) || value < 0)) return null
  return (typeof value === 'string' || typeof value === 'number') && /^(0|[1-9]\d*)$/.test(String(value)) ? String(value) : null
}
function project(sessionId: unknown, details: unknown): AssistantPlanningRequote | null {
  if (typeof sessionId !== 'string' || !sessionId || !details || typeof details !== 'object' || Array.isArray(details)) return null
  const d = details as Record<string, unknown>
  const runId = decimal(d.runId), runRevision = decimal(d.runRevision), approvalId = decimal(d.approvalId), approvalRevision = decimal(d.approvalRevision)
  if (!runId || !runRevision || !approvalId || !approvalRevision || d.nextAction !== 'REQUOTE'
    || d.retryQuote !== true || d.retryMessage !== false || d.planningSubmitted !== false) return null
  return { sessionId, runId, runRevision, approvalId, approvalRevision, nextAction: 'REQUOTE', retryQuote: true, retryMessage: false, planningSubmitted: false }
}
function fromError(error: unknown, sessionId: unknown): AssistantPlanningRequote | null {
  const e = assistantErrorEnvelope(error)
  const outer = error && typeof error === 'object' ? error as { status?: unknown; response?: { status?: unknown }; apiCryptoDecryptionFailed?: boolean; apiResponseFormatInvalid?: boolean } : {}
  const transportStatus = outer.response?.status ?? outer.status
  const transport = /^(?:[1-5]\d{2})$/.test(String(transportStatus)) ? Number(transportStatus) : 0
  if (outer.apiCryptoDecryptionFailed || outer.apiResponseFormatInvalid || e.encrypted === true
    || transportStatus != null && transport !== 409 && !(transport >= 200 && transport < 300 && String(e.code) === '409')
    || e.code != null && String(e.code) !== '409'
    || transportStatus == null && String(e.code) !== '409' || !assistantCommandWasRejected(error)
    || assistantErrorCode(error) !== 'APPROVAL_INPUT_CONTRACT_STALE') return null
  return project(sessionId, (e.data as { details?: unknown } | undefined)?.details)
}
export function assistantPlanningRequoteFromError(error: unknown, action: string, sessionId: string | null,
  runId: string | null, body: Record<string, unknown>): AssistantPlanningRequote | null {
  if (action !== 'approve') return null
  const r = fromError(error, sessionId)
  return r && r.runId === decimal(runId) && r.runRevision === decimal(body.expectedRunRevision)
    && r.approvalId === decimal(body.approvalId) && r.approvalRevision === decimal(body.expectedApprovalRevision) ? r : null
}
export function assistantPlanningRequoteFromRead(error: unknown, snapshot: Snapshot): AssistantPlanningRequote | null {
  const r = fromError(error, snapshot.sessionId), run = snapshot.activeRun
  return r && run && r.runId === decimal(run.runId) && r.runRevision === decimal(run.stateRevision) ? r : null
}
export function assistantPlanningRequoteRecords(value: unknown): AssistantPlanningRequote[] {
  if (value === undefined) return []
  if (!Array.isArray(value) || value.length > 16) throw new Error('旧报价恢复记录异常，请核实当前报价后重试')
  return value.map(row => {
    const projected = project(row?.sessionId, row)
    if (!projected || Object.keys(row).length !== keys.length || Object.keys(row).some(key => !keys.includes(key))) throw new Error('旧报价恢复记录异常，请核实当前报价后重试')
    return projected
  })
}
const tuple = (r: AssistantPlanningRequote) => [r.sessionId, r.runId, r.runRevision, r.approvalId, r.approvalRevision].join(':')
export function assistantPlanningRequoteAdd(records: AssistantPlanningRequote[], next: AssistantPlanningRequote): AssistantPlanningRequote[] {
  if (records.some(r => tuple(r) === tuple(next))) return records
  if (records.length >= 16) throw new Error('旧报价核实记录已满，当前页面暂停写入，请先核实运行状态')
  return [...records, next]
}
export function assistantPlanningRequoteBlocked(records: AssistantPlanningRequote[], snapshot: Snapshot, approval: Approval | null): boolean {
  const run = snapshot.activeRun
  return !!run && records.some(r => r.sessionId === snapshot.sessionId && r.runId === decimal(run.runId)
    && (!approval ? r.runRevision === decimal(run.stateRevision) : r.runId === decimal(approval.runId)
      && r.runRevision === decimal(approval.runRevision) && r.approvalId === decimal(approval.approvalId)
      && r.approvalRevision === decimal(approval.approvalRevision)))
}
/** 仅权威终态/切换运行或当前修订的新报价收口；普通成功、无关拒绝不清。 */
export function assistantPlanningRequoteObserve(records: AssistantPlanningRequote[], snapshot: Snapshot, approval?: Approval & { status: string }): AssistantPlanningRequote[] {
  const run = snapshot.activeRun
  const terminal = !run || ['SUCCEEDED', 'COMPLETED', 'FAILED', 'STOPPED'].includes(run.status)
  const complete = run && approval ? assistantPlanningApprovalProjection(approval, run.runId, run.stateRevision) : null
  const fresh = complete && (complete.status === 'ACCEPTED' || complete.status === 'QUOTED'
    && complete.quote.hardUpperBoundAvailable && Date.parse(complete.quote.expiresAt) > Date.now())
  const next = records.filter(r => r.sessionId !== snapshot.sessionId || !terminal && r.runId === run?.runId
    && !(fresh && (r.runRevision !== decimal(complete!.runRevision) || r.approvalId !== decimal(complete!.approvalId) || r.approvalRevision !== decimal(complete!.approvalRevision))))
  return next.length === records.length ? records : next
}
