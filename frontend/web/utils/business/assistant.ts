import { assistantReasoningPage, validateReasoningRead, type AssistantReasoningRead } from '~/utils/assistant/assistantReasoningHistory'
import { createAssistantSourceImportApi } from '~/utils/assistant/assistantSourceImports'
import { createAssistantSeriesApi } from '~/utils/assistant/assistantSeriesApi'
import { seriesOperationFromCommand, verifySeriesReceipt } from '~/utils/assistant/assistantSeriesAdoption'
import type { AssistantRunBudgetPolicy, AssistantBudgetRevisionResult } from '~/types/assistant-budget'
import { request } from '~/utils/api'
import { userProfileResponse } from './auth'
import type { ApiEnvelope } from '~/types/business-api'
import type {
  AssistantCapabilities, AssistantCommandResult, AssistantDraftSaveResult,
  AssistantPlanningApproval, AssistantApprovalConfirmation, AssistantScope,
  AssistantTransportKind, AssistantContextRefreshed, AssistantPlanConfirmed, AssistantOperationApprovalConfirmed, AssistantOperationQuote, AssistantRunStepPageRequest
} from '~/types/assistant-api'
import type { AssistantArtifactSummary, AssistantSourceManifest, AssistantArtifactMedia, AssistantSourceRevision, AssistantSourceContent, AssistantArtifactDetail, AssistantArtifactContent, AssistantArtifactReview, AssistantArtifactPage, AssistantSourceCoverage, AssistantAdoptionCapabilities, AssistantAdoptionReceipt, AssistantAdoptionCapabilityContext } from '~/types/assistant-artifact'
import { assistantDigest, coordinateAssistantRequest } from '~/utils/assistant/assistantRequestCoordinator'
import { verifyAssistantSourceManifest } from '~/utils/assistant/assistantArtifacts'
import { assistantEventsPage, assistantSessionPage, assistantSessionSnapshot, assistantSessionSummary } from '~/utils/assistant/assistantWire'
import { assistantCommandLookup, assistantCommandPage } from '~/utils/assistant/assistantRecovery'
import type { AssistantCommandPageRequest } from '~/types/assistant-recovery'
import { assistantRunStepPage, validateAssistantStepPageRequest } from '~/utils/assistant/assistantStepHistory'
import { assistantEffectResolved, optionalAssistantEffect } from '~/utils/assistant/assistantEffects'

function artifactEffects<T extends AssistantArtifactSummary>(artifact: T): T {
  const effect = optionalAssistantEffect(artifact.latestTargetEffect)
  return { ...artifact, latestTargetEffect: effect?.sourceKind === 'ARTIFACT_ADOPTION' && effect.artifactId === artifact.artifactId ? effect : undefined }
}

export interface AssistantTransport {
  kind: AssistantTransportKind
  websocketUrl?: (path: string) => string
  websocketPath?: string
  /** 稳定服务命名空间，不含令牌；同浏览器连接不同服务时隔离未同步草稿。 */
  storageNamespace?: () => string
  /** 由已验证的登录身份及服务配置生成；不能把令牌放入持久化键。 */
  identityKey: () => string
  post: <T>(path: string, body: unknown) => Promise<ApiEnvelope<T>>
}

let lastMainToken: string | null = null
let mainAuthGeneration = 0
export const mainAssistantTransport: AssistantTransport = {
  kind: 'MAIN',
  identityKey: () => {
    const token = typeof window === 'undefined' ? '' : localStorage.getItem('token') || ''
    if (token !== lastMainToken) { lastMainToken = token; mainAuthGeneration += 1 }
    return `main:${mainAuthGeneration}`
  },
  post: <T>(path: string, body: unknown): Promise<ApiEnvelope<T>> => path === '/api/user/profile' && body != null && typeof body === 'object' && Object.keys(body).length === 0
    ? userProfileResponse() as Promise<ApiEnvelope<T>> : request.post(path, body)
}

