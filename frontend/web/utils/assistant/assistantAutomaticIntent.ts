import type { AssistantPlanningApproval, AssistantSessionSnapshot } from '~/types/assistant-api'
import type { AssistantApi } from '~/utils/business/assistant'
import type { AssistantCommandReceipt, AssistantRecoveryScope } from '~/types/assistant-recovery'
import { assistantDigest } from './assistantRequestCoordinator'
import { assistantPlanningApprovalProjection } from './assistantPlanningRequote'

type CommandEvidence = { action: string; key: string; bodyDigest: string; runId: string }
type QuoteEvidence = { runId: string; runRevision: number; approvalId: string; approvalRevision: number;
  lastEventSeq: number; quoteDigest: string }
type Intent = { sessionId: string; runId: string | null; key: string; action: string; bodyDigest: string;
  purpose: 'EXECUTION' | 'QUOTE_ONLY'; accepted: boolean; cursor?: string; journalDigest?: string;
  inFlight?: CommandEvidence; quote?: QuoteEvidence }
const triggers = new Set(['send', 'resume', 'submitInput', 'approve', 'confirmPlan', 'approveOperation'])
const revokes = new Set(['pause', 'stop', 'suspendInput', 'refreshContext'])
const terminal = new Set(['COMPLETED', 'SUCCEEDED', 'FAILED', 'STOPPED', 'CANCELLED', 'CANCELED'])
const types: Record<string, string[]> = {
  send: ['MESSAGE_SEND', 'MESSAGE_INTERJECTION'], resume: ['RUN_RESUME'], submitInput: ['INPUT_SUBMIT'],
  approve: ['APPROVAL_CONFIRM'], confirmPlan: ['PLAN_CONFIRM'], approveOperation: ['OPERATION_APPROVAL_CONFIRM'],
  planningQuote: ['PLANNING_QUOTE'], operationQuote: ['OPERATION_QUOTE'], reviseBudget: ['BUDGET_REVISE']
}
// Draft/view saves do not authorize execution or transfer its ownership.
const controls = new Set(Object.values(types).flat().concat(['RUN_PAUSE', 'RUN_STOP', 'INPUT_SUSPEND', 'CONTEXT_REFRESH']))
function sameScope(scope: AssistantRecoveryScope, snapshot: AssistantSessionSnapshot): boolean {
  return scope.sessionId === snapshot.sessionId && scope.sessionRevision === snapshot.revision
    && scope.lastEventSeq === snapshot.lastEventSeq && scope.activeRunId === snapshot.activeRun?.runId
    && scope.activeRunRevision === snapshot.activeRun?.stateRevision
}
function accepted(receipt: AssistantCommandReceipt): boolean {
  return receipt.status === 'COMPLETED' && !receipt.errorCode
}

