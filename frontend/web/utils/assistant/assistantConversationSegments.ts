import type { AssistantActivity, AssistantArtifact, AssistantMessage, AssistantRunStep, AssistantSessionSnapshot, AssistantTask } from '~/types/assistant-api'
import type { AssistantRealtimeReasoning } from './assistantRealtime'
import { assistantActivityKey } from './assistantActivities'
import type { AssistantActivityBlock } from './assistantConversationActivities'
import { assistantConversationRunStage, assistantConversationStepDisplay, assistantConversationTerminal } from './assistantConversationPresentation'
import { assistantConversationArtifactGroups } from './assistantConversationPresentation'
import { assistantExecutionStatus } from './assistantExecution'

function canvasStepDisplay(step: AssistantRunStep, tasks: AssistantTask[], run?: AssistantSessionSnapshot['activeRun']) {
  if (step.stepKind === 'MAIN_AGENT' && step.errorCode === 'NATIVE_DECISION_CORRECTION_SLOT_CLAIMED') {
    return { label: '计划校验未通过 · 已转入纠正', state: 'waiting' as const, spinning: false }
  }
  const aliases: Record<string, string> = {
    'canvas.node.create_image_with_validated_settings': 'canvas.node.create_image',
    'canvas.node.create_video_with_validated_settings': 'canvas.node.create_video',
    'canvas.node.create_text_from_result': 'canvas.node.create_text',
  }
  const projected = aliases[step.toolCode || ''] ? { ...step, toolCode: aliases[step.toolCode!] } : step
  const display = assistantConversationStepDisplay(projected, tasks, run)
  const name = ({ 'aid.model.query-target': '模型能力查询', 'aid.skill.directory': 'Skill目录查询', 'aid.skill.resolve-version': 'Skill版本核对' } as Record<string, string>)[step.toolCode || '']
  return name && (display.state === 'complete' || display.state === 'failed' || display.spinning)
    ? { ...display, label: `${name} · ${display.state === 'failed' ? '执行失败' : assistantExecutionStatus(step.status)}` } : display
}

/** A result batch belongs to its producing step, including after switching conversations. */
export function assistantCanvasArtifactGroups(artifacts: AssistantArtifact[]): AssistantArtifact[][] {
  const owners = new Map<string, AssistantArtifact[]>()
  for (const artifact of artifacts) {
    const key = artifact.producerRunId && artifact.producerStepId ? `${artifact.producerRunId}:${artifact.producerStepId}` : `legacy:${artifact.artifactId}`
    const own = owners.get(key) || []
    own.push(artifact); owners.set(key, own)
  }
  return [...owners.values()].flatMap(assistantConversationArtifactGroups)
}

export interface AssistantConversationSegment extends AssistantActivityBlock {
  archiveStepId?: string
  archiveInnerStepId?: string
  archiveSkillRunId?: string
  segmentId: string
  stepIds: string[]
  kind: 'thinking' | 'operation' | 'status'
  stopped: boolean
}

const failed = (status?: string) => ['FAILED', 'FAILED_PRETASK'].includes(status || '')
const time = (value?: string) => value ? Date.parse(value) : NaN
const stopped = (status?: string) => ['STOPPED', 'CANCELLED', 'SKIPPED', 'CANCELLED_PRETASK'].includes(status || '')

function stoppedAnalysisLabel(status?: string, errorCode?: string) {
  if (errorCode === 'QUOTE_EXPIRED') return '报价已过期'
  if (errorCode === 'APPROVAL_SCOPE_STALE') return '报价范围已变化'
  if (status === 'SKIPPED') return '本步骤已跳过'
  return '本步思考已停止'
}


