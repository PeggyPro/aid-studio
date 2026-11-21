import { createRandomUuid } from '@/utils/clientId'
import { assistantOperationReferenceCurrent, assistantOperationReferenceSource, assistantOperationReferenceFromError, assistantOperationReferenceRecords, assistantOperationReferenceAdd, assistantOperationReferenceObserve, type AssistantOperationReferenceChallenge } from './assistantOperationQuote'
import { assistantOperationConfirmationAvailable } from './assistantExecution'
import { ASSISTANT_OPERATION_QUOTE_LIMIT, assistantOperationQuoteSource, assistantOperationQuoteBody, assistantOperationQuoteSourceRecord, assistantOperationQuoteFromAck, assistantOperationQuoteRecords, assistantOperationQuoteAdd, assistantOperationQuoteObserve, assistantOperationQuoteCurrent, assistantOperationQuoteCaughtUp, type AssistantOperationQuoteSource, type AssistantOperationQuoteRecord } from './assistantOperationQuote'
import { assistantRealtimeReadiness } from './assistantRealtimeReadiness'
import { AssistantBalanceRechargeObserver } from './assistantBalanceRecharge'
import { handleSseErrorRecharge } from '@/utils/api'
import { ASSISTANT_ACCEPTED_CONTROL_LIMIT, assistantIsApprovalControl, assistantAcceptedControlFromAck, assistantAcceptedControlRecords, assistantAcceptedControlMatches, assistantAcceptedControlAdd, assistantAcceptedControlObserve, assistantAcceptedControlProjected, type AssistantAcceptedControl } from './assistantAcceptedControl'
import { assistantConversationContextBlocked, assistantConversationReconciling, assistantConversationTerminal } from './assistantConversationPresentation'
import { assistantCapabilitiesForModule } from './assistantModuleCapabilities'
import { assistantActivityKey, assistantSignalActivity } from './assistantActivities'
import { AssistantRealtimeConnection, type AssistantRealtimeReasoning, type AssistantRealtimeProgress, type AssistantRealtimeDelta, type AssistantRealtimeStreamEnded } from './assistantRealtime'
import { seriesOperationFromCommand, seriesRejectionRemainsUnresolved } from './assistantSeriesAdoption'
import type { AssistantApi } from '~/utils/business/assistant'
import type { AssistantActivity, AssistantCapabilities, AssistantScope, AssistantSessionSnapshot, AssistantSessionSummary } from '~/types/assistant-api'
import { assistantCommandId, assistantDigest, assistantErrorCode, assistantErrorMessage, assistantCommandWasRejected, assistantCommandRecoveryAccessUncertain, assistantErrorState, type AssistantErrorPresentation } from './assistantRequestCoordinator'
import { assistantDisplayTranscript, assistantRememberedTranscript } from './assistantDisplayTranscript'
import { ASSISTANT_DURABLE_PREFIX, clearAssistantActorRecovery, readAssistantDurable, writeAssistantDurable, removeAssistantDurable } from './assistantDurableStorage'
import { assistantLockManager } from './assistantWriteLock'
import type { AssistantCommandLookup } from '~/types/assistant-recovery'
import { AssistantAutomaticIntent } from './assistantAutomaticIntent'
import { AssistantControlQueue, type AssistantQueuedControl } from './assistantControlQueue'
import { assistantPlanningApprovalProjection, assistantPlanningApprovalAcceptedReceipt, assistantPlanningRequoteFromError, assistantPlanningRequoteFromRead, assistantPlanningRequoteRecords, assistantPlanningRequoteBlocked, assistantPlanningRequoteAdd, assistantPlanningRequoteObserve, type AssistantPlanningRequote } from './assistantPlanningRequote'
import type { AssistantPlanningApproval } from '~/types/assistant-api'
import type { AssistantRunBudgetInput } from '~/types/assistant-budget'
import { assistantRunBudgetInput } from './assistantBudget'
import { assistantBudgetPrepareExplicitApproval, assistantBudgetSettleExplicitApproval, assistantBudgetRecoveryHeld } from './assistantBudgetRecovery'
import { assistantContextChangeSignal, assistantContextChangeText } from './assistantContextChange'

export interface AssistantDispatchReceipt {
  readonly action: string
  readonly sessionId: string | null
  readonly clientRequestId: string | null
  readonly bodyDigest: string | null
  readonly dispatchState: 'NOT_DISPATCHED' | 'DISPATCHED'
  readonly earlierUnresolvedSameKey: boolean
  readonly reason: 'BODY_INVALID' | 'BODY_COPY_FAILED' | 'WRITE_ACCESS_UNAVAILABLE' | 'BUSY' | 'PENDING_CONFLICT' | 'DISPOSED' | 'PERSIST_FAILED' | 'LOCAL_SCOPE_REJECTED' | 'ENDPOINT_UNAVAILABLE' | 'API_DISPATCHED'
}
type AssistantDispatchAttempt = { -readonly [Key in keyof AssistantDispatchReceipt]: AssistantDispatchReceipt[Key] }

export interface AssistantSessionState {
  contextConflict: { sessionId: string; runId: string; requestId?: string; contextDigest?: string } | null
  realtimeConnected: boolean
  reasoning: AssistantRealtimeReasoning | null
  publicSummaries: AssistantRealtimeReasoning[]
  transientActivities: AssistantActivity[]
  liveReply: AssistantRealtimeReasoning | null
  progress: AssistantRealtimeProgress | null
  capabilities: AssistantCapabilities | null
  sessions: AssistantSessionSummary[]
  nextSessionCursor: string | null
  sessionPageError?: string | null
  snapshot: AssistantSessionSnapshot | null
  /** Opening paint only. Sends, approvals and questions still require the authoritative snapshot. */
  rememberedTranscript: AssistantSessionSnapshot | null
  loading: boolean
  busy: boolean
  error: string | null
  errorPresentation: AssistantErrorPresentation | null
  offline: boolean
  lastCommandResult: { action: string; body: Record<string, unknown>; result: unknown } | null
  lastRejectedCommand: { action: string; sessionId: string | null; clientRequestId: string; errorCode: string; bodyDigest: string; source: 'ACKNOWLEDGED_REJECTION' | 'LOCAL_NOT_DISPATCHED' } | null
  pendingCommand: { action: string; body: Record<string, unknown> } | null
  queuedControl?: AssistantQueuedControl | null
  writable: boolean
  writeNotice: string | null
  commandRecovery: AssistantCommandLookup | null
  automaticNotice: string | null
  pendingApprovalRunId: string | null
  acceptedControls: AssistantAcceptedControl[]
  planningRequotes: AssistantPlanningRequote[]
  operationReferences: AssistantOperationReferenceChallenge[]
  operationQuotes: AssistantOperationQuoteRecord[]
  pendingOperationQuoteSource: AssistantOperationQuoteSource | null
}

const initialState: AssistantSessionState = { contextConflict: null, realtimeConnected: false, reasoning: null, publicSummaries: [], transientActivities: [], liveReply: null, progress: null, capabilities: null, sessions: [], nextSessionCursor: null, sessionPageError: null, snapshot: null, rememberedTranscript: null, loading: false, busy: false, error: null, errorPresentation: null, offline: false, pendingCommand: null, lastCommandResult: null, lastRejectedCommand: null, writable: false, writeNotice: null, commandRecovery: null, automaticNotice: null, planningRequotes: [], operationReferences: [], operationQuotes: [], pendingOperationQuoteSource: null, acceptedControls: [], pendingApprovalRunId: null }
const commandActions = new Set(['send', 'pause', 'resume', 'stop', 'submitInput', 'suspendInput', 'planningQuote', 'approve', 'refreshContext', 'confirmPlan', 'approveOperation', 'operationQuote', 'reviseBudget', 'cancelTask', 'adopt', 'adoptSeries'])
const activeSnapshotStatuses = new Set(['READY', 'RUNNING', 'PAUSING', 'STOPPING', 'RECONCILING'])
const activeSnapshotRefreshMillis = 15000

function validCommand(value: AssistantSessionState['pendingCommand']): boolean {
  return !value || (commandActions.has(value.action) && !!value.body && typeof value.body === 'object' && !Array.isArray(value.body)
    && typeof (value.body.clientRequestId ?? value.body.responseKey) === 'string' && !!(value.body.clientRequestId ?? value.body.responseKey))
}

/** 一个身份/作用域一个协调器。打开时可先画上次已确认的对话，消息与任务仍以随后的服务端快照为准。 */
export class AssistantSessionStore {
  private readonly balanceRecharge = new AssistantBalanceRechargeObserver()
  private state: AssistantSessionState = initialState
  private listeners = new Set<() => void>()
  private realtime: AssistantRealtimeConnection | null = null
  private realtimeSessionId: string | null = null
  private realtimeRefreshTimer: ReturnType<typeof setTimeout> | null = null
  private transientStreams = new Map<string, string>()
  private endedStreams = new Set<string>()
  private pendingDeltas: AssistantRealtimeDelta[] = []
  private activeStreams: { reasoning?: string; liveReply?: string } = {}
  private references = 0
  private timer: ReturnType<typeof setTimeout> | null = null
  private loadPromise: Promise<void> | null = null
  private loadReadOutcome: { succeeded: boolean } | null = null
  private initializationReadSucceeded = false
  private readRetryFlight: { generation: number; promise: Promise<void> } | null = null
  private successfulReadRevision = 0
  private refreshPromise: Promise<void> | null = null
  private refreshReadOutcome: { succeeded: boolean } | null = null
  private canvasReadError: { generation: number; sessionId: string; errorRevision: number } | null = null
  private errorRevision = 0
  private generation = 0
  private selectionIntent = 0
  private acceptedMutationRevision = 0
  private snapshotMutationRevision = -1
  private lastSnapshotRefreshAt = 0
  private pollPromise: Promise<void> | null = null
  private recoveryPromise: Promise<AssistantCommandLookup | null> | null = null
  private disposed = false
  private questionDraftGuard: ((mode?: 'SAVE' | 'PRESERVE_STALE') => Promise<boolean>) | null = null
  private selectedId: string | null = null
  private openKey: string | null = null
  private readonly storageKey: string
  private readonly transcriptKey: string
  private readonly legacyStorageKey: string
  readonly recoveryPrefix: string
  readonly recoveryScopeKey: string
  private ownsWriteLock = false
  private releaseWriteLock: (() => void) | null = null
  private writeLockAbort: AbortController | null = null
  private storageFailed = false
  private revoked = false
  private ownershipEpoch = 0
  private ownershipReady: Promise<void> | null = null
  private readonly authIdentity: string | undefined
  readonly storageNamespace: string
  private readonly automaticIntent: AssistantAutomaticIntent
  private readonly controlQueue = new AssistantControlQueue(queuedControl => this.patch({ queuedControl }))
  private automaticFlight: Promise<boolean> | null = null
  private continuationFlight: Promise<boolean> | null = null
  private automaticAttempts = new Map<string, Promise<boolean>>()
  private applyingContextRepair = false
  private contextChangeRepairKey = ''

  constructor(readonly api: AssistantApi, readonly scope: AssistantScope, readonly actorId: string, private startFresh = false) {
    this.authIdentity = api.transport.identityKey?.()
    this.storageNamespace = encodeURIComponent(api.transport.storageNamespace?.() || api.transport.kind)
    this.legacyStorageKey = `aid:assistant:${api.transport.kind}:${this.storageNamespace}:${actorId}:${assistantDigest(scope)}`
    this.recoveryPrefix = `${ASSISTANT_DURABLE_PREFIX}${api.transport.kind}:${this.storageNamespace}:${encodeURIComponent(actorId)}:`
    this.recoveryScopeKey = this.recoveryPrefix + assistantDigest(scope)
    this.storageKey = this.recoveryScopeKey + ':session'
    this.automaticIntent = new AssistantAutomaticIntent(this.recoveryScopeKey)
    this.transcriptKey = this.recoveryScopeKey + ':transcript'
  }

  registerQuestionDraftGuard = (guard: (mode?: 'SAVE' | 'PRESERVE_STALE') => Promise<boolean>) => {
    this.questionDraftGuard = guard
    return () => { if (this.questionDraftGuard === guard) this.questionDraftGuard = null }
  }
  prepareContextRefresh = () => this.questionDraftGuard?.() ?? Promise.resolve(true)
  prepareContextReplacement = () => this.questionDraftGuard?.(this.state.contextConflict ? 'PRESERVE_STALE' : 'SAVE') ?? Promise.resolve(true)

