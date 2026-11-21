import type { AssistantSessionSnapshot } from '~/types/assistant-api'

type ControlAction = 'approve' | 'confirmPlan' | 'approveOperation'
export interface AssistantAcceptedControl {
  action: ControlAction
  sessionId: string
  clientRequestId: string
  runId: string
  expectedRunRevision: number
  runRevision: number
  lastEventSeq: number
  approvalId?: string
  expectedApprovalRevision?: number
  approvalRevision?: number
  authorizationId?: string
  authorizationRevision?: number
  planRevision?: number
  definitionDigest?: string
  stepId?: string
  reservationId?: string | null
}

// Unclosed receipts are never evicted or expired. A full journal blocks new approvals,
// while its original pending command remains replayable with the same complete body/key.
export const ASSISTANT_ACCEPTED_CONTROL_LIMIT = 256
export const assistantIsApprovalControl = (action: string): action is ControlAction =>
  action === 'approve' || action === 'confirmPlan' || action === 'approveOperation'
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const revision = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const id = (value: unknown): value is string => typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n
const digest = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)

/** Only the original endpoint's complete ACK consumes a tuple. Lookup metadata does not. */
export function assistantAcceptedControlFromAck(action: string, body: Record<string, unknown>, result: unknown,
  sessionId: string | null, snapshot: AssistantSessionSnapshot | null, replay: boolean): AssistantAcceptedControl | null {
  if (!assistantIsApprovalControl(action)) return null
  const fail = (): never => { throw new Error('确认回执尚不完整，请核实原操作；未创建新的确认请求') }
  if (!id(sessionId) || typeof body.clientRequestId !== 'string' || !body.clientRequestId || !object(result)
    || !id(result.runId) || !revision(body.expectedRunRevision) || !revision(result.runRevision)
    || result.runRevision !== body.expectedRunRevision + 1 || !revision(result.lastEventSeq)) return fail()
  if (action !== 'approve' && body.runId !== result.runId) return fail()
  if (!replay && snapshot?.activeRun && (snapshot.sessionId !== sessionId || snapshot.activeRun.runId !== result.runId
    || result.lastEventSeq <= snapshot.lastEventSeq)) return fail()
  const record: AssistantAcceptedControl = { action, sessionId, clientRequestId: body.clientRequestId, runId: result.runId,
    expectedRunRevision: body.expectedRunRevision, runRevision: result.runRevision, lastEventSeq: result.lastEventSeq }
  if (action === 'confirmPlan') {
    if (!revision(body.planRevision) || !digest(body.definitionDigest) || result.planRevision !== body.planRevision
      || result.planStatus !== 'APPROVED' || result.runStatus !== 'WAITING_APPROVAL' || result.nextAction !== 'CONTINUE_AGENT_REQUIRED') return fail()
    return { ...record, planRevision: body.planRevision, definitionDigest: body.definitionDigest }
  }
  if (!id(body.approvalId) || !revision(body.expectedApprovalRevision) || result.approvalId !== body.approvalId
    || !revision(result.approvalRevision) || result.approvalRevision !== body.expectedApprovalRevision + 1) return fail()
  Object.assign(record, { approvalId: body.approvalId, expectedApprovalRevision: body.expectedApprovalRevision, approvalRevision: result.approvalRevision })
  if (action === 'approve') {
    if (result.status !== 'READY' || result.nextAction !== 'ADVANCE_AGENT' || !id(result.authorizationId) || !revision(result.authorizationRevision)) return fail()
    return { ...record, authorizationId: result.authorizationId, authorizationRevision: result.authorizationRevision }
  }
  if (result.status !== 'ACCEPTED' || result.nextAction !== 'EXECUTE_PLAN_OPERATION' || !id(result.stepId)
    || (result.reservationId != null && !id(result.reservationId))) return fail()
  const projected = snapshot?.approval
  if (!replay && projected?.approvalId === body.approvalId && projected.stepId != null && projected.stepId !== result.stepId) return fail()
  return { ...record, stepId: result.stepId, reservationId: result.reservationId == null ? null : result.reservationId }
}

