'use client'

import { useEffect, useRef } from 'react'
import type { AssistantCapabilities, AssistantPlanningApproval, AssistantSessionSnapshot } from '~/types/assistant-api'
import type { AssistantRunBudgetPolicy } from '~/types/assistant-budget'
import type { AssistantSessionStore } from '~/utils/assistant/assistantSessionStore'
import { assistantBudgetUnits, assistantRunBudgetInput, assistantConversationRunBudgetInput } from '~/utils/assistant/assistantBudget'
import { assistantBudgetApprovalAccepted, assistantBudgetRecoveryHeld, assistantBudgetSettleExplicitApproval } from '~/utils/assistant/assistantBudgetRecovery'
import { assistantPlanningReceipt } from '~/utils/assistant/assistantPlanningReceipt'
import { assistantCommandId } from '~/utils/assistant/assistantRequestCoordinator'

/** 对话入口的发送授权主助手调用；收费内容仍由 MANUAL 的操作确认保护。 */
export function useAssistantAutomaticPlanning(snapshot: AssistantSessionSnapshot, capabilities: AssistantCapabilities,
  store: AssistantSessionStore, approval: AssistantPlanningApproval | null, budget: AssistantRunBudgetPolicy | null,
  disabled: boolean, preference: 'MANUAL' | 'AUTO', clock: number, conversational = false) {
  const attempted = useRef('')
  const run = snapshot.activeRun
  useEffect(() => {
    try { assistantBudgetSettleExplicitApproval(store, snapshot) } catch { return }
    if (!run || disabled || !capabilities.planningAvailable || run.status !== 'WAITING_APPROVAL'
      || !['INITIAL_PLANNING_REQUIRED', 'REPLANNING_REQUIRED', 'CONTINUE_AGENT_REQUIRED'].includes(run.nextAction || '')) return
    if (!store.hasAutomaticIntent(snapshot) || store.planningApprovalAccepted(snapshot) || store.planningRequoteBlocked(snapshot, null) || store.planningRequoteBlocked(snapshot, approval) || assistantBudgetApprovalAccepted(store, approval)) return
    const receipt = assistantPlanningReceipt(snapshot, store.getSnapshot().lastCommandResult)
    const expectedRunRevision = receipt && receipt.approvalId === approval?.approvalId && receipt.approvalRevision === approval?.approvalRevision
      ? receipt.runRevision : run.stateRevision
    // The receipt may precede the projection. Do not consume an attempt before the CAS/proof scope catches up.
    if (run.stateRevision !== expectedRunRevision) return
    // The same run revision can carry a newer quote event. Wait before consuming an attempt/proof scope.
    if (approval && (approval.runRevision > run.stateRevision || approval.lastEventSeq > snapshot.lastEventSeq)) return
    const key = run.runId + ':' + expectedRunRevision + ':' + (approval?.approvalId || 'quote')
    if (attempted.current === key) return
    const activeBudget = budget?.runId === run.runId && budget.runRevision === expectedRunRevision
      && budget.currencyUnit === 'BALANCE' && budget.precisionScale === 4 && budget.status === 'ACTIVE' && budget.scopeState === 'CURRENT' && Date.parse(budget.expiresAt) > Date.now()
      && (!approval?.quote?.runBudgetScope || budget.availableRunBudgetScope?.scopeDigest === approval.quote.runBudgetScope.scopeDigest)
    const expiredQuote = approval?.status === 'QUOTED' && Number.isFinite(Date.parse(approval.quote?.expiresAt || ''))
      && Date.parse(approval.quote!.expiresAt) <= Date.now()
    if (expiredQuote) {
      // 未确认报价到期后由用户重新报价；只有仍有效的 AUTO 费用范围允许自动刷新。
      const bound = assistantBudgetUnits(approval.quote?.quotedUpperBound)
      const estimate = assistantBudgetUnits(approval.quote?.estimatedAmount)
      if (!activeBudget || budget?.mode !== 'AUTO' || assistantBudgetRecoveryHeld(store, snapshot, budget)
        || budget.runId !== run.runId || budget.runRevision !== run.stateRevision
        || budget.currencyUnit !== 'BALANCE' || budget.precisionScale !== 4
        || approval.runId !== run.runId || approval.runRevision !== run.stateRevision || approval.scopeKind !== 'ASSISTANT_ITERATION'
        || approval.quote?.currencyUnit !== 'BALANCE' || approval.quote.precisionScale !== 4 || !approval.quote.hardUpperBoundAvailable
        || bound === null || estimate === null || estimate > bound
        || assistantBudgetUnits(budget.remaining) === null || assistantBudgetUnits(budget.perCallMaxCharge) === null
        || assistantBudgetUnits(budget.remaining)! < bound || assistantBudgetUnits(budget.perCallMaxCharge)! < bound
        || budget.usedIterations >= budget.maxIterations || budget.usedOperations >= budget.maxOperations
        || approval.quote.runBudgetScope && budget.availableRunBudgetScope?.scopeDigest !== approval.quote.runBudgetScope.scopeDigest) return
    }
    if (!approval || approval.status === 'INVALIDATED' || expiredQuote) {
      // 报价只读取费用，不启动上游生成。
      attempted.current = key
      void store.executeAutomatic('planningQuote', { runId: run.runId, expectedRunRevision: run.stateRevision, clientRequestId: assistantCommandId() }, snapshot)
      return
    }
    if (assistantBudgetRecoveryHeld(store, snapshot, budget)) return
    const quote = approval.quote
    if (approval.status !== 'QUOTED' || approval.runId !== run.runId || approval.scopeKind !== 'ASSISTANT_ITERATION' || approval.runRevision !== expectedRunRevision || !quote?.hardUpperBoundAvailable
      || quote.currencyUnit !== 'BALANCE' || quote.precisionScale !== 4
      || !Number.isFinite(Date.parse(quote.expiresAt)) || Date.parse(quote.expiresAt) <= Date.now()) return
    const bound = assistantBudgetUnits(quote.quotedUpperBound)
    const estimate = assistantBudgetUnits(quote.estimatedAmount)
    if (bound === null || estimate === null || estimate > bound) return
    const free = bound === 0n
    if (!free && !conversational && (!activeBudget || budget?.mode !== 'AUTO')) return
    if (budget && (!activeBudget || assistantBudgetUnits(budget.remaining) === null || assistantBudgetUnits(budget.perCallMaxCharge) === null
      || assistantBudgetUnits(budget.remaining)! < bound || assistantBudgetUnits(budget.perCallMaxCharge)! < bound
      || budget.usedIterations >= budget.maxIterations || budget.usedOperations >= budget.maxOperations)) return
    if (!budget && (!quote.runBudgetScope || (!free && !conversational))) return
    attempted.current = key
    const scope = quote.runBudgetScope
    try {
    const runBudget = !budget && scope ? conversational
      ? assistantConversationRunBudgetInput(scope, preference, quote.quotedUpperBound)
      : assistantRunBudgetInput(scope, { mode: preference, automaticConsent: preference === 'AUTO',
        absoluteMaxCharge: '0.0000', perCallMaxCharge: '0.0000', maxIterations: Math.min(32, scope.maxIterations),
        maxOperations: Math.min(100, scope.maxOperations),
        expiresAt: new Date(Date.now() + Math.min(3600, scope.maxTtlSeconds) * 1000 - 1000).toISOString()
      }, { quoteUpperBound: quote.quotedUpperBound }) : undefined
    void store.executeAutomatic('approve', { approvalId: approval.approvalId, expectedApprovalRevision: approval.approvalRevision,
      expectedRunRevision, clientRequestId: assistantCommandId(), ...(runBudget ? { runBudget } : {}) }, snapshot, approval)
    } catch (error) { store.reportError(error) }
  }, [snapshot, run, disabled, capabilities.planningAvailable, approval, budget, store, preference, clock, conversational])
}
