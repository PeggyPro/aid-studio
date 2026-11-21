'use client'

import { useEffect, useState } from 'react'
import { BulbOutlined, InfoCircleOutlined } from '@ant-design/icons'
import type { AssistantMessage, AssistantSessionSnapshot } from '@/types/assistant-api'
import { AgentThinkingProcess } from '@/components/common/agent-conversation/AgentThinkingProcess'
import { markdownToStoryScriptEditorHtml } from '@/utils/htmlPlain'
import { canvasAssistantActivity } from '@/utils/studio/canvasAssistantPresentation'

function ConversationMessage({ item }: { item: AssistantMessage }) {
  if (item.role === 'system') return <details className="canvas-assistant-system"><summary><InfoCircleOutlined aria-hidden />会话提示</summary><p>{item.content}</p></details>
  const note = item.messageKind === 'USER_INTERJECTION_PENDING' ? '补充已保存，等待任务核实后应用'
    : item.messageKind === 'USER_INTERJECTION_CANCELLED' ? '补充已保存，已停止应用'
    : item.messageKind === 'USER_INTERJECTION' ? '补充要求' : ''
  return <article className={`assistant-message canvas-assistant-message is-${item.role}`} aria-label={item.role === 'user' ? '你的消息' : '助手回复'}>
    {item.role === 'assistant' ? <div className="canvas-assistant-message__author"><span><BulbOutlined aria-hidden /></span>画布助手</div> : null}
    {note ? <small className="canvas-assistant-message__note">{note}</small> : null}
    {item.role === 'assistant' ? <div className="canvas-assistant-prose" dangerouslySetInnerHTML={{ __html: markdownToStoryScriptEditorHtml(item.content) }} /> : <p>{item.content}</p>}
  </article>
}

/** 计时只表示本页面观察到的处理时长，刷新不伪造历史思考耗时。 */
function ConversationActivity({ snapshot, phaseKey }: { snapshot: AssistantSessionSnapshot; phaseKey?: string }) {
  const activity = canvasAssistantActivity(snapshot)
  const live = activity?.kind === 'thinking'
  const [timing, setTiming] = useState<{ phaseKey?: string; started: number; completed?: number } | null>(null)
  useEffect(() => {
    // 外部快照进入/离开处理阶段时读取真实时钟，历史快照不参与计时。
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (live) setTiming((current) => current?.completed || !current || current.phaseKey !== phaseKey ? { phaseKey, started: Date.now() } : current)
    else setTiming((current) => current && !current.completed ? { ...current, completed: Date.now() } : current)
  }, [live, phaseKey])
  return <>
    {live || timing ? <div className="canvas-assistant-thinking" role={live ? 'status' : undefined} aria-label={live ? '助手正在思考' : '本页面的处理记录'}>
      <AgentThinkingProcess live={live} startedAt={timing?.started} completedAt={timing?.completed}
        steps={live ? [{ id: 'assistant-thinking', kind: 'analyze', label: activity?.label || '正在思考…', status: 'active' }] : []} />
    </div> : null}
  </>
}

/** 后续主助手轮次的思考放在已有回复之后，完成后停留在本次回复之前。 */
function ConversationGroup({ items, snapshot }: { items: AssistantMessage[]; snapshot?: AssistantSessionSnapshot }) {
  const live = snapshot && canvasAssistantActivity(snapshot)?.kind === 'thinking'
  const phaseKey = live ? snapshot.steps.find((step) => ['PREPARING', 'READY', 'SUBMITTING', 'INFLIGHT', 'RUNNING', 'WAITING_TASK', 'WAITING_SKILL'].includes(step.status) && (step.stepKind === 'MAIN_AGENT' || step.operationKind === 'ASSISTANT_DECISION'))?.stepId || snapshot.activeRun?.status : undefined
  const latestSeq = items.at(-1)?.seq || 0
  const [position, setPosition] = useState({ phaseKey, afterSeq: latestSeq })
  const afterSeq = phaseKey && phaseKey !== position.phaseKey ? latestSeq : position.afterSeq
  if (position.phaseKey !== phaseKey) setPosition({ phaseKey, afterSeq })
  return <div className="canvas-assistant-message-group">
    {items.filter((item) => item.seq <= afterSeq).map((item) => <ConversationMessage key={item.id} item={item} />)}
    {snapshot ? <ConversationActivity snapshot={snapshot} phaseKey={phaseKey} /> : null}
    {items.filter((item) => item.seq > afterSeq).map((item) => <ConversationMessage key={item.id} item={item} />)}
  </div>
}

export function StudioCanvasAssistantMessages({ snapshot, pendingPrompt, pendingState = 'sending' }: { snapshot: AssistantSessionSnapshot; pendingPrompt?: string; pendingState?: 'sending' | 'accepted' | 'unknown' }) {
  const runId = snapshot.activeRun?.runId
  const activity = canvasAssistantActivity(snapshot)
  const groups: Array<{ key: string; runId?: string; items: AssistantMessage[] }> = []
  for (const item of snapshot.messages) {
    const group = groups.at(-1)
    if (item.runId && group?.runId === item.runId) group.items.push(item)
    else groups.push({ key: item.id, runId: item.runId, items: [item] })
  }
  const activeGroup = [...groups].reverse().find((group) => group.runId === runId && Boolean(runId))
  const pendingLabel = pendingState === 'accepted' ? '已发送，等待同步' : pendingState === 'unknown' ? '发送结果待核实' : '正在发送…'
  return <>
    {groups.map((group) => <ConversationGroup key={snapshot.sessionId + ':' + group.key} items={group.items} snapshot={group === activeGroup ? snapshot : undefined} />)}
    {pendingPrompt ? <article className="assistant-message canvas-assistant-message is-user is-pending" aria-label={pendingState === 'sending' ? '正在发送的消息' : '等待同步的消息'}><p>{pendingPrompt}</p><small>{pendingLabel}</small></article> : null}
    {runId && !activeGroup ? <ConversationActivity key={snapshot.sessionId + ':' + runId} snapshot={snapshot} /> : null}
    {activity && activity.kind !== 'thinking' && snapshot.activeRun?.status !== 'WAITING_APPROVAL' && !(snapshot.activeRun?.status === 'WAITING_INPUT' && ['OPEN', 'DRAFTING', 'SUSPENDED'].includes(snapshot.inputRequest?.status || '')) ? <div className={`canvas-assistant-activity is-${activity.kind}`} role={activity.kind === 'error' ? 'alert' : 'status'}>
      <span className="canvas-assistant-activity__dot" aria-hidden />
      <div><strong>{activity.label}</strong><p>{activity.detail}</p></div>
    </div> : null}

  </>
}
