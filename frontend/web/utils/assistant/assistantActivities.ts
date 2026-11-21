import type { AssistantActivity, AssistantActivityKind } from '~/types/assistant-api'

const kinds = new Set<AssistantActivityKind>(['AGENT_RESPONDING', 'PACKAGE_READING', 'TOOL_PREPARING', 'QUOTE_READY',
  'USER_APPROVAL_REQUIRED', 'TOOL_SUBMITTING', 'TOOL_RUNNING', 'TOOL_RECONCILING', 'COMPLETED', 'FAILED', 'STOPPED'])
const text = (value: unknown, limit: number) => typeof value === 'string' && value.length > 0 && value.length <= limit ? value : undefined

/** Optional newer-server presentation fields are discarded unless explicitly recognized. */
export function assistantActivity(value: unknown): AssistantActivity | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined
  const row = value as Record<string, unknown>
  const activityId = text(row.activityId, 512), runId = text(row.runId, 128)
  const safeSummary = text(row.safeSummary, 512), activityKind = row.activityKind as AssistantActivityKind
  if (!activityId || !runId || !safeSummary || !kinds.has(activityKind)) return undefined
  return { activityId, runId, activityKind, safeSummary,
    stepKind: ['MAIN_AGENT', 'TOOL', 'SKILL'].includes(String(row.stepKind)) ? row.stepKind as AssistantActivity['stepKind'] : undefined,
    outerStepId: text(row.outerStepId, 128), innerStepId: text(row.innerStepId, 128), skillRunId: text(row.skillRunId, 128),
    streamId: text(row.streamId, 512), status: text(row.status, 64), occurredAt: text(row.occurredAt, 64),
    executionStatus: text(row.executionStatus, 64), executionSummary: text(row.executionSummary, 512), executionErrorCode: text(row.executionErrorCode, 128),
    operationKind: text(row.operationKind, 128), toolCode: text(row.toolCode, 256),
    interactionId: text(row.interactionId, 128), interactionKind: row.interactionKind === 'PLAN' || row.interactionKind === 'OPERATION_APPROVAL' ? row.interactionKind : undefined,
    sourceEventSeq: Number.isSafeInteger(row.sourceEventSeq) && Number(row.sourceEventSeq) >= 0 ? Number(row.sourceEventSeq) : undefined }
}

export function assistantActivityKey(activity: Pick<AssistantActivity, 'runId' | 'outerStepId' | 'innerStepId' | 'skillRunId' | 'activityId'>): string {
  return `${activity.runId}:${activity.skillRunId && activity.innerStepId ? 'skill:' + activity.skillRunId + ':' + activity.innerStepId
    : activity.outerStepId ? 'step:' + activity.outerStepId : 'event:' + activity.activityId}`
}

/** Transient summaries are optional trusted presentation metadata, not durable history. */
export function assistantSignalActivity(value: unknown): AssistantActivity | undefined {
  if (!value || typeof value !== 'object') return undefined
  const row = value as Record<string, unknown>
  const owner = text(row.outerStepId, 128) || (text(row.skillRunId, 128) && text(row.innerStepId, 128)
    ? `${row.skillRunId}:${row.innerStepId}` : text(row.streamId, 512) || text(row.stepId, 128))
  if (!owner) return undefined
  return assistantActivity({ ...row, activityId: `live:${row.runId}:${owner}` })
}
