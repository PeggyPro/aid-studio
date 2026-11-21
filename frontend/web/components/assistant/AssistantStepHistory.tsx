'use client'

import { useEffect, useMemo, useState, useSyncExternalStore } from 'react'
import { Button } from 'antd'
import type { AssistantSessionSnapshot } from '~/types/assistant-api'
import type { AssistantSessionStore } from '~/utils/assistant/assistantSessionStore'
import { AssistantStepHistoryStore } from '~/utils/assistant/assistantStepHistory'
import { assistantExecutionStatus, assistantOperationTitle, assistantSavedArtifactCount } from '~/utils/assistant/assistantExecution'
import type { AssistantEffectHandler } from '~/types/assistant-effects'
import { AssistantEffectAction } from './AssistantEffectAction'

export function AssistantStepHistory({ snapshot, store, onLocateTarget }: { snapshot: AssistantSessionSnapshot; store: AssistantSessionStore; onLocateTarget?: AssistantEffectHandler }) {
  const runIds = [...new Set([snapshot.activeRun?.runId, ...[...snapshot.messages].reverse().map((item) => item.runId)].filter((id): id is string => !!id))]
  const [selected, setSelected] = useState('')
  const runId = runIds.includes(selected) ? selected : runIds[0]
  if (!runId) return null
  return <section aria-label="运行步骤历史" className="flex flex-col gap-2">
    {runIds.length > 1 ? <details><summary className="cursor-pointer text-xs">选择步骤所属轮次</summary><div className="flex flex-wrap gap-2">{runIds.map((id, index) => <Button key={id} size="small" type={id === runId ? 'primary' : 'default'} onClick={() => setSelected(id)}>{index === 0 ? '最近轮次' : '历史轮次 ' + index}</Button>)}</div></details> : null}
    <StepList key={snapshot.sessionId + ':' + runId} snapshot={snapshot} store={store} runId={runId} onLocateTarget={onLocateTarget} />
  </section>
}

function StepList({ snapshot, store, runId, onLocateTarget }: { snapshot: AssistantSessionSnapshot; store: AssistantSessionStore; runId: string; onLocateTarget?: AssistantEffectHandler }) {
  const history = useMemo(() => new AssistantStepHistoryStore(store.api, snapshot.sessionId, runId), [store, snapshot.sessionId, runId])
  const state = useSyncExternalStore(history.subscribe, history.getSnapshot, history.getSnapshot)
  useEffect(() => { history.activate(); return history.dispose }, [history])
  useEffect(() => { history.observe(snapshot) }, [history, snapshot])
  return <details onToggle={(event) => { if (event.currentTarget.open && !history.getSnapshot().loaded) void history.refresh() }}>
    <summary className="cursor-pointer text-xs">运行步骤与恢复状态{state.items.length ? ' · 已读取 ' + state.items.length + ' 项' : ''}</summary>
    <div className="mt-2 flex flex-col gap-2">
      <p className="text-xs">按最近步骤在前显示。历史页分别读取当时状态，刷新会核对最新一页；不会重试或执行步骤。</p>
      {state.runStatus ? <p role="status" className="text-xs">本轮状态：{assistantExecutionStatus(state.runStatus)}</p> : null}
      <p className="text-xs">步骤完成仅表示该步已执行。生成成功请以实际产物和画布节点为准。</p>
      <Button size="small" loading={state.loading} onClick={() => void history.refresh()}>刷新步骤</Button>
      {state.error ? <p role="alert" className="text-xs">{state.error}</p> : null}
      <ol className="list-inside list-decimal space-y-3 text-xs leading-6">{state.items.map((step) => {
        const savedArtifactCount = assistantSavedArtifactCount(step.outputArtifactRefs)
        return <li key={step.stepId}>
        {assistantOperationTitle(step.operationKind || step.stepKind)} · {assistantExecutionStatus(step.status)} · 第 {step.attemptNo} 次尝试
        {step.toolCode ? <span className="block">工具：{step.toolCode}</span> : null}
        {state.runStatus === 'STOPPED' && step.status === 'PREPARING' && step.providerAcceptanceState === 'NOT_SUBMITTED' && !step.mediaTaskId && !step.approvalId ? <p role="status">此轮已停止。以上为停止前的准备记录，未提交生成任务。</p> : null}
        {step.providerAcceptanceState === 'UNKNOWN' ? <p role="status">上游是否受理尚待核实，请勿重复生成。</p> : null}
        {step.safeMessage ? <p role="alert">{step.safeMessage}</p> : null}
        {savedArtifactCount ? <span className="block">已保存 {savedArtifactCount} 项产物，可在产物列表打开。</span> : null}
        {step.effects?.map((effect) => <AssistantEffectAction key={effect.effectId} effect={effect} onLocateTarget={onLocateTarget} />)}
      </li>})}</ol>
      {state.loaded && !state.items.length ? <p className="text-xs">此轮尚无步骤记录。</p> : null}
      {state.nextCursor ? <Button size="small" loading={state.loading} onClick={() => void history.more()}>加载更早步骤</Button> : null}
    </div>
  </details>
}
