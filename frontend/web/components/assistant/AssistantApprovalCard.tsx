'use client'

import { Button } from 'antd'
import type { ReactNode } from 'react'
import { StudioCanvasTooltip } from '~/components/studio/canvas/StudioCanvasTooltip'
import type { AssistantCapabilities, AssistantSessionSnapshot } from '~/types/assistant-api'
import type { AssistantSessionStore } from '~/utils/assistant/assistantSessionStore'
import { AssistantExecutionPanel } from './AssistantExecutionPanel'
import { AssistantConfirmationSection } from './AssistantConfirmationSection'
import { AssistantRunBudgetEditor } from './AssistantRunBudgetEditor'
import { assistantBudgetRenewalBlockReason, assistantComposerShowsBudgetRenewal } from '~/utils/assistant/assistantBudgetRecovery'
import { StudioCanvasAssistantBudget } from '~/components/studio/shell/StudioCanvasAssistantBudget'
import { useAssistantApprovalState } from '~/hooks/assistant/useAssistantApprovalState'
import type { AssistantEffectHandler } from '~/types/assistant-effects'

/** 总运行授权与单次调用审批均由服务端恢复，不随每次报价增加总额。 */
export function AssistantApprovalCard({ snapshot, capabilities, store, busy, executionPreference = 'MANUAL', onBudgetMode, conversational = false, onLocateTarget, presentation }: {
  snapshot: AssistantSessionSnapshot; capabilities: AssistantCapabilities; store: AssistantSessionStore; busy: boolean
  executionPreference?: 'MANUAL' | 'AUTO'
  onBudgetMode?: (value: 'MANUAL' | 'AUTO') => void
  conversational?: boolean
  presentation?: 'canvas'
  onLocateTarget?: AssistantEffectHandler
}) {
  const { projectionInvalid, contractStale, approval, budget, editingBudget, setEditingBudget, error, scopeStale, approvalReconciling, clock, loading, waiting, planning, showPlanning, invalidated, quote, expired, quoteValid, quoteAmount, budgetAvailable, confirmable, requestQuote, approve, continueHere, revise, approvalAccepted, recoveryHeld, renewalNotice, budgetExpired, budgetRevisable, disabled, setRefreshEpoch } = useAssistantApprovalState({ snapshot, capabilities, store, busy, executionPreference, onBudgetMode, conversational, blockStopped: conversational })
  const feeHint = (control: ReactNode, reason?: string) => presentation === 'canvas' && reason ? <StudioCanvasTooltip title={reason}>{control}</StudioCanvasTooltip> : control
  const feeBusyReason = disabled ? error || (approvalReconciling ? '正在核实已受理任务，完成前不能再次确认费用' : '正在核实费用或处理上次操作，请等待同步后继续') : undefined
  const feeEditReason = feeBusyReason || (!budgetRevisable ? assistantBudgetRenewalBlockReason(budget) : !budget?.availableRunBudgetScope ? '尚未取得当前费用范围，正在等待会话同步' : undefined)
  const run = snapshot.activeRun
  const previzBudgetNeedsReview = snapshot.moduleCode === 'PREVIZ_3DBOX' && waiting && planning
    && quoteValid && !!budget && !budgetAvailable
  if (!run || (!waiting && !approval && !budget && !error && !snapshot.plan && !snapshot.steps?.length)) return null
  const budgetDecision = !!budget && !approvalAccepted && !store.operationApprovalAccepted(snapshot)
    && (editingBudget || previzBudgetNeedsReview || assistantComposerShowsBudgetRenewal(snapshot, budget, clock))
  const awaitingPlanningSnapshot = !!approval && approval.runId === run.runId && approval.runRevision > run.stateRevision
  const planningPreparationBlocked = !awaitingPlanningSnapshot && waiting && planning && !loading && !error && !scopeStale && !approvalReconciling
    && capabilities.planningAvailable && approval?.status === 'QUOTED'
    && (!quoteValid || !quote?.hardUpperBoundAvailable || approval.runRevision !== run.stateRevision || expired)
  const automaticNotice = store.getSnapshot().automaticNotice
  const executionOwnsRecovery = conversational && waiting && !planning
  const observerNotice = showPlanning && !store.hasAutomaticIntent(snapshot)
    ? store.hasQuoteOnlyIntent(snapshot)
      ? '本次仅更新报价。请核对当前费用，再明确确认继续；不会因报价成功而自动执行。'
      : '当前页面仅同步查看。可查看本次费用，再明确确认继续；不会因恢复历史而自动执行。'
    : null
  const recoveryNotice = automaticNotice || observerNotice
  const showContinueHere = showPlanning && !approvalAccepted && !budgetExpired && (!store.hasAutomaticIntent(snapshot) || automaticNotice)
    && (!approval || invalidated || expired || !budget && confirmable)
  const planningCardOwnsRecovery = showContinueHere
  const refreshApprovalAction = feeHint(<Button disabled={busy || loading || !!store.getSnapshot().pendingCommand} onClick={() => setRefreshEpoch(value => value + 1)}>重新读取费用审批</Button>, busy || loading || !!store.getSnapshot().pendingCommand ? '正在处理或核实原操作，请稍候' : undefined)
  const pageRecovery = <>
    {awaitingPlanningSnapshot && !approvalAccepted ? <p role="status">正在同步当前报价对应的运行状态，请稍候再确认。</p> : null}
    {!executionOwnsRecovery && !planningCardOwnsRecovery && recoveryNotice ? <p className="assistant-inline-status" role="status">{recoveryNotice}</p> : null}
    {showContinueHere ? <AssistantConfirmationSection presentation={presentation} label="在此继续处理" actions={feeHint(<Button disabled={disabled || !capabilities.planningAvailable} onClick={() => void continueHere()}>在此继续处理</Button>, feeBusyReason || (!capabilities.planningAvailable ? '当前服务暂不可调用助手' : undefined))}>
      {planningCardOwnsRecovery && recoveryNotice ? <p role="status">{recoveryNotice}</p> : null}
      <p>继续前核对当前费用，只收取新调用的费用；沿用当前执行模式与费用边界。{quote ? `本次调用最高消耗 ${quote.quotedUpperBound}。` : ''}{!budget && confirmable && quote?.runBudgetScope?.conversationBudget ? `本次确认总费用上限 ${quote.runBudgetScope.conversationBudget.absoluteMaxCharge}，单次上限 ${quote.runBudgetScope.conversationBudget.perCallMaxCharge}，使用当前${executionPreference === 'AUTO' ? '自动' : '手动'}模式。` : ''}</p>
    </AssistantConfirmationSection> : null}
    {conversational && showPlanning && expired && !contractStale && !projectionInvalid && !approvalAccepted ? <AssistantConfirmationSection presentation={presentation} role="status" label="报价需要更新" actions={feeHint(<Button disabled={disabled || !capabilities.planningAvailable} onClick={() => void requestQuote()}>重新报价</Button>, feeBusyReason || (!capabilities.planningAvailable ? '当前服务暂不可报价' : undefined))}><p>本次报价已到期，对话与历史仍然保留。请核对更新后的费用，再确认继续。</p></AssistantConfirmationSection> : null}
    {(contractStale || projectionInvalid) && !approvalAccepted ? <AssistantConfirmationSection presentation={presentation} role="alert" label="原报价需要更新" actions={<>{conversational ? feeHint(<Button disabled={disabled || !capabilities.planningAvailable} onClick={() => void requestQuote()}>重新报价</Button>, feeBusyReason || (!capabilities.planningAvailable ? '当前服务暂不可报价' : undefined)) : null}{presentation === 'canvas' && projectionInvalid ? refreshApprovalAction : null}</>}><p>{contractStale ? '助手输入约定已更新，原报价不能继续。' : '当前报价信息不完整或已变化。'}请重新报价；对话与历史保留，不重复发送消息。</p></AssistantConfirmationSection> : null}
    {projectionInvalid && approvalAccepted ? presentation === 'canvas'
      ? <AssistantConfirmationSection presentation={presentation} label="正在核实执行状态" role="status" actions={refreshApprovalAction}><p>原费用确认已受理，报价读取不完整不会重新批准或发起新调用。</p></AssistantConfirmationSection>
      : <p role="status">原费用确认已受理，正在核实执行状态；报价读取不完整不会重新批准或发起新调用。</p> : null}
    {projectionInvalid && presentation !== 'canvas' ? refreshApprovalAction : null}
  </>
  // Only the prerequisite is actionable while its draft is open. Updating it never approves generation.
  if (conversational && budgetDecision && budget) return <AssistantConfirmationSection presentation={presentation}
    label="本次费用确认" title={budgetExpired ? '更新本轮授权' : '核对本轮授权'}
    actions={!editingBudget && budgetRevisable ? feeHint(<Button type="primary" disabled={disabled || !budget.availableRunBudgetScope} onClick={() => setEditingBudget(true)}>{budgetExpired ? '更新费用确认' : '调整授权'}</Button>, feeEditReason) : null}>
    {snapshot.approval?.scopeKind === 'PLAN_EXECUTION' ? <p>待继续：{snapshot.approval.title || '本次生成'} · 费用上限 <strong>{snapshot.approval.maxCharge}</strong> 积分</p> : null}
    {editingBudget && budgetRevisable && budget.availableRunBudgetScope
      ? presentation === 'canvas'
        ? <StudioCanvasAssistantBudget renewal={budgetExpired} key={'renew:' + budget.policyRevision + ':' + budget.availableRunBudgetScope.scopeDigest} scope={budget.availableRunBudgetScope} policy={budget} disabled={disabled} onSubmit={revise} onCancel={() => setEditingBudget(false)} />
        : <AssistantRunBudgetEditor renderDisabledHint={feeHint} renewal={budgetExpired} key={'renew:' + budget.policyRevision + ':' + budget.availableRunBudgetScope.scopeDigest} scope={budget.availableRunBudgetScope} policy={budget} disabled={disabled} submitLabel={budgetExpired ? '更新费用确认' : '确认修改授权'} onSubmit={revise} onCancel={() => setEditingBudget(false)} />
      : <><p>{budgetRevisable ? '先核对授权，再确认生成。对话与已完成内容保留，更新授权本身不扣费。' : assistantBudgetRenewalBlockReason(budget)}</p>
        <p>总上限 {budget.absoluteMaxCharge} · 已结算 {budget.settled} · 剩余 {budget.remaining} 积分</p>
        {previzBudgetNeedsReview ? <p role="status">当前额度或次数不足以继续本次调用，本次最高费用 {quote?.quotedUpperBound} 积分。</p> : null}</>}
    {recoveryNotice ? <p role="status">{recoveryNotice}</p> : null}
    {error ? <div role="alert"><p>{error}</p>{refreshApprovalAction}</div> : null}
  </AssistantConfirmationSection>
  if (conversational) return <>
    {pageRecovery}
    {approvalAccepted ? <p role="status">本次费用已确认，正在同步执行状态，请勿重复确认。</p> : null}
    {renewalNotice ? <p role="status">{renewalNotice}</p> : null}
    {(recoveryHeld || !store.hasAutomaticIntent(snapshot) || store.getSnapshot().automaticNotice) && confirmable && budgetAvailable ? <AssistantConfirmationSection presentation={presentation} label="续授权后执行确认" fallbackClassName="assistant-generation-confirmation" actions={feeHint(<Button type="primary" disabled={disabled} onClick={() => void approve()}>确认继续</Button>, feeBusyReason)}><p>费用确认已更新。请核对本次调用最高消耗 {quote?.quotedUpperBound}，再明确继续执行。</p></AssistantConfirmationSection> : null}
    {waiting ? <AssistantExecutionPanel snapshot={snapshot} capabilities={capabilities} budget={budget} clock={clock} store={store} disabled={disabled} preparing={loading || approvalReconciling} conversational presentation={presentation} recoveryNotice={executionOwnsRecovery ? recoveryNotice : undefined} onLocateTarget={onLocateTarget} /> : null}
    {waiting && planning && !capabilities.planningAvailable ? <p className="assistant-inline-error" role="status">{capabilities.planningUnavailableReason || '当前助手暂不可用，请稍后重试。'}</p> : null}
    {planningPreparationBlocked && !expired ? <AssistantConfirmationSection presentation={presentation} label="创作准备未完成" fallbackClassName="assistant-inline-error" role="status" actions={feeHint(<Button type={presentation === 'canvas' ? 'default' : 'link'} disabled={disabled} onClick={() => void requestQuote()}>重新准备</Button>, feeBusyReason)}><span>本次创作准备未完成，请重新准备。</span></AssistantConfirmationSection> : null}
    {waiting && scopeStale ? <AssistantConfirmationSection presentation={presentation} label="创作内容已更新" fallbackClassName="assistant-inline-error" role="status" actions={feeHint(<Button type={presentation === 'canvas' ? 'default' : 'link'} disabled={disabled || !capabilities.planningAvailable} onClick={() => void requestQuote()}>重新准备</Button>, feeBusyReason || (!capabilities.planningAvailable ? '当前服务暂不可报价' : undefined))}><span>创作内容已更新，需要重新准备。</span></AssistantConfirmationSection> : null}
    {error ? <AssistantConfirmationSection presentation={presentation} label="费用同步失败" fallbackClassName="assistant-inline-error" role="alert" actions={feeHint(<Button type={presentation === 'canvas' ? 'primary' : 'link'} size="small" disabled={busy || loading} onClick={() => setRefreshEpoch((value) => value + 1)}>{presentation === 'canvas' ? '重新同步' : '重试'}</Button>, busy || loading ? '正在处理或核实原操作，请稍候' : undefined)}><span>{error}</span></AssistantConfirmationSection> : null}
  </>
  return <section className="assistant-card flex min-w-0 flex-col gap-3 rounded-xl border border-[var(--studio-ui-border,rgba(255,255,255,0.15))] p-4" aria-label="费用审批">
    {pageRecovery}
    {approvalAccepted ? <p role="status" className="m-0 text-xs">本次费用已确认，正在同步执行状态，请勿重复确认。</p> : null}
    {renewalNotice ? <p role="status" className="m-0 text-xs">{renewalNotice}</p> : null}
    {budgetExpired && !budgetRevisable ? <p role="status" className="m-0 text-xs">{assistantBudgetRenewalBlockReason(budget)}</p> : null}
    {budgetExpired ? <div className="flex flex-wrap items-center gap-2" aria-label="本次费用确认"><p role="status" className="m-0 text-sm">对话与历史保留。请更新费用确认，再查看当前费用并确认继续。</p><Button disabled={disabled || !budgetRevisable} onClick={() => setEditingBudget(true)}>更新费用确认</Button></div> : null}
    {budget ? <details className="assistant-run-budget" open={editingBudget || budget.scopeState !== 'CURRENT' ? true : undefined}><summary>运行预算 · {budget.mode === 'AUTO' ? '自动生成' : '手动生成'} · 已使用 {budget.settled}</summary><div className="flex min-w-0 flex-col gap-2" aria-label="运行总预算">
      <strong className="text-sm">运行总预算</strong>
      <p className="m-0 text-sm leading-6">授权上限 <strong>{budget.absoluteMaxCharge}</strong>；单次上限 {budget.perCallMaxCharge} 平台余额单位。</p>
      <p className="m-0 text-xs leading-5">已预留 {budget.reserved}；已结算 {budget.settled}；可用额度 {budget.remaining}（服务端核算）。</p>
      <p className="m-0 text-xs leading-5">{budget.mode === 'AUTO' ? '授权范围内自动执行' : '手动确认'} · 助手轮次 {budget.usedIterations}/{budget.maxIterations} · 操作 {budget.usedOperations}/{budget.maxOperations}<br />截止：{new Date(budget.expiresAt).toLocaleString()}</p>
      {budget.scopeState !== 'CURRENT' ? <p role="status" className="m-0 text-xs">上下文或助手版本已变化，原授权范围不可继续使用。请刷新上下文后重新读取并明确修改预算授权。</p> : null}
      {budgetExpired ? <p role="status" className="m-0 text-xs">需要更新本次费用确认，对话和历史结果仍保留。</p> : null}
      {!editingBudget || !budgetRevisable ? <Button disabled={disabled || !budgetRevisable || !budget.availableRunBudgetScope || ['STOPPED', 'SUCCEEDED', 'COMPLETED', 'FAILED'].includes(run.status)} onClick={() => setEditingBudget(true)}>{budgetExpired ? '更新费用确认' : '修改运行授权'}</Button> : budget.availableRunBudgetScope ? <AssistantRunBudgetEditor renewal={budgetExpired} key={'revise:' + budget.status + ':' + budget.policyRevision + ':' + budget.availableRunBudgetScope.scopeDigest}
        scope={budget.availableRunBudgetScope} policy={budget} disabled={disabled} submitLabel={budgetExpired ? '更新费用确认' : '更新费用确认'} onSubmit={revise} onCancel={() => setEditingBudget(false)} /> : null}
    </div></details> : null}
    {waiting ? <AssistantExecutionPanel snapshot={snapshot} capabilities={capabilities} budget={budget} clock={clock} store={store} disabled={disabled} /> : null}
    {showPlanning ? <><strong className="text-sm">{approval?.status === 'ACCEPTED' ? '本次调用已授权' : waiting && !planning ? '等待执行计划确认' : '本次助手调用费用'}</strong>
    {quote ? <>
      <p className="m-0 text-sm leading-6">预估消耗 {quote.estimatedAmount}；本次调用最高消耗 <strong>{quote.quotedUpperBound}</strong> 平台余额单位。</p>
      <p className="m-0 text-xs leading-5 text-[var(--studio-ui-muted,rgba(255,255,255,0.7))]">模型：{quote.modelCode}<br />报价有效期：{new Date(quote.expiresAt).toLocaleString()}</p>
      <p className="m-0 text-xs leading-5 text-[var(--studio-ui-muted,rgba(255,255,255,0.7))]">本次报价只覆盖一次主助手调用；后续消耗始终受已批准的运行总上限约束，不自动提高总额。</p>
      {!quoteValid || !quote.hardUpperBoundAvailable ? <p role="alert" className="m-0 text-xs text-[var(--studio-danger,#fde68a)]">当前报价不能保证费用硬上限，暂不可确认。</p> : null}
      {invalidated ? <p role="status" className="m-0 text-xs">运行授权已修改，原报价已失效。请按新授权重新报价。</p> : null}
      {approval?.status === 'QUOTED' && expired ? <p role="status" className="m-0 text-xs">报价已过期，请重新报价。</p> : null}
      {approval?.status === 'QUOTED' && approval.runRevision !== run.stateRevision ? <p role="status" className="m-0 text-xs">运行状态已变化，需重新核验报价。</p> : null}
      {approval?.status === 'ACCEPTED' ? <p role="status" className="m-0 text-xs leading-5">授权已保存。实际执行和扣费以任务状态为准。</p> : null}
    </> : null}
    {waiting && !capabilities.planningAvailable ? <p className="m-0 text-sm leading-6 text-[var(--studio-ui-muted,rgba(255,255,255,0.7))]">消息已保存。{capabilities.planningUnavailableReason || '当前服务尚未开放主助手调用。'}未发起模型生成。</p> : null}
    {scopeStale ? <p role="status" className="m-0 text-xs">原报价的授权范围已失效。请重新报价并核对范围，不会自动授权或执行。</p> : null}
    {waiting && planning && !approvalAccepted && capabilities.planningAvailable && (!approval || invalidated || (approval.status === 'QUOTED' && expired)) ? <Button loading={loading} disabled={disabled} onClick={() => void requestQuote()}>{expired || invalidated || scopeStale ? '重新报价' : '查看本次费用'}</Button> : null}
    {confirmable && approval && !budget && quote?.runBudgetScope ? <AssistantRunBudgetEditor key={'initial:' + approval.approvalId + ':' + quote.runBudgetScope.scopeDigest}
      scope={quote.runBudgetScope} preferredMode={executionPreference} quoteUpperBound={quoteAmount} disabled={disabled} submitLabel="确认运行上限并批准本次费用" onSubmit={approve} /> : null}
    {confirmable && !budget && !quote?.runBudgetScope ? <p role="status" className="m-0 text-xs">尚未取得完整运行授权范围，暂不可确认费用。</p> : null}
    {waiting && planning && approval?.status === 'QUOTED' && !expired && budget ? <><Button type="primary" disabled={disabled || !confirmable || !budgetAvailable} onClick={() => void approve()}>{recoveryHeld ? '确认继续' : '确认本次费用'}</Button>{!budgetAvailable ? <p role="status" className="m-0 text-xs">当前运行额度、次数、期限或范围不足，请核验后明确修改授权。</p> : null}</> : null}
    </> : null}
    {loading ? <p role="status" className="m-0 text-xs">正在恢复费用审批…</p> : null}
    {approvalReconciling ? <p role="status" className="m-0 text-xs">正在核实已受理任务，请查看运行步骤。核实完成前不会再次报价或发起生成。</p> : null}
    {error ? <><p className="m-0 text-xs text-[var(--studio-danger,#fde68a)]" role="alert">{error}</p><Button disabled={busy || loading} onClick={() => setRefreshEpoch((value) => value + 1)}>重新读取费用审批</Button></> : null}
  </section>
}
