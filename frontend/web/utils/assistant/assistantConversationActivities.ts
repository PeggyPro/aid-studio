import type { AssistantActivity, AssistantMessage, AssistantSessionSnapshot } from '~/types/assistant-api'
import type { AssistantRealtimeReasoning } from './assistantRealtime'
import { assistantActivityKey } from './assistantActivities'
import { assistantConversationActivity, assistantConversationLatestStepFailed, assistantConversationOperationFailed, assistantConversationStepDisplay, assistantConversationTerminal } from './assistantConversationPresentation'

export interface AssistantActivityRow {
  key: string
  label: string
  state: 'active' | 'waiting' | 'complete' | 'failed'
  spinning: boolean
  /** A server-projected persisted run terminal, distinct from a completed MAIN_AGENT iteration. */
  runTerminal?: boolean
}
export interface AssistantActivityBlock {
  runId: string
  rows: AssistantActivityRow[]
  summaries: AssistantRealtimeReasoning[]
  label: string
  live: boolean
  /** Waiting to continue or answer still uses the same text highlight as an active step. */
  pendingHighlight: boolean
  state: AssistantActivityRow['state']
  beforeMessageId?: string
  afterMessageId?: string
}

/** The disclosure title already shows the current status. Repeating that sentence hides the thinking text. */
export function assistantConversationDetailRows(rows: AssistantActivityRow[], title: string): AssistantActivityRow[] {
  const heading = title.trim()
  return rows.filter(row => row.label.trim() !== heading)
}

/** Failure copy belongs in the disclosure body. A status title such as “本轮处理失败” is not the reason. */
export function assistantConversationFailureReasons(
  steps: ReadonlyArray<{ status: string; safeMessage?: string; stepId?: string; stepKind?: string; operationKind?: string; mediaTaskId?: string }>,
  tasks: ReadonlyArray<{ status: string; errorMessage?: string; taskId?: string; stepId?: string }>,
  title: string,
  preferStepFailure = false
): string[] {
  const heading = title.trim()
  const reasons: string[] = []
  const push = (value?: string) => {
    const text = value?.trim()
    if (!text || text === heading || reasons.includes(text)) return
    reasons.push(text)
  }
  for (const step of steps) {
    if (['FAILED', 'FAILED_PRETASK'].includes(step.status)) push(step.safeMessage)
  }
  for (const task of tasks) {
    if (!['FAILED', 'FAILED_PRETASK'].includes(task.status)) continue
    // The contextual Agent failure already explains this same underlying text task.
    // Keep independent task failures and retain task copy when no step reason exists.
    const explained = preferStepFailure && steps.some(step =>
      ['FAILED', 'FAILED_PRETASK'].includes(step.status)
      && (step.stepKind === 'MAIN_AGENT' || step.operationKind === 'ASSISTANT_DECISION')
      && Boolean(step.safeMessage?.trim()) && step.safeMessage?.trim() !== heading
      && (task.stepId ? step.stepId === task.stepId && (!task.taskId || !step.mediaTaskId || step.mediaTaskId === task.taskId)
        : Boolean(task.taskId && step.mediaTaskId === task.taskId)))
    if (!explained) push(task.errorMessage)
  }
  return reasons
}

/** Snapshot steps and tasks belong to the active run. Historical failures keep their own step page. */
export function assistantConversationActiveFailureReasons(snapshot: AssistantSessionSnapshot, runId: string, title: string): string[] {
  const run = snapshot.activeRun
  if (!run || run.runId !== runId || !['FAILED', 'FAILED_PRETASK', 'SUCCEEDED', 'COMPLETED'].includes(run.status) && !assistantConversationOperationFailed(snapshot)) return []
  return assistantConversationFailureReasons(snapshot.steps, snapshot.tasks, title)
}

/** Paused, failed, stopped and replanning stays still. Continuation and unanswered questions keep the highlight. */
export function assistantConversationPendingHighlight(snapshot: AssistantSessionSnapshot, runId: string): boolean {
  const run = snapshot.activeRun
  if (!run || run.runId !== runId || run.stopRequested) return false
  if (run.status === 'WAITING_INPUT') return true
  if (run.status !== 'WAITING_APPROVAL' || run.nextAction === 'REPLANNING_REQUIRED') return false
  return !(run.nextAction === 'CONTINUE_AGENT_REQUIRED' && assistantConversationLatestStepFailed(snapshot))
}

const held = new Set(['WAITING_INPUT', 'WAITING_APPROVAL', 'PAUSED', 'PAUSING', 'STOPPING', 'RECONCILING'])
const terminalKinds = new Set(['COMPLETED', 'FAILED', 'STOPPED'])

function isRunTerminalActivity(activity: AssistantActivity): boolean {
  return terminalKinds.has(activity.activityKind) && activity.stepKind === 'MAIN_AGENT'
    && !activity.outerStepId && !activity.innerStepId && !activity.skillRunId && !activity.streamId
}

function activityRow(activity: AssistantActivity, live: boolean): AssistantActivityRow {
  const terminal = terminalKinds.has(activity.activityKind) || assistantConversationTerminal(activity.status || '')
  const failed = activity.activityKind === 'FAILED' || ['FAILED', 'FAILED_PRETASK'].includes(activity.status || '')
  const waiting = ['USER_APPROVAL_REQUIRED', 'QUOTE_READY', 'TOOL_RECONCILING', 'STOPPED'].includes(activity.activityKind)
    || ['PAUSED', 'PAUSING', 'UNKNOWN', 'RECONCILING', 'QUEUED', 'STOPPING'].includes(activity.status || '')
  return { key: assistantActivityKey(activity), label: activity.safeSummary,
    state: failed ? 'failed' : waiting || !terminal && !live ? 'waiting' : terminal ? 'complete' : 'active',
    spinning: live && !terminal && !waiting, runTerminal: isRunTerminalActivity(activity) }
}

