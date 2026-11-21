import { computeResourceStatusMessage } from '~/utils/compute/resourceWaitMessage'
import { assistantTaskPhase } from './assistantTaskPhase'
import type { AssistantArtifact, AssistantInputRequest, AssistantMessage, AssistantMessageReference, AssistantOperationApproval, AssistantRunStep, AssistantSessionSnapshot, AssistantTask } from '~/types/assistant-api'
import type { AssistantTargetEffect } from '~/types/assistant-effects'
import { canLocateAssistantEffect } from './assistantEffects'
import { assistantBudgetUnits } from './assistantBudget'
import { assistantOperationFailedPause } from './assistantPausedResume'
import { assistantArtifactIsDeliverable } from './assistantArtifactPresentation'
import { assistantExecutionStatus, assistantOperationTitle } from './assistantExecution'

export const assistantConversationTerminal = (status: string): boolean => ['COMPLETED', 'SUCCEEDED', 'FAILED', 'STOPPED', 'CANCELLED', 'FAILED_PRETASK', 'CANCELLED_PRETASK', 'SKIPPED'].includes(status)

export const assistantConversationReconciling = (status?: string, providerAcceptanceState?: string): boolean => status === 'RECONCILING' || providerAcceptanceState === 'UNKNOWN'

const reconciliationSubjects: Record<string, string> = {
  'aid.model.query': '可用模型',
  'aid.skill.resolve-version': '创作方法版本',
  'aid.skill.read-resource': '创作方法文件',
  'aid.agent.resolve-version': '助手指引版本',
  'aid.agent.read-resource': '助手指引文件',
  'aid.previz.preview': '三维预览',
  'aid.previz.render-preview': '三维预览',
  'aid.previz.build': '三维构建',
  'aid.previz.build-scoped': '三维构建',
  'canvas.node.read': '画布内容',
  'canvas.project.read': '项目信息',
  'canvas.story.project.inspect': '故事工程',
  'canvas.story.source.read': '故事素材',
  'canvas.story.artifact.read': '故事内容'
}

/** Name the operation being reconciled without inferring task submission or billing. */
export function assistantConversationReconciliationLabel(step?: AssistantRunStep): string {
  const resourceStatus = computeResourceStatusMessage(step)
  if (resourceStatus) return resourceStatus
  const subject = reconciliationSubjects[step?.toolCode || '']
  return `正在核实${subject || '任务'}状态`
}

/** The snapshot owns queue state; a stream-begin event alone does not prove model reasoning. */
export function assistantConversationActiveAnalysis(snapshot: AssistantSessionSnapshot): AssistantRunStep | undefined {
  return [...snapshot.steps].reverse().find(step => (step.stepKind === 'MAIN_AGENT' || step.operationKind === 'ASSISTANT_DECISION')
    && !assistantConversationTerminal(step.status) && step.status !== 'DECISION_READY')
}

export function assistantConversationAnalysisTask(snapshot: AssistantSessionSnapshot, step?: AssistantRunStep): AssistantTask | undefined {
  return step?.mediaTaskId ? snapshot.tasks.find(task => task.taskId === step.mediaTaskId && task.stepId === step.stepId) : undefined
}

export function assistantConversationThinkingLive(snapshot: AssistantSessionSnapshot, streamLive: boolean, reasoningText: string): boolean {
  const run = snapshot.activeRun
  if (!run || !streamLive || !reasoningText.trim() || assistantConversationTerminal(run.status)
    || ['WAITING_INPUT', 'WAITING_APPROVAL', 'PAUSED', 'PAUSING', 'RECONCILING', 'STOPPING'].includes(run.status) || run.stopRequested) return false
  const step = assistantConversationActiveAnalysis(snapshot)
  if (step?.providerAcceptanceState === 'UNKNOWN' || step?.status === 'RECONCILING') return false
  const task = assistantConversationAnalysisTask(snapshot, step)
  return task?.status !== 'QUEUED' && (!task || !assistantConversationTerminal(task.status))
}

