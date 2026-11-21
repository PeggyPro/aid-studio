import type { AssistantEffectResolved, AssistantTargetEffect, AssistantTargetRef } from '~/types/assistant-effects'
import type { AssistantSessionSnapshot } from '~/types/assistant-api'

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('定位信息格式无效，请刷新')
  return value as Record<string, unknown>
}
function text(value: unknown): string {
  if (typeof value !== 'string' || !value.trim() || value !== value.trim() || value.length > 512) throw new Error('定位身份或版本格式无效')
  return value
}
function count(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error('定位读取水位无效')
  return value
}
function optional(value: unknown): string | undefined { return value == null ? undefined : text(value) }
function actions(value: unknown, available: boolean): ('LOCATE')[] {
  if (!Array.isArray(value) || value.length !== (available ? 1 : 0) || available && value[0] !== 'LOCATE') throw new Error('定位权限格式无效')
  return available ? ['LOCATE'] : []
}
export function assistantTargetRef(value: unknown): AssistantTargetRef {
  const row = object(value), version = text(row.version)
  if (row.kind === 'FREE_CANVAS') return { kind: row.kind, projectId: text(row.projectId), workspaceId: text(row.workspaceId), objectId: text(row.objectId), version }
  if (row.kind === 'FLOW_ENTITY') return { kind: row.kind, projectId: text(row.projectId), episodeId: text(row.episodeId), entityType: text(row.entityType), entityId: text(row.entityId), version }
  if (row.kind === 'SCRIPT') {
    const selection = row.selection == null ? undefined : object(row.selection)
    const range = selection ? { start: count(selection.start), end: count(selection.end), selectedTextHash: text(selection.selectedTextHash) } : undefined
    if (range && (range.end < range.start || !/^[a-f0-9]{64}$/i.test(range.selectedTextHash))) throw new Error('剧本选段定位无效')
    const contentHash = text(row.contentHash)
    if (!/^[a-f0-9]{64}$/i.test(contentHash)) throw new Error('剧本版本哈希无效')
    return { kind: row.kind, scriptId: text(row.scriptId), version, contentHash, projectId: optional(row.projectId), episodeId: optional(row.episodeId), selection: range }
  }
  throw new Error('当前定位类型尚不支持')
}
export function assistantTargetEffect(value: unknown): AssistantTargetEffect {
  const row = object(value)
  if (row.sourceKind !== 'RUNTIME_STEP' && row.sourceKind !== 'ARTIFACT_ADOPTION') throw new Error('定位来源不可识别')
  if (!['OBSERVED', 'CREATED', 'UPDATED', 'ADOPTED'].includes(String(row.effectKind))) throw new Error('定位效果不可识别')
  if (row.locateState !== 'AVAILABLE' && row.locateState !== 'UNAVAILABLE') throw new Error('定位状态不可识别')
  const available = row.locateState === 'AVAILABLE'
  if (!available && row.resultTargetRef != null) throw new Error('不可用定位不得返回目标')
  return { effectId: text(row.effectId), sourceKind: row.sourceKind, sourceId: text(row.sourceId), effectKind: row.effectKind as AssistantTargetEffect['effectKind'],
    toolCode: optional(row.toolCode), artifactId: optional(row.artifactId), locateState: row.locateState, reasonCode: optional(row.reasonCode),
    allowedActions: actions(row.allowedActions, available), resultTargetRef: available ? assistantTargetRef(row.resultTargetRef) : undefined }
}
/** 旧记录或损坏的可选投影只隐藏定位，不阻断会话/产物阅读。 */
export function optionalAssistantEffect(value: unknown): AssistantTargetEffect | undefined {
  if (value == null) return undefined
  try { return assistantTargetEffect(value) } catch { return undefined }
}
export function assistantTargetEffects(value: unknown): AssistantTargetEffect[] {
  if (!Array.isArray(value)) return []
  const ids = new Set<string>()
  return value.flatMap((entry) => {
    const effect = optionalAssistantEffect(entry)
    if (!effect || ids.has(effect.effectId)) return []
    ids.add(effect.effectId); return [effect]
  })
}
export function assistantEffectResolved(value: unknown, sessionId: string, effectId: string): AssistantEffectResolved {
  const row = object(value)
  if (text(row.sessionId) !== text(sessionId) || text(row.effectId) !== text(effectId)) throw new Error('定位响应不属于当前会话')
  if (row.locateState !== 'AVAILABLE' && row.locateState !== 'UNAVAILABLE') throw new Error('定位状态不可识别')
  const available = row.locateState === 'AVAILABLE'
  if (!available && row.currentTargetRef != null) throw new Error('不可用定位不得返回目标')
  return { sessionId, effectId, locateState: row.locateState, reasonCode: optional(row.reasonCode), allowedActions: actions(row.allowedActions, available),
    currentTargetRef: available ? assistantTargetRef(row.currentTargetRef) : undefined, observedSessionRevision: count(row.observedSessionRevision), lastEventSeq: count(row.lastEventSeq) }
}
export function canLocateAssistantEffect(effect?: AssistantTargetEffect): boolean {
  return effect?.locateState === 'AVAILABLE' && effect.allowedActions.includes('LOCATE') && !!effect.resultTargetRef
}
function targetIdentity(ref: AssistantTargetRef): string {
  if (ref.kind === 'FREE_CANVAS') return JSON.stringify([ref.kind, ref.projectId, ref.workspaceId, ref.objectId])
  if (ref.kind === 'FLOW_ENTITY') return JSON.stringify([ref.kind, ref.projectId, ref.episodeId, ref.entityType, ref.entityId])
  return JSON.stringify([ref.kind, ref.scriptId, ref.projectId, ref.episodeId])
}

