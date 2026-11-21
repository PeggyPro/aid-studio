'use client'

import { useId, useState, type ReactNode } from 'react'
import { Button } from 'antd'
import type { AssistantRunBudgetInput, AssistantRunBudgetPolicy, AssistantRunBudgetScope } from '~/types/assistant-budget'
import { assistantRunBudgetInput, type AssistantBudgetFormValues } from '~/utils/assistant/assistantBudget'
import { assistantErrorMessage } from '~/utils/assistant/assistantRequestCoordinator'
import './assistant-confirmation.css'

function localDateTime(value: string) {
  const date = new Date(value)
  return Number.isFinite(date.getTime()) ? new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16) : ''
}

/** All hosts edit the same draft and submit through the existing authoritative validator. */
export function AssistantRunBudgetEditor({ scope, policy, preferredMode = 'MANUAL', quoteUpperBound, disabled, submitLabel, onSubmit, onCancel, renewal = false, settings = false, renderDisabledHint = control => control }: {
  renderDisabledHint?: (control: ReactNode, reason?: string) => ReactNode
  renewal?: boolean; settings?: boolean
  scope: AssistantRunBudgetScope; policy?: AssistantRunBudgetPolicy; quoteUpperBound?: string; disabled: boolean
  preferredMode?: 'MANUAL' | 'AUTO'
  submitLabel: string; onSubmit: (value: AssistantRunBudgetInput) => Promise<unknown>; onCancel?: () => void
}) {
  const modeName = useId()
  const initialMode = policy?.mode || (scope.availableModes.includes(preferredMode) ? preferredMode : scope.availableModes[0] || 'MANUAL')
  const [values, setValues] = useState<AssistantBudgetFormValues>(() => ({
    mode: initialMode, absoluteMaxCharge: policy?.absoluteMaxCharge || quoteUpperBound || '',
    perCallMaxCharge: policy?.perCallMaxCharge || quoteUpperBound || '', maxIterations: policy?.maxIterations || 1,
    maxOperations: policy?.maxOperations || 1, automaticConsent: false,
    expiresAt: localDateTime(!renewal && policy?.expiresAt || new Date(Date.now() + Math.min(scope.maxTtlSeconds, 3600) * 1000 - 1000).toISOString())
  }))
  const [previousMode, setPreviousMode] = useState(initialMode)
  const [error, setError] = useState<string | null>(null)
  if (!policy && previousMode !== initialMode) {
    setPreviousMode(initialMode)
    setValues(previous => ({ ...previous, mode: initialMode, automaticConsent: false }))
  }
  const update = <K extends keyof AssistantBudgetFormValues>(key: K, value: AssistantBudgetFormValues[K]) => {
    setError(null); setValues(previous => ({ ...previous, [key]: value }))
  }
  const submit = async () => {
    if (disabled) return
    try { const input = assistantRunBudgetInput(scope, values, { policy, quoteUpperBound }); setError(null); await onSubmit(input) }
    catch (cause) { setError(assistantErrorMessage(cause)) }
  }
  return <div className="assistant-budget-editor" aria-label="运行预算授权">
    <dl className="assistant-budget-summary">
      <div><dt>本轮总额度上限</dt><dd>{values.absoluteMaxCharge || '未设置'}<small>积分</small></dd></div>
      <div><dt>每次调用最多</dt><dd>{values.perCallMaxCharge || '未设置'}<small>积分</small></dd></div>
    </dl>
    <p className="assistant-budget-caption">{values.mode === 'AUTO' ? '授权内自动执行' : '手动确认生成'} · 最多 {values.maxIterations} 轮 / {values.maxOperations} 项操作<br />有效至 {new Date(values.expiresAt).toLocaleString()}，后续调用共用总额度。</p>
    <details className="assistant-budget-details" open={settings || undefined}><summary>调整额度与执行方式</summary>
      <div className="assistant-budget-settings">
        <label>本轮总额度上限<input className="assistant-field" inputMode="decimal" value={values.absoluteMaxCharge} disabled={disabled} onChange={event => update('absoluteMaxCharge', event.target.value)} /></label>
        <label>每次调用上限<input className="assistant-field" inputMode="decimal" value={values.perCallMaxCharge} disabled={disabled} onChange={event => update('perCallMaxCharge', event.target.value)} /></label>
        <label>最多助手轮次<input className="assistant-field" type="number" min={Math.max(1, policy?.usedIterations || 0)} max={scope.maxIterations} step={1} value={values.maxIterations} disabled={disabled} onChange={event => update('maxIterations', Number(event.target.value))} /></label>
        <label>最多操作次数<input className="assistant-field" type="number" min={Math.max(1, policy?.usedOperations || 0)} max={scope.maxOperations} step={1} value={values.maxOperations} disabled={disabled} onChange={event => update('maxOperations', Number(event.target.value))} /></label>
        <label className="assistant-budget-settings__wide">授权截止时间<input className="assistant-field" type="datetime-local" value={values.expiresAt} disabled={disabled} onChange={event => update('expiresAt', event.target.value)} /></label>
      </div>
      <fieldset className="assistant-budget-modes" disabled={disabled}><legend>执行方式</legend>
        {scope.availableModes.map(mode => <label key={mode}><input type="radio" name={modeName} checked={values.mode === mode} onChange={() => { update('mode', mode); update('automaticConsent', false) }} />{mode === 'AUTO' ? '授权内自动执行' : '手动确认'}</label>)}
      </fieldset>
    </details>
    <details className="assistant-budget-details"><summary>{policy ? '已结算 ' + policy.settled + ' 积分 · 查看用量与授权范围' : '查看本次授权范围'}</summary>
      <div className="assistant-budget-scope">
        {policy ? <p>已结算 {policy.settled}，已预留 {policy.reserved}；已使用 {policy.usedIterations} 轮、{policy.usedOperations} 项操作。更新授权不重置历史。</p> : null}
        <p>模型：{scope.allowedModelCodes.join('、') || '无'}</p><p>工具：{scope.allowedToolCodes.join('、') || '无'}</p>
        <p>能力：{scope.allowedCapabilityCodes.join('、') || '无'}；媒体类型：{scope.allowedMediaTypes.join('、') || '无'}</p>
        {scope.targetRefs.length ? scope.targetRefs.map((ref, index) => <p key={index}>目标：{ref.kind} · {ref.objectId || ref.entityId || ref.scriptId || ref.resourceId || ref.workspaceId || ref.projectId}{ref.version ? ' · 版本 ' + ref.version : ''}{ref.reason ? ' · ' + ref.reason : ''}</p>) : <p>当前无写入目标</p>}
        <p>仍需单独确认：{scope.automaticForbiddenOperationKinds.join('、') || '无额外类别'}。采用产物、外部发布不由自动模式代为授权。</p>
      </div>
    </details>
    {values.mode === 'AUTO' ? <label className="assistant-budget-consent"><input type="checkbox" checked={values.automaticConsent} disabled={disabled} onChange={event => update('automaticConsent', event.target.checked)} /><span>允许在上述额度、次数、期限与范围内自动执行；超出范围需重新确认。</span></label> : null}
    {renewal ? <p className="assistant-budget-caption">更新授权本身不扣费、不执行模型；新的调用可能产生费用。</p> : null}
    {error ? <p role="alert">{error}</p> : null}
    <div className="assistant-decision__actions">{renderDisabledHint(<Button type="primary" disabled={disabled || values.mode === 'AUTO' && !values.automaticConsent} onClick={() => void submit()}>{submitLabel}</Button>, disabled ? '正在核实费用或处理上次操作，请等待同步后继续' : values.mode === 'AUTO' && !values.automaticConsent ? '请先确认自动执行的授权范围' : undefined)}{onCancel ? renderDisabledHint(<Button type="text" disabled={disabled} onClick={onCancel}>返回</Button>, disabled ? '正在核实费用或处理上次操作，请等待同步后返回' : undefined) : null}</div>
  </div>
}
