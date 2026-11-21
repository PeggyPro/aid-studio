import type { PendingSeriesAdoption, SeriesCapabilities, SeriesCapabilityRequest, SeriesCommit, SeriesReceipt, SeriesScope } from '../../types/assistant-series'

function row(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('多集采用响应无效')
  return value as Record<string, unknown>
}
function id(value: unknown): string {
  if (typeof value !== 'string' || !/^[1-9][0-9]{0,18}$/.test(value) || BigInt(value) > 9223372036854775807n) throw new Error('多集采用身份无效')
  return value
}
function text(value: unknown, max = 512): string {
  if (typeof value !== 'string' || !value.trim() || value.length > max) throw new Error('多集采用字段无效')
  return value
}
function hash(value: unknown): string {
  if (typeof value !== 'string' || !/^[0-9a-f]{64}$/.test(value)) throw new Error('多集采用哈希无效')
  return value
}
function integer(value: unknown, min = 0): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < min) throw new Error('多集采用范围或计数无效')
  return value
}
function optional(value: unknown, max = 4000): string | null {
  if (value == null) return null
  if (typeof value !== 'string' || value.length > max) throw new Error('多集采用可选字段无效')
  return value
}
function validateScope(scope: SeriesScope): void {
  id(scope.actorId); id(scope.sessionId); id(scope.projectId)
  if (scope.moduleCode !== 'SCREENWRITER' || scope.episodeId !== '0') throw new Error('多集采用需要当前项目范围的编剧会话')
}
export function validateSeriesRequest(request: SeriesCapabilityRequest): void {
  id(request.sessionId)
  if (request.mode === 'UPDATE_EXISTING') {
    if (!Array.isArray(request.items) || request.items.length < 1 || request.items.length > 100 || 'artifactId' in request || 'episodeKeyword' in request) throw new Error('更新需选择1到100个明确目标，不能混用追加模式')
    const episodes = new Set<string>()
    for (const item of request.items) { id(item.artifactId); id(item.episodeId); if (episodes.has(item.episodeId)) throw new Error('同一集不能重复更新'); episodes.add(item.episodeId) }
  } else if (request.mode === 'APPEND_NEW') {
    id(request.artifactId); optional(request.episodeKeyword, 100)
    if ('items' in request) throw new Error('追加只能提交一个整季产物')
  } else throw new Error('未知的多集采用模式')
}

