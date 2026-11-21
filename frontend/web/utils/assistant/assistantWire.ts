import { normalizeTaskEta } from '~/utils/taskSseProgressText'
import { assistantModelSelections } from './assistantModelSelections'
import type {
  AssistantMessage, AssistantMessageReference, AssistantRun, AssistantSessionSnapshot, AssistantSessionSummary,
  AssistantInputRequest, AssistantPage, AssistantEventsPage, AssistantQuestion, AssistantAnswerDraft, AssistantPlan, AssistantRunStep, AssistantTask, AssistantArtifact, AssistantOperationApproval
} from '~/types/assistant-api'
import { assistantMessageReferenceLabel, assistantReferencePreviewUrl } from './assistantConversationPresentation'
import { assistantTargetEffects, optionalAssistantEffect } from './assistantEffects'
import { assistantActivity } from './assistantActivities'

type JsonObject = Record<string, unknown>
function object(value: unknown): JsonObject {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('助手返回格式无效，请刷新')
  return value as JsonObject
}
function id(value: unknown): string {
  if (typeof value === 'string' && value) return value
  if (typeof value === 'number' && Number.isSafeInteger(value)) return String(value)
  throw new Error('助手返回的标识无效')
}
function revision(value: unknown): number {
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed) || parsed < 0) throw new Error('助手返回的版本无效')
  return parsed
}

function messageReference(value: unknown): AssistantMessageReference {
  const row = object(value)
  const available = row.status === 'AVAILABLE'
  const mediaKind = ['TEXT', 'IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT', 'OTHER'].includes(String(row.mediaKind)) ? row.mediaKind as AssistantMessageReference['mediaKind'] : undefined
  const kind = String(row.kind || '')
  return { ordinal: revision(row.ordinal), kind, label: assistantMessageReferenceLabel({ kind, mediaKind, label: typeof row.label === 'string' ? row.label : undefined }), status: available ? 'AVAILABLE' : 'UNAVAILABLE',
    workspaceId: row.workspaceId == null ? undefined : id(row.workspaceId), objectId: row.objectId == null ? undefined : id(row.objectId),
    resourceId: row.resourceId == null ? undefined : id(row.resourceId), mediaKind,
    previewUrl: available && mediaKind === 'IMAGE' ? assistantReferencePreviewUrl(row.previewUrl) : undefined }
}

export function assistantSessionSummary(value: unknown): AssistantSessionSummary {
  const row = object(value)
  if (row.moduleCode !== 'UNIVERSAL' && row.moduleCode !== 'SCREENWRITER' && row.moduleCode !== 'SPATIAL_PREVIS' && row.moduleCode !== 'PREVIZ_3DBOX')
    throw new Error('助手模块不可识别')
  return {
    sessionId: id(row.sessionId), moduleCode: row.moduleCode,
    mode: row.mode === 'CRAFT' || row.mode === 'ADAPT' ? row.mode : undefined,
    title: String(row.title || '新对话'), revision: revision(row.stateRevision),
    projectId: row.projectId == null ? undefined : id(row.projectId),
    episodeId: row.episodeId == null ? undefined : id(row.episodeId),
    workspaceId: row.workspaceId == null ? undefined : id(row.workspaceId),
    nodeObjectId: optionalText(row.nodeObjectId),
    previzProjectId: optionalText(row.previzProjectId),
    updatedAt: (row.updatedAt ?? row.lastActiveTime) == null ? undefined : String(row.updatedAt ?? row.lastActiveTime)
  }
}

export function assistantRun(value: unknown): AssistantRun | null {
  if (value == null) return null
  const row = object(value)
  return {
    runId: id(row.runId), stateRevision: revision(row.revision ?? row.stateRevision),
    executionPreference: row.executionPreference === 'AUTO' ? 'AUTO' : row.executionPreference === 'MANUAL' ? 'MANUAL' : undefined,
    automaticTextExecution: row.automaticTextExecution === true,
    automaticTextFailure: typeof row.automaticTextFailure === 'string' ? row.automaticTextFailure : undefined,
    selectedSkillCode: row.selectedSkillCode == null ? undefined : String(row.selectedSkillCode),
    selectedModels: assistantModelSelections(row.selectedModels),
    status: String(row.status), pauseReason: row.pauseReason == null ? undefined : String(row.pauseReason),
    requestDigest: row.requestDigest == null ? undefined : String(row.requestDigest),
    contextDigest: row.contextDigest == null ? undefined : String(row.contextDigest),
    planningAttempt: Number(row.planningAttempt || 0),
    hasActiveTasks: Array.isArray(row.activeTaskIds) && row.activeTaskIds.length > 0,
    stopRequested: row.stopRequested === true,
    allowedActions: Array.isArray(row.allowedActions) ? row.allowedActions.map(String) : [],
    nextAction: row.nextAction == null ? undefined : String(row.nextAction),
    resumeStatus: row.resumeStatus == null ? undefined : String(row.resumeStatus)
  }
}