  reportContextConflict = (error: unknown, binding?: { runId: string; requestId?: string; contextDigest?: string }): boolean => {
    const snapshot = this.state.snapshot, run = snapshot?.activeRun
    if (!assistantContextChangeSignal(error) || !snapshot || !run || assistantConversationTerminal(run.status)) return false
    const input = snapshot.inputRequest
    // Run views do not promise a contextDigest. Question rejection belongs to the
    // exact input request/schema context, whose digest is authoritative on the wire.
    if (binding && (binding.runId !== run.runId || (binding.requestId
      ? input?.requestId !== binding.requestId || input.contextDigest !== binding.contextDigest || !['OPEN', 'DRAFTING', 'SUSPENDED'].includes(input.status)
      : run.contextDigest !== undefined && binding.contextDigest !== run.contextDigest))) return false
    return this.recordContextConflict(snapshot, run, input)
  }

  /** A context-changed failure is a sync trigger. It must not remain a manual resync prompt. */
  private recordContextConflict(snapshot: AssistantSessionSnapshot, run: NonNullable<AssistantSessionSnapshot['activeRun']>, input: AssistantSessionSnapshot['inputRequest']): boolean {
    this.applyingContextRepair = true
    try {
      this.patch({ contextConflict: { sessionId: snapshot.sessionId, runId: run.runId, requestId: input?.requestId, contextDigest: input?.contextDigest ?? run.contextDigest }, error: null })
    } finally { this.applyingContextRepair = false }
    this.contextChangeRepairKey = ''
    return true
  }

  private scheduleContextRepair(message: string): boolean {
    const key = (this.selectedId || '') + ':' + message
    if (this.contextChangeRepairKey !== key) {
      this.contextChangeRepairKey = key
      void this.retryRead()
    }
    return true
  }

