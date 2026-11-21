import { assistantCommandId, assistantCommandRejectionIsFinal } from './assistantRequestCoordinator'
import { assistantContextWire } from './assistantContext'
import type { AssistantContextRef } from '~/types/assistant-api'

/** Loss of access during recovery does not establish the outcome of an earlier submission. */
export function assistantQueuedRejectionIsFinal(errorCode: string) {
  return assistantCommandRejectionIsFinal(errorCode)
}

export interface AssistantQueuedMessage { id: string; text: string; contextRefs?: AssistantContextRef[]; commandId?: string; body?: Record<string, unknown> }
interface QueueState { items: AssistantQueuedMessage[]; loaded: boolean; error: string | null }
const emptyState: QueueState = { items: [], loaded: false, error: null }

const identifier = (value: unknown): value is string => typeof value === 'string' && value.length > 0 && value.length <= 512
const positiveVersion = (value: unknown) => Number.isSafeInteger(value) && Number(value) > 0
function validReference(value: unknown): value is AssistantContextRef {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const ref = value as Record<string, unknown>
  if (ref.label !== undefined && (typeof ref.label !== 'string' || ref.label.length > 1000)) return false
  const fields: Record<string, string[]> = {
    FREE_CANVAS: ['workspaceId', 'objectId', 'objectVersion', 'resourceId'], CANVAS_RESOURCE: ['workspaceId', 'resourceId'],
    FLOW_ENTITY: ['projectId', 'episodeId', 'entityType', 'entityId', 'revision'], DOCUMENT: ['documentId', 'version', 'contentHash'],
    SCRIPT: ['projectId', 'episodeId', 'scriptId', 'contentHash', 'selection']
  }
  const allowed = typeof ref.kind === 'string' ? fields[ref.kind] : undefined
  if (!allowed || Object.keys(ref).some(key => !['kind', 'label', ...allowed].includes(key))) return false
  if (ref.kind === 'FREE_CANVAS') return identifier(ref.workspaceId) && identifier(ref.objectId) && positiveVersion(ref.objectVersion) && (ref.resourceId === undefined || identifier(ref.resourceId))
  if (ref.kind === 'CANVAS_RESOURCE') return identifier(ref.workspaceId) && identifier(ref.resourceId)
  if (ref.kind === 'FLOW_ENTITY') return ['projectId', 'episodeId', 'entityType', 'entityId', 'revision'].every(key => identifier(ref[key]))
  if (ref.kind === 'DOCUMENT') return ['documentId', 'version', 'contentHash'].every(key => identifier(ref[key]))
  if (!['projectId', 'episodeId', 'scriptId', 'contentHash'].every(key => identifier(ref[key]))) return false
  if (ref.selection === undefined) return true
  const selection = ref.selection as Record<string, unknown>
  return !!selection && typeof selection === 'object' && !Array.isArray(selection)
    && Object.keys(selection).every(key => ['start', 'end', 'selectedTextHash'].includes(key))
    && Number.isSafeInteger(selection.start) && Number(selection.start) >= 0 && Number.isSafeInteger(selection.end)
    && Number(selection.end) >= Number(selection.start) && identifier(selection.selectedTextHash)
}
const validReferences = (refs: unknown): refs is AssistantContextRef[] => Array.isArray(refs) && refs.every(validReference)

