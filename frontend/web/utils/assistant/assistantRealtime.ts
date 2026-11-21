import { createRandomUuid } from '@/utils/clientId'
import type { AssistantApi } from '~/utils/business/assistant'
import type { AssistantActivityKind, AssistantEvent } from '~/types/assistant-api'
import { AssistantProjectionReducer } from './assistantProjection'
import type { AssistantSessionSnapshot } from '~/types/assistant-api'

export interface AssistantRealtimeTicket {
  sessionId: string; ticket: string; expireAt: string; websocketPath: string
  heartbeatSeconds: number; idleTimeoutSeconds: number; reconnectBaseMillis: number; reconnectMaxMillis: number; lastEventSeq: number
  projectionVersion?: number
}
export interface AssistantRealtimeActivityFields {
  outerStepId?: string; innerStepId?: string; activityKind?: AssistantActivityKind; safeSummary?: string; occurredAt?: string
  sourceEventSeq?: number
  stepKind?: 'MAIN_AGENT' | 'TOOL' | 'SKILL'
}
export interface AssistantRealtimeReasoning extends AssistantRealtimeActivityFields {
  runId: string; skillRunId?: string; text: string; live: boolean; startedAt?: string; completedAt?: string
  reasoningKind?: 'PUBLIC_SUMMARY' | 'PROVIDER_REASONING'
  status?: 'COMPLETED' | 'FAILED' | 'STOPPED'
  awaitingCommit?: boolean
  stepId?: string; mediaTaskId?: string; streamId?: string
}
export interface AssistantRealtimeDelta extends AssistantRealtimeActivityFields {
  type: 'TEXT_DELTA' | 'REASONING_DELTA'; sessionId: string; runId: string; skillRunId?: string
  streamId: string; content: string; reset?: boolean; transient: true; sourceEventSeq?: number
  stepId?: string; mediaTaskId?: string
  reasoningKind?: 'PUBLIC_SUMMARY' | 'PROVIDER_REASONING'
}
export interface AssistantRealtimeProgress extends AssistantRealtimeActivityFields {
  type: 'EXECUTION_PROGRESS'; sessionId: string; runId: string; skillRunId?: string
  stage?: string; eventType?: string; stepId?: string; mediaTaskId?: string; data?: unknown; transient: true
}
export interface AssistantRealtimeStreamEnded extends AssistantRealtimeActivityFields {
  type: 'STREAM_ENDED'; sessionId: string; runId: string; skillRunId?: string
  streamId: string; streamType: 'REASONING' | 'TEXT'; status: 'COMPLETED' | 'FAILED' | 'STOPPED'; transient: true
}
type Options = {
  api: AssistantApi; sessionId: string; actorId: string; cursor: () => number; current: () => boolean
  connection: (connected: boolean) => void; event: (event: AssistantEvent) => void
  delta: (delta: AssistantRealtimeDelta) => void; progress: (progress: AssistantRealtimeProgress) => void
  ended: (ended: AssistantRealtimeStreamEnded) => void
  projection: (snapshot: AssistantSessionSnapshot) => void
  recover: () => Promise<void>
}
const sequence = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 0

/** One-use authorized tickets, bounded replay, and transient output. Never resends a command. */
export class AssistantRealtimeConnection {
  private socket: WebSocket | null = null
  private retry: ReturnType<typeof setTimeout> | null = null
  private heartbeat: ReturnType<typeof setInterval> | null = null
  private stopped = false
  private generation = 0
  private connecting = false
  private delivered = 0
  private acknowledgedAt = 0
  private heartbeatNonce: string | null = null
  private attempts = 0
  private baseMillis = 1000
  private maxMillis = 30000
  private projection: AssistantProjectionReducer | null = null
  private projectionReady = false
  private handshakeAt = 0
  connected = false
  get usesProjection() { return this.projectionReady }
  constructor(private options: Options) {}

