export interface AssistantReasoningRead {
  sessionId: string; runId: string; stepId: string; stream?: string; innerStepId?: string; skillRunId?: string; offset?: number; limit?: number
}
export interface AssistantReasoningPage {
  status: 'UNAVAILABLE' | 'COMPLETE' | 'PARTIAL' | 'INCOMPLETE' | 'AVAILABLE'
  scope: { sessionId: string; runId: string; stepId: string; innerStepId?: string; skillRunId?: string }
  streams?: string[]; stream?: string; chunks?: { content: string; innerStepId?: string; skillRunId?: string }[]
  hasMore?: boolean; nextOffset?: number; hasMoreStreams?: boolean
}
export function validateReasoningRead(body: AssistantReasoningRead): void {
  if (![body.sessionId, body.runId, body.stepId].every(id => /^[1-9][0-9]{0,18}$/.test(id))
    || (body.innerStepId === undefined) !== (body.skillRunId === undefined)
    || body.innerStepId !== undefined && !/^[1-9][0-9]{0,18}$/.test(body.innerStepId)
    || body.skillRunId !== undefined && !/^[1-9][0-9]{0,18}$/.test(body.skillRunId)
    || body.stream !== undefined && !/^[a-f0-9]{64}$/.test(body.stream)
    || body.offset !== undefined && (!Number.isInteger(body.offset) || body.offset < 0 || body.offset > 1000000)
    || body.limit !== undefined && (!Number.isInteger(body.limit) || body.limit < 1 || body.limit > 64)) throw new Error('思考记录读取参数无效')
}
export function assistantReasoningPage(raw: unknown, request: AssistantReasoningRead): AssistantReasoningPage {
  if (!raw || typeof raw !== 'object') throw new Error('思考记录响应无效')
  const value = raw as AssistantReasoningPage
  if (!['UNAVAILABLE', 'COMPLETE', 'PARTIAL', 'INCOMPLETE', 'AVAILABLE'].includes(value.status)
    || value.scope?.sessionId !== request.sessionId || value.scope?.runId !== request.runId || value.scope?.stepId !== request.stepId
    || value.scope?.innerStepId !== request.innerStepId || value.scope?.skillRunId !== request.skillRunId
    || value.stream !== undefined && (!/^[a-f0-9]{64}$/.test(value.stream) || request.stream !== undefined && request.stream !== value.stream)
    || value.streams !== undefined && (!Array.isArray(value.streams) || value.streams.length > 64 || value.streams.some(id => typeof id !== 'string' || !/^[a-f0-9]{64}$/.test(id)))
    || value.chunks !== undefined && (!Array.isArray(value.chunks) || value.chunks.length > 64 || value.chunks.some(chunk => typeof chunk?.content !== 'string' || chunk.content.length > 65536)
      || value.chunks.reduce((sum, chunk) => sum + chunk.content.length, 0) > 131072)
    || value.hasMore && (!Number.isInteger(value.nextOffset) || value.nextOffset! <= (request.offset || 0) || value.nextOffset! > 1000000)) throw new Error('思考记录不属于当前范围或超过读取限制')
  return value
}