/** Unsent text and fixed reference identities. Restoring this journal never sends or refreshes them. */
export class AssistantComposerQueue {
  private state: QueueState = emptyState
  private listeners = new Set<() => void>()
  constructor(private readonly key: string, private readonly sessionId: string) {}
  getSnapshot = () => this.state
  getServerSnapshot = () => emptyState
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private publish(state: QueueState) { this.state = state; this.listeners.forEach(listener => listener()) }
  private read() {
    try {
      const raw = localStorage.getItem(this.key)
      const parsed: unknown = raw ? JSON.parse(raw) : []
      if (!Array.isArray(parsed) || parsed.length > 20 || parsed.some(item => !item || typeof item !== 'object' || Array.isArray(item)
        || Object.keys(item).some(key => !['id', 'text', 'contextRefs', 'commandId', 'body'].includes(key)) || !this.validId(item.id)
        || typeof item.text !== 'string' || !item.text.trim() || item.text.length > 100000
        || item.commandId != null && !this.validId(item.commandId)
        || item.contextRefs !== undefined && !validReferences(item.contextRefs)
        || item.body != null && (!item.commandId || !this.validBody(item.body, item.commandId, item.text, item.contextRefs || [])))
        || new Set(parsed.map(item => item.id)).size !== parsed.length
        || parsed.reduce((total, item) => total + item.text.length, 0) > 200000) throw new Error('待发送消息恢复记录无效，请保留现场并重新打开会话')
      this.publish({ items: parsed, loaded: true, error: null })
    } catch (cause) { this.publish({ ...this.state, loaded: true, error: cause instanceof Error ? cause.message : '待发送消息无法恢复' }) }
  }
  private validId(value: unknown) { return typeof value === 'string' && /^asst-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value) }
  private validBody(value: unknown, commandId: string, text: string, refs: AssistantContextRef[]): value is Record<string, unknown> {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return false
    const body = value as Record<string, unknown>
    return Object.keys(body).every(key => ['sessionId', 'clientRequestId', 'expectedSessionRevision', 'expectedRunRevision', 'parts', 'contextRefs', 'executionPreference', 'selectedSkillCode', 'selectedModels'].includes(key))
      && body.sessionId === this.sessionId && body.clientRequestId === commandId
      && Number.isSafeInteger(body.expectedSessionRevision) && Number(body.expectedSessionRevision) >= 0
      && (body.expectedRunRevision == null || Number.isSafeInteger(body.expectedRunRevision) && Number(body.expectedRunRevision) >= 0)
      && Array.isArray(body.parts) && body.parts.length === 1 && body.parts[0]?.type === 'TEXT' && body.parts[0]?.text === text
      && Object.keys(body.parts[0]).every(key => ['type', 'text'].includes(key))
      && JSON.stringify(body.contextRefs) === JSON.stringify(refs.map(assistantContextWire)) && ['MANUAL', 'AUTO'].includes(String(body.executionPreference))
      && (!refs.length || body.expectedRunRevision == null)
      && (body.selectedSkillCode == null || typeof body.selectedSkillCode === 'string' && body.selectedSkillCode.length <= 100)
      && (body.selectedModels == null || Array.isArray(body.selectedModels) && body.selectedModels.length <= 2
        && new Set(body.selectedModels.map(item => item?.modelType)).size === body.selectedModels.length
        && body.selectedModels.every(item => item && ['image', 'video'].includes(item.modelType)
          && typeof item.modelCode === 'string' && item.modelCode.trim() && item.modelCode.length <= 100
          && Object.keys(item).every(key => ['modelType', 'modelCode'].includes(key))))
  }
  activate = () => {
    this.read()
    const changed = (event: StorageEvent) => { if (event.key === this.key || event.key === null) this.read() }
    window.addEventListener('storage', changed)
    return () => window.removeEventListener('storage', changed)
  }
  private write(items: AssistantQueuedMessage[]) {
    if (!this.state.loaded || this.state.error) throw new Error(this.state.error || '待发送消息仍在恢复')
    const raw = localStorage.getItem(this.key)
    if (JSON.stringify(raw ? JSON.parse(raw) : []) !== JSON.stringify(this.state.items)) {
      this.read(); throw new Error('待发送消息已在另一页面更新，请核对后重试')
    }
    // Persist before clearing the composer or displaying acceptance.
    localStorage.setItem(this.key, JSON.stringify(items))
    this.publish({ items, loaded: true, error: null })
  }
  enqueue = (text: string, contextRefs: AssistantContextRef[] = []) => {
    if (!text.trim() || text.length > 100000) throw new Error('消息需为 1 到 100000 个字符')
    if (this.state.items.length >= 20 || this.state.items.reduce((n, item) => n + item.text.length, 0) + text.length > 200000) throw new Error('待发送消息已满，请先引导或删除部分消息')
    if (!validReferences(contextRefs)) throw new Error('引用快照不完整，未加入待发送队列')
    this.write([...this.state.items, { id: assistantCommandId(), text, ...(contextRefs.length ? { contextRefs: structuredClone(contextRefs) } : {}) }])
  }
  remove = (id: string) => {
    if (this.state.items.some(item => item.id === id && item.commandId)) throw new Error('此消息已提交核实，不能按未发送消息删除')
    this.write(this.state.items.filter(item => item.id !== id))
  }
  begin = (id: string): AssistantQueuedMessage & { commandId: string } => {
    const item = this.state.items.find(row => row.id === id)
    if (!item) throw new Error('此消息不存在，请重新核对')
    if (item.commandId) return { ...item, commandId: item.commandId }
    const commandId = assistantCommandId()
    this.write(this.state.items.map(row => row.id === id ? { ...row, commandId } : row))
    return { ...item, commandId }
  }
  attachBody = (commandId: string, body: Record<string, unknown>) => {
    const item = this.state.items.find(row => row.commandId === commandId)
    if (!item || !this.validBody(body, commandId, item.text, item.contextRefs || [])) throw new Error('待发送消息的原操作正文无效，未发送请求')
    const canonical = JSON.parse(JSON.stringify(body)) as Record<string, unknown>
    if (item.body && JSON.stringify(item.body) !== JSON.stringify(canonical)) throw new Error('原消息正文已固定，请先核实原操作')
    this.write(this.state.items.map(row => row.commandId === commandId ? { ...row, body: canonical } : row))
  }
  rejected = (commandId: string) => this.write(this.state.items.map(item => item.commandId === commandId ? { id: item.id, text: item.text, ...(item.contextRefs?.length ? { contextRefs: item.contextRefs } : {}) } : item))
  accepted = (commandId: string) => {
    if (this.state.items.some(item => item.commandId === commandId)) this.write(this.state.items.filter(item => item.commandId !== commandId))
  }
}
