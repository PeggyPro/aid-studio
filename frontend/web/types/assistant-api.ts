import type { AssistantRunBudgetScope } from './assistant-budget'
import type { AssistantTargetEffect } from './assistant-effects'

/** 助手会话协议。运行、任务与费用为三个独立状态轴。 */
export type AssistantTransportKind = 'MAIN' | 'CANVAS'
export type AssistantModule = 'UNIVERSAL' | 'SCREENWRITER' | 'SPATIAL_PREVIS' | 'PREVIZ_3DBOX'
export type AssistantMode = 'CRAFT' | 'ADAPT'
export type AssistantId = string
export type AssistantRevision = number

export interface AssistantModelSelection {
  modelType: 'image' | 'video'
  modelCode: string
  modelName: string
  providerName?: string
  providerCode?: string
}

export type AssistantContextRef = {
  kind: 'PREVIZ_EDITOR'
  previzProjectId: string
  objectId: string
  contentHash: string
  label?: string
} | {
  kind: 'PREVIZ_IMAGE'
  previzProjectId: string
  objectId: string
  contentHash: string
  label?: string
} | {
  kind: 'PREVIZ_PROJECT'
  previzProjectId: string
  sourceVersion: string | null
  label?: string
} | {
  kind: 'FREE_CANVAS'
  workspaceId: AssistantId
  objectId: AssistantId
  objectVersion: number
  resourceId?: AssistantId
  label?: string
} | {
  kind: 'CANVAS_RESOURCE'
  workspaceId: AssistantId
  resourceId: AssistantId
  label?: string
} | {
  kind: 'FLOW_ENTITY'
  projectId: AssistantId
  episodeId: AssistantId
  entityType: string
  entityId: AssistantId
  revision: string
  label?: string
} | {
  kind: 'DOCUMENT'
  documentId: AssistantId
  version: string
  contentHash: string
  label?: string
} | {
  kind: 'SCRIPT'
  projectId: AssistantId
  episodeId: AssistantId
  scriptId: AssistantId
  contentHash: string
  selection?: { start: number; end: number; selectedTextHash: string }
  label?: string
}

export interface AssistantScope {
  previzProjectId?: string
  nodeObjectId?: string
  moduleCode: AssistantModule
  mode?: AssistantMode
  workspaceId?: AssistantId
  projectId?: AssistantId
  episodeId?: AssistantId
}

export interface AssistantCapabilities {
  sessionDeletionAvailable?: boolean
  modelSelectionOnDemand?: boolean
  transport: AssistantTransportKind
  reason?: string
  modules: AssistantModule[]
  screenwriterModes: AssistantMode[]
  contextKinds: AssistantContextRef['kind'][]
  freeCanvasAvailable: boolean
  planningAvailable: boolean
  executionAvailable: boolean
  /** Service summary flags above must not authorize an individual Canvas module. */
  moduleCapabilities?: AssistantModuleCapability[]
  /** Selected-module presentation fields, projected locally from moduleCapabilities. */
  planningUnavailableReason?: string
  executionUnavailableReason?: string
  planningModelCode?: string
  universalSkills?: { code: string; name: string; description?: string; versionId: string; packageDigest: string }[]
  skillDirectoryOnDemand?: boolean
  skillDirectoryPath?: string
  features?: string[]
  eventStream?: boolean
}

export interface AssistantModuleCapability {
  moduleCode: AssistantModule
  operationMode?: 'FULL' | 'GUIDANCE_ONLY'
  engineAvailable?: boolean
  eventStream?: boolean
  engineUnavailableReasonCode?: string
  engineUnavailableReason?: string
  planningAvailable: boolean
  executionAvailable: boolean
  planningUnavailableReasonCode?: string
  planningUnavailableReason?: string
  executionUnavailableReasonCode?: string
  executionUnavailableReason?: string
}

export interface AssistantQuestion {
  id: string
  kind: 'single' | 'multi' | 'text' | 'number' | 'textarea'
  title: string
  description?: string
  required?: boolean
  options?: { id: string; label: string; description?: string; exclusive?: boolean; controlAction?: 'PAUSE_RUN' }[]
  allowFreeText?: boolean
  minSelected?: number
  maxSelected?: number
  maxLength?: number
  min?: number
  max?: number
}

