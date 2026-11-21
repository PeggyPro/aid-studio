'use client'

import type { ReactNode } from 'react'
import { ArrowRightOutlined } from '@ant-design/icons'
import { useAssistantApprovalState, type AssistantApprovalStateProps } from '@/hooks/assistant/useAssistantApprovalState'
import { useAssistantExecution } from '@/hooks/assistant/useAssistantExecution'
import { assistantExecutionStatus, assistantOperationTitle } from '@/utils/assistant/assistantExecution'
import { StudioCanvasAssistantAction, StudioCanvasAssistantTurn } from './StudioCanvasAssistantTurn'
import { assistantBudgetRenewalBlockReason } from '~/utils/assistant/assistantBudgetRecovery'
import { StudioCanvasAssistantBudget } from './StudioCanvasAssistantBudget'

export function StudioCanvasAssistantDecision({ records, ...props }: AssistantApprovalStateProps & { records: ReactNode }) {
  const state = useAssistantApprovalState({ ...props, blockStopped: true })
  const { snapshot, capabilities, store } = props
  const { run, plan, approval: operation, confirmPlan, canApprove, approve: approveOperation } = useAssistantExecution({ snapshot, capabilities, store, budget: state.budget, clock: state.clock, disabled: state.disabled })
  if (!run) return snapshot.messages.length ? <details className="canvas-assistant-records"><summary>创作记录</summary><div className="canvas-assistant-records__body">{records}</div></details> : null
  const terminal = ['COMPLETED', 'SUCCEEDED', 'FAILED', 'STOPPED', 'CANCELLED'].includes(run.status)
  const waiting = run.status === 'WAITING_APPROVAL' && !run.stopRequested
  const planWaiting = waiting && !operation && plan?.allowedActions.includes('APPROVE_PLAN')
  const { budget, quote } = state
  const planBlocked = state.disabled || !capabilities.executionAvailable || Boolean(plan?.replanRequired) || !/^[a-f0-9]{64}$/.test(plan?.definitionDigest || '')
  const planReason = state.disabled ? '正在同步执行状态' : plan?.replanRequired ? '依赖产物未通过核验，请先重新规划' : planBlocked ? '当前计划或执行能力暂不满足确认条件' : undefined
  const reviseNeeded = waiting && budget && (!state.budgetAvailable && state.showPlanning || budget.scopeState !== 'CURRENT')
  const planDetails = plan ? <details className="canvas-assistant-disclosure"><summary>查看完整计划（共 {plan.steps.length} 步）与输入范围</summary><ol className="canvas-assistant-plan-details">{plan.steps.map(step => <li key={step.stableStepId}><strong>{step.title || assistantOperationTitle(step.operationKind)}</strong><p>{step.summary}</p><p>{step.dependsOn.length ? '前置步骤：' + step.dependsOn.map(id => { const index = plan.steps.findIndex(item => item.stableStepId === id); return index < 0 ? '待核验' : String(index + 1) }).join('、') : '无前置依赖'}{step.maxAttempts ? ' · 最多尝试 ' + step.maxAttempts + ' 次' : ''}</p>{step.bindingCode ? <p>执行项：{step.bindingCode}</p> : null}{step.inputArtifactBindings?.map((binding, index) => <p key={index}>来源步骤 {plan.steps.findIndex(item => item.stableStepId === binding.sourceStableStepId) + 1} · {binding.logicalArtifactId} · {binding.artifactType}</p>)}{step.inputArtifactRefs?.map(ref => <p key={ref}>批准输入：{ref}</p>)}{step.resolvedInputArtifactRefs?.map(ref => <p key={ref}>已核验输入：{ref}</p>)}{step.inputArtifactBindings?.length ? <p>{step.inputsResolved ? '具体产物版本已由服务端核验' : '等待上游产物归档并核验'}</p> : null}</li>)}</ol>{plan.assumptions.map((item, index) => <p key={index}>前提：{item}</p>)}{plan.completionCriteria.map(item => <p key={item.criterionId}>完成标准：{item.description}</p>)}</details> : null
  return <>
    {state.budgetExpired && budget ? <div className="canvas-assistant-actions" aria-label="本次费用确认"><p className="canvas-assistant-muted" role="status">对话与历史保留。更新费用确认本身不扣费，再查看当前费用并确认继续。</p>{state.editingBudget && state.budgetRevisable && budget.availableRunBudgetScope ? <StudioCanvasAssistantBudget renewal scope={budget.availableRunBudgetScope} policy={budget} disabled={state.disabled || terminal} onSubmit={state.revise} onCancel={() => state.setEditingBudget(false)} /> : <StudioCanvasAssistantAction disabled={state.disabled || !state.budgetRevisable || terminal} reason={!state.budgetRevisable ? assistantBudgetRenewalBlockReason(budget) : state.disabled ? '正在核实原操作' : undefined} onClick={() => state.setEditingBudget(true)}>更新费用确认</StudioCanvasAssistantAction>}</div> : null}
    {waiting && state.loading ? <p className="canvas-assistant-muted" role="status">正在核实本轮授权…</p> : null}
    {waiting && state.approvalReconciling ? <StudioCanvasAssistantTurn label="任务核实"><h3>正在核实已受理任务</h3><p>结果与费用确认后才能继续，请在记录中查看进度。</p></StudioCanvasAssistantTurn> : null}
    {waiting && !state.loading && !state.approvalReconciling ? operation ? <StudioCanvasAssistantTurn label="操作费用审批" className="canvas-assistant-decision">
      <span className="canvas-assistant-eyebrow">{budget?.mode === 'AUTO' ? '操作确认' : '手动生成'}</span><h3>{operation.title || assistantOperationTitle(operation.operationKind)}</h3><p>要开始这一步吗？</p>
      <div className="canvas-assistant-quote"><span>本项最高消耗</span><strong>{operation.maxCharge}<small>余额单位</small></strong></div>
      <p className="canvas-assistant-muted">共用本轮已批准的额度，每次确认都不会提高总上限。</p>
      <details className="canvas-assistant-disclosure"><summary>模型与报价详情</summary><p>{operation.modelCode || operation.toolCode || operation.skillCode}</p><p>有效至 {operation.expiresAt ? new Date(operation.expiresAt).toLocaleString() : '待核验'}</p>{operation.targetRefs.map((ref, i) => <p key={i}>{JSON.stringify(ref)}</p>)}</details>
      {operation.status === 'QUOTED' ? <div className="canvas-assistant-actions"><StudioCanvasAssistantAction primary disabled={state.disabled || !canApprove} reason={state.disabled ? '正在同步费用和授权' : !canApprove ? '当前额度、次数、期限或范围不满足条件，请核对运行授权' : undefined} onClick={approveOperation}>确认并生成 <ArrowRightOutlined aria-hidden /></StudioCanvasAssistantAction></div> : <p role="status">{assistantExecutionStatus(operation.status)}，执行和费用以实际任务为准。</p>}
      {!canApprove && operation.status === 'QUOTED' ? <p className="canvas-assistant-feedback" role="status">当前额度、次数、期限或范围不满足条件，请在下方记录中核对授权。</p> : null}
    </StudioCanvasAssistantTurn> : planWaiting && plan ? <StudioCanvasAssistantTurn label="执行计划" className="canvas-assistant-decision">
      <span className="canvas-assistant-eyebrow">创作方案 · 第 {plan.revision} 版</span><h3>{plan.title || '按这个计划开始创作？'}</h3><p>{plan.summary || plan.goal}</p>
      <div className="canvas-assistant-deliverables"><span>完成后你会得到</span>{plan.deliverables.map((value, index) => <p key={index}>{value}</p>)}</div>
      <ol className="canvas-assistant-plan-preview">{plan.steps.slice(0, 4).map((step, index) => <li key={step.stableStepId}><span>{index + 1}</span><div><strong>{step.title || assistantOperationTitle(step.operationKind)}</strong>{step.summary ? <p>{step.summary}</p> : null}</div></li>)}</ol>
      {plan.steps.length > 4 ? <p className="canvas-assistant-muted">另有 {plan.steps.length - 4} 个步骤，完整内容与批准的输入范围可在下方查看。</p> : null}
      {planDetails}
      <p className="canvas-assistant-muted">{budget?.mode === 'AUTO' ? '按本轮已批准的范围执行。' : '确认计划后，需要授权的生成步骤仍会逐项询问。'}</p>
      {plan.replanRequired ? <p className="canvas-assistant-feedback" role="status">依赖产物未通过核验，需要重新规划。此前已完成的步骤仍保留。</p> : null}
      <div className="canvas-assistant-actions"><StudioCanvasAssistantAction primary disabled={planBlocked} reason={planReason} onClick={confirmPlan}>确认计划并继续 <ArrowRightOutlined aria-hidden /></StudioCanvasAssistantAction></div>
    </StudioCanvasAssistantTurn> : state.showPlanning ? <StudioCanvasAssistantTurn label="助手调用确认" className="canvas-assistant-decision">
      <h3>{budget ? '继续完善你的创作' : '开始前，确认这轮创作的额度'}</h3><p>{budget ? '这次助手调用会在现有授权内理解需求并整理回复。' : '助手会先理解需求并回复，再与你确认需要生成的内容。'}</p>
      {quote ? <><div className="canvas-assistant-quote"><span>本次助手调用最高消耗</span><strong>{quote.quotedUpperBound}<small>余额单位</small></strong></div><details className="canvas-assistant-disclosure"><summary>查看本次报价</summary><p>预估 {quote.estimatedAmount} · 模型 {quote.modelCode}</p><p>有效至 {new Date(quote.expiresAt).toLocaleString()}</p></details></> : null}
      {!capabilities.planningAvailable ? <p role="status">{capabilities.planningUnavailableReason || '当前服务尚未开放助手调用。'}消息已保留。</p> : null}
      {state.scopeStale ? <p className="canvas-assistant-feedback" role="status">引用范围已变化，请重新报价并核对授权。</p> : null}
      {quote && (!state.quoteValid || !quote.hardUpperBoundAvailable) ? <p className="canvas-assistant-feedback" role="alert">当前报价不能保证费用硬上限，暂不可确认。</p> : null}
      {state.expired || state.invalidated || state.approval?.status === 'QUOTED' && state.approval.runRevision !== run.stateRevision ? <p className="canvas-assistant-feedback" role="status">报价已过期或状态已变化，请重新核验。</p> : null}
      {capabilities.planningAvailable && (!state.approval || state.invalidated || state.expired) ? <StudioCanvasAssistantAction primary disabled={state.disabled} reason={state.disabled ? '正在处理上次操作' : undefined} onClick={() => void state.requestQuote()}>{state.expired || state.invalidated || state.scopeStale ? '重新核验费用' : '查看本次费用'} <ArrowRightOutlined aria-hidden /></StudioCanvasAssistantAction> : null}
      {state.confirmable && !budget && quote?.runBudgetScope ? <StudioCanvasAssistantBudget key={'initial:' + state.approval!.approvalId + ':' + quote.runBudgetScope.scopeDigest} scope={quote.runBudgetScope} preferredMode={props.executionPreference} quoteUpperBound={state.quoteAmount} disabled={state.disabled} onSubmit={state.approve} /> : null}
      {state.confirmable && !budget && !quote?.runBudgetScope ? <p role="status">尚未取得完整授权范围，暂不可确认。</p> : null}
      {budget && state.approval?.status === 'QUOTED' && !state.expired ? <div className="canvas-assistant-actions"><StudioCanvasAssistantAction primary disabled={state.disabled || !state.confirmable || !state.budgetAvailable} reason={state.disabled ? '正在核实授权' : !state.budgetAvailable ? '当前授权不足，请核对额度、次数和期限' : undefined} onClick={() => void state.approve()}>{state.recoveryHeld ? '确认继续' : '确认并继续'} <ArrowRightOutlined aria-hidden /></StudioCanvasAssistantAction></div> : null}
    </StudioCanvasAssistantTurn> : <StudioCanvasAssistantTurn label="等待运行确认"><h3>正在同步下一步</h3><p>当前还没有可确认的计划或报价，请稍候或重新读取状态。</p><StudioCanvasAssistantAction disabled={props.busy} reason={props.busy ? '正在处理上次操作' : undefined} onClick={() => void store.refresh()}>重新读取状态</StudioCanvasAssistantAction></StudioCanvasAssistantTurn> : null}
    {state.budgetExpired && !state.budgetRevisable ? <p className="canvas-assistant-feedback" role="status">{assistantBudgetRenewalBlockReason(budget)}</p> : null}
    {state.approvalAccepted ? <p className="canvas-assistant-feedback" role="status">本次费用已确认，正在同步执行状态，请勿重复确认。</p> : null}
    {state.renewalNotice ? <p className="canvas-assistant-feedback" role="status">{state.renewalNotice}</p> : null}
    {state.error && !terminal ? <div className="canvas-assistant-feedback" role="alert"><p>{state.error}</p><StudioCanvasAssistantAction disabled={props.busy || state.loading} onClick={() => state.setRefreshEpoch(value => value + 1)}>重新核实费用</StudioCanvasAssistantAction></div> : null}
    <details className="canvas-assistant-records"><summary>创作记录与本轮授权</summary><div className="canvas-assistant-records__body">
      {budget ? <section aria-label="运行总预算"><h4>本轮{budget.mode === 'AUTO' ? '自动' : '手动'}生成授权</h4><p>总上限 {budget.absoluteMaxCharge} · 每次最多 {budget.perCallMaxCharge} 余额单位</p><p>已结算 {budget.settled} · 已预留 {budget.reserved} · 剩余 {budget.remaining}</p><p>助手轮次 {budget.usedIterations}/{budget.maxIterations} · 操作 {budget.usedOperations}/{budget.maxOperations}</p><p>有效至 {new Date(budget.expiresAt).toLocaleString()}</p>
        {budget.scopeState !== 'CURRENT' ? <p role="status">引用范围已变化，请核对并重新授权。</p> : null}{state.budgetExpired ? <p role="status">需要更新本次费用确认，对话与历史结果保留。</p> : null}
        {!reviseNeeded && !state.budgetExpired ? state.editingBudget && state.budgetRevisable && budget.availableRunBudgetScope ? <StudioCanvasAssistantBudget renewal={state.budgetExpired} key={'revise:' + budget.status + ':' + budget.policyRevision + ':' + budget.availableRunBudgetScope.scopeDigest} scope={budget.availableRunBudgetScope} policy={budget} disabled={state.disabled || terminal} onSubmit={state.revise} onCancel={() => state.setEditingBudget(false)} /> : <StudioCanvasAssistantAction disabled={state.disabled || !state.budgetRevisable || !budget.availableRunBudgetScope || terminal} reason={terminal ? '本轮已经结束' : !state.budgetRevisable ? assistantBudgetRenewalBlockReason(budget) : state.disabled ? '正在处理上次操作' : undefined} onClick={() => state.setEditingBudget(true)}>{state.budgetExpired ? '更新费用确认' : '调整本轮授权'}</StudioCanvasAssistantAction> : null}
      </section> : null}
      {plan && !planWaiting ? planDetails : null}{records}
    </div></details>
    {reviseNeeded && !state.budgetExpired ? <div className="canvas-assistant-feedback" role="status"><p>本轮授权的额度、次数或范围需要调整后才能继续。</p>{state.budgetRevisable && budget?.availableRunBudgetScope ? <StudioCanvasAssistantBudget renewal={state.budgetExpired} key={'required:' + budget.status + ':' + budget.policyRevision + ':' + budget.availableRunBudgetScope.scopeDigest} scope={budget.availableRunBudgetScope} policy={budget} quoteUpperBound={state.quoteAmount} disabled={state.disabled} onSubmit={state.revise} /> : null}</div> : null}
  </>
}