export function assistantConversationContextBlocked(snapshot: AssistantSessionSnapshot): boolean {
  if (snapshot.activeRun?.status === 'PAUSED' && snapshot.activeRun.pauseReason === 'OPERATION_PREPARATION_REJECTED'
    && snapshot.plan?.status === 'APPROVED') {
    const latest = [...snapshot.steps].reverse().find(step => step.planRevision === snapshot.plan?.revision)
    if (latest?.status === 'FAILED_PRETASK' && latest.providerAcceptanceState === 'NOT_SUBMITTED'
      && latest.errorCode === 'TOOL_PREPARATION_REFERENCE_CONTEXT_INVALID') return true
  }
  const step = assistantConversationActiveAnalysis(snapshot)
  return snapshot.activeRun?.status === 'PAUSED' && snapshot.activeRun.nextAction === 'REPLANNING_REQUIRED'
    && step?.status === 'READY' && !step.mediaTaskId && ['ASSISTANT_CONTEXT_STALE', 'ASSISTANT_SCOPE_DENIED'].includes(step.errorCode || '')
}

/** Operation failure pauses this run for explicit recovery; it is not a user pause or a terminal run. */
export function assistantConversationOperationFailed(snapshot: AssistantSessionSnapshot): boolean {
  return assistantOperationFailedPause(snapshot)
}

/** The deployed service permits refresh only at approval/pause; stale input cannot suspend. */
export function assistantContextRecoveryAction(snapshot: AssistantSessionSnapshot): 'REFRESH' | 'END_QUESTION' | 'UNAVAILABLE' {
  const run = snapshot.activeRun
  if (!run || run.stopRequested) return 'UNAVAILABLE'
  if (['WAITING_APPROVAL', 'PAUSED'].includes(run.status)) return 'REFRESH'
  return run.status === 'WAITING_INPUT' && run.allowedActions.includes('STOP') ? 'END_QUESTION' : 'UNAVAILABLE'
}

/** Older failed attempts remain visible in history but cannot override a later recovered step. */
export function assistantConversationLatestStepFailed(snapshot: AssistantSessionSnapshot): boolean {
  const latest = snapshot.steps.at(-1)
  return !!latest && ['FAILED', 'FAILED_PRETASK'].includes(latest.status)
}

export function assistantConversationPreparingAutomatically(run?: AssistantSessionSnapshot['activeRun']): boolean {
  return run?.status === 'WAITING_APPROVAL' && run.automaticTextExecution === true
    && ['INITIAL_PLANNING_REQUIRED', 'REPLANNING_REQUIRED', 'CONTINUE_AGENT_REQUIRED'].includes(run.nextAction || '')
}