/** All preview rows are shown/confirmed as one batch; no client-side split or page subset. */
export function verifySeriesCapabilities(value: unknown, request: SeriesCapabilityRequest, scope: SeriesScope): SeriesCapabilities {
  validateScope(scope); validateSeriesRequest(request)
  const data = row(value)
  if (request.sessionId !== scope.sessionId || data.schemaVersion !== 'assistant-series-adoption-capabilities/v1' || data.mode !== request.mode || id(data.projectId) !== scope.projectId
    || typeof data.allowed !== 'boolean' || !Array.isArray(data.items)) throw new Error('预览不属于当前项目或模式')
  const limit = request.mode === 'APPEND_NEW' ? 200 : 100
  if (data.items.length > limit || data.allowed && !data.items.length) throw new Error('预览集数超出模式限制')
  const episodes = new Set<string>()
  let previousEnd = -1
  const items = data.items.map((value, index) => {
    const item = row(value), artifactId = id(item.artifactId), start = integer(item.sourceCharStart), end = integer(item.sourceCharEnd), effective = integer(item.effectiveCharCount)
    if (integer(item.itemIndex) !== index || end < start || effective > 10000 || item.allowed !== true && item.allowed !== false) throw new Error('预览顺序、范围或单集字数无效')
    const episodeId = item.episodeId == null ? null : id(item.episodeId), currentTargetRevision = optional(item.currentTargetRevision, 128)
    if (request.mode === 'UPDATE_EXISTING') {
      const expected = request.items[index]
      if (!expected || episodeId !== expected.episodeId || artifactId !== expected.artifactId || !currentTargetRevision || episodes.has(episodeId)) throw new Error('更新预览与选中的完整目标集不符')
      episodes.add(episodeId)
    } else {
      if (artifactId !== request.artifactId || episodeId !== null || currentTargetRevision !== null || start < previousEnd) throw new Error('追加预览包含未授权目标或重叠分片')
      previousEnd = end
    }
    return { itemIndex: index, artifactId, artifactRef: text(item.artifactRef), artifactContentHash: hash(item.artifactContentHash), segmentContentHash: hash(item.segmentContentHash),
      episodeId, episodeNo: integer(item.episodeNo, 1), currentTargetRevision, proposedTitle: optional(item.proposedTitle), proposedDescription: optional(item.proposedDescription),
      sourceCharStart: start, sourceCharEnd: end, effectiveCharCount: effective, allowed: item.allowed as boolean, reason: optional(item.reason) }
  })
  if (request.mode === 'UPDATE_EXISTING' && data.allowed && items.length !== request.items.length) throw new Error('服务端预览未覆盖整个更新批次')
  if (data.allowed && items.some(item => !item.allowed || !item.effectiveCharCount)) throw new Error('预览含不可采用的目标')
  const snapshot = optional(data.splitCapabilitySnapshot, 180000)
  if (request.mode === 'APPEND_NEW' && data.allowed && (!snapshot || !/^[A-Za-z0-9_-]+$/.test(snapshot) || !data.splitterContractVersion
    || items.some(item => item.artifactContentHash !== items[0]?.artifactContentHash) || items.reduce((sum,item) => sum + item.effectiveCharCount, 0) > 100000)) throw new Error('追加预览缺少可重算快照或超出全文上限')
  if (request.mode === 'UPDATE_EXISTING' && snapshot !== null) throw new Error('更新模式不接受拆分候选快照')
  return { schemaVersion: 'assistant-series-adoption-capabilities/v1', mode: request.mode, projectId: scope.projectId,
    projectEpisodeSetDigest: hash(data.projectEpisodeSetDigest), capabilityDigest: hash(data.capabilityDigest), splitterContractVersion: optional(data.splitterContractVersion),
    splitCapabilitySnapshot: snapshot, allowed: data.allowed as boolean, reason: optional(data.reason), items }
}

export function seriesCommitFromPreview(scope: SeriesScope, preview: SeriesCapabilities, clientRequestId: string): SeriesCommit {
  validateScope(scope); text(clientRequestId, 64)
  if (!preview.allowed || !preview.items.length || preview.items.some(item => !item.allowed) || preview.projectId !== scope.projectId) throw new Error('该批次尚不可确认')
  const base = { sessionId: scope.sessionId, capabilityDigest: preview.capabilityDigest, expectedProjectEpisodeSetDigest: preview.projectEpisodeSetDigest, clientRequestId }
  if (preview.mode === 'APPEND_NEW') {
    if (!preview.splitCapabilitySnapshot || preview.items.length > 200) throw new Error('追加快照缺失')
    return { ...base, mode: preview.mode, artifactId: preview.items[0].artifactId, artifactContentHash: preview.items[0].artifactContentHash, splitCapabilitySnapshot: preview.splitCapabilitySnapshot }
  }
  if (preview.items.length > 100) throw new Error('更新集数超限')
  return { ...base, mode: preview.mode, items: preview.items.map(item => {
    if (!item.episodeId || !item.currentTargetRevision) throw new Error('更新目标缺少当前版本')
    return { artifactId: item.artifactId, artifactContentHash: item.artifactContentHash, episodeId: item.episodeId, expectedTargetRevision: item.currentTargetRevision }
  }) }
}

