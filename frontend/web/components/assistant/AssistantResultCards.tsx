'use client'

import { assistantTaskPhase } from '~/utils/assistant/assistantTaskPhase'

import { AssistantTaskEta } from './AssistantTaskEta'
import type { AssistantBudget, AssistantRunStep, AssistantTask } from '~/types/assistant-api'
import type { AssistantSessionStore } from '~/utils/assistant/assistantSessionStore'
import { assistantExecutionStatus } from '~/utils/assistant/assistantExecution'

export function AssistantBudgetCard({ budget }: { budget: AssistantBudget }) {
  return <section className="assistant-card assistant-card-compact rounded-lg border border-[var(--studio-ui-border,rgba(255,255,255,0.15))] p-3 text-xs leading-6" aria-label="本次运行费用">
    <div>累计上限：{budget.cap} {budget.unit}</div>
    <div>已结算：{budget.settledAmount} · 未决预留：{budget.reservedAmount}</div>
    <div>状态：{budget.status}</div>
  </section>
}

export function AssistantTaskCard({ task, step }: { task: AssistantTask; step?: AssistantRunStep; store: AssistantSessionStore; busy: boolean }) {
  const decision = Boolean(step && step.stepId === task.stepId && step.mediaTaskId === task.taskId
    && (step.stepKind === 'MAIN_AGENT' || step.operationKind === 'ASSISTANT_DECISION'))
  const mediaLabels: Record<string, string> = { TEXT: '文本调用', IMAGE: '图片生成', VIDEO: '视频生成', AUDIO: '音频生成', MUSIC: '音乐生成' }
  const billingLabels: Record<string, string> = { INIT: '尚未结算', PENDING: '待处理', RESERVED: '已预留', FROZEN: '已冻结', PRE_CHARGED: '已预扣', SETTLING: '结算中', REFUNDING: '退款中', SUCCESS: '已结算', SETTLED: '已结算', CHARGED: '已扣费', REFUNDED: '已退款', RELEASED: '预留已释放', FREE: '无需付费', FAILED: '费用待核验' }
  return <section className="assistant-card assistant-card-compact flex flex-col gap-2 rounded-xl border border-[var(--studio-ui-border,rgba(255,255,255,0.15))] p-3" aria-label="生成进度">
    <strong className="text-sm">{decision ? '助手分析与决策' : mediaLabels[task.mediaType || ''] || '生成任务'}</strong>
    <span className="text-xs">{assistantTaskPhase(task) || assistantExecutionStatus(task.status)}{task.progressSource === 'LOCAL_QUEUE' && ['PENDING', 'QUEUED'].includes(task.status) ? ' · 本地队列' : task.progressSource === 'PROVIDER' && !['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(task.status) ? ' · 生成服务处理' : ''}</span>
    <AssistantTaskEta task={task} />
    {decision && task.status === 'SUCCEEDED' ? <p className="text-xs">本次模型调用已返回，整轮创作仍按后续步骤继续。</p> : null}
    {task.modelCode ? <span className="text-xs">模型：{task.modelCode}</span> : null}
    {task.billingStatus ? <span className="text-xs">费用：{billingLabels[task.billingStatus] || '待核验'}</span> : null}
    {!['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(task.status) && !task.allowedActions?.length ? <p className="text-xs">此任务暂未提供停止生成操作。暂停或停止后续执行不会撤销已受理任务或费用。</p> : null}
  </section>
}
