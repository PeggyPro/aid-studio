'use client'

import { assistantConfirmOperation } from '~/utils/assistant/assistantOperationQuote'
import { useEffect, useRef, useState } from 'react'
import { confirmCanvasAssistantOperation } from '~/utils/studio/canvasAssistantOperationConfirmation'
import type { AssistantCapabilities, AssistantSessionSnapshot } from '~/types/assistant-api'
import type { AssistantRunBudgetPolicy } from '~/types/assistant-budget'
import type { AssistantSessionStore } from '~/utils/assistant/assistantSessionStore'
import { assistantCommandId } from '~/utils/assistant/assistantRequestCoordinator'
import { assistantOperationConfirmationAvailable, assistantOperationWithinApprovedScope } from '~/utils/assistant/assistantExecution'

export function useAssistantExecution({ snapshot, capabilities, budget, clock, store, disabled, presentation }: { snapshot: AssistantSessionSnapshot; capabilities: AssistantCapabilities; budget: AssistantRunBudgetPolicy | null; clock: number; store: AssistantSessionStore; disabled: boolean; conversational?: boolean; presentation?: 'canvas' }) {
  const run = snapshot.activeRun, plan = snapshot.plan
  const confirmation = useRef<AbortController | null>(null)
  const [confirmationState, setConfirmationState] = useState<{ key: string; controller: AbortController } | null>(null)
  const confirmationKey = snapshot.sessionId + ':' + run?.runId
  const confirming = confirmationState?.key === confirmationKey && !confirmationState.controller.signal.aborted
  useEffect(() => () => { confirmation.current?.abort(); confirmation.current = null }, [store, confirmationKey])
  const approval = snapshot.approval?.scopeKind === 'PLAN_EXECUTION' ? snapshot.approval : null
  const planAccepted = store.planConfirmationAccepted(snapshot), operationAccepted = store.operationApprovalAccepted(snapshot)
  // Automatic plans and operations advance on the server, including after this page disconnects.
  const confirmPlan = () => {
    if (planAccepted || !run || run.stopRequested || !plan || plan.replanRequired || disabled || run.status !== 'WAITING_APPROVAL' || !capabilities.executionAvailable || !plan.allowedActions.includes('APPROVE_PLAN') || !/^[a-f0-9]{64}$/.test(plan.definitionDigest)) return
    void store.execute('confirmPlan', { runId: run.runId, expectedRunRevision: run.stateRevision, planRevision: plan.revision, definitionDigest: plan.definitionDigest, clientRequestId: assistantCommandId() })
  }
  const canApprove = !store.operationReferenceChallenge(snapshot) && assistantOperationConfirmationAvailable(snapshot) && !store.operationQuoteSynchronizing(snapshot) && !operationAccepted && !!approval && approval.status === 'QUOTED' && approval.allowedActions.includes('CONFIRM_APPROVAL')
    && run?.status === 'WAITING_APPROVAL' && !run.stopRequested && capabilities.executionAvailable && assistantOperationWithinApprovedScope(approval, budget, clock)
  const expiryRead = useRef('')
  useEffect(() => {
    if (!run || !approval || approval.status !== 'QUOTED' || !approval.allowedActions.includes('CONFIRM_APPROVAL') || !approval.expiresAt) return
    const expires = Date.parse(approval.expiresAt)
    if (clock === 0 || !Number.isFinite(expires) || expires > clock) return
    const live = store.getSnapshot()
    if (live.busy || live.pendingCommand) return
    const key = `${run.runId}:${approval.approvalId}:${approval.approvalRevision}:${approval.expiresAt}`
    if (expiryRead.current === key) return
    expiryRead.current = key
    void store.refresh()
  }, [run, approval, clock, store])
  const approve = () => {
    if (!run || !approval || !canApprove || disabled) return
    if (presentation !== 'canvas') { void assistantConfirmOperation(store, snapshot, budget); return }
    if (confirmation.current) return
    const controller = new AbortController()
    confirmation.current = controller
    setConfirmationState({ key: confirmationKey, controller })
    void confirmCanvasAssistantOperation(store, snapshot, budget, controller.signal).finally(() => {
      if (confirmation.current === controller) confirmation.current = null
      if (!controller.signal.aborted) setConfirmationState(current => current?.controller === controller ? null : current)
    })
  }
  return { run, plan, approval, confirmPlan, canApprove, approve, confirming, planAccepted, operationAccepted }
}
