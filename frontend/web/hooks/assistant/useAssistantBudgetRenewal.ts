'use client'

import { useEffect, useRef, useState } from 'react'
import type { AssistantSessionSnapshot } from '~/types/assistant-api'
import type { AssistantRunBudgetInput, AssistantRunBudgetPolicy } from '~/types/assistant-budget'
import type { AssistantSessionStore } from '~/utils/assistant/assistantSessionStore'
import { assistantCommandId, assistantErrorMessage } from '~/utils/assistant/assistantRequestCoordinator'
import { assistantBudgetBeginRenewal, assistantBudgetCanRenew, assistantBudgetExpired, assistantBudgetRenewalAccepted,
  assistantBudgetSettleExplicitApproval, assistantBudgetSubmissionCurrent } from '~/utils/assistant/assistantBudgetRecovery'

/** Renew only the spending permission. The conversation and its pinned run remain intact. */
export function useAssistantBudgetRenewal(snapshot: AssistantSessionSnapshot, budget: AssistantRunBudgetPolicy | null,
  store: AssistantSessionStore, blocked: boolean, onAccepted: () => void) {
  const flight = useRef(false), settled = useRef('')
  const [renewing, setRenewing] = useState(false)
  const [renewalNotice, setNotice] = useState<string | null>(null)
  useEffect(() => { setNotice(null); settled.current = '' }, [snapshot.sessionId, snapshot.activeRun?.runId])
  useEffect(() => {
    const settle = () => {
      const current = store.getSnapshot().snapshot
      if (current?.sessionId !== snapshot.sessionId || current.activeRun?.runId !== snapshot.activeRun?.runId) return
      try {
        assistantBudgetSettleExplicitApproval(store, snapshot)
        if (assistantBudgetRenewalAccepted(store, snapshot)) {
          const id = String(store.getSnapshot().lastCommandResult?.body.clientRequestId)
          if (settled.current === id) return
          settled.current = id; setNotice(snapshot.activeRun?.status === 'PAUSED' ? '费用确认已更新，历史消费与结果保留。可继续处理，再查看当前费用并确认继续。' : '费用确认已更新，历史消费与结果保留。请查看当前费用并确认继续。'); onAccepted()
        }
      } catch (cause) { setNotice(assistantErrorMessage(cause)) }
    }
    settle(); return store.subscribe(settle)
  }, [store, snapshot.sessionId, snapshot.activeRun?.runId, onAccepted])
  const revise = async (input: AssistantRunBudgetInput, renew = false) => {
    if (!budget || blocked || flight.current) return
    flight.current = true; setRenewing(true); setNotice(null)
    try {
      if (!assistantBudgetSubmissionCurrent(snapshot, budget, store.getSnapshot())) throw new Error('会话或授权状态已变化，请重新同步后确认；原对话仍保留')
      const fresh = await store.api.budgetDetail(budget.runId)
      if (!assistantBudgetSubmissionCurrent(snapshot, budget, store.getSnapshot())
        || fresh.runId !== budget.runId || fresh.authorizationId !== budget.authorizationId
        || fresh.currencyUnit !== 'BALANCE' || fresh.precisionScale !== 4 || fresh.runRevision !== budget.runRevision || fresh.policyRevision !== budget.policyRevision
        || fresh.authorizationRevision !== budget.authorizationRevision || fresh.scopeState !== budget.scopeState
        || fresh.availableRunBudgetScope?.scopeDigest !== input.scopeDigest) throw new Error('授权范围或版本已更新，请重新读取后明确确认')
      if (assistantBudgetExpired(fresh) !== renew) throw new Error('消费授权状态已变化，请重新核对期限并明确确认')
      if (renew && !assistantBudgetCanRenew(fresh)) throw new Error('当前消费授权暂不能重新确认，请核实权限或原操作状态；对话与历史仍保留')
      const body = { runId: budget.runId, expectedRunRevision: budget.runRevision, expectedPolicyRevision: budget.policyRevision,
        expectedAuthorizationRevision: budget.authorizationRevision, clientRequestId: assistantCommandId(), runBudget: input,
        ...(renew ? { renewExpired: true } : {}) }
      if (renew) assistantBudgetBeginRenewal(store, snapshot, body)
      const success = await store.execute('reviseBudget', body)
      if (success && !renew) onAccepted()
    } catch (cause) { setNotice(assistantErrorMessage(cause)) }
    finally { flight.current = false; setRenewing(false) }
  }
  return { revise, renewing, renewalNotice }
}
