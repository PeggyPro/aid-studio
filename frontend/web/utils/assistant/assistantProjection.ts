import { assistantSessionSnapshot } from './assistantWire'
import type { AssistantSessionSnapshot } from '~/types/assistant-api'

const fields = new Set(['session', 'messages', 'hasEarlierMessages', 'activeRun', 'inputRequest',
  'inputHistory', 'inputHistoryHasMore', 'conversationArtifacts', 'conversationArtifactsHasMore',
  'plan', 'steps', 'stepsHasMore', 'tasks', 'artifacts', 'artifactsHasMore', 'approval', 'lastEventSeq'])
const optionalFields = new Set(['activities', 'activitiesHasMore'])
const knownField = (field: string) => fields.has(field) || optionalFields.has(field)
type RecordValue = Record<string, unknown>
const record = (value: unknown): value is RecordValue => !!value && typeof value === 'object' && !Array.isArray(value)
const sequence = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 0

/** The cursor belongs to one subscription; HTTP history and other sockets cannot change its base. */
export class AssistantProjectionReducer {
  private base: RecordValue | null = null
  private cursor = 0
  constructor(private sessionId: string, private subscriptionId: string, private actorId: string) {}

  apply(value: unknown): AssistantSessionSnapshot | null {
    if (!record(value) || value.type !== 'SESSION_PROJECTION' || value.projectionVersion !== 1
      || value.sessionId !== this.sessionId || value.subscriptionId !== this.subscriptionId
      || !sequence(value.fromSeq) || !sequence(value.toSeq) || value.toSeq < value.fromSeq) {
      throw new Error('助手实时视图无效')
    }
    if (this.base && value.toSeq < this.cursor) return null
    let candidate: RecordValue
    if (value.kind === 'SNAPSHOT') {
      const initial = value.snapshot
      if (this.base || !record(initial) || [...fields].some(field => !(field in initial))) {
        throw new Error('助手实时快照不完整')
      }
      candidate = initial
    } else if (value.kind === 'DELTA') {
      if (this.base && value.toSeq === this.cursor && value.fromSeq < this.cursor) return null
      if (!this.base || value.fromSeq !== this.cursor || !record(value.changes)
        || Object.keys(value.changes).some(key => !knownField(key))) throw new Error('助手实时增量需要恢复')
      candidate = { ...this.base, ...value.changes }
    } else throw new Error('助手实时视图类型无效')
    if (candidate.lastEventSeq !== value.toSeq) throw new Error('助手实时序号不一致')
    if (Object.keys(candidate).some(field => !knownField(field)) || !record(candidate.session) || ['messages', 'inputHistory', 'conversationArtifacts', 'steps', 'tasks', 'artifacts'].some(field => !Array.isArray(candidate[field]))
      || candidate.activities !== undefined && !Array.isArray(candidate.activities)
      || candidate.activitiesHasMore !== undefined && typeof candidate.activitiesHasMore !== 'boolean'
      || ['hasEarlierMessages', 'inputHistoryHasMore', 'conversationArtifactsHasMore', 'stepsHasMore', 'artifactsHasMore'].some(field => typeof candidate[field] !== 'boolean')
      || ['activeRun', 'inputRequest', 'plan', 'approval'].some(field => candidate[field] !== null && !record(candidate[field]))) throw new Error('助手实时视图字段无效')
    // Decode before accepting the base. A malformed card/result cannot poison future patches.
    const snapshot = assistantSessionSnapshot(candidate, this.actorId)
    if (snapshot.sessionId !== this.sessionId) throw new Error('助手实时会话不一致')
    this.base = candidate
    this.cursor = value.toSeq
    return snapshot
  }
}