export function verifySeriesReceipt(value: unknown, pending: PendingSeriesAdoption): SeriesReceipt {
  const data = row(value), request = pending.request, preview = pending.preview
  if (data.schemaVersion !== 'assistant-series-adoption-receipt/v1' || id(data.sessionId) !== pending.scope.sessionId
    || data.clientRequestId !== request.clientRequestId || data.mode !== request.mode || id(data.projectId) !== pending.scope.projectId
    || data.beforeEpisodeSetDigest !== request.expectedProjectEpisodeSetDigest || !Array.isArray(data.items) || data.items.length !== preview.items.length) throw new Error('批次回执身份或数量不符，需继续核实原操作')
  const episodes = new Set<string>(), adoptions = new Set<string>()
  const items = data.items.map((value, index) => {
    const item = row(value), expected = preview.items[index], episodeId = id(item.episodeId), adoptionId = id(item.adoptionId)
    if (item.itemIndex !== index || id(item.artifactId) !== expected.artifactId || item.artifactRef !== expected.artifactRef
      || hash(item.artifactContentHash) !== expected.artifactContentHash || hash(item.segmentContentHash) !== expected.segmentContentHash
      || item.sourceCharStart !== expected.sourceCharStart || item.sourceCharEnd !== expected.sourceCharEnd || item.episodeNo !== expected.episodeNo
      || episodes.has(episodeId) || adoptions.has(adoptionId)) throw new Error('回执不能证明整个有序批次已采用')
    episodes.add(episodeId); adoptions.add(adoptionId)
    const previous = optional(item.previousTargetRevision,128), createdEpisodeId = item.createdEpisodeId == null ? null : id(item.createdEpisodeId), createdScriptId = item.createdScriptId == null ? null : id(item.createdScriptId)
    if (item.targetObjectId !== 'project:' + pending.scope.projectId + ':episode:' + episodeId || !createdScriptId) throw new Error('回执目标或新剧本身份不符')
    if (request.mode === 'UPDATE_EXISTING' && (episodeId !== expected.episodeId || previous !== expected.currentTargetRevision || createdEpisodeId !== null)
      || request.mode === 'APPEND_NEW' && (createdEpisodeId !== episodeId || !createdScriptId)) throw new Error('回执模式或目标版本不符')
    return { itemIndex: index, adoptionId, artifactId: expected.artifactId, artifactRef: expected.artifactRef, artifactContentHash: expected.artifactContentHash, segmentContentHash: expected.segmentContentHash,
      targetObjectId: text(item.targetObjectId), episodeId, episodeNo: integer(item.episodeNo,1), previousTargetRevision: previous, targetRevision: text(item.targetRevision,128),
      createdEpisodeId, createdScriptId, sourceCharStart: expected.sourceCharStart, sourceCharEnd: expected.sourceCharEnd }
  })
  return { schemaVersion: 'assistant-series-adoption-receipt/v1', commandId: id(data.commandId), sessionId: pending.scope.sessionId, clientRequestId: request.clientRequestId,
    mode: request.mode, projectId: pending.scope.projectId, beforeEpisodeSetDigest: hash(data.beforeEpisodeSetDigest), afterEpisodeSetDigest: hash(data.afterEpisodeSetDigest), items }
}

export function verifyPendingSeriesAdoption(value: PendingSeriesAdoption): PendingSeriesAdoption {
  if (value.schemaVersion !== 1) throw new Error('待核实批次存储版本不支持，已保留原数据')
  const preview = verifySeriesCapabilities(value.preview, value.preparedRequest, value.scope)
  const expected = seriesCommitFromPreview(value.scope, preview, value.request.clientRequestId)
  if (JSON.stringify(expected) !== JSON.stringify(value.request)) throw new Error('待核实批次正文与已确认预览不符，禁止发送')
  return structuredClone(value)
}

/** Command metadata stays in the existing private journal; only request is sent over the wire. */
export function seriesOperationFromCommand(body: Record<string, unknown>): PendingSeriesAdoption {
  const operation = verifyPendingSeriesAdoption(body.seriesOperation as PendingSeriesAdoption)
  if (body.clientRequestId !== operation.request.clientRequestId || body.sessionId !== operation.scope.sessionId) throw new Error('整批采用恢复标识不一致，已保留原记录')
  return operation
}

const unresolvedSeriesErrors = new Set(['SERIES_COMMAND_REUSED', 'SERIES_COMMAND_TYPE_CONFLICT', 'SERIES_COMMAND_IN_PROGRESS',
  'SERIES_COMMAND_FAILED', 'SERIES_COMMAND_COLLISION_UNKNOWN', 'SERIES_COMMAND_STATE_INVALID', 'SERIES_COMMAND_RECEIPT_INVALID', 'SERIES_ADOPTION_RECEIPT_INVALID'])
export const seriesRejectionRemainsUnresolved = (errorCode: string) => unresolvedSeriesErrors.has(errorCode)
