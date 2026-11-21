import type { AssistantActivity, AssistantInputRequest, AssistantMessage, AssistantRunStep, AssistantScope, AssistantSessionSnapshot, AssistantTask } from '~/types/assistant-api'

/** Last authoritative transcript used only to paint an opening conversation. It cannot authorize a send or confirmation. */
export function assistantDisplayTranscript(snapshot: AssistantSessionSnapshot): AssistantSessionSnapshot {
  return {
    sessionId: snapshot.sessionId, title: snapshot.title, revision: snapshot.revision, moduleCode: snapshot.moduleCode, mode: snapshot.mode,
    workspaceId: snapshot.workspaceId, projectId: snapshot.projectId, episodeId: snapshot.episodeId, nodeObjectId: snapshot.nodeObjectId,
    actorId: snapshot.actorId, lastEventSeq: snapshot.lastEventSeq,
    messages: snapshot.messages.map(message => ({
      id: message.id, seq: message.seq, runId: message.runId, role: message.role, content: message.content,
      createdAt: message.createdAt, status: message.status, sourceType: message.sourceType, messageKind: message.messageKind,
      references: message.references?.map(({ previewUrl: _previewUrl, ...reference }) => reference)
    })),
    nextMessageCursor: null,
    activeRun: snapshot.activeRun ? { ...snapshot.activeRun, allowedActions: [] } : null,
    inputHistory: snapshot.inputHistory, inputRequest: null, plan: null,
    steps: snapshot.steps.map(step => ({ ...step, effects: step.effects || [] })), stepsHasMore: false,
    tasks: snapshot.tasks, artifacts: [], artifactsHasMore: false,
    activities: snapshot.activities, activitiesHasMore: false, conversationArtifacts: [], approval: null
  }
}

function text(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value : null
}

function sameScope(snapshot: AssistantSessionSnapshot, scope: AssistantScope): boolean {
  return snapshot.moduleCode === scope.moduleCode && snapshot.mode === scope.mode
    && (snapshot.projectId || '') === (scope.projectId || '') && (snapshot.episodeId || '') === (scope.episodeId || '')
    && (snapshot.workspaceId || '') === (scope.workspaceId || '') && (snapshot.nodeObjectId || '') === (scope.nodeObjectId || '')
}

function messages(value: unknown): AssistantMessage[] {
  if (!Array.isArray(value)) return []
  return value.flatMap(item => {
    if (!item || typeof item !== 'object') return []
    const message = item as Partial<AssistantMessage>
    const messageId = text(message.id)
    if (!messageId || typeof message.seq !== 'number' || !Number.isFinite(message.seq)) return []
    if (message.role !== 'user' && message.role !== 'assistant' && message.role !== 'system') return []
    if (typeof message.content !== 'string') return []
    return [{
      id: messageId, seq: message.seq, runId: text(message.runId) || undefined, role: message.role, content: message.content,
      createdAt: typeof message.createdAt === 'string' ? message.createdAt : undefined,
      status: typeof message.status === 'string' ? message.status : undefined,
      messageKind: typeof message.messageKind === 'string' ? message.messageKind : undefined,
      references: Array.isArray(message.references) ? message.references.flatMap(reference => {
        if (!reference || typeof reference !== 'object' || typeof reference.ordinal !== 'number' || typeof reference.kind !== 'string') return []
        if (reference.status !== 'AVAILABLE' && reference.status !== 'UNAVAILABLE') return []
        const { previewUrl: _previewUrl, ...safe } = reference
        return [safe]
      }) : undefined
    }]
  })
}

function steps(value: unknown): AssistantRunStep[] {
  if (!Array.isArray(value)) return []
  return value.flatMap(item => {
    if (!item || typeof item !== 'object') return []
    const step = item as AssistantRunStep
    return typeof step.stepId === 'string' ? [{ ...step, effects: Array.isArray(step.effects) ? step.effects : [] }] : []
  })
}

function activities(value: unknown): AssistantActivity[] {
  if (!Array.isArray(value)) return []
  return value.filter((item): item is AssistantActivity => !!item && typeof item === 'object'
    && typeof (item as AssistantActivity).activityId === 'string' && typeof (item as AssistantActivity).runId === 'string'
    && typeof (item as AssistantActivity).safeSummary === 'string')
}

function tasks(value: unknown): AssistantTask[] {
  if (!Array.isArray(value)) return []
  return value.filter((item): item is AssistantTask => !!item && typeof item === 'object' && typeof (item as AssistantTask).taskId === 'string')
}

function history(value: unknown): AssistantInputRequest[] {
  if (!Array.isArray(value)) return []
  return value.filter((item): item is AssistantInputRequest => !!item && typeof item === 'object'
    && typeof (item as AssistantInputRequest).requestId === 'string' && Array.isArray((item as AssistantInputRequest).questions)
    && Array.isArray((item as AssistantInputRequest).drafts))
}

/** Ignore a remembered transcript that belongs to another actor, scope, or session. */
export function assistantRememberedTranscript(value: unknown, expected: { actorId: string; scope: AssistantScope; sessionId: string | null }): AssistantSessionSnapshot | null {
  if (!expected.sessionId || !value || typeof value !== 'object') return null
  const snapshot = value as Partial<AssistantSessionSnapshot>
  if (snapshot.sessionId !== expected.sessionId || snapshot.actorId !== expected.actorId) return null
  if (typeof snapshot.moduleCode !== 'string' || typeof snapshot.title !== 'string' || typeof snapshot.revision !== 'number') return null
  const candidate = snapshot as AssistantSessionSnapshot
  if (!sameScope(candidate, expected.scope)) return null
  const visible = messages(snapshot.messages)
  if (!visible.length) return null
  const activeRun = snapshot.activeRun && typeof snapshot.activeRun === 'object' && typeof snapshot.activeRun.runId === 'string'
    ? { ...snapshot.activeRun, allowedActions: [] } : null
  return {
    ...candidate, actorId: expected.actorId, sessionId: expected.sessionId, messages: visible, nextMessageCursor: null, activeRun, inputRequest: null,
    inputHistory: history(snapshot.inputHistory), plan: null, steps: steps(snapshot.steps), stepsHasMore: false, tasks: tasks(snapshot.tasks),
    artifacts: [], artifactsHasMore: false, activities: activities(snapshot.activities), activitiesHasMore: false, conversationArtifacts: [], approval: null
  }
}