export function assistantAnswerDraft(value: unknown): AssistantAnswerDraft {
  const row = object(value)
  const state = row.answerState
  if (state !== 'ANSWERED' && state !== 'PARTIAL' && state !== 'UNANSWERED' && state !== 'CLEARED') throw new Error('答案状态不可识别')
  return { questionId: String(row.questionId), revision: revision(row.draftRevision),
    selectedOptionIds: Array.isArray(row.selectedOptionIds) ? row.selectedOptionIds.map(String) : [],
    freeText: row.freeText == null ? '' : String(row.freeText), state }
}

function inputRequest(value: unknown): AssistantInputRequest | null {
  if (value == null) return null
  const row = object(value)
  const kinds: Record<string, AssistantQuestion['kind']> = { SINGLE: 'single', MULTI: 'multi', TEXT: 'text', NUMBER: 'number', TEXTAREA: 'textarea' }
  const questions = (Array.isArray(row.questions) ? row.questions : []).map((value): AssistantQuestion => {
    const question = object(value)
    const kind = kinds[String(question.type)]
    if (!kind) throw new Error('当前问题类型尚不支持，请更新客户端')
    return { id: String(question.questionId), kind, title: String(question.title),
      description: question.description == null ? undefined : String(question.description),
      required: question.required === true, allowFreeText: question.allowFreeText === true,
      minSelected: question.minSelections == null ? undefined : Number(question.minSelections),
      maxSelected: question.maxSelections == null ? undefined : Number(question.maxSelections),
      maxLength: question.maxLength == null ? undefined : Number(question.maxLength),
      min: question.min == null ? undefined : Number(question.min), max: question.max == null ? undefined : Number(question.max),
      options: (Array.isArray(question.options) ? question.options : []).map((value) => {
        const option = object(value)
        return { id: String(option.optionId), label: String(option.label), description: option.description == null ? undefined : String(option.description), exclusive: option.exclusive === true, controlAction: option.controlAction === 'PAUSE_RUN' ? 'PAUSE_RUN' as const : undefined }
      }) }
  })
  const view = row.view == null ? null : object(row.view)
  return { schemaVersion: Number(row.schemaVersion || 1), requestId: id(row.requestId), runId: id(row.runId),
    createdAt: optionalText(row.createdAt), completedAt: optionalText(row.completedAt),
    schemaDigest: String(row.schemaDigest), contextDigest: String(row.contextDigest),
    status: row.status as AssistantInputRequest['status'], questions,
    drafts: (Array.isArray(row.drafts) ? row.drafts : []).map(assistantAnswerDraft),
    cursorQuestionId: view?.cursorQuestionId == null ? undefined : String(view.cursorQuestionId) }
}