/** Waiting for another assistant decision is distinct from an actual generation quote. */
export function assistantConversationRunStage(snapshot: AssistantSessionSnapshot, thinkingLive: boolean, hasReasoning: boolean, analysisStreamStatus?: 'COMPLETED' | 'FAILED' | 'STOPPED'): string {
  const run = snapshot.activeRun
  if (!run) return '创作步骤'
  if (run.status === 'STOPPED' || run.status === 'CANCELLED') return '已停止'
  if (run.status === 'FAILED') return '未能完成'
  if (['COMPLETED', 'SUCCEEDED'].includes(run.status)) return '处理已结束'
  if (run.status === 'WAITING_INPUT') return '等待你的回答'
  if (run.status === 'WAITING_APPROVAL') {
    if (assistantConversationPreparingAutomatically(run)) return '正在准备本次处理'
    if (run.nextAction === 'REPLANNING_REQUIRED') return '需要调整方案'
    if (run.nextAction === 'CONTINUE_AGENT_REQUIRED') {
      return assistantConversationLatestStepFailed(snapshot) ? '本步未完成' : '等待继续处理'
    }
    return snapshot.approval?.status === 'QUOTED' ? '等待确认生成' : '等待继续处理'
  }
  if (run.status === 'PAUSED') return assistantConversationContextBlocked(snapshot) ? '分析已暂停，引用需要更新'
    : assistantConversationOperationFailed(snapshot) ? '本步执行失败，已暂停' : '已暂停'
  if (run.status === 'PAUSING') return '正在暂停'
  if (run.status === 'RECONCILING') return assistantConversationReconciliationLabel(
    [...snapshot.steps].reverse().find(step => assistantConversationReconciling(step.status, step.providerAcceptanceState)))
  if (run.status === 'STOPPING' || run.stopRequested) return '正在停止'
  const analysis = assistantConversationActiveAnalysis(snapshot)
  if (assistantConversationReconciling(analysis?.status, analysis?.providerAcceptanceState)) return assistantConversationReconciliationLabel(analysis)
  const task = assistantConversationAnalysisTask(snapshot, analysis)
  if (task?.status === 'QUEUED') return '等待分析'
  if (task && assistantConversationTerminal(task.status)) return ['SUCCEEDED', 'COMPLETED'].includes(task.status) ? '正在整理分析结果' : '正在同步结果'
  if (analysisStreamStatus === 'FAILED') return '本次分析未完成'
  if (analysisStreamStatus === 'STOPPED') return '本次分析已停止'
  if (analysisStreamStatus === 'COMPLETED') return '正在整理分析结果'
  if (analysis && !thinkingLive) return !analysis.mediaTaskId ? '正在准备分析' : hasReasoning ? '思考已结束' : '正在分析需求'
  return thinkingLive ? '正在思考' : hasReasoning ? '思考已结束' : snapshot.steps.length ? '创作步骤' : '正在准备本次处理'
}

/** A persisted step can remain READY while a run is paused or ended. Its unfinished state
 * is not evidence of a live model call; accepted tasks retain their own authoritative state. */
export function assistantConversationStepDisplay(step: AssistantRunStep, tasks: AssistantTask[], run?: AssistantSessionSnapshot['activeRun']): { label: string; state: 'active' | 'waiting' | 'complete' | 'failed'; spinning: boolean } {
  const title = assistantConversationStepTitle(step, tasks)
  const task = step.mediaTaskId ? tasks.find(item => item.taskId === step.mediaTaskId && item.stepId === step.stepId) : undefined
  const failed = ['FAILED', 'FAILED_PRETASK'].includes(step.status) || !!task && ['FAILED', 'FAILED_PRETASK'].includes(task.status)
  if (failed) return { label: task && ['FAILED', 'FAILED_PRETASK'].includes(task.status) && !['FAILED', 'FAILED_PRETASK'].includes(step.status) ? assistantConversationTaskTitle(task, [step]) + ' · ' + assistantExecutionStatus(task.status) : title, state: 'failed', spinning: false }
  if (assistantConversationTerminal(step.status) || step.status === 'DECISION_READY') return { label: title, state: 'complete', spinning: false }
  if (assistantConversationReconciling(step.status, step.providerAcceptanceState)) return { label: assistantConversationReconciliationLabel(step), state: 'waiting', spinning: false }
  const held = !run || assistantConversationTerminal(run.status) || ['PAUSED', 'WAITING_INPUT', 'WAITING_APPROVAL', 'STOPPING', 'PAUSING', 'RECONCILING'].includes(run.status) || run.stopRequested
  if (held) {
    const label = task && assistantConversationTerminal(task.status) ? '任务结果已返回，等待同步' : task ? '任务已受理，等待结果' : ['ASSISTANT_CONTEXT_STALE', 'ASSISTANT_SCOPE_DENIED'].includes(step.errorCode || '') ? '分析已暂停，引用需要更新' : run && assistantConversationTerminal(run.status) ? '本步未执行' : run?.status === 'WAITING_INPUT' ? '等待你的回答后继续' : run?.status === 'WAITING_APPROVAL' ? '等待确认后继续' : '本步已暂停'
    return { label, state: 'waiting', spinning: false }
  }
  if (task && assistantConversationTerminal(task.status)) return { label: title, state: 'waiting', spinning: false }
  return { label: title, state: task?.status === 'QUEUED' ? 'waiting' : 'active', spinning: task?.status !== 'QUEUED' }
}

