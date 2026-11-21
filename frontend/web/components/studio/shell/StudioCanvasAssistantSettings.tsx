'use client'

import { useEffect, useState } from 'react'
import { Button, Input } from 'antd'
import { useAssistantBudgetRenewal } from '@/hooks/assistant/useAssistantBudgetRenewal'
import type { AssistantSessionSnapshot } from '@/types/assistant-api'
import type { AssistantRunBudgetPolicy, CanvasBudget } from '@/types/assistant-budget'
import { assistantBudgetUnits } from '@/utils/assistant/assistantBudget'
import { assistantBudgetCanRenew, assistantBudgetExpired, assistantBudgetRenewalBlockReason } from '@/utils/assistant/assistantBudgetRecovery'
import { assistantErrorCode, assistantErrorMessage } from '@/utils/assistant/assistantRequestCoordinator'
import type { AssistantSessionStore } from '@/utils/assistant/assistantSessionStore'
import { StudioCanvasAssistantBudget } from './StudioCanvasAssistantBudget'

export function StudioCanvasBudgetDefaults({ budget, onSave }: {
  budget?: CanvasBudget
  onSave: (value?: CanvasBudget) => void
}) {
  const [total, setTotal] = useState(budget?.absoluteMaxCharge ?? '')
  const [perCall, setPerCall] = useState(budget?.perCallMaxCharge ?? '')
  const [feedback, setFeedback] = useState<{ error: boolean; text: string }>()
  const save = (value?: CanvasBudget) => {
    try {
      onSave(value)
      if (!value) { setTotal(''); setPerCall('') }
      setFeedback({ error: false, text: value ? '已保存，新一轮对话将使用此预算。' : '已恢复系统默认预算。' })
    } catch (cause) { setFeedback({ error: true, text: assistantErrorMessage(cause) }) }
  }
  return <form className="canvas-assistant-budget-defaults" onSubmit={event => {
    event.preventDefault()
    const absoluteMaxCharge = total.trim(), perCallMaxCharge = perCall.trim()
    const totalUnits = assistantBudgetUnits(absoluteMaxCharge), callUnits = assistantBudgetUnits(perCallMaxCharge)
    if (totalUnits === null || callUnits === null || callUnits > totalUnits) {
      setFeedback({ error: true, text: '请填写有效积分额度，最多四位小数。单次上限不能超过总上限。' })
      return
    }
    save({ absoluteMaxCharge, perCallMaxCharge })
  }}>
    <p>设置此画布中新一轮助手运行的积分上限。</p>
    <label>总上限（积分）<Input inputMode="decimal" value={total} onChange={event => { setTotal(event.target.value); setFeedback(undefined) }} /></label>
    <label>单次上限（积分）<Input inputMode="decimal" value={perCall} onChange={event => { setPerCall(event.target.value); setFeedback(undefined) }} /></label>
    <div><Button htmlType="submit" type="primary">保存预算</Button>{budget ? <Button type="text" onClick={() => save()}>恢复默认</Button> : null}</div>
    {feedback ? <p role={feedback.error ? 'alert' : 'status'}>{feedback.text}</p> : null}
  </form>
}

export function StudioCanvasAssistantSettings({ snapshot, store, busy }: {
  snapshot: AssistantSessionSnapshot
  store: AssistantSessionStore
  busy: boolean
}) {
  const run = snapshot.activeRun
  const runId = run?.runId
  const revision = run?.stateRevision
  const [read, setRead] = useState<{ runId: string; revision: number; budget?: AssistantRunBudgetPolicy; error?: string }>()
  const [refresh, setRefresh] = useState(0)
  const result = read && read.runId === runId && read.revision === revision ? read : undefined
  const budget = result?.budget ?? null
  const expired = assistantBudgetExpired(budget)
  const historical = !!run && ['SUCCEEDED', 'COMPLETED', 'FAILED', 'STOPPED', 'CANCELLED'].includes(run.status)
  const { revise, renewing, renewalNotice } = useAssistantBudgetRenewal(snapshot, budget, store,
    busy || historical || run?.stopRequested === true, () => setRefresh(value => value + 1))

  useEffect(() => {
    if (!runId || revision === undefined) return
    let current = true
    void store.api.budgetDetail(runId).then(budget => {
      if (!current) return
      if (budget.runId !== runId || budget.runRevision !== revision) {
        setRead({ runId, revision, error: '授权状态已更新，请重新读取。' })
        return
      }
      setRead({ runId, revision, budget })
    }).catch(error => {
      if (current) setRead({ runId, revision, error: assistantErrorCode(error) === 'RUN_BUDGET_NOT_FOUND' ? undefined : assistantErrorMessage(error) })
    })
    return () => { current = false }
  }, [store, runId, revision, refresh])

  useEffect(() => {
    if (!budget || expired) return
    const remaining = Date.parse(budget.expiresAt) - Date.now()
    const timer = setTimeout(() => setRefresh(value => value + 1), Math.max(0, remaining))
    return () => clearTimeout(timer)
  }, [budget, expired])

  return <div className="assistant-panel">
    <p>调整本轮画布助手的积分上限、执行方式和授权期限。</p>
    {!runId || result && !budget && !result.error ? <p role="status">发送一条创作消息后，即可在这里调整本轮生成预算。</p> : null}
    {runId && !result ? <p role="status">正在读取生成预算…</p> : null}
    {result?.error ? <div role="alert"><p>{result.error}</p><Button onClick={() => { setRead(undefined); setRefresh(value => value + 1) }}>重新读取</Button></div> : null}
    {budget ? <>
      <p>已使用 {budget.settled} · 已预留 {budget.reserved} · 剩余 {budget.remaining} 积分</p>
      {historical ? <p role="status">本轮已结束。开始新一轮后可调整新一轮的生成预算。</p>
        : expired && !assistantBudgetCanRenew(budget) ? <p role="status">{assistantBudgetRenewalBlockReason(budget)}</p>
        : budget.availableRunBudgetScope ? <StudioCanvasAssistantBudget
          key={budget.policyRevision + ':' + budget.authorizationRevision + ':' + budget.availableRunBudgetScope.scopeDigest}
          scope={budget.availableRunBudgetScope} policy={budget} renewal={expired} settings
          disabled={busy || renewing || run?.hasActiveTasks === true || run?.stopRequested === true}
          onSubmit={input => revise(input, expired)} />
        : <p role="status">当前授权范围暂不可用。</p>}
    </> : null}
    {renewalNotice ? <p role="status">{renewalNotice}</p> : null}
  </div>
}
