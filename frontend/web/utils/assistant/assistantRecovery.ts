import type { AssistantCommandLookup, AssistantCommandPage, AssistantCommandPageRequest, AssistantCommandReceipt, AssistantRecoveryScope } from '~/types/assistant-recovery'

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('操作回执格式异常，请同步权威会话')
  return value as Record<string, unknown>
}
function id(value: unknown, zero = false): string {
  if (typeof value !== 'string' || !/^\d{1,19}$/.test(value) || BigInt(value) > 9223372036854775807n || BigInt(value) < (zero ? 0n : 1n)) throw new Error('操作回执标识异常')
  return value
}
function revision(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error('操作回执版本异常')
  return value
}
function code(value: unknown): string {
  if (typeof value !== 'string' || !/^[A-Za-z0-9._:-]{1,64}$/.test(value)) throw new Error('操作回执状态异常')
  return value
}
function scope(value: unknown, sessionId: string): AssistantRecoveryScope {
  const row = object(value)
  if (id(row.sessionId) !== sessionId) throw new Error('操作回执不属于当前会话')
  const result: AssistantRecoveryScope = { sessionId, sessionRevision: revision(row.sessionRevision), lastEventSeq: revision(row.lastEventSeq) }
  if (row.activeRunId != null) { result.activeRunId = id(row.activeRunId); result.activeRunRevision = revision(row.activeRunRevision) }
  else if (row.activeRunRevision != null) throw new Error('操作回执活动运行不完整')
  return result
}
function receipt(value: unknown): AssistantCommandReceipt {
  const row = object(value), outcome: Record<string, string> = {}
  const allowed = new Set(['sessionId', 'sessionRevision', 'runId', 'runRevision', 'messageId', 'messageSeq', 'requestId', 'requestRevision', 'draftRevision', 'approvalId', 'approvalRevision', 'planRevision', 'stepId', 'reservationId', 'authorizationId', 'lastEventSeq'])
  for (const [key, item] of Object.entries(object(row.outcome))) {
    if (!allowed.has(key) || typeof item !== 'string' || !/^\d{1,20}$/.test(item)) throw new Error('操作回执包含不支持的结果字段')
    outcome[key] = item
  }
  if (row.payloadDigest != null && (typeof row.payloadDigest !== 'string' || !/^[a-fA-F0-9]{64}$/.test(row.payloadDigest))) throw new Error('操作回执摘要异常')
  return { commandId: id(row.commandId), clientRequestId: code(row.clientRequestId), commandType: code(row.commandType), status: code(row.status),
    payloadDigest: row.payloadDigest as string | null ?? null, errorCode: row.errorCode == null ? null : code(row.errorCode), outcome }
}

export function assistantCommandLookup(value: unknown, sessionId: string, clientRequestId: string): AssistantCommandLookup {
  const row = object(value), observed = row.observation
  if (row.replacementKeyAllowed !== false || !['OBSERVED', 'NOT_OBSERVED'].includes(String(observed))) throw new Error('操作恢复指令异常，不会替换原请求键')
  const item = row.receipt == null ? null : receipt(row.receipt)
  if ((observed === 'OBSERVED') !== !!item || (item && item.clientRequestId !== clientRequestId)) throw new Error('操作回执与原请求不一致')
  const directive = item?.status === 'COMPLETED' ? 'REFRESH_AUTHORITATIVE_STATE' : 'UNKNOWN_REPLAY_SAME_KEY_ONLY'
  if (row.recoveryDirective !== directive) throw new Error('操作恢复指令与状态不一致')
  return { scope: scope(row.scope, sessionId), observation: observed as AssistantCommandLookup['observation'], recoveryDirective: directive, replacementKeyAllowed: false, receipt: item }
}

export function assistantCommandPage(value: unknown, request: AssistantCommandPageRequest): AssistantCommandPage {
  const row = object(value), currentScope = scope(row.scope, request.sessionId), through = id(row.throughCommandId, true)
  if (!Array.isArray(row.items) || row.items.length > (request.limit ?? 20) || typeof row.hasMore !== 'boolean') throw new Error('操作回执分页异常')
  if (request.afterCommandId != null && (through !== request.throughCommandId || currentScope.sessionRevision !== request.expectedSessionRevision || currentScope.lastEventSeq !== request.expectedLastEventSeq)) throw new Error('操作回执分页范围已变化，请从第一页重新同步')
  const items = row.items.map(receipt)
  let previous = BigInt(request.afterCommandId ?? '0')
  for (const item of items) { const next = BigInt(item.commandId); if (next <= previous || next > BigInt(through)) throw new Error('操作回执顺序或范围异常'); previous = next }
  const nextCommandId = items.at(-1)?.commandId ?? null
  if (row.nextCommandId !== nextCommandId || (row.hasMore && (!nextCommandId || previous >= BigInt(through)))) throw new Error('操作回执游标异常')
  return { scope: currentScope, items, hasMore: row.hasMore, nextCommandId, throughCommandId: through }
}