/** CANVAS 标记会话业务入口；默认共用主站后端与登录，旧独立网关按其配置鉴权。 */
export function createAssistantApi(transport: AssistantTransport, identity = transport.identityKey()) {
  const root = '/api/user/assistant'
  const seriesApi = createAssistantSeriesApi(transport)
  async function post<T>(path: string, body: unknown): Promise<T> {
    if (identity !== transport.identityKey()) throw new Error('登录状态已变化，请重新打开助手')
    const envelope = await transport.post<T>(`${root}${path}`, body)
    if (identity !== transport.identityKey()) throw new Error('登录状态已变化，请重新打开助手')
    if ((envelope.code === 0 || envelope.code === 200) && envelope.data == null && path === '/budget/detail')
      throw Object.assign(new Error('运行预算尚未创建'), { errorCode: 'RUN_BUDGET_NOT_FOUND', budgetAbsent: true })
    if ((envelope.code !== 0 && envelope.code !== 200) || envelope.data == null) throw envelope
    return envelope.data
  }
  function read<T>(path: string, body: unknown = {}, force = false, cacheMs = 300) {
    return coordinateAssistantRequest<T>({
      key: `${transport.kind}:${identity}:${path}:${assistantDigest(body)}`,
      body, force, cacheMs, execute: () => post<T>(path, body)
    })
  }
  function command<T = AssistantCommandResult>(path: string, body: Record<string, unknown>) {
    const commandKey = body.clientRequestId ?? body.clientOperationId ?? body.clientSessionId ?? body.responseKey ?? body.key ?? body.operationId
    if (!commandKey) return Promise.reject(new Error('缺少操作标识，请重新操作'))
    return coordinateAssistantRequest<T>({
      key: `${transport.kind}:${identity}:${path}:${String(commandKey)}`,
      body, execute: () => post<T>(path, body)
    })
  }
  return {
    transport,
    deleteSession: (body: { sessionId: string; expectedRevision: number; clientRequestId: string }) => command<{ sessionId: string; deleted: boolean; archived: boolean; archiveKey: string }>('/session/delete', body),
    ...createAssistantSourceImportApi(transport,identity),
    seriesCapabilities: seriesApi.capabilities,
    adoptSeries: (body: Record<string, unknown>) => {
      const operation = seriesOperationFromCommand(body)
      return seriesApi.commit(operation.request).then(value => verifySeriesReceipt(value, operation))
    },
    capabilities: (force = false) => read<AssistantCapabilities>('/capabilities', { transport: transport.kind }, force, 5000),
    sessions: (scope: AssistantScope, cursor?: string) => read<unknown>('/session/page', { transport: transport.kind, moduleCode: scope.moduleCode, previzProjectId: scope.previzProjectId, workspaceId: scope.workspaceId, nodeObjectId: scope.nodeObjectId, cursor, limit: 20 }).then(assistantSessionPage),
    open: (body: Record<string, unknown>) => command<unknown>('/session/open', { ...body, transport: transport.kind }).then(assistantSessionSummary),
    detail: (sessionId: string, actorId: string, force = false, beforeMessageSeq?: string) => read<unknown>('/session/detail', { sessionId, beforeMessageSeq, messageLimit: 50 }, force, 0).then((value) => assistantSessionSnapshot(value, actorId)),
    realtimeTicket: (sessionId: string, afterSeq: number) => post<import('~/utils/assistant/assistantRealtime').AssistantRealtimeTicket>('/realtime/ticket', { sessionId, afterSeq, projectionVersion: 1 }),
    events: (sessionId: string, afterSeq: number) => read<unknown>('/session/events/page', { sessionId, afterSeq, limit: 100 }, false, 0).then(assistantEventsPage),
    commandLookup: (sessionId: string, clientRequestId: string) => read<unknown>('/commands/lookup', { sessionId, clientRequestId }, false, 0).then((value) => assistantCommandLookup(value, sessionId, clientRequestId)),
    commandPage: (body: AssistantCommandPageRequest) => read<unknown>('/commands/page', { limit: 20, ...body }, false, 0).then((value) => assistantCommandPage(value, body)),
    effectResolve: (sessionId: string, effectId: string) => {
      if (typeof sessionId !== 'string' || !sessionId.trim() || typeof effectId !== 'string' || !effectId.trim()) return Promise.reject(new Error('缺少定位身份'))
      return read<unknown>('/effect/resolve', { sessionId, effectId }, false, 0).then((value) => assistantEffectResolved(value, sessionId, effectId))
    },
    reasoningRead: (body: AssistantReasoningRead) => {
      validateReasoningRead(body)
      return read<unknown>('/reasoning/read', { limit: 16, ...body }, false, 1000).then(value => assistantReasoningPage(value, body))
    },
    runStepPage: (body: AssistantRunStepPageRequest) => {
      validateAssistantStepPageRequest(body)
      return read<unknown>('/run/step/page', { limit: 20, ...body }, false, 0).then((value) => assistantRunStepPage(value, body))
    },
    send: (body: Record<string, unknown>) => command('/message/send', body),
    saveDraft: (body: Record<string, unknown>) => command<AssistantDraftSaveResult>('/input/draft/save', body),
    saveView: (body: Record<string, unknown>) => read<{ requestId: string; cursorQuestionId: string }>('/input/view/save', body, true, 0),
    submitInput: (body: Record<string, unknown>) => command('/input/submit', body),
    suspendInput: (body: Record<string, unknown>) => command('/input/suspend', body),
    planningQuote: (body: Record<string, unknown>) => command<AssistantPlanningApproval>('/planning/quote', body),
    approvalDetail: (runId: string) => read<AssistantPlanningApproval>('/approval/detail', { runId }, false, 0),
    budgetDetail: (runId: string) => read<AssistantRunBudgetPolicy>('/budget/detail', { runId }, false, 0),
    refreshContext: (body: Record<string, unknown>) => command<AssistantContextRefreshed>('/context/refresh', body),
    reviseBudget: (body: Record<string, unknown>) => command<AssistantBudgetRevisionResult>('/budget/revise', body),
    approve: (body: Record<string, unknown>) => command<AssistantApprovalConfirmation>('/approval/confirm', body),
    confirmPlan: (body: Record<string, unknown>) => command<AssistantPlanConfirmed>('/plan/confirm', body),
    approveOperation: (body: Record<string, unknown>) => command<AssistantOperationApprovalConfirmed>('/operation/approval/confirm', body),
    operationQuote: (body: Record<string, unknown>) => command<AssistantOperationQuote>('/operation/quote', body),
    revisePlan: (body: Record<string, unknown>) => command('/plan/revise', body),
    runCommand: (action: 'pause' | 'resume' | 'stop', body: Record<string, unknown>) => command(`/run/${action}`, body),
    cancelTask: (body: Record<string, unknown>) => command('/task/cancel-request', body),
    retryStep: (body: Record<string, unknown>) => command('/step/retry', body),
    artifactPage: (body: { runId?: string; logicalArtifactId?: string; artifactType?: string; reviewStatus?: string; cursor?: string; limit?: number }, force = false) => read<AssistantArtifactPage<AssistantArtifactSummary>>('/artifact/page', { limit: 20, ...body }, force, 0).then((page) => ({ ...page, items: page.items.map(artifactEffects) })),
    artifactDetail: (artifactId: string) => read<AssistantArtifactDetail>('/artifact/detail', { artifactId }, false, 0).then(artifactEffects),
    artifactMedia: (artifactId: string) => read<AssistantArtifactMedia>('/artifact/media', { artifactId }, false, 0),
    artifactContent: (artifactId: string, afterChunkIndex?: number) => read<AssistantArtifactContent>('/artifact/content', { artifactId, afterChunkIndex, limit: 20 }, false, 0),
    artifactReviews: (artifactId: string, cursor?: string) => read<AssistantArtifactPage<AssistantArtifactReview>>('/artifact/review/page', { artifactId, cursor, limit: 20 }, false, 0),
    sourceManifest: (runId: string) => read<AssistantSourceManifest>('/artifact/source/manifest', { runId }, false, 0).then((value) => verifyAssistantSourceManifest(runId, value)),
    sourcePage: (runId: string, cursor?: string) => read<AssistantArtifactPage<AssistantSourceRevision>>('/artifact/source/page', { runId, cursor, limit: 20 }, false, 0),
    sourceContent: (runId: string, sourceRevisionId: string, afterChunkIndex = -1) => read<AssistantSourceContent>('/artifact/source/content', { runId, sourceRevisionId, afterChunkIndex, limit: 20 }, false, 0),
    artifactCoverage: (runId: string, sourceRevisionId: string, cursor?: string) => read<AssistantSourceCoverage>('/artifact/source/coverage', { runId, sourceRevisionId, cursor, limit: 50 }, false, 0),
    adoptionCapabilities: (sessionId: string, artifactId: string, context: AssistantAdoptionCapabilityContext = {}) => read<AssistantAdoptionCapabilities>('/artifact/adoption/capabilities', { ...context, sessionId, artifactId }, false, 0),
    adopt: (body: Record<string, unknown>) => command<AssistantAdoptionReceipt>('/artifact/adopt', body),
    resolveContext: (body: Record<string, unknown>) => read<{ refs: unknown[]; issues: string[] }>('/context/resolve', body)
  }
}

export type AssistantApi = ReturnType<typeof createAssistantApi>
