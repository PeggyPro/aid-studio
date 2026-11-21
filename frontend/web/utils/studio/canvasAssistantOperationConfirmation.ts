import type { AssistantSessionSnapshot } from '@/types/assistant-api'
import type { AssistantRunBudgetPolicy } from '@/types/assistant-budget'
import type { AssistantSessionStore } from '@/utils/assistant/assistantSessionStore'
import { assistantBudgetUnits } from '@/utils/assistant/assistantBudget'
import { assistantDigest } from '@/utils/assistant/assistantRequestCoordinator'
import { assistantConfirmOperation, assistantOperationQuoteAvailable, assistantOperationQuoteCaughtUp, assistantOperationQuoteSource } from '@/utils/assistant/assistantOperationQuote'

const flights = new WeakMap<AssistantSessionStore, Promise<boolean>>()

/** One explicit canvas confirmation may renew its expired quote, within the displayed operation and charge. */
export function confirmCanvasAssistantOperation(store: AssistantSessionStore, rendered: AssistantSessionSnapshot,
  budget: AssistantRunBudgetPolicy | null, signal: AbortSignal): Promise<boolean> {
  const existing = flights.get(store)
  if (existing) return existing
  const promise = confirm(store, rendered, budget, signal).finally(() => { if (flights.get(store) === promise) flights.delete(store) })
  flights.set(store, promise)
  return promise
}

async function confirm(store: AssistantSessionStore, rendered: AssistantSessionSnapshot,
  budget: AssistantRunBudgetPolicy | null, signal: AbortSignal): Promise<boolean> {
  if (signal.aborted || !budget) return false
  const approval = rendered.approval
  const deadline = Date.parse(approval?.expiresAt || '')
  // Valid quotes keep the ordinary authoritative confirmation path.
  if (Number.isFinite(deadline) && deadline > Date.now()) return assistantConfirmOperation(store, rendered, budget)
  const source = assistantOperationQuoteSource(rendered)
  const before = store.getSnapshot()
  if (!source || source.status !== 'QUOTED' || !Number.isFinite(deadline)
    || assistantDigest(assistantOperationQuoteSource(before.snapshot)) !== assistantDigest(source)
    || !before.writable || before.busy || before.pendingCommand || !before.capabilities?.executionAvailable
    || budget.mode !== 'MANUAL' || !assistantOperationQuoteAvailable(rendered, budget, Date.now())
    || store.operationReferenceChallenge(rendered) || store.operationApprovalAccepted(rendered)) return false
  const sameScope = () => {
    const snapshot = store.getSnapshot().snapshot
    return !signal.aborted && snapshot?.sessionId === source.sessionId && snapshot.activeRun?.runId === source.runId
      && snapshot.activeRun.status === 'WAITING_APPROVAL' && !snapshot.activeRun.stopRequested
      && snapshot.approval?.stepId === source.stepId && snapshot.approval.planRevision === source.planRevision
      && snapshot.approval.modelCode === approval?.modelCode && snapshot.approval.toolCode === approval?.toolCode
      && snapshot.approval.operationKind === approval?.operationKind
  }
  try {
    if (!await store.requestOperationQuote(rendered) || !sameScope()) return false
    const state = store.getSnapshot(), snapshot = state.snapshot!
    const record = store.operationQuoteReceipt(snapshot)
    if (!record || state.lastCommandResult?.action !== 'operationQuote'
      || record.clientRequestId !== state.lastCommandResult.body.clientRequestId
      || assistantDigest(record.source) !== assistantDigest(source)
      || record.result.referenceRevalidated || record.result.executionSubmitted !== false) return false
    const oldCharge = assistantBudgetUnits(source.quotedUpperBound), charge = assistantBudgetUnits(record.result.quote.quotedUpperBound)
    if (charge === null || oldCharge === null || charge > oldCharge) {
      store.reportError(new Error('更新后的费用高于本次确认金额，请核对新费用后再确认生成。'))
      return false
    }
    await store.refresh()
    if (!sameScope()) return false
    if (!assistantOperationQuoteCaughtUp(record, store.getSnapshot().snapshot!)) {
      const synchronized = await new Promise<boolean>(resolve => {
        let unsubscribe = () => {}
        const finish = (value: boolean) => { clearTimeout(timer); unsubscribe(); signal.removeEventListener('abort', cancel); resolve(value) }
        const cancel = () => finish(false)
        const check = () => {
          if (!sameScope()) finish(false)
          else if (assistantOperationQuoteCaughtUp(record, store.getSnapshot().snapshot!)) finish(true)
        }
        const timer = setTimeout(() => finish(false), 10_000)
        signal.addEventListener('abort', cancel, { once: true })
        unsubscribe = store.subscribe(check)
        check()
      })
      if (!synchronized || !sameScope()) return false
    }
    const freshBudget = await store.api.budgetDetail(source.runId)
    const current = store.getSnapshot().snapshot
    if (!sameScope() || !current || !assistantOperationQuoteCaughtUp(record, current)) return false
    if (freshBudget.authorizationId !== budget.authorizationId || freshBudget.authorizationRevision !== budget.authorizationRevision
      || freshBudget.policyRevision !== budget.policyRevision || freshBudget.scopeDigest !== budget.scopeDigest
      || freshBudget.mode !== budget.mode || current.approval?.modelCode !== approval?.modelCode
      || current.approval?.toolCode !== approval?.toolCode || current.approval?.operationKind !== approval?.operationKind
      || Date.parse(current.approval?.expiresAt || '') <= Date.now() + 1000) {
      store.reportError(new Error('本次操作或费用授权已变化，请核对当前内容后再确认生成。'))
      return false
    }
    return assistantConfirmOperation(store, current, freshBudget)
  } catch (error) {
    if (!signal.aborted) store.reportError(error)
    return false
  }
}