export function assistantSessionSnapshot(value: unknown, actorId: string): AssistantSessionSnapshot {
  const row = object(value)
  const session = row.session ? object(row.session) : row
  const messages = (Array.isArray(row.messages) ? row.messages : []).map((value): AssistantMessage => {
    const message = object(value)
    const role = String(message.role).toLowerCase()
    const draft = role === 'assistant' && message.status === 'COMPLETE' && message.flowScreenplayDraft
      ? object(message.flowScreenplayDraft) : undefined
    const selection = draft?.selection ? object(draft.selection) : undefined
    const range = selection && typeof selection.start === 'number' && typeof selection.end === 'number'
      && Number.isSafeInteger(selection.start) && Number.isSafeInteger(selection.end)
      && selection.start >= 0 && selection.end > selection.start
      ? { start: selection.start, end: selection.end } : undefined
    const flowScreenplayDraft = draft && typeof draft.baseline === 'string'
      && /^script:[0-9]+:v[0-9]+:[a-f0-9]{64}$/.test(draft.baseline)
      && (draft.outcome === 'EPISODE_DRAFT' || draft.outcome === 'SELECTION_DRAFT' && range)
      ? { baseline: draft.baseline, outcome: draft.outcome, selection: range } as AssistantMessage['flowScreenplayDraft'] : undefined
    return {
      id: id(message.messageId ?? message.id), seq: revision(message.seq), runId: message.runtimeRunId == null ? undefined : id(message.runtimeRunId),
      role: role === 'user' || role === 'assistant' ? role : 'system',
      content: Array.isArray(message.parts) && message.parts.some((part) => object(part).type === 'TEXT')
        ? message.parts.filter((part) => object(part).type === 'TEXT').map((part) => String(object(part).text ?? '')).join('\n')
        : String(message.content ?? message.contentText ?? ''),
      messageKind: optionalText(message.messageKind), status: String(message.status || ''), createdAt: String(message.createdAt || ''),
      ...(flowScreenplayDraft ? { flowScreenplayDraft } : {}),
      references: role === 'user' && Array.isArray(message.references) ? message.references.map(messageReference) : []
    }
  })
  const tasks = Array.isArray(row.tasks) ? row.tasks.map(assistantTask) : []
  const activeRun = assistantRun(row.activeRun)
  if (activeRun && tasks.some((task) => !['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(task.status))) activeRun.hasActiveTasks = true
  return {
    ...assistantSessionSummary(session), actorId,
    lastEventSeq: revision(row.lastEventSeq ?? session.lastEventSeq), messages,
    nextMessageCursor: row.hasEarlierMessages === true && messages.length ? String(messages[0].seq) : null,
    activeRun,
    inputRequest: inputRequest(row.inputRequest),
    inputHistory: Array.isArray(row.inputHistory) ? row.inputHistory.map(inputRequest).filter((input): input is AssistantInputRequest => input !== null) : [],
    inputHistoryHasMore: row.inputHistoryHasMore === true,
    conversationArtifacts: Array.isArray(row.conversationArtifacts) ? row.conversationArtifacts.map(assistantArtifact) : [],
    conversationArtifactsHasMore: row.conversationArtifactsHasMore === true,
    activities: Array.isArray(row.activities) ? row.activities.map(assistantActivity).filter((item): item is NonNullable<typeof item> => !!item) : [],
    activitiesHasMore: row.activitiesHasMore === true,
    plan: assistantPlan(row.plan), budget: undefined,
    steps: Array.isArray(row.steps) ? row.steps.map(assistantRunStep) : [], stepsHasMore: row.stepsHasMore === true,
    approval: assistantOperationApproval(row.approval),
    tasks,
    artifacts: Array.isArray(row.artifacts) ? row.artifacts.map(assistantArtifact) : [], artifactsHasMore: row.artifactsHasMore === true,
    features: Array.isArray(row.features) ? row.features.map(String) : []
  }
}

export function assistantSessionPage(value: unknown): AssistantPage<AssistantSessionSummary> {
  const row = object(value)
  if (!Array.isArray(row.items)) throw new Error('会话列表格式无效')
  return { rows: row.items.map(assistantSessionSummary), nextCursor: row.nextCursor == null ? null : String(row.nextCursor) }
}

export function assistantEventsPage(value: unknown): AssistantEventsPage {
  const row = object(value)
  if (!Array.isArray(row.items)) throw new Error('事件列表格式无效')
  return {
    events: row.items as AssistantEventsPage['events'], nextSeq: revision(row.scannedThroughSeq),
    hasMore: row.hasMore === true, snapshotRequired: row.snapshotRequired === true
  }
}

function strings(value: unknown): string[] { return Array.isArray(value) ? value.map(String) : [] }
function optionalText(value: unknown): string | undefined { return value == null ? undefined : String(value) }
function optionalRevision(value: unknown): number | undefined { return value == null ? undefined : revision(value) }
function artifactInputRefs(value: unknown): string[] {
  if (value == null) return []
  if (!Array.isArray(value) || value.some((ref) => typeof ref !== 'string' || !ref)) throw new Error('计划产物输入无效，请重新读取')
  return value as string[]
}
function artifactInputBindings(value: unknown): AssistantPlan['steps'][number]['inputArtifactBindings'] {
  if (value == null) return []
  if (!Array.isArray(value)) throw new Error('计划产物绑定无效，请重新读取')
  return value.map((entry) => {
    const binding = object(entry)
    if (typeof binding.sourceStableStepId !== 'string' || !binding.sourceStableStepId || typeof binding.logicalArtifactId !== 'string' || !binding.logicalArtifactId || typeof binding.artifactType !== 'string' || !binding.artifactType) throw new Error('计划产物绑定缺少来源或类型，请重新读取')
    return { sourceStableStepId: binding.sourceStableStepId, logicalArtifactId: binding.logicalArtifactId, artifactType: binding.artifactType }
  })
}
export function assistantPlan(value: unknown): AssistantPlan | null {
  if (value == null) return null
  const row = object(value)
  return { revision: revision(row.revision), status: String(row.status), definitionDigest: String(row.definitionDigest), contextDigest: String(row.contextDigest),
    replanRequired: row.replanRequired === true, title: optionalText(row.title), summary: optionalText(row.summary), goal: optionalText(row.goal), assumptions: strings(row.assumptions), deliverables: strings(row.deliverables), allowedActions: strings(row.allowedActions),
    steps: (Array.isArray(row.steps) ? row.steps : []).map((value) => { const step = object(value); return { stableStepId: id(step.stableStepId), position: optionalRevision(step.position), operationKind: optionalText(step.operationKind), executorKind: optionalText(step.executorKind), bindingCode: optionalText(step.bindingCode), maxAttempts: optionalRevision(step.maxAttempts), title: optionalText(step.title), summary: optionalText(step.summary), dependsOn: strings(step.dependsOn), inputArtifactRefs: artifactInputRefs(step.inputArtifactRefs), inputArtifactBindings: artifactInputBindings(step.inputArtifactBindings), resolvedInputArtifactRefs: artifactInputRefs(step.resolvedInputArtifactRefs), inputsResolved: step.inputsResolved === true, inputResolutionErrorCode: optionalText(step.inputResolutionErrorCode) } }),
    completionCriteria: (Array.isArray(row.completionCriteria) ? row.completionCriteria : []).map((value) => { const criterion = object(value); return { criterionId: id(criterion.criterionId), criterionKind: String(criterion.criterionKind), description: String(criterion.description) } }) }
}
export function assistantRunStep(value: unknown): AssistantRunStep {
  const row = object(value)
  return { stepId: id(row.stepId), createdAt: optionalText(row.createdAt), effects: assistantTargetEffects(row.effects).filter((effect) => effect.sourceKind === 'RUNTIME_STEP' && effect.sourceId === row.stepId && row.status === 'SUCCEEDED'), stableStepId: optionalText(row.stableStepId), planRevision: revision(row.planRevision), position: optionalRevision(row.position), attemptNo: revision(row.attemptNo), stepKind: optionalText(row.stepKind), operationKind: optionalText(row.operationKind), toolCode: optionalText(row.toolCode), status: String(row.status), approvalId: optionalText(row.approvalId), mediaTaskId: optionalText(row.mediaTaskId), providerAcceptanceState: optionalText(row.providerAcceptanceState), outputArtifactRefs: strings(row.outputArtifactRefs), errorCode: optionalText(row.errorCode), safeMessage: optionalText(row.safeMessage) }
}
function assistantTask(value: unknown): AssistantTask {
  const row = object(value)
  const progress = typeof row.progress === 'number' && Number.isFinite(row.progress) && row.progress >= 0 && row.progress <= 100 ? row.progress : undefined
  return { eta: normalizeTaskEta(row.eta), queuePosition: optionalRevision(row.queuePosition), title: optionalText(row.title), stage: optionalText(row.stage), errorMessage: optionalText(row.errorMessage), progress, taskId: id(row.taskId), stepId: optionalText(row.stepId), mediaType: optionalText(row.mediaType), modelCode: optionalText(row.modelCode), status: String(row.status), billingStatus: optionalText(row.billingStatus), progressSource: optionalText(row.progressSource), outputDurationSeconds: optionalRevision(row.outputDurationSeconds), allowedActions: strings(row.allowedActions) }
}
function assistantArtifact(value: unknown): AssistantArtifact {
  const row = object(value)
  const effect = optionalAssistantEffect(row.latestTargetEffect)
  return { presentationRole: row.presentationRole === 'INTERNAL_STATE' ? 'INTERNAL_STATE' : 'DELIVERABLE', producerRunId: optionalText(row.producerRunId), producerStepId: optionalText(row.producerStepId), createdAt: optionalText(row.createdAt), title: optionalText(row.title), artifactId: id(row.artifactId), latestTargetEffect: effect?.sourceKind === 'ARTIFACT_ADOPTION' && effect.artifactId === row.artifactId ? effect : undefined, ref: optionalText(row.ref), type: String(row.type), revision: revision(row.revision), status: String(row.status), reviewStatus: optionalText(row.reviewStatus), summary: optionalText(row.summary) }
}
function assistantOperationApproval(value: unknown): AssistantOperationApproval | null {
  if (value == null) return null
  const row = object(value)
  if (typeof row.maxCharge !== 'string') throw new Error('操作费用格式无效')
  return { approvalId: id(row.approvalId), approvalRevision: revision(row.approvalRevision), scopeKind: String(row.scopeKind), status: String(row.status), planRevision: revision(row.planRevision), stepId: optionalText(row.stepId), operationKind: optionalText(row.operationKind), title: optionalText(row.title), summary: optionalText(row.summary), toolCode: optionalText(row.toolCode), skillCode: optionalText(row.skillCode), modelCode: optionalText(row.modelCode), capabilityCodes: strings(row.capabilityCodes), mediaTypes: strings(row.mediaTypes), quoteId: optionalText(row.quoteId), quotedUpperBound: typeof row.quotedUpperBound === 'string' ? row.quotedUpperBound : '', hardUpperBoundAvailable: row.hardUpperBoundAvailable === true, maxCharge: row.maxCharge, currencyUnit: String(row.currencyUnit), expiresAt: optionalText(row.expiresAt), targetRefs: Array.isArray(row.targetRefs) ? row.targetRefs.map(object) : [], allowedActions: strings(row.allowedActions) }
}