/** Page-local intent is only a candidate. The server journal proves every automatic dispatch. */
export class AssistantAutomaticIntent {
  private value: Intent | null = null
  private readonly storage: Storage | null
  private readonly storageKey: string
  private blocked = false
  constructor(scopeKey: string) {
    this.storageKey = scopeKey + ':page-execution-intent'
    try { this.storage = typeof window === 'undefined' ? null : window.sessionStorage }
    catch { this.storage = null; this.blocked = true; return }
    if (!this.storage) return
    // sessionStorage can be copied by opening/duplicating a tab. Ordinary navigation
    // and history loading never inherit execution; only this tab's reload can recover it.
    const reload = performance.getEntriesByType?.('navigation').some(entry => (entry as PerformanceNavigationTiming).type === 'reload')
    if (!reload) { try { this.storage.removeItem(this.storageKey) } catch { this.blocked = true }; return }
    try {
      const raw = this.storage.getItem(this.storageKey)
      if (!raw) return
      const parsed = JSON.parse(raw) as { version?: number; value?: Intent }
      const v = parsed.value
      if (raw.length > 1000000 || ![1, 2].includes(parsed.version ?? 0) || !v || typeof v.sessionId !== 'string'
        || !(v.runId === null || typeof v.runId === 'string') || typeof v.key !== 'string' || !v.key
        || !types[v.action] || typeof v.bodyDigest !== 'string' || typeof v.accepted !== 'boolean'
        || v.cursor !== undefined && !/^\d{1,19}$/.test(v.cursor)
        || v.journalDigest !== undefined && !/^[a-fA-F0-9]{64}$/.test(v.journalDigest)
        || parsed.version === 2 && !['EXECUTION', 'QUOTE_ONLY'].includes(v.purpose)) throw new Error()
      // Old records did not distinguish viewing fees from payment consent.
      // Preserve their original key/body, but require a fresh explicit confirmation.
      if (parsed.version !== 2) { this.value = { ...v, purpose: 'QUOTE_ONLY', inFlight: undefined, quote: undefined }; return }
      if (v.inFlight && (!types[v.inFlight.action] && !revokes.has(v.inFlight.action)
        || typeof v.inFlight.key !== 'string' || !v.inFlight.key || typeof v.inFlight.bodyDigest !== 'string'
        || v.inFlight.runId !== v.runId)) throw new Error()
      if (v.quote && (v.quote.runId !== v.runId || !/^\d+$/.test(v.quote.approvalId)
        || !Number.isSafeInteger(v.quote.runRevision) || v.quote.runRevision < 0
        || !Number.isSafeInteger(v.quote.approvalRevision) || v.quote.approvalRevision < 0
        || !Number.isSafeInteger(v.quote.lastEventSeq) || v.quote.lastEventSeq < 0 || typeof v.quote.quoteDigest !== 'string')) throw new Error()
      this.value = v
    } catch { this.blocked = true }
  }
  private save(value: Intent | null) {
    try {
    if (!this.storage) throw new Error('当前页面不能可靠保存执行意图，请明确确认本次操作')
    if (value) {
      const raw = JSON.stringify({ version: 2, value })
      if (raw.length > 1000000) throw new Error('当前页面的执行恢复记录过大，未发起新的操作')
      this.storage.setItem(this.storageKey, raw)
      if (this.storage.getItem(this.storageKey) !== raw) throw new Error('当前页面的执行意图未可靠保存，未发起新的操作')
    } else this.storage.removeItem(this.storageKey)
    this.value = value; this.blocked = false
    } catch (error) { this.blocked = true; throw error }
  }
  prepare(action: string, body: Record<string, unknown>, snapshot: AssistantSessionSnapshot | null,
    retry: boolean, _continuation: boolean, automatic = false) {
    if (retry || !snapshot) return // Never replace the original UNKNOWN key/body/provenance.
    const commandKey = String(body.clientRequestId ?? body.responseKey), bodyDigest = assistantDigest(body)
    if (!automatic && (action === 'planningQuote' || action === 'operationQuote' || triggers.has(action))) {
      const priorFeeOnly = this.quoteOnly(snapshot) && action !== 'send' && action !== 'approve' && action !== 'approveOperation'
      const purpose = action === 'planningQuote' || action === 'operationQuote' || priorFeeOnly ? 'QUOTE_ONLY' : 'EXECUTION'
      this.save({ sessionId: snapshot.sessionId, runId: action === 'planningQuote' || action === 'operationQuote' ? snapshot.activeRun?.runId ?? null : null,
        key: commandKey, action, bodyDigest, purpose, accepted: false })
    } else if (this.value?.accepted && this.value.sessionId === snapshot.sessionId
      && this.value.runId === snapshot.activeRun?.runId && (types[action] || revokes.has(action))) {
      // The exact local dispatch is durable. Neither observations nor a late unrelated
      // ACK can advance an execution anchor; UNKNOWN recovery matches this same body.
      this.save({ ...this.value, inFlight: { action, key: commandKey, bodyDigest, runId: this.value.runId! } })
    }
  }
  settle(action: string, body: Record<string, unknown>, result: unknown, snapshot: AssistantSessionSnapshot | null, _automatic: boolean) {
    const saved = this.value
    if (!saved || !snapshot || saved.sessionId !== snapshot.sessionId) return
    const commandKey = body.clientRequestId ?? body.responseKey, bodyDigest = assistantDigest(body)
    const matching = saved.key === commandKey && saved.action === action && saved.bodyDigest === bodyDigest
    const inFlight = saved.inFlight
    const advancing = saved.accepted && !!inFlight && inFlight.key === commandKey && inFlight.action === action
      && inFlight.bodyDigest === bodyDigest && inFlight.runId === saved.runId
    if (!matching && !advancing) return
    if (revokes.has(action)) { this.save(null); return }
    const record = result as { runId?: string; snapshot?: AssistantSessionSnapshot; budgetPolicy?: { runId?: string } } | null
    const runId = record?.runId ?? record?.snapshot?.activeRun?.runId ?? record?.budgetPolicy?.runId
    if (typeof runId !== 'string' || !runId || advancing && runId !== saved.runId) return
    const projected = action === 'planningQuote'
      ? assistantPlanningApprovalProjection(result, runId, (result as AssistantPlanningApproval | null)?.runRevision ?? NaN) : null
    if (action === 'planningQuote' && (!projected || projected.status !== 'QUOTED')) return
    if (matching && !(triggers.has(action) || action === 'planningQuote' || action === 'operationQuote')) return
    this.save({ ...saved, key: String(commandKey), action, bodyDigest, runId, accepted: true,
      cursor: undefined, journalDigest: undefined, inFlight: undefined,
      quote: projected ? { runId, runRevision: projected.runRevision, approvalId: projected.approvalId,
        approvalRevision: projected.approvalRevision, lastEventSeq: projected.lastEventSeq, quoteDigest: assistantDigest(projected.quote) }
        : action === 'operationQuote' ? undefined : saved.quote })
  }
  reject(action: string, body: Record<string, unknown>) {
    const v = this.value, commandKey = body.clientRequestId ?? body.responseKey
    if (v?.action === action && v.key === commandKey && !v.accepted) this.save(null)
    else if (v?.inFlight?.action === action && v.inFlight.key === commandKey && v.inFlight.bodyDigest === assistantDigest(body)) this.save({ ...v, inFlight: undefined })
  }
  quoteOnly(snapshot: AssistantSessionSnapshot): boolean {
    return !!snapshot.activeRun && (this.blocked || !!this.value && this.value.purpose === 'QUOTE_ONLY'
      && this.value.sessionId === snapshot.sessionId && (this.value.runId === null || this.value.runId === snapshot.activeRun.runId))
  }
  authorizesApproval(snapshot: AssistantSessionSnapshot, body: Record<string, unknown>, approval?: AssistantPlanningApproval): boolean {
    const saved = this.value, q = saved?.quote, run = snapshot.activeRun
    const projected = approval && run && assistantPlanningApprovalProjection(approval, run.runId, run.stateRevision)
    return this.candidate(snapshot) && !!run && !!q && !!projected && projected.status === 'QUOTED'
      && q.runId === run?.runId && q.runRevision === run.stateRevision && q.approvalId === body.approvalId
      && q.approvalRevision === body.expectedApprovalRevision && q.runRevision === body.expectedRunRevision
      && q.approvalId === projected.approvalId && q.approvalRevision === projected.approvalRevision
      && q.quoteDigest === assistantDigest(projected.quote) && q.lastEventSeq <= snapshot.lastEventSeq
      && projected.lastEventSeq <= snapshot.lastEventSeq && Date.parse(projected.quote.expiresAt) > Date.now()
  }
  observe(snapshot: AssistantSessionSnapshot) {
    const v = this.value
    if (v?.accepted && (v.sessionId !== snapshot.sessionId || v.runId !== snapshot.activeRun?.runId
      || terminal.has(snapshot.activeRun.status))) this.save(null)
  }
  clear() { if (this.value) this.save(null) }
  candidate(snapshot: AssistantSessionSnapshot): boolean {
    return !this.blocked && this.value?.purpose === 'EXECUTION' && !!this.value.accepted && this.value.sessionId === snapshot.sessionId && this.value.runId === snapshot.activeRun?.runId
      && !terminal.has(snapshot.activeRun.status) && !snapshot.activeRun.stopRequested && snapshot.inputRequest?.status !== 'SUSPENDED'
  }
  async prove(api: AssistantApi, snapshot: AssistantSessionSnapshot): Promise<'OWNER' | 'OBSERVER' | 'UNKNOWN'> {
    let saved = this.value
    if (!saved || !this.candidate(snapshot)) return 'OBSERVER'
    const lookup = await api.commandLookup(saved.sessionId, saved.key)
    if (this.value !== saved || !sameScope(lookup.scope, snapshot)) return 'UNKNOWN'
    const anchor = lookup.receipt
    if (!anchor || lookup.observation !== 'OBSERVED' || !accepted(anchor)
      || anchor.clientRequestId !== saved.key || !types[saved.action]?.includes(anchor.commandType) || anchor.outcome.runId !== saved.runId
      || !anchor.payloadDigest || !/^[a-fA-F0-9]{64}$/.test(anchor.payloadDigest)
      || saved.journalDigest !== undefined && saved.journalDigest !== anchor.payloadDigest) return 'UNKNOWN'
    // The server digest is opaque: it is NOT assistantDigest's canonical JSON string.
    // Fix the first authoritative value for this already acknowledged key, including across reload.
    if (!saved.journalDigest) { this.save({ ...saved, journalDigest: anchor.payloadDigest }); saved = this.value! }
    // First page supplies the stable upper boundary. Its historical items are not scanned.
    const boundary = await api.commandPage({ sessionId: saved.sessionId, limit: 1 })
    if (this.value !== saved || !sameScope(boundary.scope, snapshot)) return 'UNKNOWN'
    if (BigInt(boundary.throughCommandId) < BigInt(anchor.commandId)) return 'UNKNOWN'
    let cursor = saved.cursor && BigInt(saved.cursor) > BigInt(anchor.commandId) ? saved.cursor : anchor.commandId
    if (BigInt(cursor) > BigInt(boundary.throughCommandId)) return 'UNKNOWN'
    // Bound work since our last acknowledged/proved anchor, never the entire conversation.
    for (let page = 0; BigInt(cursor) < BigInt(boundary.throughCommandId); page++) {
      if (page === 4) return 'UNKNOWN'
      const tail = await api.commandPage({ sessionId: saved.sessionId, afterCommandId: cursor,
        throughCommandId: boundary.throughCommandId, expectedSessionRevision: boundary.scope.sessionRevision,
        expectedLastEventSeq: boundary.scope.lastEventSeq, limit: 50 })
      if (this.value !== saved || !sameScope(tail.scope, snapshot) || tail.throughCommandId !== boundary.throughCommandId) return 'UNKNOWN'
      for (const item of tail.items) {
        if (!controls.has(item.commandType)) continue
        if (accepted(item)) { this.save(null); return 'OBSERVER' }
        if (!(item.status === 'COMPLETED' && item.errorCode)
          && !['FAILED', 'REJECTED', 'CANCELLED', 'CANCELED'].includes(item.status)) return 'UNKNOWN'
      }
      if (!tail.hasMore) { cursor = boundary.throughCommandId; break }
      if (!tail.nextCommandId || BigInt(tail.nextCommandId) <= BigInt(cursor)) return 'UNKNOWN'
      cursor = tail.nextCommandId
    }
    if (this.value !== saved) return 'UNKNOWN'
    this.save({ ...saved, cursor })
    return 'OWNER'
  }
}