/** Describe the actual current operation, without turning provider reasoning into activity copy. */
export function assistantConversationActivity(snapshot: AssistantSessionSnapshot): { step?: AssistantRunStep; label: string; spinning: boolean } {
  const run = snapshot.activeRun
  const step = [...snapshot.steps].reverse().find(item => !assistantConversationTerminal(item.status) && item.status !== 'DECISION_READY')
  if (assistantConversationPreparingAutomatically(run) && !run?.stopRequested)
    return { label: '正在准备本次处理', spinning: true }
  if (!run || assistantConversationTerminal(run.status) || ['WAITING_INPUT', 'WAITING_APPROVAL', 'PAUSED', 'PAUSING', 'STOPPING', 'RECONCILING'].includes(run.status) || run.stopRequested) {
    return { step, label: assistantConversationRunStage(snapshot, false, false), spinning: false }
  }
  if (!step) return { label: snapshot.steps.length ? '正在衔接下一步' : '正在准备处理你的需求', spinning: true }
  const display = assistantConversationStepDisplay(step, snapshot.tasks, run)
  if ((step.stepKind !== 'MAIN_AGENT' && step.operationKind !== 'ASSISTANT_DECISION') || !display.spinning) return { step, label: display.label, spinning: display.spinning }
  const preceding = snapshot.steps.slice(0, snapshot.steps.indexOf(step)).reverse().find(item => ['SUCCEEDED', 'COMPLETED', 'DECISION_READY'].includes(item.status) && item.stepKind !== 'MAIN_AGENT' && item.operationKind !== 'ASSISTANT_DECISION')
  if (preceding?.stepKind === 'SKILL' || preceding?.operationKind === 'ASSISTANT_SKILL') return { step, label: '正在核对创作结果', spinning: true }
  const createdKind = assistantConversationNodeCreationKind(preceding?.toolCode)
  if (createdKind) return { step, label: `正在核对${mediaLabels[createdKind.toUpperCase()]}节点`, spinning: true }
  if (preceding) return { step, label: '正在核对执行结果', spinning: true }
  return { step, label: step.mediaTaskId ? '正在整理你的创作需求' : '正在准备处理你的需求', spinning: true }
}

export function assistantMessageReferenceLabel(ref: Pick<AssistantMessageReference, 'label' | 'kind' | 'mediaKind'>): string {
  if (typeof ref.label === 'string' && ref.label.trim()) return ref.label.trim()
  const labels = { TEXT: '文本素材', IMAGE: '图片素材', VIDEO: '视频素材', AUDIO: '音频素材', DOCUMENT: '参考文档', OTHER: '引用素材' }
  return ref.mediaKind ? labels[ref.mediaKind] : ref.kind === 'DOCUMENT' ? '参考文档' : ref.kind === 'SCRIPT' ? '剧本文本' : '引用素材'
}

/** Historical thumbnails are authorized server projections, never blob/local URLs or executable schemes. */
export function assistantReferencePreviewUrl(value: unknown): string | undefined {
  if (typeof value !== 'string' || /[\u0000-\u0020\\]/.test(value) || !/^(https?:\/\/|\/(?!\/))/.test(value)) return undefined
  try {
    const url = new URL(value, 'https://assistant.invalid')
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? value : undefined
  } catch { return undefined }
}