export interface AssistantAnswerDraft {
  questionId: string
  revision: number
  selectedOptionIds: string[]
  freeText: string
  state: 'ANSWERED' | 'PARTIAL' | 'UNANSWERED' | 'CLEARED'
}

export interface AssistantInputRequest {
  createdAt?: string
  completedAt?: string
  schemaVersion: number
  requestId: AssistantId
  runId: AssistantId
  schemaDigest: string
  contextDigest: string
  status: 'OPEN' | 'DRAFTING' | 'SUBMITTED' | 'CONSUMED' | 'SUSPENDED' | 'STALE' | 'EXPIRED' | 'WITHDRAWN'
  cursorQuestionId?: string
  questions: AssistantQuestion[]
  drafts: AssistantAnswerDraft[]
}

/** Server-authorized presentation of the references frozen with this particular user message. */
export interface AssistantMessageReference {
  ordinal: number
  kind: string
  label?: string | null
  status: 'AVAILABLE' | 'UNAVAILABLE'
  workspaceId?: AssistantId
  objectId?: AssistantId
  resourceId?: AssistantId
  mediaKind?: 'TEXT' | 'IMAGE' | 'VIDEO' | 'AUDIO' | 'DOCUMENT' | 'OTHER'
  previewUrl?: string
}

export interface AssistantMessage {
  id: AssistantId
  seq: number
  runId?: AssistantId
  role: 'user' | 'assistant' | 'system'
  content: string
  createdAt?: string
  status?: string
  sourceType?: string
  messageKind?: string
  references?: AssistantMessageReference[]
  flowScreenplayDraft?: {
    baseline: string
    outcome: 'EPISODE_DRAFT' | 'SELECTION_DRAFT'
    selection?: { start: number; end: number }
  }
}

export interface AssistantRun {
  automaticTextExecution?: boolean
  automaticTextFailure?: string
  selectedModels?: AssistantModelSelection[]
  executionPreference?: 'MANUAL' | 'AUTO'
  selectedSkillCode?: string
  nextAction?: string
  resumeStatus?: string
  runId: AssistantId
  stateRevision: number
  status: string
  pauseReason?: string
  requestDigest?: string
  contextDigest?: string
  planningAttempt?: number
  hasActiveTasks?: boolean
  stopRequested?: boolean
  allowedActions: string[]
  sourceType?: string
}

export interface AssistantBudget {
  authorizationId: AssistantId
  revision: number
  cap: string
  reservedAmount: string
  settledAmount: string
  availableAmount?: string
  unit: string
  status: string
}

export interface AssistantPlanStep {
  stableStepId: string
  position?: number
  operationKind?: string
  executorKind?: string
  bindingCode?: string
  maxAttempts?: number
  title?: string
  summary?: string
  dependsOn: string[]
  inputArtifactRefs: string[]
  inputArtifactBindings: { sourceStableStepId: string; logicalArtifactId: string; artifactType: string }[]
  resolvedInputArtifactRefs: string[]
  inputsResolved: boolean
  inputResolutionErrorCode?: string
}

export interface AssistantPlan {
  replanRequired?: boolean
  revision: number
  status: string
  definitionDigest: string
  contextDigest: string
  title?: string
  summary?: string
  goal?: string
  assumptions: string[]
  deliverables: string[]
  steps: AssistantPlanStep[]
  completionCriteria: { criterionId: string; criterionKind: string; description: string }[]
  allowedActions: string[]
}

export interface AssistantRunStep {
  createdAt?: string
  effects: AssistantTargetEffect[]
  stepId: string
  stableStepId?: string
  planRevision: number
  position?: number
  attemptNo: number
  stepKind?: string
  operationKind?: string
  toolCode?: string
  status: string
  approvalId?: string
  mediaTaskId?: string
  providerAcceptanceState?: string
  outputArtifactRefs: string[]
  errorCode?: string
  safeMessage?: string
}

export interface AssistantRunStepPageRequest {
  runId: string
  beforeStepId?: string
  limit?: number
}

export interface AssistantRunStepPage {
  scope: {
    sessionId: string
    observedSessionRevision: number
    observedLastEventSeq: number
    runId: string
    observedRunRevision: number
    runStatus: string
    nextAction: string | null
  }
  items: AssistantRunStep[]
  hasMore: boolean
  nextCursor: string | null
}

