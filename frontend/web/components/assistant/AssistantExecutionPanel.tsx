'use client'

import { assistantOperationQuoteAvailable } from '~/utils/assistant/assistantOperationQuote'
import { Button } from 'antd'
import type { ReactNode } from 'react'
import { AssistantConfirmationSection } from './AssistantConfirmationSection'
import type { AssistantCapabilities, AssistantSessionSnapshot } from '~/types/assistant-api'
import type { AssistantRunBudgetPolicy } from '~/types/assistant-budget'
import type { AssistantSessionStore } from '~/utils/assistant/assistantSessionStore'
import { assistantExecutionStatus, assistantOperationNeedsMediaConfirmation, assistantOperationTitle, assistantOperationWithinApprovedScope } from '~/utils/assistant/assistantExecution'
import { assistantArtifactLabel } from '~/utils/assistant/assistantArtifacts'
import type { AssistantEffectHandler } from '~/types/assistant-effects'
import { assistantApprovalContentLabel, assistantApprovalPromptEffect, assistantConversationCharge } from '~/utils/assistant/assistantConversationPresentation'
import { assistantBudgetUnits } from '~/utils/assistant/assistantBudget'
import { useAssistantExecution } from '~/hooks/assistant/useAssistantExecution'
import { assistantBudgetRecoveryHeld } from '~/utils/assistant/assistantBudgetRecovery'
import { StudioCanvasTooltip } from '~/components/studio/canvas/StudioCanvasTooltip'
import { AssistantPromptAction } from './AssistantConversationTimeline'