/** Canvas chronology is owned by a concrete step/stream, never by the first reply of a run. */
export function assistantConversationSegments(snapshot: AssistantSessionSnapshot, messages: AssistantMessage[],
  transient: AssistantActivity[] = [], summaries: AssistantRealtimeReasoning[] = []): AssistantConversationSegment[] {
  const stoppedRuns = new Set([...(snapshot.activities || []), ...transient]
    .filter(event => event.activityKind === 'STOPPED' && event.stepKind === 'MAIN_AGENT' && !event.outerStepId && !event.innerStepId)
    .map(event => event.runId))
  const groups = new Map<string, { runId: string; stepIds: string[]; events: AssistantActivity[]; summaries: AssistantRealtimeReasoning[] }>()
  const ensure = (key: string, runId: string, stepId?: string) => {
    let group = groups.get(key)
    if (!group) { group = { runId, stepIds: stepId ? [stepId] : [], events: [], summaries: [] }; groups.set(key, group) }
    return group
  }
  for (const event of [...(snapshot.activities || []), ...transient]) {
    // Run-level completion is not a successful tool result and is not a thinking segment.
    if (event.stepKind === 'MAIN_AGENT' && !event.outerStepId && !event.innerStepId && !event.streamId
      && ['COMPLETED', 'STOPPED'].includes(event.activityKind)) continue
    if (event.stepKind === 'MAIN_AGENT' && !event.outerStepId && event.activityKind === 'FAILED'
      && ((snapshot.activeRun?.runId === event.runId && snapshot.steps.some(step => failed(step.status)))
        || [...(snapshot.activities || []), ...transient].some(item => item.runId === event.runId && item.outerStepId && failed(item.executionStatus || item.status)))) continue
    const key = event.interactionKind && event.interactionId ? `${event.runId}:${event.interactionKind}:${event.interactionId}`
      : !event.outerStepId && event.streamId ? `${event.runId}:stream:${event.streamId}` : assistantActivityKey(event)
    ensure(key, event.runId, event.interactionKind || event.skillRunId && event.innerStepId ? undefined : event.outerStepId).events.push(event)
  }
  for (const summary of summaries) {
    if (!['PUBLIC_SUMMARY', 'PROVIDER_REASONING'].includes(summary.reasoningKind || '') || !summary.text.trim()) continue
    const key = summary.stepId ? `${summary.runId}:step:${summary.stepId}` : `${summary.runId}:stream:${summary.streamId}`
    ensure(key, summary.runId, summary.stepId).summaries.push(summary)
  }
  const run = snapshot.activeRun
  if (run) for (const step of snapshot.steps) {
    if (![...groups.values()].some(group => group.runId === run.runId && group.stepIds.includes(step.stepId))) {
      ensure(`${run.runId}:step:${step.stepId}`, run.runId, step.stepId)
    }
  }
  if (run && !assistantConversationTerminal(run.status) && ![...groups.values()].some(group => group.runId === run.runId)) {
    ensure(`${run.runId}:preparing`, run.runId)
  }
  const awaitingApproval = new Set([...groups.values()].flatMap(group => {
    const event = [...group.events].sort((a, b) => (a.sourceEventSeq ?? 0) - (b.sourceEventSeq ?? 0)).at(-1)
    if (event?.interactionKind !== 'OPERATION_APPROVAL' || event.status !== 'WAITING_APPROVAL' || !event.outerStepId) return []
    const step = run?.runId === event.runId ? snapshot.steps.find(item => item.stepId === event.outerStepId) : undefined
    const status = step?.status || event.executionStatus
    return !step?.mediaTaskId && ['AWAITING_APPROVAL', 'WAITING_APPROVAL', 'QUOTED'].includes(status || '') ? [`${event.runId}:${event.outerStepId}`] : []
  }))
  const chronology = new Map<string, number>()
  const restoreChronology = snapshot.moduleCode === 'PREVIZ_3DBOX'
  const segments = [...groups].filter(([, group]) => !group.stepIds.some(id => awaitingApproval.has(`${group.runId}:${id}`))).map(([segmentId, group]): AssistantConversationSegment => {
    const ownRun = run?.runId === group.runId ? run : undefined
    const step = ownRun ? snapshot.steps.find(item => group.stepIds.includes(item.stepId)) : undefined
    const events = [...group.events].sort((a, b) => (a.sourceEventSeq ?? 0) - (b.sourceEventSeq ?? 0))
    // Ignore late nonterminal stream signals once the same operation has a durable terminal.
    const terminal = events.filter(event => assistantConversationTerminal(event.status || '') || ['COMPLETED', 'FAILED', 'STOPPED'].includes(event.activityKind))
    const event = terminal.at(-1) || events.at(-1)
    const execution = events.filter(item => item.executionStatus).at(-1)
    const status = event?.interactionKind && event.status === 'WAITING_APPROVAL' && stoppedRuns.has(group.runId)
      ? 'CANCELLED' : execution?.executionStatus || event?.status
    const approved = !!event?.interactionKind && event.status === 'APPROVED'
    const main = step?.stepKind === 'MAIN_AGENT' || step?.operationKind === 'ASSISTANT_DECISION' || event?.stepKind === 'MAIN_AGENT' || group.summaries.length > 0
    const projectedStep: AssistantRunStep | undefined = !step && !event?.interactionKind && event?.outerStepId && execution?.executionStatus && (execution.operationKind || execution.toolCode) ? {
      stepId: event.outerStepId, stepKind: execution.stepKind, operationKind: execution.operationKind, toolCode: execution.toolCode,
      status: execution.executionStatus, errorCode: execution.executionErrorCode, planRevision: 0, attemptNo: 0, effects: [], outputArtifactRefs: [],
    } : undefined
    const display = step ? canvasStepDisplay(step, snapshot.tasks, ownRun)
      : projectedStep ? canvasStepDisplay(projectedStep, [], undefined) : undefined
    const placeholder = !step && !event && !group.summaries.length
    const live = display ? display.spinning : !!ownRun && !assistantConversationTerminal(ownRun.status)
      && !['PAUSED', 'WAITING_INPUT', 'WAITING_APPROVAL', 'STOPPING', 'RECONCILING'].includes(ownRun.status)
      && !ownRun.stopRequested && !terminal.length && !assistantConversationTerminal(status || '') && status !== 'DECISION_READY' && !approved
      && !event?.interactionKind && (group.summaries.some(item => item.live) || !!event || placeholder)
    const state = approved ? 'complete' : stopped(step?.status || status) ? 'waiting' : display?.state || (failed(status) || !execution && event?.activityKind === 'FAILED' ? 'failed'
      : approved || status === 'DECISION_READY' || assistantConversationTerminal(status || '') || terminal.length ? 'complete' : live ? 'active' : 'waiting')
    // A failed analysis step does not reverse successful operations in this run.
    const hasCompletedOperation = !!ownRun && !!step && snapshot.steps.some(item =>
      item.stepKind !== 'MAIN_AGENT' && item.operationKind !== 'ASSISTANT_DECISION'
      && item.status === 'SUCCEEDED' && /^\d+$/.test(item.stepId) && /^\d+$/.test(step.stepId)
      && BigInt(item.stepId) < BigInt(step.stepId))
    const mainFailureLabel = hasCompletedOperation ? '后续处理未完成' : '本步分析未完成'
    const label = event?.interactionKind === 'PLAN' ? approved ? '处理方案已确认' : stopped(status) ? '处理方案已取消' : '等待确认处理方案'
      : event?.interactionKind === 'OPERATION_APPROVAL' ? approved ? '已确认本次生成' : stopped(status) ? '本次生成确认已取消' : failed(status) ? '本次操作未执行' : '等待确认本次生成'
      : main ? state === 'failed' ? mainFailureLabel : stopped(step?.status || status) ? stoppedAnalysisLabel(step?.status || status, step?.errorCode || execution?.executionErrorCode) : live ? '思考中…' : state === 'complete' ? '思考过程' : display?.label || event?.safeSummary || '等待处理'
      : ['aid.skill.read-resource', 'aid.agent.read-resource'].includes(step?.toolCode || execution?.toolCode || '') && state === 'complete' && execution?.executionSummary ? execution.executionSummary
      : display?.label || execution?.executionSummary || event?.safeSummary || (ownRun ? assistantConversationRunStage(snapshot, false, false) : '处理记录')
    const times = [...events.map(item => time(item.occurredAt)), ...group.summaries.map(item => time(item.startedAt))].filter(Number.isFinite)
    const occurred = times.length ? Math.min(...times) : restoreChronology ? time(step?.createdAt) : NaN
    chronology.set(segmentId, Number.isFinite(occurred) ? occurred : Number.POSITIVE_INFINITY)
    const ownMessages = messages.filter(item => item.runId === group.runId)
    const reply = Number.isFinite(occurred) ? ownMessages.find(item => item.role === 'assistant' && time(item.createdAt) >= occurred) : undefined
    const last = ownMessages.at(-1)
    return { segmentId, runId: group.runId, stepIds: group.stepIds, archiveStepId: step?.stepId || event?.outerStepId, archiveInnerStepId: event?.innerStepId, archiveSkillRunId: event?.innerStepId ? event.skillRunId : undefined, kind: event?.interactionKind ? 'status' : main ? 'thinking' : 'operation', stopped: !approved && stopped(step?.status || status),
      rows: [], summaries: group.summaries, label, live, state, pendingHighlight: state === 'waiting' && !!ownRun && !assistantConversationTerminal(ownRun.status) && !ownRun.stopRequested && !stopped(step?.status || status),
      beforeMessageId: reply?.id, afterMessageId: reply ? undefined : last?.id }
  })
  return restoreChronology ? segments.sort((a, b) => {
    const first = chronology.get(a.segmentId)!, second = chronology.get(b.segmentId)!
    if (first !== second) return first < second ? -1 : 1
    const left = a.stepIds[0], right = b.stepIds[0]
    return left && right && /^\d+$/.test(left) && /^\d+$/.test(right) ? (BigInt(left) < BigInt(right) ? -1 : BigInt(left) > BigInt(right) ? 1 : 0) : 0
  }) : segments
}