/** An immutable producing run is the batch boundary. Older records without it stay separate. */
export function assistantConversationArtifactGroups(artifacts: AssistantArtifact[]): AssistantArtifact[][] {
  const groups: AssistantArtifact[][] = [], media = new Map<string, AssistantArtifact[]>()
  for (const artifact of artifacts) {
    if (!assistantArtifactIsDeliverable(artifact)) continue
    if (!artifact.producerRunId || !['IMAGE', 'VIDEO'].includes(artifact.type)) { groups.push([artifact]); continue }
    const key = artifact.producerRunId + ':' + artifact.type
    const group = media.get(key)
    if (group) group.push(artifact)
    else { const next = [artifact]; media.set(key, next); groups.push(next) }
  }
  return groups
}

/** Only server-persisted answers are summarized; an unsubmitted local draft is never described as answered. */
export function assistantQuestionSummary(input: AssistantInputRequest): { question: string; answer: string }[] {
  if (!['SUBMITTED', 'CONSUMED'].includes(input.status)) return []
  return input.questions.map((question) => {
    const draft = input.drafts.find((item) => item.questionId === question.id)
    const choices = question.options?.filter((option) => draft?.selectedOptionIds.includes(option.id)).map((option) => option.label) || []
    const answer = [...choices, draft?.freeText.trim()].filter(Boolean).join('；')
    return { question: question.title, answer: answer || '未填写' }
  })
}

/** Persisted completion time places the compact answer before the subsequent reply; old records keep their run tail. */
export function assistantQuestionSummaryAnchor(input: AssistantInputRequest, messages: AssistantMessage[]): { beforeMessageId?: string; afterMessageId?: string } {
  const ownMessages = messages.filter((message) => message.runId === input.runId)
  const completedAt = Date.parse(input.completedAt || '')
  if (Number.isFinite(completedAt)) {
    const following = ownMessages.find((message) => {
      const createdAt = Date.parse(message.createdAt || '')
      return Number.isFinite(createdAt) && (createdAt > completedAt || createdAt === completedAt && message.role === 'assistant')
    })
    if (following) return { beforeMessageId: following.id }
  }
  return { afterMessageId: ownMessages.at(-1)?.id }
}

/** Immutable archive identity and creation time keep historical outputs beside their actual producing conversation. */
export function assistantConversationArtifactAnchor(artifact: AssistantArtifact, messages: AssistantMessage[]): { beforeMessageId?: string; afterMessageId?: string } {
  if (!artifact.producerRunId) return {}
  const ownMessages = messages.filter((message) => message.runId === artifact.producerRunId)
  const createdAt = Date.parse(artifact.createdAt || '')
  if (Number.isFinite(createdAt)) {
    const following = ownMessages.find((message) => {
      const messageTime = Date.parse(message.createdAt || '')
      return Number.isFinite(messageTime) && messageTime >= createdAt && message.role === 'assistant'
    })
    if (following) return { beforeMessageId: following.id }
  }
  return { afterMessageId: ownMessages.at(-1)?.id }
}

export function assistantConversationCharge(value: string): string | null {
  if (assistantBudgetUnits(value) === null) return null
  return value.replace(/(\.\d*?[1-9])0+$/, '$1').replace(/\.0+$/, '')
}

const mediaLabels: Record<string, string> = { IMAGE: '图片', VIDEO: '视频', AUDIO: '音频', MUSIC: '音乐', TEXT: '文本' }
export function assistantApprovalContentLabel(approval: AssistantOperationApproval): string {
  const type = approval.mediaTypes.find((item) => mediaLabels[item])
  return type ? mediaLabels[type] : approval.operationKind === 'ASSISTANT_SKILL' ? '创作内容' : '内容'
}

/** Prompt navigation uses the proven creation receipt for the exact quoted target, never an input object guessed from a title. */
export function assistantApprovalPromptEffect(snapshot: AssistantSessionSnapshot, approval: AssistantOperationApproval): AssistantTargetEffect | undefined {
  const refs = approval.targetRefs.filter((ref) => ref.kind === 'FREE_CANVAS' && typeof ref.objectId === 'string' && typeof ref.workspaceId === 'string')
  return snapshot.steps.flatMap((step) => step.effects).find((effect) => {
    const ref = effect.resultTargetRef
    return effect.effectKind === 'CREATED' && canLocateAssistantEffect(effect) && ref?.kind === 'FREE_CANVAS'
      && refs.some((target) => target.objectId === ref.objectId && target.workspaceId === ref.workspaceId
        && (typeof target.projectId !== 'string' || target.projectId === ref.projectId))
  })
}