/** One block per producing run, anchored before its first public reply. Late events update that block. */
export function assistantConversationActivityBlocks(snapshot: AssistantSessionSnapshot, messages: AssistantMessage[],
  transient: AssistantActivity[] = [], summaries: AssistantRealtimeReasoning[] = []): AssistantActivityBlock[] {
  const run = snapshot.activeRun
  const runs = new Map<string, AssistantActivity[]>()
  for (const activity of [...(snapshot.activities || []), ...transient]) {
    const values = runs.get(activity.runId) || []
    values.push(activity); runs.set(activity.runId, values)
  }
  if (run && !runs.has(run.runId)) runs.set(run.runId, [])
  const publicSummaries = summaries.filter(summary => summary.reasoningKind === 'PUBLIC_SUMMARY' && summary.text.trim())
  for (const summary of publicSummaries) if (!runs.has(summary.runId)) runs.set(summary.runId, [])
  return [...runs].map(([runId, activities]) => {
    const ownRun = run?.runId === runId ? run : undefined
    const live = !!ownRun && !assistantConversationTerminal(ownRun.status) && !held.has(ownRun.status) && !ownRun.stopRequested
    const rows = new Map<string, AssistantActivityRow>()
    const mainSteps = new Set<string>()
    // Older servers have steps but no activity projection. Those statuses remain authoritative.
    if (ownRun) for (const step of snapshot.steps) {
      const main = step.stepKind === 'MAIN_AGENT' || step.operationKind === 'ASSISTANT_DECISION'
      if (main) mainSteps.add(step.stepId)
      const key = main ? `${runId}:main` : `${runId}:step:${step.stepId}`
      const display = assistantConversationStepDisplay(step, snapshot.tasks, ownRun)
      rows.set(key, { key, ...display, ...(main ? { label: display.spinning ? '正在整理回复'
        : display.state === 'complete' ? '已整理本轮回复' : display.label } : {}) })
    }
    // Snapshot precedes transient in ties; durable terminal evidence always wins over an older live signal.
    const merged = new Map<string, AssistantActivity>()
    for (const activity of activities) {
      const key = activity.stepKind === 'MAIN_AGENT' || activity.outerStepId && mainSteps.has(activity.outerStepId)
        ? `${runId}:main` : assistantActivityKey(activity)
      const previous = merged.get(key)
      // A run terminal is synthesized from the persisted run row. It is authoritative for the
      // whole historical block and must survive an older step event or a late transient signal.
      if (previous && isRunTerminalActivity(previous) && !isRunTerminalActivity(activity)) continue
      if (previous && terminalKinds.has(previous.activityKind) && !terminalKinds.has(activity.activityKind)
        && previous.outerStepId === activity.outerStepId) continue
      if (previous && previous.sourceEventSeq !== undefined && activity.sourceEventSeq !== undefined && previous.sourceEventSeq > activity.sourceEventSeq) continue
      merged.set(key, activity)
    }
    for (const [key, activity] of merged) {
      const step = ownRun && activity.outerStepId ? snapshot.steps.find(item => item.stepId === activity.outerStepId) : undefined
      // A late transient cannot reverse a terminal/held authoritative step or run.
      if (step && (assistantConversationTerminal(step.status) || step.status === 'DECISION_READY')
        && !terminalKinds.has(activity.activityKind)) continue
      rows.set(key, { ...activityRow(activity, live), key })
    }
    // Completed MAIN iterations are implementation detail when actual operations are available.
    const main = rows.get(`${runId}:main`)
    if (main && main.state === 'complete' && !main.runTerminal && rows.size > 1) rows.delete(`${runId}:main`)
    const values = [...rows.values()]
    const runTerminal = values.find(row => row.runTerminal)
    const fallback = ownRun ? assistantConversationActivity(snapshot) : undefined
    const last = values.at(-1)
    const failed = !!ownRun && assistantConversationOperationFailed(snapshot) || ownRun?.status === 'FAILED' || runTerminal?.state === 'failed' || !ownRun && last?.state === 'failed'
    const state = runTerminal ? runTerminal.state : failed ? 'failed' : live ? 'active' : ownRun && (held.has(ownRun.status) || ownRun.stopRequested || ['STOPPED', 'CANCELLED'].includes(ownRun.status)) ? 'waiting'
      : ownRun && assistantConversationTerminal(ownRun.status) ? 'complete' : last?.state || 'waiting'
    const label = runTerminal?.label || (ownRun && !live ? fallback!.label : values.filter(row => row.spinning).at(-1)?.label || last?.label || fallback?.label || '已记录的处理过程')
    const ownMessages = messages.filter(message => message.runId === runId)
    const reply = ownMessages.find(message => message.role === 'assistant')
    const user = ownMessages.filter(message => message.role === 'user').at(-1)
    return { runId, rows: values, summaries: publicSummaries.filter(summary => summary.runId === runId), label, live, state,
      pendingHighlight: assistantConversationPendingHighlight(snapshot, runId),
      beforeMessageId: reply?.id, afterMessageId: reply ? undefined : user?.id }
  }).filter(block => block.rows.length || block.summaries.length || block.runId === run?.runId)
}