export interface AssistantOperationApproval {
  approvalId: string
  approvalRevision: number
  scopeKind: string
  status: string
  planRevision: number
  stepId?: string
  operationKind?: string
  title?: string
  summary?: string
  toolCode?: string
  skillCode?: string
  modelCode?: string
  capabilityCodes: string[]
  mediaTypes: string[]
  quoteId?: string
  estimatedAmount?: string | null
  precisionScale?: 4
  quotedUpperBound: string
  hardUpperBoundAvailable: boolean
  maxCharge: string
  currencyUnit: string
  expiresAt?: string
  targetRefs: Record<string, unknown>[]
  allowedActions: string[]
}

/** 只报价：既有操作的完整回执不代表执行已提交。 */
export interface AssistantOperationQuote {
  runId: string
  runRevision: number
  approvalId: string
  approvalRevision: number
  previousApprovalId: string | null
  stepId: string
  planRevision: number
  scopeKind: 'PLAN_EXECUTION'
  status: 'QUOTED'
  nextAction: 'CONFIRM_OPERATION_APPROVAL'
  quoteDisposition: 'UNCHANGED' | 'REPLACED'
  referenceRevalidated: boolean
  referenceResources: Array<{ resourceId: string; identityHash: string; mediaType: 'IMAGE' | 'VIDEO' | 'AUDIO' }>
  quote: {
    quoteId: string
    modelCode: string | null
    estimatedAmount: string | null
    quotedUpperBound: string
    currencyUnit: 'BALANCE'
    precisionScale: 4
    expiresAt: string
    hardUpperBoundAvailable: true
  }
  lastEventSeq: number
  executionSubmitted: false
}

export interface AssistantPlanConfirmed {
  runId: string
  runRevision: number
  planRevision: number
  planStatus: string
  runStatus: string
  nextAction: string
  lastEventSeq: number
}
export interface AssistantOperationApprovalConfirmed {
  runId: string
  runRevision: number
  approvalId: string
  approvalRevision: number
  stepId: string
  reservationId?: string | null
  status: string
  nextAction: string
  lastEventSeq: number
}

export interface AssistantTask {
  eta?: import('~/utils/taskSseProgressText').TaskEtaProgress
  queuePosition?: number
  taskId: AssistantId
  stepId?: AssistantId
  title?: string
  mediaType?: string
  modelCode?: string
  outputDurationSeconds?: number
  allowedActions: string[]
  status: string
  stage?: string
  progress?: number | null
  progressSource?: string
  canCancel?: boolean
  billingStatus?: string
  errorMessage?: string
}

export interface AssistantArtifact {
  presentationRole?: 'DELIVERABLE' | 'INTERNAL_STATE'
  latestTargetEffect?: AssistantTargetEffect
  producerRunId?: AssistantId
  producerStepId?: AssistantId
  createdAt?: string
  artifactId: AssistantId
  revision: number
  title?: string
  ref?: string
  reviewStatus?: string
  type: string
  status: string
  summary?: string
  content?: string
  resourceId?: AssistantId
  taskId?: AssistantId
  url?: string
  canAdopt?: boolean
}

export interface AssistantSessionSummary extends AssistantScope {
  sessionId: AssistantId
  title: string
  revision: number
  updatedAt?: string
  sourceType?: string
}

export type AssistantActivityKind = 'AGENT_RESPONDING' | 'PACKAGE_READING' | 'TOOL_PREPARING' | 'QUOTE_READY'
  | 'USER_APPROVAL_REQUIRED' | 'TOOL_SUBMITTING' | 'TOOL_RUNNING' | 'TOOL_RECONCILING' | 'COMPLETED' | 'FAILED' | 'STOPPED'

/** Authorized activity projection. It never contains raw provider reasoning. */
export interface AssistantActivity {
  activityId: string
  runId: AssistantId
  outerStepId?: AssistantId
  innerStepId?: AssistantId
  skillRunId?: AssistantId
  streamId?: string
  activityKind: AssistantActivityKind
  stepKind?: 'MAIN_AGENT' | 'TOOL' | 'SKILL'
  safeSummary: string
  status?: string
  occurredAt?: string
  sourceEventSeq?: number
  executionStatus?: string
  executionSummary?: string
  executionErrorCode?: string
  operationKind?: string
  toolCode?: string
  interactionId?: AssistantId
  interactionKind?: 'PLAN' | 'OPERATION_APPROVAL'
}