/** Recognize only supported creation tools, including their structured-settings variants. */
export function assistantConversationNodeCreationKind(toolCode?: string): string | undefined {
  return /^canvas\.node\.create_(text|image|video|audio|music)$/.exec(toolCode || '')?.[1]
    || /^canvas\.node\.create_(image|video)_with_settings$/.exec(toolCode || '')?.[1]
}

export function assistantConversationStepTitle(step: AssistantRunStep, tasks: AssistantTask[] = []): string {
  const nodeKind = assistantConversationNodeCreationKind(step.toolCode)
  if (nodeKind) return `${mediaLabels[nodeKind.toUpperCase()]}节点${['SUCCEEDED', 'COMPLETED'].includes(step.status) ? '已创建' : assistantConversationTerminal(step.status) ? assistantExecutionStatus(step.status) : '创建中'}`
  if (step.stepKind === 'MAIN_AGENT' || step.operationKind === 'ASSISTANT_DECISION') {
    if (['SUCCEEDED', 'COMPLETED', 'DECISION_READY'].includes(step.status)) return '已整理本轮回复'
    if (assistantConversationTerminal(step.status)) return '助手处理 · ' + assistantExecutionStatus(step.status)
    if (assistantConversationReconciling(step.status, step.providerAcceptanceState)) return assistantConversationReconciliationLabel(step)
    const task = step.mediaTaskId ? tasks.find(item => item.taskId === step.mediaTaskId && item.stepId === step.stepId) : undefined
    if (task?.status === 'QUEUED') return '回复任务已排队，等待执行'
    if (task && assistantConversationTerminal(task.status)) return ['SUCCEEDED', 'COMPLETED'].includes(task.status) ? '正在整理回复' : '助手处理 · ' + assistantExecutionStatus(task.status)
    return '正在整理回复'
  }
  if (step.stepKind === 'SKILL' || step.operationKind === 'ASSISTANT_SKILL') return `${assistantConversationTerminal(step.status) ? '创作方法' : '正在应用创作方法'}${assistantConversationTerminal(step.status) ? ' · ' + assistantExecutionStatus(step.status) : ''}`
  const read = ({ 'aid.skill.read-resource': '创作方法', 'aid.agent.read-resource': '助手指引', 'canvas.node.read': '画布内容', 'canvas.project.read': '项目内容', 'aid.model.query': '可用模型' } as Record<string, string>)[step.toolCode || '']
  if (read) return ['SUCCEEDED', 'COMPLETED'].includes(step.status) ? `已读取${read}` : assistantConversationTerminal(step.status) ? `读取${read} · ${assistantExecutionStatus(step.status)}` : `正在读取${read}`
  const action = ({ 'canvas.node.read': '阅读画布内容', 'canvas.node.patch': '更新画布内容', 'canvas.artifact.candidates': '查看已有素材', 'canvas.artifact.adopt': '将内容放入画布',
    'canvas.media.generate_image': '生成图片', 'canvas.media.generate_image_with_parameters': '生成图片', 'canvas.media.generate_video': '生成视频', 'canvas.media.generate_audio': '生成语音', 'canvas.media.generate_music': '生成音乐', 'creative.text.submit': '生成文本',
    'aid.previz.source-inspect': '查询工程目录', 'aid.previz.source-read': '读取工程源码',
    'aid.previz.source-write': '保存工程源码', 'aid.previz.source-edit': '精确修改工程源码',
    'aid.previz.source-read-page': '分页读取工程源码', 'aid.previz.source-list': '列出工程目录',
    'aid.previz.source-glob': '查找工程文件', 'aid.previz.source-grep': '搜索工程源码',
    'aid.previz.progress-list': '整理搭建进度', 'aid.previz.preview': '预览三维场景',
    'aid.previz.render-preview': '生成并保存场景预览', 'aid.previz.build': '构建三维场景' } as Record<string, string>)[step.toolCode || '']
  if (action) return action + ' · ' + assistantExecutionStatus(step.status)
  return assistantOperationTitle(step.operationKind) + ' · ' + assistantExecutionStatus(step.status)
}

