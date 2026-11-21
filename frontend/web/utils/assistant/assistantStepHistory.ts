import type { AssistantRunStep, AssistantRunStepPage, AssistantRunStepPageRequest, AssistantSessionSnapshot } from '~/types/assistant-api'
import type { AssistantApi } from '~/utils/business/assistant'
import { assistantRunStep } from './assistantWire'
import { assistantErrorMessage } from './assistantRequestCoordinator'

function row(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('步骤分页格式无效')
  return value as Record<string, unknown>
}
function longId(value: unknown): string {
  if (typeof value !== 'string' || !/^[1-9][0-9]{0,18}$/.test(value) || BigInt(value) > 9223372036854775807n) throw new Error('步骤标识格式无效')
  return value
}
function revision(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error('步骤读取水位无效')
  return value
}
export function validateAssistantStepPageRequest(request: AssistantRunStepPageRequest): void {
  longId(request.runId)
  if (request.beforeStepId != null) longId(request.beforeStepId)
  if (request.limit != null && (!Number.isInteger(request.limit) || request.limit < 1 || request.limit > 50)) throw new Error('步骤分页大小无效')
}

/** 保留 Long 游标原字符串；只消费已冻结的安全 Step 投影。 */
export function assistantRunStepPage(value: unknown, request: AssistantRunStepPageRequest): AssistantRunStepPage {
  validateAssistantStepPageRequest(request)
  const data = row(value), scope = row(data.scope)
  if (longId(scope.runId) !== request.runId || !Array.isArray(data.items) || data.items.length > (request.limit ?? 20)
    || typeof data.hasMore !== 'boolean' || typeof scope.runStatus !== 'string' || !scope.runStatus) throw new Error('步骤分页与当前运行不符')
  let previous = request.beforeStepId ? BigInt(request.beforeStepId) : 9223372036854775808n
  const items = data.items.map((value) => {
    const item = row(value), id = longId(item.stepId)
    if (BigInt(id) >= previous) throw new Error('步骤分页顺序或游标无效')
    previous = BigInt(id)
    return assistantRunStep(item)
  })
  const nextCursor = data.nextCursor == null ? null : longId(data.nextCursor)
  if (data.hasMore ? !items.length || nextCursor !== items[items.length - 1].stepId : nextCursor !== null) throw new Error('步骤分页游标未正确前进')
  return { scope: { sessionId: longId(scope.sessionId), runId: request.runId,
    observedSessionRevision: revision(scope.observedSessionRevision), observedLastEventSeq: revision(scope.observedLastEventSeq),
    observedRunRevision: revision(scope.observedRunRevision), runStatus: scope.runStatus,
    nextAction: typeof scope.nextAction === 'string' ? scope.nextAction : null }, items, hasMore: data.hasMore, nextCursor }
}

type Watermark = { sessionRevision: number; eventSeq: number; runRevision: number }
const older = (left: Watermark, right: Watermark) => left.sessionRevision < right.sessionRevision || left.eventSeq < right.eventSeq || left.runRevision < right.runRevision
interface HistoryState { runStatus?: string; items: AssistantRunStep[]; loaded: boolean; loading: boolean; nextCursor: string | null; error: string | null }

/** 会话/运行独占的只读控制器；串行分页，实时快照优先于迟到的历史页。 */
export class AssistantStepHistoryStore {
  private state: HistoryState = { items: [], loaded: false, loading: false, nextCursor: null, error: null }
  private listeners = new Set<() => void>()
  private watermark: Watermark = { sessionRevision: 0, eventSeq: 0, runRevision: 0 }
  private generation = 0
  private inFlight: Promise<void> | null = null
  private queuedRefresh = false
  private started = false
  private disposed = false
  constructor(private api: Pick<AssistantApi, 'runStepPage'>, readonly sessionId: string, readonly runId: string) {}
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => this.listeners.delete(listener) }
  activate = () => { this.disposed = false }
  dispose = () => { this.disposed = true; this.generation += 1; this.inFlight = null; this.queuedRefresh = false; this.started = false; this.patch({ loading: false }) }
  private patch(value: Partial<HistoryState>) { this.state = { ...this.state, ...value }; this.listeners.forEach((listener) => listener()) }
  private merge(items: AssistantRunStep[]) {
    return [...new Map([...this.state.items, ...items].map((item) => [item.stepId, item])).values()]
      .sort((a, b) => BigInt(a.stepId) > BigInt(b.stepId) ? -1 : 1)
  }
  observe = (snapshot: AssistantSessionSnapshot) => {
    if (this.disposed || snapshot.sessionId !== this.sessionId) return
    const active = snapshot.activeRun?.runId === this.runId
    const mark = { sessionRevision: snapshot.revision, eventSeq: snapshot.lastEventSeq, runRevision: active ? snapshot.activeRun!.stateRevision : this.watermark.runRevision }
    if (older(mark, this.watermark)) return
    const changed = older(this.watermark, mark)
    this.watermark = mark
    if (active) this.patch({ runStatus: snapshot.activeRun!.status, items: this.merge(snapshot.steps || []) })
    if (changed && this.started) {
      if (this.inFlight) this.queuedRefresh = true
      else void this.refresh()
    }
  }
  refresh = (): Promise<void> => {
    if (this.disposed) return Promise.resolve()
    this.started = true
    if (this.inFlight) return this.inFlight
    return this.read()
  }
  more = (): Promise<void> => {
    if (this.disposed) return Promise.resolve()
    if (this.inFlight) return this.inFlight
    if (!this.state.loaded) return this.refresh()
    return this.state.nextCursor ? this.read(this.state.nextCursor) : Promise.resolve()
  }
  private read(beforeStepId?: string): Promise<void> {
    const generation = this.generation, before = { ...this.watermark }
    this.patch({ loading: true, error: null })
    this.inFlight = (async () => {
      try {
        const page = await this.api.runStepPage({ runId: this.runId, beforeStepId, limit: 20 })
        if (this.disposed || generation !== this.generation) return
        if (page.scope.sessionId !== this.sessionId || page.scope.runId !== this.runId) throw new Error('步骤不属于当前会话或运行')
        const mark = { sessionRevision: page.scope.observedSessionRevision, eventSeq: page.scope.observedLastEventSeq, runRevision: page.scope.observedRunRevision }
        if (older(mark, this.watermark)) {
          if (older(before, this.watermark)) this.queuedRefresh = true
          else this.patch({ error: '步骤读取早于当前状态，请稍后刷新。' })
          return
        }
        this.watermark = mark
        this.patch({ runStatus: page.scope.runStatus, items: this.merge(page.items), loaded: true, nextCursor: page.nextCursor })
      } catch (cause) { if (!this.disposed && generation === this.generation) this.patch({ error: assistantErrorMessage(cause) }) }
      finally {
        if (!this.disposed && generation === this.generation) {
          this.inFlight = null
          this.patch({ loading: false })
          if (this.queuedRefresh) { this.queuedRefresh = false; await this.read() }
        }
      }
    })()
    return this.inFlight
  }
}