  getSnapshot = () => this.state
  reportError = (error: unknown) => this.patch({ ...assistantErrorState(error) })
  getServerSnapshot = () => initialState
  hasAutomaticIntent = (snapshot: AssistantSessionSnapshot): boolean => !!snapshot.activeRun
    && !this.controlQueue.fenced(snapshot.sessionId, snapshot.activeRun.runId) && this.automaticIntent.candidate(snapshot)
  hasQuoteOnlyIntent = (snapshot: AssistantSessionSnapshot): boolean => this.automaticIntent.quoteOnly(snapshot)
  planningApprovalAccepted = (snapshot: AssistantSessionSnapshot): boolean => assistantAcceptedControlProjected(this.state.acceptedControls, 'approve', snapshot) || assistantPlanningApprovalAcceptedReceipt(this.state.lastCommandResult, snapshot)
  operationApprovalAccepted = (snapshot: AssistantSessionSnapshot): boolean => assistantAcceptedControlProjected(this.state.acceptedControls, 'approveOperation', snapshot)
  operationQuoteReceipt = (snapshot: AssistantSessionSnapshot) => assistantOperationQuoteCurrent(this.state.operationQuotes, snapshot)
  operationQuoteSynchronizing = (snapshot: AssistantSessionSnapshot): boolean => {
    const record = this.operationQuoteReceipt(snapshot)
    return !!record && !this.operationApprovalAccepted(snapshot) && !assistantOperationQuoteCaughtUp(record, snapshot)
  }
  operationReferenceChallenge = (snapshot: AssistantSessionSnapshot) => assistantOperationReferenceCurrent(this.state.operationReferences, snapshot)
  requestOperationQuote = (rendered: AssistantSessionSnapshot, referenceRevalidationToken?: string): Promise<boolean> => {
    const latest = this.state.snapshot
    if (!latest || !this.state.writable || this.state.busy || this.state.pendingCommand
      || latest.sessionId !== rendered.sessionId || latest.revision !== rendered.revision || latest.lastEventSeq !== rendered.lastEventSeq
      || latest.activeRun?.runId !== rendered.activeRun?.runId || latest.activeRun?.stateRevision !== rendered.activeRun?.stateRevision
      || this.operationQuoteSynchronizing(latest) || this.operationApprovalAccepted(latest)) return Promise.resolve(false)
    const source = assistantOperationQuoteSource(latest)
    if (!source) { this.reportError(new Error('当前操作报价或运行状态已变化，请先同步；原任务不会重放')); return Promise.resolve(false) }
    const body = { ...assistantOperationQuoteBody(source, assistantCommandId()), ...(referenceRevalidationToken === undefined ? {} : { referenceRevalidationToken }) }
    return this.execute('operationQuote', body)
  }
  planConfirmationAccepted = (snapshot: AssistantSessionSnapshot): boolean => assistantAcceptedControlProjected(this.state.acceptedControls, 'confirmPlan', snapshot)
  planningRequoteBlocked = (snapshot: AssistantSessionSnapshot, approval: AssistantPlanningApproval | null): boolean =>
    assistantPlanningRequoteBlocked(this.state.planningRequotes, snapshot, approval)
  observePlanningApprovalRejected = (snapshot: AssistantSessionSnapshot, error: unknown): boolean => {
    const current = this.state.snapshot
    if (this.state.pendingCommand || !current || current.sessionId !== snapshot.sessionId || current.activeRun?.runId !== snapshot.activeRun?.runId
      || current.activeRun?.stateRevision !== snapshot.activeRun?.stateRevision || current.revision !== snapshot.revision
      || current.lastEventSeq !== snapshot.lastEventSeq) return false
    const record = assistantPlanningRequoteFromRead(error, current)
    if (!record) return false
    // A read started before a new quote ACK cannot reintroduce the old tuple.
    if (this.planningApprovalAccepted(current)) return true
    const run = current.activeRun
    const command = this.state.lastCommandResult
    const quoted = run && command?.action === 'planningQuote' && command.body.runId === run.runId
      ? assistantPlanningApprovalProjection(command.result, run.runId, run.stateRevision) : null
    if (quoted && quoted.lastEventSeq >= current.lastEventSeq && quoted.status === 'QUOTED' && Date.parse(quoted.quote.expiresAt) > Date.now()
      && (record.approvalId !== quoted.approvalId || record.approvalRevision !== String(quoted.approvalRevision))) return true
    try { this.patch({ planningRequotes: assistantPlanningRequoteAdd(this.state.planningRequotes, record) }); this.persist() }
    catch (cause) { this.storageFailed = true; this.patch({ writable: false, writeNotice: assistantErrorMessage(cause) }) }
    return true
  }
  observePlanningApproval = (snapshot: AssistantSessionSnapshot, approval: AssistantPlanningApproval) => {
    const current = this.state.snapshot
    if (!current || current.sessionId !== snapshot.sessionId || current.activeRun?.runId !== snapshot.activeRun?.runId
      || current.activeRun?.stateRevision !== snapshot.activeRun?.stateRevision || current.revision !== snapshot.revision
      || current.lastEventSeq !== snapshot.lastEventSeq) return
    const next = assistantPlanningRequoteObserve(this.state.planningRequotes, current, approval)
    if (next === this.state.planningRequotes) return
    this.patch({ planningRequotes: next })
    try { this.persist() } catch (error) { this.storageFailed = true; this.patch({ writable: false, writeNotice: assistantErrorMessage(error) }) }
  }
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }

  private patch(next: Partial<AssistantSessionState>) {
    if (this.disposed) return
    // Every writer funnels through here, so a context-changed failure cannot surface as a button.
    if (!this.applyingContextRepair && typeof next.error === 'string' && assistantContextChangeText(next.error)) {
      const snapshot = this.state.snapshot
      const run = snapshot?.activeRun
      const repaired = snapshot && run && !assistantConversationTerminal(run.status)
        ? this.recordContextConflict(snapshot, run, snapshot.inputRequest)
        : false
      if (repaired || this.api.transport.kind === 'CANVAS' && this.scheduleContextRepair(next.error)) next = { ...next, error: null, errorPresentation: null }
    }
    if ('error' in next) this.errorRevision += 1
    if ('error' in next && !('errorPresentation' in next)) {
      next = { ...next, errorPresentation: next.error ? { kind: 'UNKNOWN' } : null }
    }
    this.state = { ...this.state, ...next }
    this.listeners.forEach((listener) => listener())
    this.pumpControl()
  }

  private pumpControl() {
    const snapshot = this.state.snapshot, run = snapshot?.activeRun
    this.controlQueue.pump({ sessionId: snapshot?.sessionId ?? null, runId: run?.runId ?? null,
      revision: run?.stateRevision ?? -1, writable: this.state.writable, busy: this.state.busy,
      pendingCommand: !!this.state.pendingCommand, unavailable: this.disposed || this.revoked || run?.stopRequested === true,
      allowedActions: run?.allowedActions ?? [] }, async (control, revision) => {
      const current = this.state.snapshot
      if (current?.sessionId !== control.sessionId || current.activeRun?.runId !== control.runId || this.disposed || this.revoked) return false
      return this.execute(control.action, { runId: control.runId, expectedRevision: revision, clientRequestId: control.clientRequestId })
    })
  }

  private readRecoveryEnabled(): boolean {
    return this.api.transport.kind === 'CANVAS'
      || this.api.transport.kind === 'MAIN' && this.scope.moduleCode === 'PREVIZ_3DBOX'
  }

  private reportReadError(error: unknown, sessionId: string, generation: number, offline?: boolean) {
    const errorState = assistantErrorState(error)
    if (this.scope.moduleCode === 'PREVIZ_3DBOX'
      && /^Request failed with status code 5\d{2}$/.test(errorState.error)) {
      errorState.error = '暂时无法读取三维会话，正在重新同步；已有内容保留。'
    }
    if (this.readRecoveryEnabled()) {
      this.canvasReadError = { generation, sessionId, errorRevision: this.errorRevision + 1 }
    }
    this.patch({ ...errorState, ...(offline === undefined ? {} : { offline }) })
  }

  private recoverReadError(sessionId: string, generation: number): boolean {
    const readError = this.canvasReadError
    this.canvasReadError = null
    return this.readRecoveryEnabled() && !this.state.pendingCommand
      && this.api.transport.identityKey?.() === this.authIdentity
      && readError?.generation === generation && readError.sessionId === sessionId
      && readError.errorRevision === this.errorRevision
  }

  private persist() {
    if (!this.ownsWriteLock || this.storageFailed) return
    writeAssistantDurable(this.storageKey, { selectedId: this.selectedId, openKey: this.openKey, pendingCommand: this.state.pendingCommand, operationReferences: this.state.operationReferences, operationQuotes: this.state.operationQuotes, pendingOperationQuoteSource: this.state.pendingOperationQuoteSource, planningRequotes: this.state.planningRequotes, acceptedControls: this.state.acceptedControls, pendingApprovalRunId: this.state.pendingApprovalRunId, savedAt: Date.now() })
  }

  private restore() {
    try {
      type Saved = { selectedId?: string; openKey?: string; pendingCommand?: AssistantSessionState['pendingCommand']; planningRequotes?: unknown; operationReferences?: unknown; operationQuotes?: unknown; pendingOperationQuoteSource?: unknown; acceptedControls?: unknown; pendingApprovalRunId?: unknown; savedAt: number }
      let saved = readAssistantDurable<Saved>(this.storageKey)
      let legacy: string | null = null
      if (!saved) {
        legacy = sessionStorage.getItem(this.legacyStorageKey)
        if (legacy) {
          saved = JSON.parse(legacy) as Saved
        }
      }
      if (!saved) return
      if (!Number.isFinite(saved.savedAt) || !validCommand(saved.pendingCommand || null)
        || (saved.selectedId != null && typeof saved.selectedId !== 'string') || (saved.openKey != null && typeof saved.openKey !== 'string')) throw new Error('本地恢复记录不完整')
      const planningRequotes = assistantPlanningRequoteRecords(saved.planningRequotes)
      const acceptedControls = assistantAcceptedControlRecords(saved.acceptedControls)
      const operationReferences = assistantOperationReferenceRecords(saved.operationReferences)
      const operationQuotes = assistantOperationQuoteRecords(saved.operationQuotes)
      const pendingOperationQuoteSource = saved.pendingOperationQuoteSource == null ? null : assistantOperationQuoteSourceRecord(saved.pendingOperationQuoteSource)
      if (saved.pendingCommand?.action === 'operationQuote'
        ? !pendingOperationQuoteSource || assistantDigest(saved.pendingCommand.body) !== assistantDigest(assistantOperationQuoteBody(pendingOperationQuoteSource, String(saved.pendingCommand.body.clientRequestId)))
        : pendingOperationQuoteSource !== null) throw new Error('原操作报价请求与恢复记录不一致')
      if (saved.pendingApprovalRunId != null && (typeof saved.pendingApprovalRunId !== 'string' || !/^[1-9]\d{0,18}$/.test(saved.pendingApprovalRunId)
        || saved.pendingCommand?.action !== 'approve')) throw new Error('原批准运行记录不完整')
      const pendingApprovalRunId = typeof saved.pendingApprovalRunId === 'string' ? saved.pendingApprovalRunId : null
      if (legacy && this.ownsWriteLock) { writeAssistantDurable(this.storageKey, saved); sessionStorage.removeItem(this.legacyStorageKey) }
      // Unknown commands/open requests never expire silently; only idle selection metadata does.
      if (!saved.pendingCommand && !saved.openKey && !planningRequotes.length && !operationQuotes.length && !acceptedControls.length && Date.now() - saved.savedAt > 7 * 86400_000) {
        if (this.ownsWriteLock) removeAssistantDurable(this.storageKey)
        return
      }
      const selectedId = typeof saved.selectedId === 'string' ? saved.selectedId : null
      if (selectedId !== this.selectedId) this.changeSessionSelection(selectedId)
      this.openKey = typeof saved.openKey === 'string' ? saved.openKey : null
      this.patch({ pendingCommand: saved.pendingCommand || null, operationReferences, operationQuotes, pendingOperationQuoteSource, planningRequotes, acceptedControls, pendingApprovalRunId })
    } catch (error) { this.storageFailed = true; this.patch({ writable: false, writeNotice: assistantErrorMessage(error) }) }
  }

  /** Display memory is separate from the command journal and never blocks sending when it cannot be read. */
  private restoreTranscript() {
    if (this.state.snapshot) return
    if (this.startFresh && !this.state.pendingCommand && !this.openKey) {
      if (this.state.rememberedTranscript) this.patch({ rememberedTranscript: null })
      return
    }
    try {
      const saved = readAssistantDurable<unknown>(this.transcriptKey)
      this.patch({ rememberedTranscript: assistantRememberedTranscript(saved, { actorId: this.actorId, scope: this.scope, sessionId: this.selectedId }) })
    } catch { this.patch({ rememberedTranscript: null }) }
  }

  private rememberTranscript(snapshot: AssistantSessionSnapshot) {
    try { writeAssistantDurable(this.transcriptKey, assistantDisplayTranscript(snapshot)) }
    catch { /* A full transcript must not fail the command journal or the authoritative read. */ }
  }

  private beginOwnership() {
    const epoch = ++this.ownershipEpoch
    this.writeLockAbort = new AbortController()
    let settle!: () => void
    this.ownershipReady = new Promise<void>((resolve) => { settle = resolve })
    this.writeLockAbort.signal.addEventListener('abort', settle, { once: true })
    this.patch({ writable: false, writeNotice: '另一标签页正在编辑此助手；当前仅同步查看，待其关闭后接管' })
    const current = () => !this.disposed && !this.revoked && this.references > 0 && epoch === this.ownershipEpoch
    const failed = (error: unknown) => {
      settle()
      if (current() && (error as { name?: string })?.name !== 'AbortError') this.patch({ writable: false, writeNotice: assistantErrorMessage(error) })
    }
    try {
      const locks = assistantLockManager()
      const own = async (initial = false, lost?: AbortSignal) => {
        if (!current() || lost?.aborted) { settle(); return }
        this.ownsWriteLock = true
        lost?.addEventListener('abort', () => {
          if (epoch !== this.ownershipEpoch) return
          this.ownsWriteLock = false
          this.releaseWriteLock?.()
          this.patch({ writable: false, writeNotice: assistantErrorMessage(lost.reason) })
        }, { once: true })
        this.restore()
        this.restoreTranscript()
        // A new owner must not publish a snapshot read before the previous owner finished.
        const ownershipRevision = ++this.acceptedMutationRevision
        settle()
        this.patch({ writable: !this.storageFailed, writeNotice: this.storageFailed ? this.state.writeNotice : null })
        const loading = this.loadPromise
        const loadOutcome = this.loadReadOutcome
        if (!initial && loading) {
          // Initial selection and ownership share the same authoritative startup read.
          await loading
          if (loadOutcome?.succeeded && !this.disposed && !this.revoked && epoch === this.ownershipEpoch
            && this.state.snapshot?.sessionId === this.selectedId && this.snapshotMutationRevision < ownershipRevision) await this.refresh()
        } else if (!initial || !this.loadPromise && !this.initializationReadSucceeded) {
          if (this.initializationReadSucceeded) await this.refresh()
          else await this.load()
        }
        await new Promise<void>((resolve) => { if (this.disposed || epoch !== this.ownershipEpoch || lost?.aborted) resolve(); else this.releaseWriteLock = resolve })
        if (epoch === this.ownershipEpoch) this.ownsWriteLock = false
      }
      // Resolve the initial ownership decision before reading. Observers do not wait
      // for another tab to close; a later handoff still invalidates older reads.
      void locks.request(this.recoveryScopeKey, { ifAvailable: true }, async (lock) => {
        if (!current()) { settle(); return }
        if (lock) return own(true, lock.lost)
        settle()
        void locks.request(this.recoveryScopeKey, { signal: this.writeLockAbort!.signal }, (grant) => {
          if (grant) return own(false, grant.lost)
        }).catch(failed)
      }).catch(failed)
    } catch (error) { failed(error) }
  }

  private async withWriteAccess<T>(operation: () => Promise<T>): Promise<T> {
    if (this.disposed || this.storageFailed) throw new Error(this.state.writeNotice || '当前恢复记录不可写')
    if (this.ownsWriteLock) return operation()
    if (this.references) throw new Error(this.state.writeNotice || '此助手当前在另一标签页编辑')
    return assistantLockManager().request(this.recoveryScopeKey, { ifAvailable: true }, async (lock) => {
      if (!lock) throw new Error('此助手当前在另一标签页编辑')
      this.ownsWriteLock = true
      lock.lost?.addEventListener('abort', () => { this.ownsWriteLock = false }, { once: true })
      try { this.restore(); if (this.storageFailed) throw new Error(this.state.writeNotice || '恢复记录异常'); return await operation() }
      finally { this.ownsWriteLock = false }
    })
  }

  private storageChanged = (event: StorageEvent) => {
    if (this.revoked) return
    if (event.key === this.storageKey && !this.ownsWriteLock) {
      this.restore()
      this.acceptedMutationRevision += 1
      if (this.initializationReadSucceeded) void this.refresh()
      else void this.load()
    }
    if (this.api.transport.identityKey?.() !== this.authIdentity) this.clearPrivateRecovery()
    else if (this.api.transport.kind === 'MAIN' && (event.key === 'token' || event.key === 'user-info')) {
      // Auth transport rejects stale identities; erase this actor's private recovery on logout/switch.
      const user = (() => { try { return JSON.parse(localStorage.getItem('user-info') || '{}') } catch { return {} } })()
      if (!localStorage.getItem('token') || String(user.id || '') !== this.actorId) this.clearPrivateRecovery()
    }
  }

  clearPrivateRecovery = () => {
    this.controlQueue.cancel()
    try { this.automaticIntent.clear() } catch { /* Never grant automation on uncertain private storage. */ }
    this.revoked = true
    this.storageFailed = true
    this.state = { ...initialState, writeNotice: '登录身份已变化，请重新打开助手' }
    this.listeners.forEach((listener) => listener())
    this.stopRealtime()
    this.selectedId = null; this.openKey = null; this.questionDraftGuard = null
    if (this.timer) clearTimeout(this.timer)
    this.timer = null
    this.generation += 1
    this.ownershipEpoch += 1
    this.writeLockAbort?.abort(); this.releaseWriteLock?.()
    this.ownsWriteLock = false
    window.removeEventListener('storage', this.storageChanged)
    try {
      clearAssistantActorRecovery(this.recoveryPrefix)
      for (const key of Object.keys(sessionStorage)) {
        if (key.startsWith(`aid:assistant:${this.api.transport.kind}:${this.storageNamespace}:${this.actorId}:`)
          || key.startsWith(`aid:assistant:questions:${this.api.transport.kind}:${this.storageNamespace}:${this.actorId}:`)) sessionStorage.removeItem(key)
      }
    } catch { this.patch({ writeNotice: '登录身份已变化，本地存储无法清理；请清除此站点的数据后再使用助手' }) }
  }

  acquire = () => {
    this.disposed = false
    this.references += 1
    if (this.references === 1) {
      this.restore()
      this.restoreTranscript()
      window.addEventListener('storage', this.storageChanged)
      window.addEventListener('online', this.wake)
      window.addEventListener('offline', this.wake)
      document.addEventListener('visibilitychange', this.wake)
      this.ensureRealtime()
      this.beginOwnership()
      void this.load()
    }
    return () => {
      this.references -= 1
      if (this.references === 0) {
        this.stopRealtime()
        if (this.timer) clearTimeout(this.timer)
        this.timer = null
        window.removeEventListener('online', this.wake)
        window.removeEventListener('offline', this.wake)
        document.removeEventListener('visibilitychange', this.wake)
        try { if (!this.storageFailed) this.persist() } catch { /* Existing durable command remains intact. */ }
        window.removeEventListener('storage', this.storageChanged)
        this.writeLockAbort?.abort(); this.releaseWriteLock?.(); this.releaseWriteLock = null
        this.ownershipEpoch += 1
        this.ownsWriteLock = false
        this.disposed = true
        this.controlQueue.cancel()
        this.generation += 1
        this.questionDraftGuard = null
        this.state = initialState
        this.selectedId = null
        this.loadPromise = null
        this.refreshPromise = null
        this.pollPromise = null
        this.recoveryPromise = null
      }
    }
  }

  private wake = () => {
    if (this.api.transport.identityKey?.() !== this.authIdentity) { this.clearPrivateRecovery(); return }
    this.patch({ offline: !navigator.onLine })
    this.ensureRealtime()
    this.realtime?.wake()
    if (navigator.onLine && document.visibilityState === 'visible' && !this.state.realtimeConnected) void this.poll()
  }

  load = (): Promise<void> => {
    if (this.revoked) return Promise.resolve()
    if (this.loadPromise) return this.loadPromise
    const outcome = { succeeded: false }
    this.initializationReadSucceeded = false
    this.loadReadOutcome = outcome
    this.canvasReadError = null
    this.patch({ loading: true, error: null })
    const generation = this.generation
    const resumeExisting = Boolean(this.selectedId) && !(this.startFresh && !this.state.pendingCommand && !this.openKey)
    const earlyDetail = resumeExisting ? this.refresh() : null
    const earlyOutcome = earlyDetail ? this.refreshReadOutcome : null
    const earlySessionId = resumeExisting ? this.selectedId : null
    this.loadPromise = (async () => {
      try {
        await this.ownershipReady
        if (generation !== this.generation || this.revoked || this.disposed) return
        const capabilities = assistantCapabilitiesForModule(await this.api.capabilities(true), this.scope.moduleCode)
        if (generation !== this.generation) return
        this.patch({ capabilities })
        this.ensureRealtime()
        if (!capabilities.modules.includes(this.scope.moduleCode)) throw new Error('当前服务未开放此助手')
        const page = await this.api.sessions(this.scope)
        if (generation !== this.generation) return
        const sessions = page.rows.filter((session) => session.moduleCode === this.scope.moduleCode
          && session.mode === this.scope.mode && (session.previzProjectId || '') === (this.scope.previzProjectId || '') && (session.projectId || '') === (this.scope.projectId || '')
          && (session.workspaceId || '') === (this.scope.workspaceId || '')
          && (session.nodeObjectId || '') === (this.scope.nodeObjectId || '')
          && (session.episodeId || '') === (this.scope.episodeId || ''))
        this.patch({ sessions, nextSessionCursor: page.nextCursor || null, sessionPageError: null })
        this.initializationReadSucceeded = true
        const selected = this.startFresh && !this.state.pendingCommand && !this.openKey ? null
          : this.selectedId ? { sessionId: this.selectedId } : sessions[0]
        if (selected) {
          this.selectedId = selected.sessionId
          if (this.state.rememberedTranscript && this.state.rememberedTranscript.sessionId !== selected.sessionId) this.patch({ rememberedTranscript: null })
          this.persist()
          // Ownership may restore a newer selection while this startup read is in flight.
          let refreshedId: string | null
          let usedEarlyDetail = false
          do {
            refreshedId = this.selectedId
            const reading = !usedEarlyDetail && earlyDetail && refreshedId === earlySessionId ? earlyDetail : this.refresh()
            const readOutcome = reading === earlyDetail ? earlyOutcome : this.refreshReadOutcome
            if (reading === earlyDetail) usedEarlyDetail = true
            await reading
            outcome.succeeded = readOutcome?.succeeded === true && this.selectedId === refreshedId
          } while (generation === this.generation && this.selectedId !== refreshedId)
        }
        else { this.patch({ snapshot: null, rememberedTranscript: null }); outcome.succeeded = true }
      } catch (error) { if (generation === this.generation) this.patch({ ...assistantErrorState(error) }) }
      finally { if (generation === this.generation) { this.patch({ loading: false }); this.schedule() } }
    })().finally(() => {
      if (generation === this.generation) this.loadPromise = null
      if (this.loadReadOutcome === outcome) this.loadReadOutcome = null
    })
    return this.loadPromise
  }

  /** An explicit retry joins the current read, then retries once only if that read failed. */
  retryRead = (): Promise<void> => {
    if (this.revoked || this.disposed) return Promise.resolve()
    const generation = this.generation
    if (this.readRetryFlight?.generation === generation) return this.readRetryFlight.promise
    const selectedId = this.selectedId
    const reads = [this.loadPromise, this.refreshPromise].filter((read): read is Promise<void> => read !== null)
    const outcomes = [this.loadReadOutcome, this.refreshReadOutcome]
    const readRevision = this.successfulReadRevision
    const current = () => !this.revoked && !this.disposed && this.generation === generation
      && (selectedId === null || this.selectedId === selectedId)
    const flight = { generation, promise: Promise.resolve() }
    flight.promise = Promise.resolve().then(async () => {
      await Promise.all(reads)
      if (!current()) return
      // Another accepted read may already have satisfied this explicit retry.
      if (this.initializationReadSucceeded && !this.state.error && (reads.length > 0 && outcomes.some(outcome => outcome?.succeeded)
        || this.successfulReadRevision > readRevision)) return
      await this.load()
    }).finally(() => { if (this.readRetryFlight === flight) this.readRetryFlight = null })
    this.readRetryFlight = flight
    return flight.promise
  }

  loadMoreSessions = async () => {
    if (this.revoked) return
    if (!this.state.nextSessionCursor || this.state.loading) return
    const generation = this.generation
    this.patch({ loading: true, sessionPageError: null })
    try {
      const page = await this.api.sessions(this.scope, this.state.nextSessionCursor)
      if (generation !== this.generation) return
      const rows = page.rows.filter((item) => item.moduleCode === this.scope.moduleCode && item.mode === this.scope.mode
        && (item.previzProjectId || '') === (this.scope.previzProjectId || '') && (item.projectId || '') === (this.scope.projectId || '') && (item.episodeId || '') === (this.scope.episodeId || '')
        && (item.workspaceId || '') === (this.scope.workspaceId || '') && (item.nodeObjectId || '') === (this.scope.nodeObjectId || ''))
      const sessions = [...new Map([...this.state.sessions, ...rows].map((item) => [item.sessionId, item])).values()]
      this.patch({ sessions, nextSessionCursor: page.nextCursor || null, sessionPageError: null })
    } catch (error) {
      if (generation === this.generation) this.patch({
        sessionPageError: assistantErrorMessage(error),
        // Losing access is still a conversation-wide concern; ordinary page failures are not.
        ...(assistantCommandRecoveryAccessUncertain(error) ? assistantErrorState(error) : {}),
      })
    }
    finally { if (generation === this.generation) this.patch({ loading: false }) }
  }

  /** Every selection source, including another tab's journal, crosses the same read/stream boundary. */
  private changeSessionSelection(sessionId: string | null, loading = false) {
    this.selectedId = sessionId
    this.generation += 1
    this.refreshPromise = null
    this.pollPromise = null
    this.loadPromise = null
    this.canvasReadError = null
    if (this.timer) clearTimeout(this.timer)
    this.timer = null
    this.stopRealtime()
    this.patch({ snapshot: null, rememberedTranscript: null, error: null, loading,
      contextConflict: null, lastCommandResult: null, lastRejectedCommand: null, commandRecovery: null, automaticNotice: null })
  }

  select = async (sessionId: string) => {
    if (this.revoked) throw new Error('登录身份已变化，请重新打开助手')
    if (this.state.busy || this.state.pendingCommand) throw new Error('请先核实当前操作，再切换会话')
    const intent = ++this.selectionIntent
    if (!await this.prepareContextRefresh()) throw new Error('问题草稿尚未保存，请先处理再切换会话')
    if (this.revoked || this.disposed) throw new Error('会话身份已变化，请重新打开助手')
    if (intent !== this.selectionIntent) return
    if (this.state.busy || this.state.pendingCommand) throw new Error('请先核实当前操作，再切换会话')
    this.startFresh = false
    this.changeSessionSelection(sessionId, true)
    const generation = this.generation
    this.persist()
    try { await this.refresh() }
    finally { if (generation === this.generation && this.selectedId === sessionId) this.patch({ loading: false }) }
    this.schedule()
    return sessionId
  }

  private deleteRequests = new Map<string, { revision: number; key: string }>()
  private deleteFlight: { sessionId: string; promise: Promise<boolean> } | null = null

  /** Delete by identity without selecting a historical session or disturbing the current transcript. */
  deleteSession = (sessionId: string): Promise<boolean> => {
    if (this.deleteFlight) return this.deleteFlight.sessionId === sessionId ? this.deleteFlight.promise : Promise.resolve(false)
    const promise = this.withWriteAccess(async () => {
      if (this.revoked || this.api.transport.identityKey() !== this.authIdentity) throw new Error('登录身份已变化，请重新打开助手')
      const snapshot = this.state.snapshot?.sessionId === sessionId ? this.state.snapshot : null
      if ((!snapshot && !this.state.sessions.some(item => item.sessionId === sessionId)) || this.state.busy || this.state.loading
        || this.state.pendingCommand || !this.state.capabilities?.sessionDeletionAvailable) return false
      const generation = this.generation
      this.patch({ busy: true, ...(snapshot ? { error: null } : {}) })
      try {
        let request = this.deleteRequests.get(sessionId)
        if (!request) {
          // Background list revisions may be old. Read the target without changing selection.
          const target = snapshot ?? await this.api.detail(sessionId, this.actorId, true)
          if (target.sessionId !== sessionId) throw new Error('会话读取结果不匹配，请重试')
          request = { revision: target.revision, key: `assistant-delete:${sessionId}:${target.revision}` }
          this.deleteRequests.set(sessionId, request)
        }
        if (this.revoked || this.disposed || generation !== this.generation || this.api.transport.identityKey() !== this.authIdentity)
          throw new Error('会话身份已变化，请重新打开助手')
        const result = await this.api.deleteSession({ sessionId, expectedRevision: request.revision, clientRequestId: request.key })
        if (!result.deleted || !result.archived || result.sessionId !== sessionId) throw new Error('删除回执不完整，请重新核实会话')
        this.deleteRequests.delete(sessionId)
        if (generation !== this.generation || this.revoked || this.disposed || this.api.transport.identityKey() !== this.authIdentity) return true
        const sessions = this.state.sessions.filter(item => item.sessionId !== sessionId)
        if (this.selectedId !== sessionId) { this.patch({ sessions }); return true }
        this.stopRealtime()
        if (this.timer) clearTimeout(this.timer)
        this.timer = null; this.generation += 1; this.selectedId = null; this.startFresh = true; this.openKey = null
        this.refreshPromise = null; this.pollPromise = null; this.loadPromise = null
        this.controlQueue.cancel(); this.automaticIntent.clear()
        this.patch({ rememberedTranscript: null, snapshot: null, sessions, error: null, loading: false, lastCommandResult: null,
          lastRejectedCommand: null, commandRecovery: null, automaticNotice: null, planningRequotes: [], operationReferences: [], operationQuotes: [], pendingOperationQuoteSource: null, acceptedControls: [], pendingApprovalRunId: null })
        try { removeAssistantDurable(this.transcriptKey) } catch { this.patch({ writeNotice: '对话已删除，本机历史缓存清理失败，请清除此站点缓存' }) }
        this.persist()
        return true
      } catch (cause) {
        if (['ASSISTANT_SESSION_REVISION_CONFLICT', 'ASSISTANT_SESSION_DELETE_BUSY'].includes(assistantErrorCode(cause) || '')) this.deleteRequests.delete(sessionId)
        throw cause
      } finally { this.patch({ busy: false }) }
    }).finally(() => { this.deleteFlight = null })
    this.deleteFlight = { sessionId, promise }
    return promise
  }

  deleteCurrentSession = (): Promise<boolean> => this.state.snapshot ? this.deleteSession(this.state.snapshot.sessionId) : Promise.resolve(false)

  create = async () => {
    this.canvasReadError = null
    try { return await this.withWriteAccess(this.createOwned) }
    catch (error) { this.patch({ ...assistantErrorState(error) }) }
  }

  private createOwned = async () => {
    if (this.state.busy || this.state.pendingCommand) return
    const beforeGuard = this.generation
    if (!await this.prepareContextRefresh()) throw new Error('问题草稿尚未保存，请先处理再新建会话')
    if (this.revoked || this.disposed || beforeGuard !== this.generation) throw new Error('会话身份已变化，请重新打开助手')
    if (this.state.busy || this.state.pendingCommand) return
    this.patch({ busy: true, error: null })
    const generation = this.generation
    let requestKey: string | null = null
    try {
      this.openKey ??= `asst:${createRandomUuid()}`
      requestKey = this.openKey
      this.persist()
      const opened = await this.api.open({ ...this.scope, clientSessionId: this.openKey, title: '新对话' })
      if (generation !== this.generation) return
      this.openKey = null
      this.patch({ sessions: [opened, ...this.state.sessions] })
      this.changeSessionSelection(opened.sessionId, true)
      this.startFresh = false
      this.persist()
      await this.refresh()
      this.schedule()
      return opened.sessionId
    } catch (error) { this.openKey = requestKey; this.patch({ ...assistantErrorState(error) }) }
    finally { this.patch({ busy: false, loading: false }) }
  }

  refresh = (): Promise<void> => {
    if (this.revoked) return Promise.resolve()
    if (this.startFresh && !this.state.pendingCommand && !this.openKey) return Promise.resolve()
    if (this.refreshPromise) return this.refreshPromise
    const id = this.selectedId
    if (!id) return Promise.resolve()
    const generation = this.generation
    const outcome = { succeeded: false }
    this.refreshReadOutcome = outcome
    this.refreshPromise = (async () => {
      await this.ownershipReady
      // 实时权威投影若已覆盖当前变更，旧 HTTP 读取结束后无需再补读。
      const coveredByAcceptedProjection = () => {
        if (this.state.snapshot?.sessionId !== id || this.snapshotMutationRevision !== this.acceptedMutationRevision) return false
        outcome.succeeded = true
        return true
      }
      // 写成功后不能发布写前取得的快照；等待原读取结束，再串行补读一次。
      while (generation === this.generation && id === this.selectedId) {
        const observedMutationRevision = this.acceptedMutationRevision
        const observedErrorRevision = this.errorRevision
        try {
          const snapshot = await this.api.detail(id, this.actorId, true)
          if (generation !== this.generation || id !== this.selectedId) return
          if (observedMutationRevision !== this.acceptedMutationRevision) {
            if (coveredByAcceptedProjection()) return
            continue
          }
          if (!this.acceptSnapshot(snapshot, id, generation)) return
          outcome.succeeded = true
          return
        } catch (error) {
          if (generation !== this.generation || id !== this.selectedId) return
          if (observedMutationRevision !== this.acceptedMutationRevision) {
            if (coveredByAcceptedProjection()) return
            continue
          }
          if (observedErrorRevision !== this.errorRevision) return
          this.reportReadError(error, id, generation)
          return
        }
      }
    })().finally(() => {
      if (generation === this.generation) this.refreshPromise = null
      if (this.refreshReadOutcome === outcome) this.refreshReadOutcome = null
    })
    return this.refreshPromise
  }

  private acceptSnapshot(snapshot: AssistantSessionSnapshot, id: string, generation: number) {
    if (this.revoked || generation !== this.generation || id !== this.selectedId) return
    if (snapshot.sessionId !== id) throw new Error('会话标识不一致')
    if (snapshot.moduleCode !== this.scope.moduleCode || snapshot.mode !== this.scope.mode
      || (snapshot.previzProjectId || '') !== (this.scope.previzProjectId || '') || (snapshot.projectId || '') !== (this.scope.projectId || '')
      || (snapshot.episodeId || '') !== (this.scope.episodeId || '')
      || (snapshot.workspaceId || '') !== (this.scope.workspaceId || '') || (snapshot.nodeObjectId || '') !== (this.scope.nodeObjectId || '')) throw new Error('会话不属于当前作用域')
    const previous = this.state.snapshot
    if (previous?.sessionId === id && previous.lastEventSeq > snapshot.lastEventSeq) return
    if (previous?.sessionId === id && previous.messages.length && snapshot.messages.length
      && previous.messages[0].seq < snapshot.messages[0].seq) {
      const messages = new Map([...previous.messages, ...snapshot.messages].map((item) => [item.id, item]))
      snapshot = { ...snapshot, messages: [...messages.values()].sort((a, b) => a.seq - b.seq), nextMessageCursor: previous.nextMessageCursor }
    }
    this.lastSnapshotRefreshAt = Date.now()
    this.snapshotMutationRevision = this.acceptedMutationRevision
    try { this.automaticIntent.observe(snapshot) } catch { /* Failed intent persistence blocks automation only. */ }
    // 画布的只读恢复成功可收起该次读取错误，不能清除写入拒绝或未知命令提示。
    const recoveredReadError = this.recoverReadError(id, generation)
    const transientPatch: Partial<AssistantSessionState> = {}
    const activeRunId = snapshot.activeRun?.runId
    const finished = !snapshot.activeRun || !activeSnapshotStatuses.has(snapshot.activeRun.status)
    transientPatch.publicSummaries = this.state.publicSummaries.map(stream => {
      if (!stream.live) return stream
      const outerId = stream.outerStepId || (!stream.skillRunId ? stream.stepId : undefined)
      const step = outerId ? snapshot.steps.find(item => item.stepId === outerId) : undefined
      const held = stream.runId !== activeRunId || finished || assistantConversationReconciling(snapshot.activeRun?.status)
        || !!step && (assistantConversationTerminal(step.status) || step.status === 'DECISION_READY' || assistantConversationReconciling(step.status, step.providerAcceptanceState))
      if (!held) return stream
      if (stream.streamId) this.closeStream(stream.streamId)
      return { ...stream, live: false }
    })
    transientPatch.transientActivities = this.state.transientActivities.filter(activity => activity.runId === activeRunId && !finished)
    for (const field of ['reasoning', 'liveReply'] as const) {
      const stream = this.state[field]
      if (!stream) continue
      const task = !stream.skillRunId && stream.mediaTaskId ? snapshot.tasks.find(item => item.taskId === stream.mediaTaskId && item.stepId === stream.stepId) : undefined
      const outerId = stream.outerStepId || (!stream.skillRunId ? stream.stepId : undefined)
      const step = outerId ? snapshot.steps.find(item => item.stepId === outerId) : undefined
      const reconciling = assistantConversationReconciling(snapshot.activeRun?.status)
        || assistantConversationReconciling(step?.status, step?.providerAcceptanceState)
      const ended = finished || task && assistantConversationTerminal(task.status) || step && (assistantConversationTerminal(step.status) || step.status === 'DECISION_READY')
      const unsuccessful = [task?.status, step?.status].some(status => !!status && assistantConversationTerminal(status)
        && !['SUCCEEDED', 'SUCCESS', 'COMPLETED'].includes(status))
      const advanced = !!outerId && snapshot.steps.some(item => /^[1-9][0-9]*$/.test(item.stepId)
        && /^[1-9][0-9]*$/.test(outerId) && BigInt(item.stepId) > BigInt(outerId))
      const awaitingProjection = stream.awaitingCommit && !finished && !unsuccessful && !advanced
      if (field === 'liveReply' && (stream.runId !== activeRunId || ended && !awaitingProjection || reconciling || snapshot.activeRun?.stopRequested)) {
        if (stream.streamId) {
          this.closeStream(stream.streamId)
          this.pendingDeltas = this.pendingDeltas.filter(delta => delta.streamId !== stream.streamId)
        }
        delete this.activeStreams.liveReply
        transientPatch.liveReply = null
        continue
      }
      if (stream.runId !== activeRunId || field === 'liveReply' && snapshot.messages.some(message => message.role === 'assistant'
        && message.runId === activeRunId && message.content === stream.text)) transientPatch[field] = null
      else if (reconciling) {
        const streamId = this.activeStreams[field]
        if (streamId) {
          this.closeStream(streamId)
          this.pendingDeltas = this.pendingDeltas.filter(delta => delta.streamId !== streamId)
          delete this.activeStreams[field]
        }
        transientPatch[field] = null
      } else if (stream.live && ended) {
        if (this.activeStreams[field]) this.closeStream(this.activeStreams[field]!)
        const status = [snapshot.activeRun?.status, task?.status, step?.status].some(value => value === 'FAILED' || value === 'FAILED_PRETASK') ? 'FAILED'
          : [snapshot.activeRun?.status, task?.status, step?.status].some(value => value === 'STOPPED' || value === 'CANCELLED') ? 'STOPPED' : 'COMPLETED'
        transientPatch[field] = field === 'liveReply' && stream.skillRunId ? null : { ...stream, live: false, status, completedAt: new Date().toISOString() }
      }
    }
    if (this.state.progress && (this.state.progress.runId !== activeRunId || finished
      || assistantConversationReconciling(snapshot.activeRun?.status)
      || snapshot.steps.some(step => step.stepId === (this.state.progress?.outerStepId || (!this.state.progress?.skillRunId ? this.state.progress?.stepId : undefined))
        && (assistantConversationTerminal(step.status) || step.status === 'DECISION_READY' || assistantConversationReconciling(step.status, step.providerAcceptanceState))))) transientPatch.progress = null
    const conflict = this.state.contextConflict
    const contextConflict = conflict && conflict.sessionId === snapshot.sessionId && conflict.runId === activeRunId && snapshot.activeRun && !assistantConversationTerminal(snapshot.activeRun.status)
      && (conflict.requestId ? conflict.requestId === snapshot.inputRequest?.requestId && conflict.contextDigest === snapshot.inputRequest.contextDigest
        && ['OPEN', 'DRAFTING', 'SUSPENDED'].includes(snapshot.inputRequest.status) : conflict.contextDigest === snapshot.activeRun.contextDigest) ? conflict : null
    const planningRequotes = assistantPlanningRequoteObserve(this.state.planningRequotes, snapshot)
    const acceptedControls = assistantAcceptedControlObserve(this.state.acceptedControls, snapshot)
    this.contextChangeRepairKey = ''
    this.patch({ snapshot, planningRequotes, acceptedControls, operationReferences: assistantOperationReferenceObserve(this.state.operationReferences, snapshot), operationQuotes: assistantOperationQuoteObserve(this.state.operationQuotes, snapshot), contextConflict, offline: false, ...transientPatch, ...(recoveredReadError ? { error: null } : {}) })
    if (this.api.transport.kind === 'CANVAS' && this.balanceRecharge.observe(previous, snapshot)
      && this.references > 0 && this.authIdentity === this.api.transport.identityKey()) {
      handleSseErrorRecharge({ needRecharge: true, rechargeOwner: 'USER' })
    }
    this.successfulReadRevision += 1
    this.rememberTranscript(snapshot)
    this.ensureRealtime()
    this.flushPendingDeltas()
    return true
  }

  loadOlder = async () => {
    if (this.revoked) return
    const snapshot = this.state.snapshot
    if (!snapshot?.nextMessageCursor || this.state.busy) return
    const generation = this.generation
    const current = () => !this.disposed && !this.revoked && generation === this.generation
      && this.api.transport.identityKey() === this.authIdentity && this.selectedId === snapshot.sessionId
      && this.state.snapshot?.sessionId === snapshot.sessionId
    this.patch({ busy: true })
    try {
      const older = await this.api.detail(snapshot.sessionId, this.actorId, true, snapshot.nextMessageCursor)
      if (!current()) return
      if (older.sessionId !== snapshot.sessionId || older.moduleCode !== this.scope.moduleCode || older.mode !== this.scope.mode
        || (older.projectId || '') !== (this.scope.projectId || '') || (older.episodeId || '') !== (this.scope.episodeId || '')
        || (older.workspaceId || '') !== (this.scope.workspaceId || '') || (older.nodeObjectId || '') !== (this.scope.nodeObjectId || '')) throw new Error('更早的对话不属于当前作用域')
      const latest = this.state.snapshot!
      const messages = [...older.messages, ...latest.messages]
      const unique = new Map(messages.map((message) => [message.id, message]))
      this.patch({ snapshot: { ...latest, messages: [...unique.values()].sort((a, b) => a.seq - b.seq), nextMessageCursor: older.nextMessageCursor } })
    } catch (error) { if (current()) this.patch({ ...assistantErrorState(error) }) }
    finally { if (current()) this.patch({ busy: false }) }
  }

  private stopRealtime() {
    this.realtime?.stop(); this.realtime = null; this.realtimeSessionId = null
    if (this.realtimeRefreshTimer) clearTimeout(this.realtimeRefreshTimer)
    this.realtimeRefreshTimer = null; this.transientStreams.clear(); this.activeStreams = {}; this.endedStreams.clear(); this.pendingDeltas = []
    this.patch({ realtimeConnected: false, reasoning: null, publicSummaries: [], transientActivities: [], liveReply: null, progress: null })
  }

  private ensureRealtime() {
    const sessionId = this.state.snapshot?.sessionId
    // Read-only readiness is independent from write ownership and execution intent.
    const decision = assistantRealtimeReadiness({ references: this.references, disposed: this.disposed, revoked: this.revoked,
      snapshotSessionId: sessionId, selectedSessionId: this.selectedId,
      eventStream: this.state.capabilities?.moduleCapabilities?.find(module => module.moduleCode === this.scope.moduleCode)?.eventStream ?? this.state.capabilities?.eventStream,
      moduleAvailable: !!this.state.capabilities?.modules.includes(this.scope.moduleCode),
      websocketAvailable: !!this.api.transport.websocketUrl, identityCurrent: this.api.transport.identityKey() === this.authIdentity,
      subscriptionSessionId: this.realtimeSessionId, subscriptionExists: !!this.realtime })
    if (decision === 'STOP') { if (this.realtime) this.stopRealtime(); return }
    if (decision === 'KEEP') return
    if (!sessionId) return
    this.stopRealtime()
    const generation = this.generation
    this.realtimeSessionId = sessionId
    this.realtime = new AssistantRealtimeConnection({ api: this.api, sessionId, actorId: this.actorId,
      cursor: () => this.state.snapshot?.sessionId === sessionId ? this.state.snapshot.lastEventSeq : 0,
      current: () => !this.disposed && !this.revoked && generation === this.generation && this.selectedId === sessionId
        && this.api.transport.identityKey() === this.authIdentity,
      connection: connected => {
        if (!connected) { this.transientStreams.clear(); this.activeStreams = {}; this.endedStreams.clear(); this.pendingDeltas = [] }
        this.patch({ realtimeConnected: connected, ...(!connected ? {
          reasoning: this.state.reasoning?.live ? null : this.state.reasoning,
          publicSummaries: this.state.publicSummaries.filter(summary => !summary.live),
          transientActivities: [],
          liveReply: null,
          progress: null
        } : {}) }); this.schedule()
      },
      event: () => {
        // An event may arrive while an older HTTP snapshot is in flight; force that read to retry.
        this.acceptedMutationRevision += 1
        if (!this.realtimeRefreshTimer) this.realtimeRefreshTimer = setTimeout(() => {
          this.realtimeRefreshTimer = null; void this.refresh()
        }, 150)
      },
      projection: snapshot => {
        this.acceptedMutationRevision += 1
        this.acceptSnapshot(snapshot, sessionId, generation)
      },
      delta: delta => this.receiveDelta(delta),
      ended: ended => {
        this.closeStream(ended.streamId)
        this.pendingDeltas = this.pendingDeltas.filter(delta => delta.streamId !== ended.streamId)
        this.patch({ publicSummaries: this.state.publicSummaries.map(summary => summary.runId === ended.runId && summary.streamId === ended.streamId
          ? { ...summary, live: false, status: ended.status, completedAt: ended.occurredAt || new Date().toISOString() } : summary),
          ...(ended.runId === this.state.snapshot?.activeRun?.runId && activeSnapshotStatuses.has(this.state.snapshot.activeRun.status) ? this.activityPatch(ended) : {}) })
        const field = ended.streamType === 'REASONING' ? 'reasoning' : 'liveReply'
        const previous = this.state[field]
        if (ended.runId === previous?.runId && this.activeStreams[field] === ended.streamId) {
          // A normally closed stream remains explicitly provisional until the authoritative
          // projection arrives. Failed/stopped/disconnected output must never linger as a reply.
          const artifactBody = field === 'liveReply' && !!ended.skillRunId
          this.patch({ [field]: field === 'liveReply' && ended.status !== 'COMPLETED' ? null
            : { ...previous, live: false, status: ended.status, awaitingCommit: field === 'liveReply', completedAt: new Date().toISOString() } })
          delete this.activeStreams[field]
          if (artifactBody && !this.realtime?.usesProjection) { this.acceptedMutationRevision += 1; void this.refresh() }
        }
      },
      progress: progress => {
        const run = this.state.snapshot?.activeRun
        const outerId = progress.outerStepId || (!progress.skillRunId ? progress.stepId : undefined)
        const step = outerId ? this.state.snapshot?.steps.find(item => item.stepId === outerId) : undefined
        if (progress.runId === run?.runId && activeSnapshotStatuses.has(run.status) && !run.stopRequested
          && !assistantConversationReconciling(run.status)
          && !assistantConversationReconciling(step?.status, step?.providerAcceptanceState)
          && (!step || !assistantConversationTerminal(step.status) && step.status !== 'DECISION_READY')) this.patch({ progress, ...this.activityPatch(progress) })
      },
      recover: () => this.refresh()
    })
    this.realtime.start()
  }

  private receiveDelta(delta: AssistantRealtimeDelta) {
    if (delta.type === 'REASONING_DELTA' && !['PUBLIC_SUMMARY', 'PROVIDER_REASONING'].includes(delta.reasoningKind || '')) return
    if (this.endedStreams.has(delta.streamId)) return
    const snapshot = this.state.snapshot
    const run = snapshot?.activeRun
    const outerId = delta.outerStepId || (!delta.skillRunId ? delta.stepId : undefined)
    const step = outerId ? snapshot?.steps.find(item => item.stepId === outerId) : undefined
    if (run?.runId === delta.runId && (assistantConversationReconciling(run.status)
      || assistantConversationReconciling(step?.status, step?.providerAcceptanceState))) {
      this.closeStream(delta.streamId)
      return
    }
    // Transient output can precede its coalesced durable view. Keep it bounded until that view arrives.
    if (!run || run.runId !== delta.runId || outerId && (!step || !delta.skillRunId && delta.mediaTaskId && step.mediaTaskId !== delta.mediaTaskId)) {
      this.pendingDeltas.push(delta)
      while (this.pendingDeltas.length > 128 || this.pendingDeltas.reduce((size, item) => size + item.content.length, 0) > 262144) this.pendingDeltas.shift()
      return
    }
    const task = !delta.skillRunId && delta.mediaTaskId ? snapshot?.tasks.find(item => item.taskId === delta.mediaTaskId && item.stepId === delta.stepId) : undefined
    if (!activeSnapshotStatuses.has(run.status) || run.stopRequested || task && assistantConversationTerminal(task.status)
      || step && (assistantConversationTerminal(step.status) || step.status === 'DECISION_READY')) return
    const key = delta.type + ':' + delta.streamId
    if (!this.transientStreams.has(key) && this.transientStreams.size >= 32) this.transientStreams.delete(this.transientStreams.keys().next().value!)
    const text = ((delta.reset ? '' : this.transientStreams.get(key) || '') + delta.content).slice(-131072)
    this.transientStreams.set(key, text)
    const field = delta.type === 'REASONING_DELTA' ? 'reasoning' : 'liveReply'
    const previous = this.state[field]
    const sameStream = this.activeStreams[field] === delta.streamId
    this.activeStreams[field] = delta.streamId
    const stream: AssistantRealtimeReasoning = { runId: delta.runId, skillRunId: delta.skillRunId, stepId: delta.stepId, mediaTaskId: delta.mediaTaskId, streamId: delta.streamId,
      outerStepId: delta.outerStepId, innerStepId: delta.innerStepId,
      reasoningKind: delta.type === 'REASONING_DELTA' ? delta.reasoningKind : undefined, text, live: true,
      startedAt: sameStream && previous?.runId === delta.runId && !delta.reset ? previous.startedAt : new Date().toISOString() }
    this.patch({ [field]: stream, ...this.activityPatch(delta), ...(field === 'reasoning' ? {
      publicSummaries: [...new Map([...this.state.publicSummaries.map(item => [item.streamId, item] as const), [delta.streamId, stream] as const]).values()].slice(-32)
    } : {}) })
  }

  private activityPatch(signal: AssistantRealtimeProgress | AssistantRealtimeDelta | AssistantRealtimeStreamEnded): Partial<AssistantSessionState> {
    const activity = assistantSignalActivity(signal)
    if (!activity) return {}
    const key = assistantActivityKey(activity)
    const index = this.state.transientActivities.findIndex(item => assistantActivityKey(item) === key)
    const previous = this.state.transientActivities[index]
    const terminal = (item: AssistantActivity) => ['COMPLETED', 'FAILED', 'STOPPED'].includes(item.activityKind)
      || assistantConversationTerminal(item.status || '')
    if (previous && (terminal(previous) && !terminal(activity)
      || previous.sourceEventSeq !== undefined && activity.sourceEventSeq !== undefined && activity.sourceEventSeq < previous.sourceEventSeq)) return {}
    // This row is the aggregate for a real step. An unsequenced update cannot erase its known watermark.
    const merged = { ...activity, sourceEventSeq: activity.sourceEventSeq ?? previous?.sourceEventSeq }
    const activities = [...this.state.transientActivities]
    if (index < 0) activities.push(merged)
    else activities[index] = merged
    return { transientActivities: activities.slice(-100) }
  }

  private flushPendingDeltas() {
    const pending = this.pendingDeltas
    this.pendingDeltas = []
    for (const delta of pending) this.receiveDelta(delta)
  }

  private closeStream(streamId: string) {
    this.endedStreams.add(streamId)
    if (this.endedStreams.size > 128) this.endedStreams.delete(this.endedStreams.values().next().value!)
  }

  private schedule() {
    if (this.timer) clearTimeout(this.timer)
    this.timer = null
    if (this.references > 0 && !this.revoked && !this.state.realtimeConnected) this.timer = setTimeout(() => { void this.poll() }, 3000)
  }

  private poll = (): Promise<void> => {
    if (this.pollPromise) return this.pollPromise
    if (!this.references || this.revoked || this.state.realtimeConnected) return Promise.resolve()
    const generation = this.generation
    const snapshot = this.state.snapshot
    // Assign the single-flight promise before any synchronous early completion.
    // Hidden/offline/no-snapshot paths otherwise overwrite finally's null with
    // an already resolved promise and permanently suppress subsequent polling.
    this.pollPromise = Promise.resolve().then(async () => {
      const observedErrorRevision = this.errorRevision
      try {
        if (snapshot && navigator.onLine && document.visibilityState === 'visible') {
          const page = await this.api.events(snapshot.sessionId, snapshot.lastEventSeq)
          if (generation !== this.generation || this.selectedId !== snapshot.sessionId) return
          // Older runtime records may have changed state without emitting an event. A bounded
          // read-only refresh recovers those records without replaying their generation requests.
          const activeSnapshotDue = !!snapshot.activeRun && activeSnapshotStatuses.has(snapshot.activeRun.status)
            && Date.now() - this.lastSnapshotRefreshAt >= activeSnapshotRefreshMillis
          // Throttle attempted fallback reads as well as successful reads, so a failed
          // detail request does not turn the normal event poll into repeated snapshots.
          if (activeSnapshotDue) this.lastSnapshotRefreshAt = Date.now()
          if (page.snapshotRequired || page.nextSeq > snapshot.lastEventSeq || page.events.length || activeSnapshotDue) await this.refresh()
          else if (this.scope.moduleCode === 'PREVIZ_3DBOX'
            && this.recoverReadError(snapshot.sessionId, generation)) {
            // A successful unchanged event page also proves the read connection recovered.
            // Do not force another snapshot or replay commands merely to clear its old error.
            this.patch({ error: null, offline: false })
          }
        }
      } catch (error) {
        if (generation !== this.generation) return
        if (observedErrorRevision !== this.errorRevision
          || snapshot && (this.selectedId !== snapshot.sessionId || this.state.snapshot !== snapshot)) return
        if (snapshot) this.reportReadError(error, snapshot.sessionId, generation, !navigator.onLine)
        else this.patch({ offline: !navigator.onLine, ...assistantErrorState(error) })
        if (assistantErrorCode(error) === 'SNAPSHOT_REQUIRED') await this.refresh()
      } finally {
        if (generation === this.generation) { this.pollPromise = null; this.schedule() }
      }
    })
    return this.pollPromise
  }

  execute = async (action: string, body: Record<string, unknown>): Promise<boolean> => {
    return (await this.executeWithReceipt(action, body)).accepted
  }

  executeWithReceipt = async (action: string, body: Record<string, unknown>): Promise<{ accepted: boolean; receipt: AssistantDispatchReceipt }> => {
    return this.dispatchWithReceipt(action, body, false)
  }

  /** An explicit intent reuses a current quote; viewing history never acquires it. */
  continueHere = (rendered: AssistantSessionSnapshot, quoted?: AssistantPlanningApproval, initialBudget?: AssistantRunBudgetInput): Promise<boolean> => {
    if (this.continuationFlight) return this.continuationFlight
    const currentScope = () => {
      const current = this.state.snapshot, run = current?.activeRun
      return !this.disposed && !this.revoked && this.api.transport.identityKey?.() === this.authIdentity
        && !!current && !!run && this.state.writable && !this.state.busy && !this.state.pendingCommand
        && current.sessionId === rendered.sessionId && run.runId === rendered.activeRun?.runId
        && run.stateRevision === rendered.activeRun.stateRevision && current.revision === rendered.revision
        && current.lastEventSeq === rendered.lastEventSeq && current.inputRequest?.status !== 'SUSPENDED'
        && run.status === 'WAITING_APPROVAL' && !run.stopRequested && !!this.state.capabilities?.planningAvailable
        && ['INITIAL_PLANNING_REQUIRED', 'REPLANNING_REQUIRED', 'CONTINUE_AGENT_REQUIRED'].includes(run.nextAction || '')
        && !assistantConversationContextBlocked(current) && !(this.state.contextConflict?.sessionId === current.sessionId && this.state.contextConflict.runId === run.runId)
    }
    const operation = Promise.resolve().then(async () => {
      if (!currentScope()) return false
      const run = rendered.activeRun!
      try {
        const approval = rendered.approval
        const hasApproval = approval?.scopeKind === 'ASSISTANT_ITERATION' && ['QUOTED', 'ACCEPTED'].includes(approval.status)
        const [approvalRead, budgetRead] = await Promise.allSettled([
          hasApproval ? this.api.approvalDetail(run.runId) : Promise.resolve(null), this.api.budgetDetail(run.runId)])
        if (!currentScope()) return false
        if (approvalRead.status === 'fulfilled' && approvalRead.value) {
          const approval = assistantPlanningApprovalProjection(approvalRead.value, run.runId, run.stateRevision, budgetRead.status === 'rejected')
          if (!approval) throw new Error('当前报价信息尚待核实，请重新读取费用审批')
          this.observePlanningApproval(rendered, approval)
          if (approval.status === 'QUOTED' && Date.parse(approval.quote.expiresAt) > Date.now()) {
            // No second planning/quote for an already executable quote. Confirm only
            // the exact fee/scope the user saw, after a fresh read and the same CAS.
            if (!quoted || !initialBudget || budgetRead.status !== 'rejected' || assistantErrorCode(budgetRead.reason) !== 'RUN_BUDGET_NOT_FOUND'
              || quoted.approvalId !== approval.approvalId || quoted.approvalRevision !== approval.approvalRevision
              || quoted.runId !== approval.runId || quoted.runRevision !== approval.runRevision
              || assistantDigest(quoted.quote) !== assistantDigest(approval.quote) || this.planningRequoteBlocked(rendered, approval)) {
              throw new Error('已有当前有效报价，请核对当前费用与授权范围后确认继续')
            }
            if (initialBudget.scopeDigest !== approval.quote.runBudgetScope?.scopeDigest) throw new Error('当前费用范围已变化，请重新核对')
            const runBudget = assistantRunBudgetInput(approval.quote.runBudgetScope!, { ...initialBudget,
              automaticConsent: initialBudget.mode === 'AUTO' }, { quoteUpperBound: approval.quote.quotedUpperBound })
            if (!currentScope()) return false
            const body = { approvalId: approval.approvalId, expectedApprovalRevision: approval.approvalRevision,
              expectedRunRevision: approval.runRevision, clientRequestId: assistantCommandId(), runBudget,
              ...(assistantBudgetRecoveryHeld(this, rendered, null) ? { confirmRenewedExecution: true } : {}) }
            assistantBudgetPrepareExplicitApproval(this, rendered, null, body)
            return (await this.dispatchWithReceipt('approve', body, false)).accepted
          }
          if (quoted || approval.status !== 'INVALIDATED' && approval.status !== 'QUOTED') throw new Error('当前报价已变化，请重新同步费用审批')
        } else if (approvalRead.status === 'rejected' && assistantErrorCode(approvalRead.reason) !== 'APPROVAL_NOT_FOUND' && !this.observePlanningApprovalRejected(rendered, approvalRead.reason)) {
          throw approvalRead.reason
        }
        if (quoted) throw new Error('当前报价已变化，请重新同步费用审批')
        if (budgetRead.status === 'rejected' && assistantErrorCode(budgetRead.reason) !== 'RUN_BUDGET_NOT_FOUND') throw budgetRead.reason
        if (!currentScope()) return false
        return (await this.dispatchWithReceipt('planningQuote', { runId: run.runId, expectedRunRevision: run.stateRevision,
          clientRequestId: assistantCommandId() }, false, true)).accepted
      } catch (error) { this.reportError(error); if (currentScope()) await this.refresh(); return false }
    }).finally(() => { if (this.continuationFlight === operation) this.continuationFlight = null })
    this.continuationFlight = operation
    return operation
  }

  /** Effects may only continue this page's accepted intent after a bounded journal proof. */
  executeAutomatic = (action: 'planningQuote' | 'approve' | 'confirmPlan' | 'approveOperation', body: Record<string, unknown>, rendered: AssistantSessionSnapshot, approvalProof?: AssistantPlanningApproval): Promise<boolean> => {
    if (action === 'approve' && !this.automaticIntent.authorizesApproval(rendered, body, approvalProof)) {
      this.patch({ automaticNotice: '请核对当前报价，并明确确认本次费用后继续。' }); return Promise.resolve(false)
    }
    if (!this.hasAutomaticIntent(rendered)) return Promise.resolve(false)
    const signature = assistantDigest({ action, sessionId: rendered.sessionId, runId: rendered.activeRun?.runId,
      revision: rendered.activeRun?.stateRevision, approvalId: body.approvalId, approvalRevision: body.expectedApprovalRevision,
      planRevision: body.planRevision, definitionDigest: body.definitionDigest })
    const prior = this.automaticAttempts.get(signature)
    if (prior) return prior
    if (this.automaticFlight) return Promise.resolve(false)
    const current = () => !this.disposed && !this.revoked && this.state.writable && !this.state.busy && !this.state.pendingCommand
      && !assistantConversationContextBlocked(rendered) && !(this.state.contextConflict?.sessionId === rendered.sessionId && this.state.contextConflict.runId === rendered.activeRun?.runId)
      && this.api.transport.identityKey?.() === this.authIdentity && this.state.snapshot?.sessionId === rendered.sessionId
      && this.state.snapshot.activeRun?.runId === rendered.activeRun?.runId
      && this.state.snapshot.activeRun?.stateRevision === rendered.activeRun?.stateRevision
      && this.state.snapshot.revision === rendered.revision && this.state.snapshot.lastEventSeq === rendered.lastEventSeq
    const operation = Promise.resolve().then(async () => {
      if (!current()) return false
      try {
        const proof = await this.automaticIntent.prove(this.api, rendered)
        if (proof !== 'OWNER' || !current() || !this.hasAutomaticIntent(rendered)
          || action === 'approve' && !this.automaticIntent.authorizesApproval(rendered, body, approvalProof)) {
          this.patch({ automaticNotice: proof === 'OBSERVER' ? '当前页面仅同步查看。请查看当前费用并明确确认继续。' : '自动继续的操作归属尚待核实，请重新同步或明确确认继续。' })
          return false
        }
        this.patch({ automaticNotice: null })
        return (await this.dispatchWithReceipt(action, body, true, false, approvalProof)).accepted
      } catch (error) {
        this.patch({ automaticNotice: '自动继续的操作归属尚待核实：' + assistantErrorMessage(error) })
        return false
      }
    }).finally(() => { if (this.automaticFlight === operation) this.automaticFlight = null })
    this.automaticFlight = operation
    this.automaticAttempts.set(signature, operation)
    if (this.automaticAttempts.size > 64) this.automaticAttempts.delete(this.automaticAttempts.keys().next().value!)
    return operation
  }

  private dispatchWithReceipt = async (action: string, body: Record<string, unknown>, automatic: boolean, continuation = false, approvalProof?: AssistantPlanningApproval): Promise<{ accepted: boolean; receipt: AssistantDispatchReceipt }> => {
    const attempt: AssistantDispatchAttempt = { action, sessionId: this.selectedId, clientRequestId: null,
      bodyDigest: null, dispatchState: 'NOT_DISPATCHED', earlierUnresolvedSameKey: false, reason: 'BODY_COPY_FAILED' }
    let accepted = false
    try {
      const originalKey = body?.clientRequestId ?? body?.responseKey
      attempt.clientRequestId = typeof originalKey === 'string' ? originalKey : null
      attempt.sessionId = typeof body?.sessionId === 'string' ? body.sessionId : this.selectedId
      this.captureEarlierCommand(attempt)
      // The journal and outgoing request share this immutable-at-call-time copy, never a caller's later edits.
      const submittedBody = JSON.parse(JSON.stringify(body)) as Record<string, unknown>
      attempt.bodyDigest = assistantDigest(submittedBody)
      attempt.sessionId = typeof submittedBody?.sessionId === 'string' ? submittedBody.sessionId : this.selectedId
      const key = submittedBody?.clientRequestId ?? submittedBody?.responseKey
      attempt.clientRequestId = typeof key === 'string' ? key : null
      this.captureEarlierCommand(attempt)
      attempt.reason = this.disposed ? 'DISPOSED' : 'WRITE_ACCESS_UNAVAILABLE'
      accepted = await this.withWriteAccess(() => this.executeOwned(action, submittedBody, attempt, automatic, continuation, approvalProof))
    }
    catch (error) { this.patch({ ...assistantErrorState(error) }) }
    return { accepted, receipt: Object.freeze({ ...attempt }) }
  }

  private captureEarlierCommand(attempt: AssistantDispatchAttempt) {
    const pending = this.state.pendingCommand
    if (pending?.action === attempt.action && attempt.clientRequestId !== null
      && (pending.body.clientRequestId ?? pending.body.responseKey) === attempt.clientRequestId) attempt.earlierUnresolvedSameKey = true
  }

  private executeOwned = async (action: string, body: Record<string, unknown>, attempt: AssistantDispatchAttempt, automatic: boolean, continuation: boolean, approvalProof?: AssistantPlanningApproval): Promise<boolean> => {
    this.captureEarlierCommand(attempt)
    if (!validCommand({ action, body })) { attempt.reason = 'BODY_INVALID'; this.patch({ error: '操作标识或类型不完整，未发送请求' }); return false }
    if (this.state.busy) { attempt.reason = 'BUSY'; return false }
    const pending = this.state.pendingCommand
    const queued = this.controlQueue.current()
    if (queued && queued.clientRequestId !== body.clientRequestId && !pending) {
      attempt.reason = 'LOCAL_SCOPE_REJECTED'; return false
    }
    if (pending && (pending.action !== action || assistantDigest(pending.body) !== assistantDigest(body))) {
      attempt.reason = 'PENDING_CONFLICT'
      this.patch({ error: '上次操作尚待核实，请先重试原操作' }); return false
    }
    if (this.disposed) { attempt.reason = 'DISPOSED'; return false }
    if (automatic && (!this.state.snapshot || !this.hasAutomaticIntent(this.state.snapshot)
      || typeof body.runId === 'string' && body.runId !== this.state.snapshot.activeRun?.runId
      || typeof body.expectedRunRevision === 'number' && body.expectedRunRevision !== this.state.snapshot.activeRun?.stateRevision
      || action === 'approve' && !this.automaticIntent.authorizesApproval(this.state.snapshot, body, approvalProof))) {
      attempt.reason = 'LOCAL_SCOPE_REJECTED'
      this.patch({ automaticNotice: '当前费用或操作意图已变化，请核对后明确确认继续。' }); return false
    }
    if (!pending && assistantIsApprovalControl(action)) {
      if (assistantAcceptedControlMatches(this.state.acceptedControls, action, body, this.selectedId)) {
        attempt.reason = 'LOCAL_SCOPE_REJECTED'
        this.patch({ error: '已受理本次确认，正在等待最新执行状态；请勿重复确认' }); return false
      }
      if (this.state.acceptedControls.length >= ASSISTANT_ACCEPTED_CONTROL_LIMIT) {
        attempt.reason = 'LOCAL_SCOPE_REJECTED'
        this.patch({ error: '已受理确认记录已满，请先同步权威状态；原操作仍可核实' }); return false
      }
    }
    if (!pending && assistantIsApprovalControl(action)) {
      const snapshot = this.state.snapshot, run = snapshot?.activeRun
      if (!snapshot || snapshot.sessionId !== this.selectedId || !run || run.stopRequested || run.status !== 'WAITING_APPROVAL'
        || (action !== 'approve' && body.runId !== run.runId)) {
        attempt.reason = 'LOCAL_SCOPE_REJECTED'
        this.patch({ error: '运行状态已更新，请同步当前确认内容后继续' }); return false
      }
    }
    if (!pending && action === 'approveOperation') {
      const current = this.state.snapshot, run = current?.activeRun, approval = current?.approval
      if (!current || !run || this.operationReferenceChallenge(current) || !assistantOperationConfirmationAvailable(current) || !approval || approval.scopeKind !== 'PLAN_EXECUTION' || approval.status !== 'QUOTED'
        || approval.approvalId !== body.approvalId || approval.approvalRevision !== body.expectedApprovalRevision
        || run.stateRevision !== body.expectedRunRevision || this.operationQuoteSynchronizing(current)
        || automatic && (body.confirmRenewedExecution === true || assistantBudgetRecoveryHeld(this, current, null))) {
        attempt.reason = 'LOCAL_SCOPE_REJECTED'; this.patch({ error: '当前操作费用或执行状态已变化，请同步后明确确认' }); return false
      }
    }
    const operationSource = action === 'operationQuote' ? pending ? this.state.pendingOperationQuoteSource : assistantOperationReferenceSource(assistantOperationQuoteSource(this.state.snapshot), body, this.state.operationReferences) : null
    if (action === 'operationQuote' && (!operationSource || this.operationQuoteSynchronizing(this.state.snapshot!) && !pending
      || assistantDigest(body) !== assistantDigest(assistantOperationQuoteBody(operationSource, String(body.clientRequestId)))
      || !pending && (automatic || this.state.operationQuotes.length >= ASSISTANT_OPERATION_QUOTE_LIMIT || this.operationApprovalAccepted(this.state.snapshot!)))) {
      attempt.reason = 'LOCAL_SCOPE_REJECTED'; this.patch({ error: '当前操作报价尚待核实，请同步或核实原请求；不会确认费用' }); return false
    }
    const submittedSnapshot = this.state.snapshot
    const submittedRunId = this.state.snapshot?.activeRun?.runId ?? null
    if (!pending && action === 'approve' && this.state.snapshot && assistantPlanningRequoteBlocked(this.state.planningRequotes, this.state.snapshot,
      { approvalId: body.approvalId, approvalRevision: body.expectedApprovalRevision, runId: submittedRunId, runRevision: body.expectedRunRevision })) {
      attempt.reason = 'LOCAL_SCOPE_REJECTED'
      this.patch({ error: '原报价的助手输入约定已更新，请重新报价后再确认；对话与历史保留' }); return false
    }
    const generation = this.generation
    this.canvasReadError = null
    this.patch({ busy: true, error: null, commandRecovery: null, lastRejectedCommand: null, pendingCommand: { action, body }, pendingOperationQuoteSource: operationSource,
      pendingApprovalRunId: action === 'approve' ? (pending ? this.state.pendingApprovalRunId : submittedRunId) : null })
    try {
      attempt.reason = 'PERSIST_FAILED'
      this.automaticIntent.prepare(action, body, this.state.snapshot, !!pending, continuation, automatic)
      this.persist()
      const api = this.api
      if (action === 'adoptSeries') {
        attempt.reason = 'LOCAL_SCOPE_REJECTED'
        const operation = seriesOperationFromCommand(body)
        if (api.transport.kind !== 'MAIN' || this.scope.moduleCode !== 'SCREENWRITER' || this.scope.episodeId !== '0'
          || operation.scope.actorId !== this.actorId || operation.scope.projectId !== this.scope.projectId
          || operation.scope.sessionId !== this.state.snapshot?.sessionId) throw new Error('整批采用不属于当前身份或项目级会话，原操作已保留')
      }
      attempt.reason = 'ENDPOINT_UNAVAILABLE'
      const runCommand = action === 'pause' || action === 'resume' || action === 'stop'
      const endpoint = (api as unknown as Record<string, unknown>)[runCommand ? 'runCommand' : action]
      if (typeof endpoint !== 'function') throw new Error('操作接口不可用，未发送请求')
      attempt.dispatchState = 'DISPATCHED'; attempt.reason = 'API_DISPATCHED'
      const result: unknown = await endpoint.apply(api, runCommand ? [action, body] : [body])
      if (generation !== this.generation) return false
      const quoteAck = action === 'planningQuote' ? assistantPlanningApprovalProjection(result, String(body.runId),
        (result as AssistantPlanningApproval | null)?.runRevision ?? NaN) : null
      if (action === 'planningQuote' && (!quoteAck || quoteAck.status !== 'QUOTED' || !Number.isSafeInteger(body.expectedRunRevision)
        || quoteAck.runRevision < (body.expectedRunRevision as number) || !submittedSnapshot || submittedSnapshot.sessionId !== attempt.sessionId
        || submittedSnapshot.activeRun?.runId !== body.runId || !pending && quoteAck.lastEventSeq < submittedSnapshot.lastEventSeq)) {
        throw new Error('原报价回执尚不完整，请使用原请求核实；不会重新报价或确认费用')
      }
      // An idempotent replay returns the original receipt, even after later events.
      // Resolve that command without promoting its historical quote to current approval.
      if (pending && quoteAck && submittedSnapshot && quoteAck.lastEventSeq < submittedSnapshot.lastEventSeq) {
        this.patch({ pendingCommand: null, pendingOperationQuoteSource: null, pendingApprovalRunId: null,
          lastCommandResult: null })
        this.persist()
        await this.refresh()
        return true
      }
      const operationQuote = action === 'operationQuote' ? assistantOperationQuoteFromAck(operationSource!, body, result) : null
      const planningRequotes = quoteAck && this.state.snapshot?.sessionId === attempt.sessionId
        && quoteAck.lastEventSeq >= this.state.snapshot.lastEventSeq
        ? assistantPlanningRequoteObserve(this.state.planningRequotes, this.state.snapshot, quoteAck) : this.state.planningRequotes
      const acceptedControl = assistantAcceptedControlFromAck(action, body, result, attempt.sessionId, submittedSnapshot, !!pending)
      if (acceptedControl?.action === 'approve' && this.state.pendingApprovalRunId && acceptedControl.runId !== this.state.pendingApprovalRunId)
        throw new Error('原确认运行回执不一致，请核实原操作')
      const acceptedControls = acceptedControl ? assistantAcceptedControlAdd(this.state.acceptedControls, acceptedControl) : this.state.acceptedControls
      const operationQuotes = operationQuote ? assistantOperationQuoteAdd(this.state.operationQuotes, operationQuote) : this.state.operationQuotes
      this.acceptedMutationRevision += 1
      this.patch({ acceptedControls, planningRequotes, operationQuotes, pendingOperationQuoteSource: null, pendingCommand: null, pendingApprovalRunId: null, lastCommandResult: { action, body, result }, ...(action === 'refreshContext' ? { contextConflict: null } : {}) })
      try { this.persist() } catch (storageError) {
        this.storageFailed = true
        this.patch({ writable: false, writeNotice: assistantErrorMessage(storageError), pendingCommand: { action, body }, pendingOperationQuoteSource: operationSource, pendingApprovalRunId: acceptedControl?.action === 'approve' ? acceptedControl.runId : null })
        throw storageError
      }
      // A received success stays accepted even if optional page-local intent storage fails.
      try { this.automaticIntent.settle(action, body, result, this.state.snapshot, automatic); this.patch({ automaticNotice: null }) }
      catch (intentError) { this.patch({ automaticNotice: '已受理本次操作，当前页面自动继续待核实：' + assistantErrorMessage(intentError) }) }
      if (!automatic && ['resume', 'approve', 'confirmPlan', 'approveOperation'].includes(action) && submittedSnapshot?.activeRun)
        this.controlQueue.release(submittedSnapshot.sessionId, submittedSnapshot.activeRun.runId)
      if (action === 'approveOperation' && submittedSnapshot) {
        try { assistantBudgetSettleExplicitApproval(this, submittedSnapshot) } catch { this.patch({ automaticNotice: '本次操作已受理，费用确认恢复记录仍待核实' }) }
      }
      // A quote already returns the authoritative approval; snapshot recovery need not delay its confirmation.
      if (action === 'planningQuote' && this.api.transport.kind === 'CANVAS') void this.refresh()
      else await this.refresh()
      return true
    } catch (error) {
      if (generation !== this.generation) return false
      const dispatched = attempt.dispatchState === 'DISPATCHED'
      const preserveEarlier = pending && (!dispatched || assistantCommandRecoveryAccessUncertain(error))
      if (!preserveEarlier && (!dispatched || (assistantCommandWasRejected(error) && !(action === 'adoptSeries' && seriesRejectionRemainsUnresolved(assistantErrorCode(error)))))) this.patch({ pendingCommand: null, pendingOperationQuoteSource: null, pendingApprovalRunId: null,
        lastRejectedCommand: { action, sessionId: attempt.sessionId, clientRequestId: String(body.clientRequestId ?? body.responseKey), errorCode: assistantErrorCode(error),
          bodyDigest: attempt.bodyDigest!, source: dispatched ? 'ACKNOWLEDGED_REJECTION' : 'LOCAL_NOT_DISPATCHED' } })
      else this.patch({ pendingCommand: pending ?? { action, body } })
      if (!this.state.pendingCommand) {
        const reference = dispatched && action === 'operationQuote' && operationSource ? assistantOperationReferenceFromError(error, operationSource, body) : null
        if (reference) {
          try { this.patch({ operationReferences: assistantOperationReferenceAdd(this.state.operationReferences, reference) }) }
          catch (cause) { this.storageFailed = true; this.patch({ writable: false, writeNotice: assistantErrorMessage(cause) }) }
        }
        const requote = dispatched ? assistantPlanningRequoteFromError(error, action, attempt.sessionId, submittedRunId, body) : null
        if (requote) {
          try { this.patch({ planningRequotes: assistantPlanningRequoteAdd(this.state.planningRequotes, requote) }) }
          catch (cause) { this.storageFailed = true; this.patch({ writable: false, writeNotice: assistantErrorMessage(cause) }) }
        }
        try { this.automaticIntent.reject(action, body) } catch { /* Storage uncertainty cannot authorize automation. */ }
      }
      const presented = assistantErrorState(error)
      if ((action === 'operationQuote' || action === 'approveOperation') && presented.errorPresentation.kind === 'CREATION') {
        presented.errorPresentation = { ...presented.errorPresentation, kind: 'UNKNOWN' }
      }
      this.patch(presented)
      if (assistantCommandWasRejected(error)) this.reportContextConflict(error)
      try { this.persist() } catch (storageError) { this.patch({ ...assistantErrorState(storageError) }) }
      await this.refresh()
      return false
    } finally { if (generation === this.generation) this.patch({ busy: false }) }
  }

  inspectPending = (): Promise<AssistantCommandLookup | null> => {
    if (this.recoveryPromise) return this.recoveryPromise
    const pending = this.state.pendingCommand, sessionId = this.selectedId, generation = this.generation
    if (!pending || !sessionId || this.revoked) return Promise.resolve(null)
    const key = String(pending.body.clientRequestId ?? pending.body.responseKey)
    this.recoveryPromise = (async () => {
      try {
        const recovery = await this.api.commandLookup(sessionId, key)
        if (generation !== this.generation || this.selectedId !== sessionId || this.state.pendingCommand !== pending) return null
        this.patch({ commandRecovery: recovery, error: null })
        if (recovery.recoveryDirective === 'REFRESH_AUTHORITATIVE_STATE') {
          this.acceptedMutationRevision += 1
          await this.refresh()
        }
        return recovery
      } catch (error) {
        if (generation === this.generation) this.patch({ ...assistantErrorState(error) })
        return null
      }
    })().finally(() => { if (generation === this.generation) this.recoveryPromise = null })
    return this.recoveryPromise
  }

  retryPending = async () => {
    const pending = this.state.pendingCommand
    // Lookup never clears a private command or recreates one from public outcome metadata.
    // Explicit replay still uses the original endpoint/body/key to verify payload identity and obtain its real result.
    if (pending && await this.inspectPending() && this.state.pendingCommand === pending) await this.execute(pending.action, pending.body)
  }

  control = (action: 'pause' | 'resume' | 'stop') => {
    const snapshot = this.state.snapshot, run = snapshot?.activeRun
    if (!snapshot || !run || !this.state.writable || this.revoked || this.disposed) return Promise.resolve(false)
    if (action !== 'resume') {
      if (run.stopRequested || !run.allowedActions.includes(action.toUpperCase())) return Promise.resolve(false)
      // Revoke only page-local automation immediately; retain the original unresolved command and receipt.
      try { this.automaticIntent.clear() }
      catch (error) { this.patch({ automaticNotice: '已暂停本页自动继续；本地意图保存失败：' + assistantErrorMessage(error) }) }
      return this.controlQueue.request(action, snapshot.sessionId, run.runId, assistantCommandId())
    }
    return this.execute(action, { runId: run.runId, expectedRevision: run.stateRevision, clientRequestId: assistantCommandId() })
  }
}
