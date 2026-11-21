'use client'

import type { AssistantConversationView } from '@/components/assistant/AssistantSessionPanel'
import { StudioCanvasAssistantMessages } from './StudioCanvasAssistantMessages'
import { StudioCanvasAssistantQuestion } from './StudioCanvasAssistantQuestion'
import { StudioCanvasAssistantDecision } from './StudioCanvasAssistantDecision'
import { StudioCanvasAssistantAction } from './StudioCanvasAssistantTurn'

/** 保留历史分支呈现；当前画布使用 AssistantConversationTimeline，不同时挂载此入口。 */
export function StudioCanvasAssistantConversation(view: AssistantConversationView) {
  const { snapshot, store, busy } = view
  const run = snapshot.activeRun
  const input = snapshot.inputRequest
  const showQuestion = input && ['OPEN', 'DRAFTING', 'SUSPENDED'].includes(input.status) && !run?.stopRequested
  return <>
    <StudioCanvasAssistantMessages snapshot={snapshot} pendingPrompt={view.pendingPrompt} pendingState={view.pendingState} />
    {showQuestion ? <StudioCanvasAssistantQuestion key={input.requestId + ':' + input.schemaDigest} input={input} store={store} busy={busy} /> : null}
    {run?.allowedActions.includes('RESUME') && !run.stopRequested && !showQuestion ? <div className="canvas-assistant-actions"><StudioCanvasAssistantAction primary disabled={busy} reason={busy ? '正在同步会话' : undefined} onClick={() => void store.control('resume')}>继续创作</StudioCanvasAssistantAction></div> : null}
    <StudioCanvasAssistantDecision snapshot={snapshot} capabilities={view.capabilities} store={store} busy={busy} executionPreference={view.executionPreference} onBudgetMode={view.onBudgetMode} records={<>
      <div className="canvas-assistant-actions">
        {run?.allowedActions.includes('PAUSE') && !run.stopRequested ? <StudioCanvasAssistantAction disabled={busy} reason={busy ? '正在处理上次操作' : undefined} onClick={() => void store.control('pause')}>暂停后续执行</StudioCanvasAssistantAction> : null}
        {view.canRefreshContext ? <StudioCanvasAssistantAction disabled={busy || view.refreshingContext} reason={busy || view.refreshingContext ? '正在保存引用或处理上次操作' : undefined} onClick={view.refreshContext}>更新引用内容</StudioCanvasAssistantAction> : null}
      </div>
      {view.records}
    </>} />
  </>
}
