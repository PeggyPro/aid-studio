'use client'

import { useEffect, useState } from 'react'
import { useAssistantBudgetExpiryRefresh } from '~/hooks/assistant/useAssistantBudgetExpiryRefresh'
import type { AssistantCapabilities, AssistantPlanningApproval, AssistantSessionSnapshot } from '~/types/assistant-api'
import type { AssistantRunBudgetInput, AssistantRunBudgetPolicy } from '~/types/assistant-budget'
import type { AssistantSessionStore } from '~/utils/assistant/assistantSessionStore'
import { assistantBudgetUnits, assistantConversationRunBudgetInput } from '~/utils/assistant/assistantBudget'
import { assistantMainApprovalReadScope } from '~/utils/assistant/assistantApprovalReadScope'
import { assistantCommandId, assistantErrorCode, assistantErrorMessage, assistantErrorEnvelope } from '~/utils/assistant/assistantRequestCoordinator'
import { assistantContextChangeSignal } from '~/utils/assistant/assistantContextChange'
import { assistantPlanningReceipt } from '~/utils/assistant/assistantPlanningReceipt'
import { assistantPlanningApprovalProjection } from '~/utils/assistant/assistantPlanningRequote'
import { useAssistantBudgetRenewal } from './useAssistantBudgetRenewal'
import { assistantBudgetApprovalAccepted, assistantBudgetCanRenew, assistantBudgetExpired, assistantBudgetRecoveryHeld, assistantBudgetPrepareExplicitApproval, assistantBudgetSubmissionCurrent } from '~/utils/assistant/assistantBudgetRecovery'
import { useAssistantAutomaticPlanning } from './useAssistantAutomaticPlanning'