/** 计划批准与单项操作批准使用两个正式命令，均只回传绑定版本。 */
export function AssistantExecutionPanel({ snapshot, capabilities, budget, clock, store, disabled, preparing = false, conversational = false, onLocateTarget, presentation, recoveryNotice }: { snapshot: AssistantSessionSnapshot; capabilities: AssistantCapabilities; budget: AssistantRunBudgetPolicy | null; clock: number; store: AssistantSessionStore; disabled: boolean; preparing?: boolean; conversational?: boolean; presentation?: 'canvas'; onLocateTarget?: AssistantEffectHandler; recoveryNotice?: string | null }) {
  const { run, plan, approval, confirmPlan, canApprove, approve, confirming, planAccepted, operationAccepted } = useAssistantExecution({ snapshot, capabilities, budget, clock, store, disabled, conversational, presentation })
  if (!run) return null
  const blockedDependencyCount = conversational
    ? (plan?.steps.filter(step => step.inputResolutionErrorCode === 'PLAN_DEPENDENCY_FAILED').length ?? 0)
    : 0
  const withRecovery = (content: ReactNode) => <>
    {recoveryNotice ? <p className="assistant-inline-status" role="status">{recoveryNotice}</p> : null}
    {blockedDependencyCount > 0 ? <p className="assistant-inline-error" role="status">前置任务失败，{blockedDependencyCount} 个依赖步骤未提交。</p> : null}
    {content}
  </>
  if ((budget?.mode || run.executionPreference) === 'AUTO'
    && run.nextAction === 'PLAN_EXECUTION_APPROVAL_REQUIRED' && !run.stopRequested)
    return withRecovery(<p className="assistant-inline-status" role="status">自动模式：系统正在校验并继续执行计划，无需确认。</p>)
  const referenceChallenge = store.operationReferenceChallenge(snapshot)
  const referenceActive = !!referenceChallenge && Date.parse(referenceChallenge.expiresAt) > clock
  const referenceSummary = referenceChallenge ? <div role="status" aria-label="核对当前素材"><p>原素材缺少可核验的版本记录。请核对服务端当前素材，再重新报价；此操作不提交生成。</p><ul>{referenceChallenge.resources.map(resource => <li key={resource.resourceId} className="break-all">素材 {resource.resourceId} · {resource.mediaType} · 版本标识 {resource.identityHash}</li>)}</ul>{!referenceActive ? <p>素材核对信息已到期，请重新获取后再核对。</p> : null}</div> : null
  const quoteLabel = referenceChallenge ? referenceActive ? '核对当前素材并重新报价' : '重新获取素材核对' : '重新报价'
  const quoteReceipt = store.operationQuoteReceipt(snapshot)
  const quoteSynchronizing = store.operationQuoteSynchronizing(snapshot)
  const confirmOffered = !!approval && approval.status === 'QUOTED' && approval.allowedActions.includes('CONFIRM_APPROVAL')
  const quoteExpired = !!approval?.expiresAt && Number.isFinite(Date.parse(approval.expiresAt)) && Date.parse(approval.expiresAt) <= clock && clock > 0
  const operationQuoteNeeded = !confirmOffered && (!!referenceChallenge || quoteExpired || approval?.status === 'INVALIDATED')
  const sessionState = store.getSnapshot()
  const quoteScopeRejected = sessionState.lastRejectedCommand?.action === 'operationQuote'
    && sessionState.lastRejectedCommand.source === 'ACKNOWLEDGED_REJECTION'
    && sessionState.lastRejectedCommand.sessionId === snapshot.sessionId
    && (sessionState.lastRejectedCommand.errorCode === 'OPERATION_SCOPE_INVALID' || /批准范围|审批范围/.test(sessionState.error || ''))
  const outsideApprovedScope = !!approval && !!budget && !assistantOperationWithinApprovedScope(approval, budget, clock)
  const scopeBlocked = operationQuoteNeeded && !referenceChallenge && (quoteScopeRejected || outsideApprovedScope)
  const canRequote = !disabled && !operationAccepted && !quoteSynchronizing && capabilities.executionAvailable
    && assistantOperationQuoteAvailable(snapshot, budget, clock)
  if (quoteSynchronizing && quoteReceipt && !confirming) return withRecovery(<AssistantConfirmationSection presentation={presentation} label="同步操作报价" role="status" approvalId={quoteReceipt.result.approvalId} fallbackClassName="flex flex-col gap-2" actions={presentation === 'canvas' && disabled ? <StudioCanvasTooltip title="正在核实原操作，请等待同步后继续"><Button disabled onClick={() => void store.refresh()}>重新同步</Button></StudioCanvasTooltip> : <Button disabled={disabled} onClick={() => void store.refresh()}>重新同步</Button>}>
    <p>本次操作最新最高费用为 {quoteReceipt.result.quote.quotedUpperBound} 平台余额单位。有效期：{new Date(quoteReceipt.result.quote.expiresAt).toLocaleString()}。</p>
    <p>正在同步更新后的操作报价；尚未确认费用，也未提交执行。</p>
  </AssistantConfirmationSection>)
  const requestOperationQuote = () => { if (canRequote) void store.requestOperationQuote(snapshot, referenceActive ? referenceChallenge!.referenceRevalidationToken : undefined) }

  const automaticOwner = store.hasAutomaticIntent(snapshot) && !store.getSnapshot().automaticNotice && !assistantBudgetRecoveryHeld(store, snapshot, budget)
  if (conversational) {
    if (operationAccepted) return withRecovery(<p className="assistant-inline-status" role="status">已受理本次操作，正在同步执行状态，请勿重复确认。</p>)
    if (planAccepted && !approval) return withRecovery(<p className="assistant-inline-status" role="status">已受理执行计划，正在同步最新状态。</p>)
    if (plan?.replanRequired) return withRecovery(<p className={`assistant-inline-error${presentation === 'canvas' ? ' canvas-assistant-status-shimmer' : ''}`} role="status">正在重新整理创作方案，已完成的内容会保留。</p>)
    if (!capabilities.executionAvailable && (plan || approval)) return withRecovery(<p className="assistant-inline-error" role="status">{capabilities.executionUnavailableReason || '当前创作服务暂不可用。'}</p>)
    if (plan && !approval && (budget?.mode || run.executionPreference) !== 'AUTO' && plan.allowedActions.includes('APPROVE_PLAN')) {
      const canConfirm = !planAccepted && !plan.replanRequired && run.status === 'WAITING_APPROVAL' && !run.stopRequested && capabilities.executionAvailable
        && plan.allowedActions.includes('APPROVE_PLAN') && /^[a-f0-9]{64}$/.test(plan.definitionDigest)
      const button = <Button type={presentation === 'canvas' ? 'primary' : 'default'} disabled={disabled || !canConfirm} onClick={confirmPlan}>确认执行计划</Button>
      return <AssistantConfirmationSection presentation={presentation} label="确认继续计划" actions={presentation === 'canvas' && (disabled || !canConfirm) ? <StudioCanvasTooltip title="正在核实运行状态或计划暂不可确认，请同步后再继续">{button}</StudioCanvasTooltip> : button}>
        <p role="status">{recoveryNotice || '当前页面仅同步查看。明确确认计划后按现有费用范围继续，新的调用仍可能产生费用。'}</p>
        {recoveryNotice ? <p>沿用已授权的费用范围，新的调用仍可能产生费用。</p> : null}
      </AssistantConfirmationSection>
    }
    if (!approval || !['QUOTED', 'INVALIDATED'].includes(approval.status)) return withRecovery(null)
    const amount = assistantConversationCharge(approval.maxCharge)
    // Readiness is separate from authority: a pending budget read is not an execution rejection.
    if (!operationQuoteNeeded && automaticOwner && !assistantOperationNeedsMediaConfirmation(snapshot)
      && (assistantBudgetUnits(approval.maxCharge) === 0n || budget?.mode === 'AUTO' && !budget.automaticForbiddenOperationKinds.includes(approval.operationKind || ''))) return canApprove || presentation === 'canvas' && preparing ? null : <p className="assistant-inline-error" role="status">本次生成暂无法启动，请重新核对当前会话。</p>
    const decline = <Button type="text" disabled={disabled || !run.allowedActions.includes('STOP')} onClick={() => void store.control('stop')}>暂不生成</Button>
    if (operationQuoteNeeded && scopeBlocked) {
      const sync = <Button type="primary" disabled={disabled} onClick={() => void store.refresh()}>重新同步</Button>
      return withRecovery(<AssistantConfirmationSection presentation={presentation} label="生成超出批准范围" title="生成范围需要核对" role="alert" approvalId={approval.approvalId} actions={<>{sync}{presentation === 'canvas' ? decline : null}</>}>
          <div className="assistant-generation-confirmation-detail">
            <span>{quoteScopeRejected && sessionState.error ? sessionState.error : '已生成的费用不能重新报价。'}</span>
            <span>请同步最新状态；对话和已完成内容仍保留。</span>
            {amount !== null ? <span>已生成费用 <strong className="assistant-generation-confirmation-amount">{amount}</strong> 积分</span> : null}
          </div>
      </AssistantConfirmationSection>)
    }
    if (operationQuoteNeeded) {
      const button = <Button type="primary" disabled={!canRequote} onClick={requestOperationQuote}>{quoteLabel}</Button>
      return withRecovery(<AssistantConfirmationSection presentation={presentation} label="更新操作费用" title={referenceChallenge ? '请先核对当前素材' : '更新本次费用'} approvalId={approval.approvalId} actions={<>
        {presentation === 'canvas' && !canRequote ? <StudioCanvasTooltip title="请先核实原请求、同步当前运行与费用授权；停止或未受理状态不明时不能重新报价">{button}</StudioCanvasTooltip> : button}
        {presentation === 'canvas' ? decline : null}
      </>}>
          <div className="assistant-generation-confirmation-detail">
            {amount !== null ? <span>已生成费用 <strong className="assistant-generation-confirmation-amount">{amount}</strong> 积分</span> : null}
            <span>重新报价只更新费用，不提交生成。</span>
          </div>
          {referenceSummary}
      </AssistantConfirmationSection>)
    }
    const effect = assistantApprovalPromptEffect(snapshot, approval)
    const label = assistantApprovalContentLabel(approval)
    const title = approval.title && approval.title !== approval.toolCode && approval.title !== approval.skillCode
      ? approval.title : `${label}已准备`
    return withRecovery(<AssistantConfirmationSection presentation={presentation} label="确认生成" title={label === '图片' ? '生成图片' : label === '视频' ? '生成视频' : title}
      approvalId={approval.approvalId} modelCode={approval.modelCode} toolCode={approval.toolCode}
      detail={effect ? <AssistantPromptAction effect={effect} onLocateTarget={onLocateTarget} label="查看提示词" /> : null}
      actions={<><Button type="primary" loading={confirming} disabled={disabled || !canApprove || confirming} onClick={approve}>确认生成</Button>{decline}</>}>
        <div className="assistant-decision__cost">{amount === null ? <span>费用暂不可读取</span> : <><span>本次费用上限</span><span><strong>{amount}</strong> 积分</span></>}</div>
        {confirming ? <p role="status" className="canvas-assistant-status-shimmer">正在同步本次费用并提交生成…</p> : quoteReceipt ? <p role="status">报价已更新，请核对当前费用，再确认本次操作。</p> : null}
        {!canApprove && !confirming ? <p className={presentation === 'canvas' ? 'assistant-inline-status' : 'assistant-inline-error'} role="status">{presentation === 'canvas' ? '本次操作暂不可确认，请核对最新状态和费用授权。' : '当前确认已失效或可用额度不足，请重新核对。'}</p> : null}
    </AssistantConfirmationSection>)
  }
  return <>
    {plan ? <section aria-label="执行计划" className="flex flex-col gap-2">
      <strong className="text-sm">{plan.title || '执行计划'} · 版本 {plan.revision} · {assistantExecutionStatus(plan.status)}</strong>
      {plan.goal ? <p className="whitespace-pre-wrap text-sm leading-6">{plan.goal}</p> : null}
      {plan.replanRequired ? <p role="status" className="text-xs">此计划的依赖产物未通过核验，需要重新规划。原消息、计划和已完成步骤已保留；不会跳过校验执行。</p> : null}
      {plan.assumptions.length ? <details><summary className="cursor-pointer text-xs">计划前提</summary><ul className="list-inside list-disc text-xs leading-6">{plan.assumptions.map((value, i) => <li key={i}>{value}</li>)}</ul></details> : null}
      {plan.deliverables.length ? <div className="text-xs"><span>预期产物</span><ul className="list-inside list-disc leading-6">{plan.deliverables.map((value, i) => <li key={i}>{value}</li>)}</ul></div> : null}
      <ol className="list-inside list-decimal space-y-2 text-xs leading-6">{plan.steps.map((step) => {
        const dependencyFailed = step.inputResolutionErrorCode === 'PLAN_DEPENDENCY_FAILED'
        return <li key={step.stableStepId}>
          {assistantOperationTitle(step.operationKind)}{step.bindingCode ? ' · ' + step.bindingCode : ''}
          {dependencyFailed ? <span className="block" role="status">前置任务失败，本步骤未提交</span> : null}
          {!dependencyFailed && step.dependsOn.length ? <span className="block">在第 {step.dependsOn.map((id) => { const index = plan.steps.findIndex((item) => item.stableStepId === id); return index >= 0 ? index + 1 : '待核验' }).join('、')} 步完成后执行</span> : null}
          {(step.inputArtifactBindings || []).length ? <div className="rounded border border-white/10 p-2" aria-label="批准的依赖产物输入">
            <strong>批准读取以下步骤产物</strong>
            <ul className="list-inside list-disc">{step.inputArtifactBindings.map((binding, index) => {
              const sourceIndex = plan.steps.findIndex((item) => item.stableStepId === binding.sourceStableStepId)
              const source = sourceIndex >= 0 ? plan.steps[sourceIndex] : null
              return <li key={index} className="break-all">{source ? `第 ${sourceIndex + 1} 步${source.title ? '「' + source.title + '」' : ''}` : '待核验来源步骤'}（{binding.sourceStableStepId}）的「{binding.logicalArtifactId}」 · {assistantArtifactLabel(binding.artifactType)}</li>
            })}</ul>
            {!dependencyFailed ? <p>{step.inputResolutionErrorCode ? '该步骤的依赖产物无法核验，需要重新规划。' : step.inputsResolved === true ? '具体产物版本已由服务端核验。' : '等待上游产物归档；服务端只会绑定以上已批准的来源和类型。'}</p> : null}
          </div> : null}
          {(step.inputArtifactRefs || []).length ? <details><summary className="cursor-pointer">批准读取的已有产物版本</summary><ul>{step.inputArtifactRefs.map((ref) => <li key={ref} className="break-all">{ref}</li>)}</ul></details> : null}
          {step.inputsResolved === true && (step.resolvedInputArtifactRefs || []).length ? <details><summary className="cursor-pointer">实际绑定的输入版本</summary><ul>{step.resolvedInputArtifactRefs.map((ref) => <li key={ref} className="break-all">{ref}</li>)}</ul></details> : null}
          {step.maxAttempts && !dependencyFailed ? <span className="block">最多尝试 {step.maxAttempts} 次，仍受运行预算约束</span> : null}
        </li>
      })}</ol>
      {plan.completionCriteria.length ? <details><summary className="cursor-pointer text-xs">完成标准</summary><ul className="list-inside list-disc text-xs leading-6">{plan.completionCriteria.map((item) => <li key={item.criterionId}>{item.description}</li>)}</ul></details> : null}
      {plan.allowedActions.includes('APPROVE_PLAN') ? <><p className="text-xs">确认此版本的步骤及明确列出的产物输入范围；具体消耗仍受运行授权和单项审批约束。</p><Button type="primary" disabled={disabled || planAccepted || !!plan.replanRequired || run.status !== 'WAITING_APPROVAL' || !capabilities.executionAvailable || !/^[a-f0-9]{64}$/.test(plan.definitionDigest)} onClick={confirmPlan}>确认执行计划</Button></> : null}
    </section> : null}
    {approval ? <section aria-label="操作费用审批" data-assistant-operation-approval-id={approval.approvalId} data-assistant-operation-model-code={approval.modelCode} data-assistant-operation-tool-code={approval.toolCode} className="flex flex-col gap-2">
      <strong className="text-sm">{approval.status === 'ACCEPTED' ? '本次操作已授权' : '确认本次操作'}</strong>
      <p className="text-sm">{assistantOperationTitle(approval.operationKind)}{approval.title ? ' · ' + approval.title : ''}</p>
      {approval.modelCode ? <p className="text-xs">模型：{approval.modelCode}</p> : null}
      {approval.toolCode || approval.skillCode ? <p className="text-xs">{approval.toolCode ? '工具：' + approval.toolCode : '创作方法：' + approval.skillCode}</p> : null}
      <p className="text-sm">本次操作最高消耗 <strong>{approval.maxCharge}</strong> 平台余额单位；使用已批准的总额度，不增加运行上限。</p>
      {approval.expiresAt ? <p className="text-xs">有效期：{new Date(approval.expiresAt).toLocaleString()}</p> : null}
      {approval.targetRefs.length ? <details><summary className="cursor-pointer text-xs">操作绑定的目标版本</summary><ul className="list-inside list-disc text-xs leading-6">{approval.targetRefs.map((ref, index) => <li key={index} className="break-all">{JSON.stringify(ref)}</li>)}</ul></details> : null}
      {operationAccepted ? <p role="status" className="text-xs">已受理本次操作，正在同步执行状态，请勿重复确认。</p> : scopeBlocked ? <p role="status" className="text-xs">这次生成超出当前已批准范围。请同步最新状态后再继续。</p> : operationQuoteNeeded && ['QUOTED', 'INVALIDATED'].includes(approval.status) ? <><p role="status" className="text-xs">费用需要按当前授权重新核对。重新报价只更新费用，不提交执行。</p>{referenceSummary}<Button disabled={!canRequote} onClick={requestOperationQuote}>{quoteLabel}</Button></> : approval.status === 'QUOTED' ? <><Button type="primary" disabled={disabled || !canApprove} onClick={approve}>确认本次操作费用</Button>{!canApprove ? <p role="status" className="text-xs">此操作的额度、次数、期限、状态或执行能力尚不满足授权条件，请先核对运行授权。</p> : null}</> : <p role="status" className="text-xs">{assistantExecutionStatus(approval.status)}；完成与费用结算以实际任务结果为准。</p>}
    </section> : null}
    {!capabilities.executionAvailable && (plan || approval) ? <p role="status" className="text-xs">{capabilities.executionUnavailableReason || '当前服务尚未开放此计划的执行能力。'}</p> : null}
  </>
}