  start() { if (!this.stopped) void this.connect() }
  stop() {
    this.stopped = true; this.generation += 1
    this.clearTimers(); this.disconnect()
  }
  wake() {
    if (this.stopped) return
    if (!navigator.onLine || document.visibilityState !== 'visible') {
      this.generation += 1; this.connecting = false; this.clearTimers(); this.disconnect(); return
    }
    if (!this.socket && !this.connecting) { this.clearTimers(); void this.connect() }
  }
  private clearTimers() {
    if (this.retry) clearTimeout(this.retry)
    if (this.heartbeat) clearInterval(this.heartbeat)
    this.retry = null; this.heartbeat = null
  }
  private disconnect() {
    const socket = this.socket; this.socket = null
    this.connected = false; this.options.connection(false)
    this.projection = null; this.projectionReady = false
    if (socket) { socket.onopen = null; socket.onmessage = null; socket.onclose = null; socket.onerror = null; socket.close() }
  }
  private reconnect() {
    this.clearTimers(); this.disconnect(); this.connecting = false
    if (this.stopped || !this.options.current() || !navigator.onLine || document.visibilityState !== 'visible') return
    const delay = Math.min(this.maxMillis, this.baseMillis * 2 ** Math.min(this.attempts++, 5))
    this.retry = setTimeout(() => { this.retry = null; void this.connect() }, delay)
  }
  private async connect() {
    if (this.stopped || this.connecting || this.socket || !this.options.current()
      || !navigator.onLine || document.visibilityState !== 'visible' || !this.options.api.transport.websocketUrl) return
    this.connecting = true
    const generation = ++this.generation
    try {
      const afterSeq = this.options.cursor()
      const ticket = await this.options.api.realtimeTicket(this.options.sessionId, afterSeq)
      if (generation !== this.generation || this.stopped || !this.options.current()) return
      const path = ticket.websocketPath
      const expectedPath = this.options.api.transport.websocketPath || '/ws/canvas/assistant'
      // Never follow an arbitrary URL returned by an invalid/misconfigured response.
      if (String(ticket.sessionId) !== this.options.sessionId || typeof path !== 'string'
        || !path.startsWith(`${expectedPath}?ticket=`) || !/^[A-Za-z0-9_%.-]+$/.test(path.slice(expectedPath.length + 8))
        || typeof ticket.ticket !== 'string' || !ticket.ticket
        || decodeURIComponent(path.slice(path.indexOf('=') + 1)) !== ticket.ticket
        || !sequence(ticket.lastEventSeq)) throw new Error('Invalid assistant realtime ticket')
      const url = this.options.api.transport.websocketUrl(path)
      if (!/^wss?:\/\//.test(url)) throw new Error('Invalid assistant websocket protocol')
      this.baseMillis = Math.max(1000, Math.min(10000, ticket.reconnectBaseMillis || 1000))
      this.maxMillis = Math.max(this.baseMillis, Math.min(60000, ticket.reconnectMaxMillis || 30000))
      const interval = Math.max(10, Math.min(30, ticket.heartbeatSeconds || 15)) * 1000
      const idle = Math.max(interval * 2, Math.min(120, ticket.idleTimeoutSeconds || 45) * 1000)
      this.delivered = afterSeq
      const socket = new WebSocket(url)
      this.socket = socket
      this.acknowledgedAt = Date.now()
      this.heartbeatNonce = null
      socket.onopen = () => {
        if (generation !== this.generation || !this.options.current()) { socket.close(); return }
        // Handshake, not TCP open, establishes a healthy subscription.
        this.heartbeat = setInterval(() => {
          if (Date.now() - this.acknowledgedAt >= idle || this.projection && !this.projectionReady && Date.now() - this.handshakeAt >= idle) { this.reconnect(); return }
          if (socket.readyState === WebSocket.OPEN) {
            this.heartbeatNonce = createRandomUuid()
            socket.send(JSON.stringify({ type: 'HEARTBEAT', nonce: this.heartbeatNonce, acknowledgedSeq: this.projection ? this.delivered : this.options.cursor() }))
          }
        }, interval)
      }
      socket.onmessage = (message) => {
        if (generation !== this.generation || this.stopped || !this.options.current()) return
        if (typeof message.data !== 'string' || message.data.length > 524288) { this.reconnect(); return }
        try {
          const value = JSON.parse(message.data) as Record<string, unknown>
          if (!value || String(value.sessionId ?? this.options.sessionId) !== this.options.sessionId) return
          if (value.type !== 'HEARTBEAT_ACK' && value.type !== 'SESSION_CLOSED' && value.sessionId !== this.options.sessionId) return
          if (value.type === 'SESSION_CLOSED') { this.reconnect(); return }
          if (value.type !== 'CONNECTED' && !this.connected) return
          if (value.type === 'CONNECTED') {
            if (value.protocolVersion !== 1 || !sequence(value.lastEventSeq)
              || !sequence(value.resumeAfterSeq) || value.resumeAfterSeq !== afterSeq) { this.reconnect(); return }
            if (value.projectionVersion === 1) {
              if (typeof value.subscriptionId !== 'string' || !value.subscriptionId) { this.reconnect(); return }
              this.projection = new AssistantProjectionReducer(this.options.sessionId, value.subscriptionId, this.options.actorId)
            } else if (value.projectionVersion != null && value.projectionVersion !== 0) { this.reconnect(); return }
            this.connected = true; this.connecting = false; this.attempts = 0
            this.handshakeAt = Date.now()
            this.acknowledgedAt = Date.now()
            if (!this.projection) this.options.connection(true)
          } else if (value.type === 'HEARTBEAT_ACK') {
            if (typeof value.nonce === 'string' && value.nonce === this.heartbeatNonce) this.acknowledgedAt = Date.now()
          } else if (value.type === 'SNAPSHOT_REQUIRED') {
            this.clearTimers(); this.disconnect(); this.connecting = true
            void this.options.recover().finally(() => {
              if (generation === this.generation && !this.stopped) { this.connecting = false; void this.connect() }
            })
          } else if (value.type === 'SESSION_PROJECTION') {
            if (!this.projection) { this.reconnect(); return }
            const snapshot = this.projection.apply(value)
            if (snapshot) {
              this.options.projection(snapshot)
              this.delivered = snapshot.lastEventSeq
              if (!this.projectionReady) { this.projectionReady = true; this.options.connection(true) }
            }
          } else if (value.type === 'RUNTIME_EVENT') {
            if (this.projection) { this.reconnect(); return }
            const seq = value.seq; const event = value.event as AssistantEvent | undefined
            if (!sequence(seq) || !event || event.seq !== seq || String(event.sessionId) !== this.options.sessionId
              || typeof event.type !== 'string' || !event.eventId) { this.reconnect(); return }
            if (seq <= Math.max(this.delivered, this.options.cursor())) return
            if (seq !== Math.max(this.delivered, this.options.cursor()) + 1) {
              this.clearTimers(); this.disconnect(); this.connecting = true
              void this.options.recover().finally(() => {
                if (generation === this.generation && !this.stopped) { this.connecting = false; void this.connect() }
              }); return
            }
            this.delivered = seq; this.options.event(event)
          } else if ((value.type === 'TEXT_DELTA' || value.type === 'REASONING_DELTA') && value.transient === true
            && typeof value.runId === 'string' && typeof value.streamId === 'string'
            && typeof value.content === 'string' && value.content.length <= 65536) {
            if (value.type === 'REASONING_DELTA' && !['PUBLIC_SUMMARY', 'PROVIDER_REASONING'].includes(String(value.reasoningKind))) return
            this.options.delta(value as unknown as AssistantRealtimeDelta)
          } else if (value.type === 'EXECUTION_PROGRESS' && value.transient === true && typeof value.runId === 'string') {
            this.options.progress(value as unknown as AssistantRealtimeProgress)
          } else if (value.type === 'STREAM_ENDED' && value.transient === true && typeof value.runId === 'string'
            && typeof value.streamId === 'string' && ['REASONING', 'TEXT'].includes(String(value.streamType))
            && ['COMPLETED', 'FAILED', 'STOPPED'].includes(String(value.status))) {
            this.options.ended(value as unknown as AssistantRealtimeStreamEnded)
          }
        } catch { this.reconnect() }
      }
      socket.onclose = () => { if (generation === this.generation) this.reconnect() }
      socket.onerror = () => { if (generation === this.generation) this.reconnect() }
    } catch {
      if (generation === this.generation) this.reconnect()
    } finally { if (generation === this.generation && !this.socket) this.connecting = false }
  }
}