export interface AssistantApprovalStateProps {
  snapshot: AssistantSessionSnapshot; capabilities: AssistantCapabilities; store: AssistantSessionStore; busy: boolean
  executionPreference?: 'MANUAL' | 'AUTO'; onBudgetMode?: (value: 'MANUAL' | 'AUTO') => void
  blockStopped?: boolean
  conversational?: boolean
}
export function useAssistantApprovalState({ snapshot, capabilities, store, busy, executionPreference = 'MANUAL', onBudgetMode, blockStopped = false, conversational = false }: AssistantApprovalStateProps) {
  const [readApproval, setApproval] = useState<AssistantPlanningApproval | null>(null)
  const [budget, setBudget] = useState<AssistantRunBudgetPolicy | null>(null)
  const [editingBudget, setEditingBudget] = useState(false)
  const [loadedKey, setLoadedKey] = useState('')
  const [readError, setError] = useState<string | null>(null)
  const [readMalformed, setMalformed] = useState(false)
  const [readScopeStale, setScopeStale] = useState(false)
  const [readApprovalReconciling, setApprovalReconciling] = useState(false)
  const [clock, setClock] = useState(0)
  const [refreshEpoch, setRefreshEpoch] = useState(0)
  const run = snapshot.activeRun
  const automaticText = run?.automaticTextExecution === true
  const runId = run?.runId
  const readSessionId = snapshot.sessionId, readSessionRevision = snapshot.revision, readEventSeq = snapshot.lastEventSeq, readRunRevision = run?.stateRevision
  const { readBudget, loading: budgetRefreshing, error: budgetRefreshError } = useAssistantBudgetExpiryRefresh(store, snapshot, budget, clock, busy, setBudget)
  const stopped = blockStopped && run?.stopRequested === true
  const waiting = run?.status === 'WAITING_APPROVAL'
  const planning = !automaticText && ['INITIAL_PLANNING_REQUIRED', 'REPLANNING_REQUIRED', 'CONTINUE_AGENT_REQUIRED'].includes(run?.nextAction || '')
  const shouldLoad = !!runId && (!automaticText
    || ['PLAN_EXECUTION_APPROVAL_REQUIRED', 'CONFIRM_OPERATION_APPROVAL'].includes(run?.nextAction || ''))
  const mainApprovalScope = !automaticText && assistantMainApprovalReadScope(run)
  const rawReceipt = assistantPlanningReceipt(snapshot, store.getSnapshot().lastCommandResult)
  const receipt = rawReceipt && assistantPlanningApprovalProjection(rawReceipt, rawReceipt.runId, rawReceipt.runRevision)
  const canReadMainApproval = snapshot.approval?.scopeKind === 'ASSISTANT_ITERATION'
    && ['QUOTED', 'ACCEPTED'].includes(snapshot.approval.status)
  const planningRevision = rawReceipt?.runRevision ?? run?.stateRevision
  useEffect(() => {
    if (budget && budget.runId === runId && budget.runRevision === planningRevision && budget.mode) onBudgetMode?.(budget.mode)
  }, [budget, runId, planningRevision, onBudgetMode])
  // A quote may replace the old one without changing runRevision or creating a policy.
  // Recheck this exact read scope and quote receipt even when budget/detail is absent.
  const receiptKey = receipt ? [receipt.approvalId, receipt.approvalRevision, receipt.runRevision, receipt.lastEventSeq].join(':') : ''
  const projectedKey = snapshot.approval?.scopeKind === 'ASSISTANT_ITERATION'
    ? [snapshot.approval.approvalId, snapshot.approval.approvalRevision, snapshot.approval.status].join(':') : ''
  const readKey = [snapshot.sessionId, snapshot.revision, runId, run?.nextAction, planningRevision,
    waiting && planning ? projectedKey : snapshot.lastEventSeq, refreshEpoch, receiptKey].join(':')
  const loading = (shouldLoad && loadedKey !== readKey) || budgetRefreshing
  const projectionInvalid = loadedKey === readKey && readMalformed
  const approval = mainApprovalScope && !projectionInvalid ? receipt ?? (loadedKey === readKey && readApproval?.runId === runId ? readApproval : null) : null
  const scopeStale = !!mainApprovalScope && loadedKey === readKey && readScopeStale
  const approvalReconciling = !!mainApprovalScope && loadedKey === readKey && readApprovalReconciling
  const error = (loadedKey === readKey ? readError : null) || budgetRefreshError

  useEffect(() => {
    if (!runId || !shouldLoad || busy || loadedKey === readKey) return
    const observed = store.getSnapshot().snapshot
    if (!observed || observed.sessionId !== readSessionId || observed.revision !== readSessionRevision || observed.lastEventSeq !== readEventSeq
      || observed.activeRun?.runId !== runId || observed.activeRun?.stateRevision !== readRunRevision) return
    let current = true
    void Promise.allSettled([mainApprovalScope ? (receipt ? Promise.resolve(receipt) : canReadMainApproval
      ? store.api.approvalDetail(runId) : Promise.resolve(null)) : Promise.resolve(null), readBudget(runId)]).then(([approvalResult, budgetResult]) => {
      const latest = store.getSnapshot().snapshot
      if (!current || !latest || latest.sessionId !== readSessionId || latest.revision !== readSessionRevision
        || latest.lastEventSeq !== readEventSeq || latest.activeRun?.runId !== runId || latest.activeRun.stateRevision !== readRunRevision) return
      const errors: string[] = []
      const contractStaleRead = approvalResult.status === 'rejected' && store.observePlanningApprovalRejected(observed, approvalResult.reason)
      const reconciling = approvalResult.status === 'rejected' && assistantErrorCode(approvalResult.reason) === 'APPROVAL_RECONCILIATION_REQUIRED'
      setApprovalReconciling(reconciling)
      const stale = approvalResult.status === 'rejected' && assistantErrorCode(approvalResult.reason) === 'APPROVAL_SCOPE_STALE'
        && (assistantErrorEnvelope(approvalResult.reason).data as { details?: { requoteRequired?: boolean } } | undefined)?.details?.requoteRequired === true
      setScopeStale(stale)
      if (approvalResult.status === 'fulfilled') {
        const existingBudget = budgetResult.status === 'fulfilled' ? budgetResult.value : null
        const requireBudgetScope = !existingBudget || existingBudget.runId !== runId || existingBudget.runRevision !== planningRevision
          || existingBudget.status !== 'ACTIVE' || existingBudget.scopeState !== 'CURRENT' || existingBudget.currencyUnit !== 'BALANCE' || existingBudget.precisionScale !== 4
        const projected = mainApprovalScope ? assistantPlanningApprovalProjection(approvalResult.value, runId, planningRevision!, requireBudgetScope) : null
        setMalformed(!!mainApprovalScope && (canReadMainApproval || !!receipt) && !projected); setApproval(projected)
        if (projected) store.observePlanningApproval(observed, projected)
      } else {
        setApproval(null); setMalformed(false)
        if (!contractStaleRead && !stale && !reconciling && assistantErrorCode(approvalResult.reason) !== 'APPROVAL_NOT_FOUND' && !assistantContextChangeSignal(approvalResult.reason)) errors.push(assistantErrorMessage(approvalResult.reason))
        if (assistantContextChangeSignal(approvalResult.reason)) store.reportContextConflict(approvalResult.reason)
      }
      if (budgetResult.status === 'fulfilled' && budgetResult.value.runId === runId && budgetResult.value.runRevision === planningRevision) setBudget(budgetResult.value)
      else { setBudget(null); if (budgetResult.status === 'fulfilled') errors.push('费用状态已变化，请重新同步当前对话。')
        else if (assistantErrorCode(budgetResult.reason) !== 'RUN_BUDGET_NOT_FOUND' && !assistantContextChangeSignal(budgetResult.reason)) errors.push(assistantErrorMessage(budgetResult.reason))
        if (budgetResult.status === 'rejected' && assistantContextChangeSignal(budgetResult.reason)) store.reportContextConflict(budgetResult.reason) }
      setError(errors.length ? [...new Set(errors)].join('；') : null); setClock(Date.now()); setLoadedKey(readKey)
    })
    return () => { current = false }
  }, [store, runId, shouldLoad, readKey, loadedKey, receipt, busy, mainApprovalScope, canReadMainApproval, readSessionId, readSessionRevision, readEventSeq, readRunRevision, planningRevision, readBudget])

  useEffect(() => {
    if (approval?.status !== 'QUOTED' && !budget) return
    const timer = setInterval(() => setClock(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [approval, budget])

  const { revise: revisePolicy, renewing, renewalNotice } = useAssistantBudgetRenewal(snapshot, budget, store,
    busy || loading || !!error || approvalReconciling || stopped || !!store.getSnapshot().pendingCommand, () => { setEditingBudget(false); setRefreshEpoch(value => value + 1) })

  useAssistantAutomaticPlanning(snapshot, capabilities, store, approval, budget,
    automaticText || busy || loading || !!error || scopeStale || approvalReconciling || projectionInvalid || store.planningRequoteBlocked(snapshot, null) || stopped || !!store.getSnapshot().pendingCommand, executionPreference, clock, conversational)

  const showPlanning = !automaticText && waiting && planning && snapshot.plan?.status !== 'PROPOSED' && snapshot.approval?.scopeKind !== 'PLAN_EXECUTION'
  const contractStale = store.planningRequoteBlocked(snapshot, null) || store.planningRequoteBlocked(snapshot, approval)
  const invalidated = approval?.status === 'INVALIDATED' || contractStale || projectionInvalid
  const approvalAccepted = assistantBudgetApprovalAccepted(store, approval) || store.planningApprovalAccepted(snapshot)
  const quote = approval?.quote
  const expired = !!quote && (!Number.isFinite(Date.parse(quote.expiresAt)) || Date.parse(quote.expiresAt) <= clock)
  const pending = !!store.getSnapshot().pendingCommand
  const upperBoundUnits = quote ? assistantBudgetUnits(quote.quotedUpperBound) : null
  const estimatedUnits = quote ? assistantBudgetUnits(quote.estimatedAmount) : null
  const quoteValid = !!quote && quote.currencyUnit === 'BALANCE' && quote.precisionScale === 4
    && upperBoundUnits !== null && estimatedUnits !== null && estimatedUnits <= upperBoundUnits
  const quoteAmount = quoteValid ? quote!.quotedUpperBound : undefined
  const remaining = budget ? assistantBudgetUnits(budget.remaining) : null
  const perCall = budget ? assistantBudgetUnits(budget.perCallMaxCharge) : null
  const quoteUnits = quoteAmount ? assistantBudgetUnits(quoteAmount) : null
  const budgetAvailable = !!budget && budget.runId === runId && budget.runRevision === planningRevision
    && budget.status === 'ACTIVE' && budget.scopeState === 'CURRENT'
    && (!quote?.runBudgetScope || budget.availableRunBudgetScope?.scopeDigest === quote.runBudgetScope.scopeDigest)
    && budget.currencyUnit === 'BALANCE' && budget.precisionScale === 4 && Date.parse(budget.expiresAt) > clock
    && remaining !== null && perCall !== null && quoteUnits !== null && remaining >= quoteUnits && perCall >= quoteUnits
    && budget.usedIterations < budget.maxIterations && budget.usedOperations < budget.maxOperations
  const confirmable = waiting && planning && approval?.status === 'QUOTED' && approval.runRevision === planningRevision
    && (!budget || assistantBudgetSubmissionCurrent(snapshot, budget, store.getSnapshot()))
    && quoteValid && quote?.hardUpperBoundAvailable && !expired && capabilities.planningAvailable && !error && !approvalAccepted && !contractStale && !projectionInvalid
  const requestQuote = () => !run || disabled || approvalAccepted || store.planningApprovalAccepted(snapshot) || !capabilities.planningAvailable ? Promise.resolve(false) : store.execute('planningQuote', { runId: run.runId,
    expectedRunRevision: run.stateRevision, clientRequestId: assistantCommandId() })
  const approve = (runBudget?: AssistantRunBudgetInput) => {
    if (store.planningApprovalAccepted(snapshot) || assistantBudgetApprovalAccepted(store, approval)) return Promise.resolve(false)
    if (!run || disabled || !confirmable || !approval || (budget ? !budgetAvailable : !runBudget)) return Promise.resolve(false)
    if (budget && !assistantBudgetSubmissionCurrent(snapshot, budget, store.getSnapshot())) return Promise.resolve(false)
    const needsConfirmation = assistantBudgetRecoveryHeld(store, snapshot, budget)
    const body = { approvalId: approval.approvalId, expectedApprovalRevision: approval.approvalRevision,
      expectedRunRevision: approval.runRevision, clientRequestId: assistantCommandId(), ...(runBudget ? { runBudget } : {}),
      ...(needsConfirmation ? { confirmRenewedExecution: true } : {}) }
    try { assistantBudgetPrepareExplicitApproval(store, snapshot, budget, body) }
    catch (cause) { store.reportError(cause); return Promise.resolve(false) }
    return store.execute('approve', body)
  }
  const continueHere = () => {
    if (disabled || approvalAccepted || !capabilities.planningAvailable) return Promise.resolve(false)
    if (confirmable && approval && !budget && approval.quote.runBudgetScope) {
      try { return store.continueHere(snapshot, approval,
        assistantConversationRunBudgetInput(approval.quote.runBudgetScope, executionPreference, approval.quote.quotedUpperBound)) }
      catch (cause) { store.reportError(cause); return Promise.resolve(false) }
    }
    return store.continueHere(snapshot)
  }
  const recoveryHeld = assistantBudgetRecoveryHeld(store, snapshot, budget)
  const budgetExpired = assistantBudgetExpired(budget, clock)
  const budgetRevisable = !!budget && (assistantBudgetCanRenew(budget, clock) || ['ACTIVE', 'EXHAUSTED'].includes(budget.status) && !budgetExpired)
  const revise = (input: AssistantRunBudgetInput) => revisePolicy(input, budgetExpired)
  const disabled = renewing || busy || loading || pending || !!error || approvalReconciling || stopped
  return { projectionInvalid, contractStale, approval, budget, editingBudget, setEditingBudget, error, scopeStale, approvalReconciling, clock, loading, waiting, planning, showPlanning, invalidated, quote, expired, quoteValid, quoteAmount, budgetAvailable, confirmable, requestQuote, approve, continueHere, revise, approvalAccepted, recoveryHeld, renewing, renewalNotice, budgetExpired, budgetRevisable, disabled, setRefreshEpoch }
}