/** 每次点击重验服务端权限；同会话/效果单飞且只产生一次定位副作用。 */
export class AssistantEffectLocator {
  private inFlight = new Map<string, Promise<void>>()
  private generation = 0
  private active = true
  constructor(private api: { effectResolve: (sessionId: string, effectId: string) => Promise<AssistantEffectResolved> },
    private snapshot: () => AssistantSessionSnapshot | null | undefined,
    private locate: (ref: AssistantTargetRef, isCurrent: () => boolean) => void | Promise<void>) {}
  activate = () => { this.active = true }
  dispose = () => { this.active = false; this.generation += 1; this.inFlight.clear() }
  resolve = (input: AssistantTargetEffect, isCurrent = () => true): Promise<void> => {
    const before = this.snapshot(), effect = assistantTargetEffect(input)
    if (!this.active || !isCurrent() || !before || !canLocateAssistantEffect(effect)) return Promise.reject(new Error('此项暂时无法定位'))
    const key = before.sessionId + ':' + effect.effectId, existing = this.inFlight.get(key)
    if (existing) return existing
    const generation = this.generation
    const pending = (async () => {
      const raw = await this.api.effectResolve(before.sessionId, effect.effectId)
      const current = this.snapshot()
      if (!this.active || !isCurrent() || generation !== this.generation || !current || current.sessionId !== before.sessionId || current.actorId !== before.actorId
        || current.workspaceId !== before.workspaceId || current.projectId !== before.projectId || current.episodeId !== before.episodeId) throw new Error('会话或工作区已变化，请重新定位')
      const resolved = assistantEffectResolved(raw, before.sessionId, effect.effectId)
      if (resolved.observedSessionRevision < current.revision || resolved.lastEventSeq < current.lastEventSeq) throw new Error('定位响应早于当前状态，请重新定位')
      if (resolved.locateState !== 'AVAILABLE' || !resolved.allowedActions.includes('LOCATE') || !resolved.currentTargetRef) throw new Error('关联对象已不可用或无访问权限')
      if (targetIdentity(effect.resultTargetRef!) !== targetIdentity(resolved.currentTargetRef)) throw new Error('定位目标与已记录结果不符')
      const ref = resolved.currentTargetRef
      if (ref.kind === 'FREE_CANVAS' && ref.workspaceId !== current.workspaceId || current.projectId && ref.projectId && ref.projectId !== current.projectId) throw new Error('关联对象不属于当前工作区')
      await this.locate(ref, () => this.active && generation === this.generation && isCurrent() && this.snapshot()?.sessionId === before.sessionId && this.snapshot()?.actorId === before.actorId)
    })().finally(() => { if (this.inFlight.get(key) === pending) this.inFlight.delete(key) })
    this.inFlight.set(key, pending)
    return pending
  }
}