export interface AssistantSessionSnapshot extends AssistantSessionSummary {
  actorId: AssistantId
  lastEventSeq: number
  messages: AssistantMessage[]
  nextMessageCursor?: string | null
  activeRun?: AssistantRun | null
  inputHistory?: AssistantInputRequest[]
  inputHistoryHasMore?: boolean
  conversationArtifacts?: AssistantArtifact[]
  conversationArtifactsHasMore?: boolean
  activities?: AssistantActivity[]
  activitiesHasMore?: boolean
  inputRequest?: AssistantInputRequest | null
  plan?: AssistantPlan | null
  budget?: AssistantBudget | null
  steps: AssistantRunStep[]
  stepsHasMore: boolean
  approval?: AssistantOperationApproval | null
  artifactsHasMore: boolean
  tasks: AssistantTask[]
  artifacts: AssistantArtifact[]
  features?: string[]
}

export interface AssistantPage<T> {
  rows: T[]
  nextCursor?: string | null
}

export interface AssistantEvent {
  eventId: AssistantId
  sessionId: AssistantId
  seq: number
  type: string
  runId?: AssistantId
  entityRevision?: number
  causationId?: string
  data: Record<string, unknown>
}

export interface AssistantEventsPage {
  events: AssistantEvent[]
  nextSeq: number
  snapshotRequired?: boolean
  hasMore?: boolean
}

/** 服务端持久报价；金额不可由客户端编辑后回传。 */
export interface AssistantPlanningApproval {
  approvalId: AssistantId
  approvalRevision: number
  runId: AssistantId
  runRevision: number
  scopeKind: 'ASSISTANT_ITERATION'
  status: 'QUOTED' | 'ACCEPTED' | 'INVALIDATED'
  nextAction: string
  quote: {
    quoteId: AssistantId
    modelCode: string
    estimatedAmount: string
    quotedUpperBound: string
    currencyUnit: 'BALANCE'
    precisionScale: 4
    expiresAt: string
    hardUpperBoundAvailable: boolean
    runBudgetScope?: AssistantRunBudgetScope
  }
  lastEventSeq: number
}

export interface AssistantApprovalConfirmation {
  approvalId: AssistantId
  approvalRevision: number
  authorizationId: AssistantId
  authorizationRevision: number
  runId: AssistantId
  runRevision: number
  status: 'READY'
  nextAction: 'ADVANCE_AGENT'
  lastEventSeq: number
}

export interface AssistantCommandResult {
  sessionId: AssistantId
  stateRevision?: number
  runId?: AssistantId
  requestDigest?: string
  snapshot?: AssistantSessionSnapshot
}

export interface AssistantDraftPatch {
  questionId: string
  expectedDraftRevision: number
  selectedOptionIds: string[]
  freeText: string
  state: 'ANSWERED' | 'PARTIAL' | 'UNANSWERED' | 'CLEARED'
}

export interface AssistantDraftSaveResult {
  requestId: AssistantId
  questionId: string
  draftRevision: number
  answerState: AssistantAnswerDraft['state']
  unansweredCount: number
  runRevision: number
}

/** 刷新只传身份；当前版本/hash 必须由服务端重新解析。 */
export type AssistantContextIdentity =
  | { kind: 'PREVIZ_PROJECT'; previzProjectId: string }
  | { kind: 'PREVIZ_EDITOR'; previzProjectId: string; objectId: string; contentHash: string }
  | { kind: 'PREVIZ_IMAGE'; previzProjectId: string; objectId: string; contentHash: string }
  | { kind: 'DOCUMENT'; documentId: string }
  | { kind: 'FREE_CANVAS'; workspaceId: string; objectId?: string }
  | { kind: 'CANVAS_RESOURCE'; workspaceId: string; resourceId: string }
  | { kind: 'SCRIPT'; scriptId: string; selection?: { start: number; end: number; selectedTextHash: string } }
  | { kind: 'FLOW_ENTITY'; projectId: string; entityType: string; entityId: string }

export interface AssistantContextRefreshed {
  runId: string
  runRevision: number
  contextDigest: string
  targetRefs: Record<string, unknown>[]
  status: 'WAITING_APPROVAL' | 'PAUSED' | 'PAUSING'
  nextAction: 'REPLANNING_REQUIRED' | 'CANCEL_FOR_REPLAN'
  lastEventSeq: number
}