export function assistantConversationTaskTitle(task: AssistantTask, steps: AssistantRunStep[]): string {
  const step = steps.find((item) => item.stepId === task.stepId && item.mediaTaskId === task.taskId)
  if (step?.stepKind === 'MAIN_AGENT' || step?.operationKind === 'ASSISTANT_DECISION') return '助手分析'
  return (mediaLabels[task.mediaType || ''] || '内容') + '生成'
}

/** An unknown submission is scoped to its exact task and never overrides a terminal result. */
export function assistantConversationTaskReconciling(task: AssistantTask, steps: AssistantRunStep[] = []): boolean {
  if (assistantConversationTerminal(task.status)) return false
  const step = steps.find(item => item.stepId === task.stepId && item.mediaTaskId === task.taskId)
  return assistantConversationReconciling(task.status) || Boolean(step && assistantConversationReconciling(step.status, step.providerAcceptanceState))
}

/** A percentage is meaningful only while the matching submission state is known. */
export function assistantConversationProgress(task: AssistantTask, steps: AssistantRunStep[] = []): number | null {
  if (assistantConversationTaskReconciling(task, steps)) return null
  return typeof task.progress === 'number' && Number.isFinite(task.progress) && task.progress >= 0 && task.progress <= 100 ? task.progress : null
}

export function assistantConversationStage(stage?: string, eventType?: string): string | null {
  const labels: Record<string, string> = {
    PLANNING: '正在整理创作方案', ANALYZING: '正在分析需求', THINKING: '正在思考',
    PREPARING: '正在准备生成内容', PROMPT_PROCESSING: '正在整理提示词', QUEUED: '已排队，等待生成',
    GENERATING: '正在生成内容', PROCESSING: '正在生成内容', UPLOADING: '正在保存生成结果',
    SAVING: '正在保存内容', COMPLETED: '本步处理完成', SUCCEEDED: '本步处理完成',
    MAIN_AGENT_STARTED: '正在分析需求', MAIN_AGENT_COMPLETED: '本次分析已完成',
    SKILL_STARTED: '正在应用创作方法', SKILL_COMPLETED: '创作方法处理完成',
    TOOL_STARTED: '正在操作画布', TOOL_COMPLETED: '画布已更新',
    INPUT_CREATED: '有一个问题需要你补充', INPUT_SUBMITTED: '已收到你的回答',
    TASK_QUEUED: '生成任务已排队', TASK_STARTED: '正在生成内容', TASK_COMPLETED: '生成结果已准备好'
  }
  if (stage && labels[stage]) return labels[stage]
  if (eventType && labels[eventType]) return labels[eventType]
  return null
}

export function assistantConversationTaskStatus(task: AssistantTask, steps: AssistantRunStep[] = []): string {
  if (['SUCCEEDED', 'COMPLETED'].includes(task.status)) return '生成完成'
  if (['FAILED', 'FAILED_PRETASK'].includes(task.status)) return '生成失败'
  if (['CANCELLED', 'CANCELLED_PRETASK'].includes(task.status)) return '已取消'
  if (task.status === 'STOPPED') return '已停止'
  if (assistantConversationTaskReconciling(task, steps)) return '提交结果待核实'
  return assistantConversationStage(task.stage) || assistantTaskPhase(task) || (['PENDING', 'QUEUED'].includes(task.status) ? '排队中' : assistantExecutionStatus(task.status))
}