export function assistantAcceptedControlRecords(value: unknown): AssistantAcceptedControl[] {
  if (value === undefined) return []
  if (!Array.isArray(value) || value.length > ASSISTANT_ACCEPTED_CONTROL_LIMIT + 1) throw new Error('本地确认记录容量或格式异常，请先核实已受理操作')
  return value.map((row: unknown) => {
    if (!object(row) || !assistantIsApprovalControl(String(row.action))) throw new Error('本地确认记录不完整')
    const action = row.action as ControlAction
    const body = { clientRequestId: row.clientRequestId, runId: row.runId, expectedRunRevision: row.expectedRunRevision,
      approvalId: row.approvalId, expectedApprovalRevision: row.expectedApprovalRevision, planRevision: row.planRevision, definitionDigest: row.definitionDigest }
    const result = { ...row, status: action === 'approve' ? 'READY' : 'ACCEPTED', nextAction: action === 'approve' ? 'ADVANCE_AGENT' : action === 'confirmPlan' ? 'CONTINUE_AGENT_REQUIRED' : 'EXECUTE_PLAN_OPERATION', planStatus: 'APPROVED', runStatus: 'WAITING_APPROVAL' }
    const parsed = assistantAcceptedControlFromAck(action, body, result, row.sessionId as string, null, true)!
    // Reject injected/unknown fields rather than persisting arbitrary endpoint data.
    if (Object.keys(row).some((key) => !(key in parsed))) throw new Error('本地确认记录含未知字段')
    return parsed
  })
}

export function assistantAcceptedControlMatches(records: AssistantAcceptedControl[], action: string, body: Record<string, unknown>, sessionId: string | null): boolean {
  return records.some((record) => record.action === action && record.sessionId === sessionId
    && (action === 'confirmPlan' ? record.runId === body.runId && record.planRevision === body.planRevision && record.definitionDigest === body.definitionDigest
      : record.approvalId === body.approvalId && record.expectedApprovalRevision === body.expectedApprovalRevision
        && (action === 'approve' || record.runId === body.runId)))
}

export function assistantAcceptedControlAdd(records: AssistantAcceptedControl[], record: AssistantAcceptedControl): AssistantAcceptedControl[] {
  const same = records.find((item) => item.action === record.action && item.sessionId === record.sessionId && item.clientRequestId === record.clientRequestId)
  if (same) {
    if (JSON.stringify(same) !== JSON.stringify(record)) throw new Error('原确认回执不一致，请核实原操作')
    return records
  }
  if (records.length > ASSISTANT_ACCEPTED_CONTROL_LIMIT) throw new Error('已受理确认记录已满，请同步权威状态后继续')
  return [...records, record]
}

/** Only a later authoritative terminal/new-run projection closes receipts; never a timer or another ACK. */
export function assistantAcceptedControlObserve(records: AssistantAcceptedControl[], snapshot: AssistantSessionSnapshot): AssistantAcceptedControl[] {
  const run = snapshot.activeRun
  if (!run) return records
  const next = records.filter((record) => record.sessionId !== snapshot.sessionId || snapshot.lastEventSeq < record.lastEventSeq
    || (run.runId === record.runId && (run.stateRevision < record.runRevision || !['COMPLETED', 'SUCCEEDED', 'FAILED', 'STOPPED', 'CANCELLED', 'CANCELED'].includes(run.status))))
  return next.length === records.length ? records : next
}

export function assistantAcceptedControlProjected(records: AssistantAcceptedControl[], action: ControlAction, snapshot: AssistantSessionSnapshot): boolean {
  const run = snapshot.activeRun
  if (!run) return false
  if (action === 'approve') return records.some((row) => row.action === action && row.sessionId === snapshot.sessionId
    && row.runId === run.runId && run.stateRevision <= row.runRevision)
  const body = action === 'confirmPlan' ? { runId: run.runId, planRevision: snapshot.plan?.revision, definitionDigest: snapshot.plan?.definitionDigest }
    : { runId: run.runId, approvalId: snapshot.approval?.approvalId, expectedApprovalRevision: snapshot.approval?.approvalRevision }
  // Keep the receipt for duplicate suppression, but stop projecting a pending confirmation
  // once the authoritative run, event stream and plan have caught up with that receipt.
  if (action === 'confirmPlan') return records.some((row) => assistantAcceptedControlMatches([row], action, body, snapshot.sessionId)
    && (run.stateRevision < row.runRevision || snapshot.lastEventSeq < row.lastEventSeq || snapshot.plan?.status === 'PROPOSED'))
  return assistantAcceptedControlMatches(records, action, body, snapshot.sessionId)
}
