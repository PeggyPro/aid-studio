import type { NotificationEvent, NotificationPage, NotificationState, NotificationTicket, StudioNotification, StudioNotificationDetail } from '@/types/notification'

/** Revisions are independent monotonic decimal integers, never JS Number or lexical order. */
export function compareNotificationVersion(next: string, current: string): 'newer' | 'equal' | 'older' | 'conflict' {
  if (typeof next !== 'string' || next.length > 64 || !/^\d+:\d+$/.test(next) || current && !/^\d+:\d+$/.test(current)) return 'conflict'
  if (!current) return 'newer'
  const [a, b] = next.split(':').map(BigInt), [x, y] = current.split(':').map(BigInt)
  if (a === x && b === y) return 'equal'
  return a >= x && b >= y ? 'newer' : a <= x && b <= y ? 'older' : 'conflict'
}

export interface StudioNotificationApi {
  list: (page: number, isRead?: boolean) => Promise<NotificationPage>
  detail: (id: string) => Promise<StudioNotificationDetail>
  unread: () => Promise<NotificationState>
  read: (id: string) => Promise<NotificationState>
  readAll: () => Promise<NotificationState>
  ticket: (version: string) => Promise<NotificationTicket>
}

export interface StudioNotificationView {
  open: boolean; unreadCount: number; stateVersion: string; rows: StudioNotification[]; total: number
  pageNum: number; pageSize: number; unreadOnly: boolean; loading: boolean; error: string
  selectedId: string; detail: StudioNotificationDetail | null; detailLoading: boolean; detailError: string
  readAllBusy: boolean; updated: boolean; connection: 'connecting' | 'online' | 'offline'
}

const initialView = (): StudioNotificationView => ({ open: false, unreadCount: 0, stateVersion: '', rows: [], total: 0,
  pageNum: 0, pageSize: 20, unreadOnly: false, loading: false, error: '', selectedId: '', detail: null,
  detailLoading: false, detailError: '', readAllBusy: false, updated: false, connection: 'connecting' })

function errorText(error: unknown): string { return error instanceof Error ? error.message : '通知读取失败，请重试' }
function forbidden(error: unknown): boolean { return [401, 403].includes(Number((error as { status?: unknown })?.status)) }
const LIST_REVISION_RETRY_DELAYS = [250, 750, 1500]

/** One account-scoped coordinator owns reads, writes, push, correction and reconnect. */
export class StudioNotificationClient {
  private view = initialView()
  private listeners = new Set<() => void>()
  private inflight = new Map<string, Promise<unknown>>()
  private cache = new Map<string, { at: number; value: unknown }>()
  private events = new Set<string>()
  private active = false
  private socket: WebSocket | null = null
  private opening = false
  private attempts = 0
  private heartbeat: ReturnType<typeof setInterval> | undefined
  private correction: ReturnType<typeof setInterval> | undefined
  private reconnect: ReturnType<typeof setTimeout> | undefined
  private refreshTimer: ReturnType<typeof setTimeout> | undefined
  private refreshPending = false
  private staleListRetries = 0
  private listedVersion = ''
  private lastFrame = 0
  private selection = 0
  private listGeneration = 0
  private retryPage = 1
  constructor(private api: StudioNotificationApi, private current: () => boolean,
    private socketFactory: (ticket: NotificationTicket) => WebSocket) {}
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  getSnapshot = () => this.view
  private alive() { return this.active && this.current() }
  private patch(patch: Partial<StudioNotificationView>) {
    if (!this.alive()) return
    this.view = { ...this.view, ...patch }; this.listeners.forEach(listener => listener())
  }
  private request<T>(key: string, run: () => Promise<T>, force = false, cache = true): Promise<T> {
    const pending = this.inflight.get(key)
    if (pending) return pending as Promise<T>
    const saved = this.cache.get(key)
    if (!force && cache && saved && Date.now() - saved.at < 500) return Promise.resolve(saved.value as T)
    const promise = Promise.resolve().then(() => {
      if (!this.alive()) throw new Error('通知账户已切换')
      return run()
    }).then(value => {
      if (!this.alive()) throw new Error('通知账户已切换')
      if (cache) {
        this.cache.set(key, { at: Date.now(), value })
        while (this.cache.size > 48) this.cache.delete(this.cache.keys().next().value!)
      }
      return value
    }).finally(() => { if (this.inflight.get(key) === promise) this.inflight.delete(key) })
    this.inflight.set(key, promise)
    return promise
  }
  private adopt(state: NotificationState, resync = true) {
    const order = compareNotificationVersion(state.stateVersion, this.view.stateVersion)
    if (order === 'conflict') { if (resync) this.scheduleState(); return false }
    if (order === 'older' || !Number.isSafeInteger(state.unreadCount) || state.unreadCount < 0) return false
    this.patch({ unreadCount: state.unreadCount, stateVersion: state.stateVersion })
    return order === 'newer'
  }
  private scheduleState() {
    // All competing hints share the same physical unread request.
    void this.refreshState(true).catch(() => undefined)
  }
  refreshState = async (force = true) => {
    try { const state = await this.request('unread', this.api.unread, force); if (this.adopt(state, false)) this.scheduleList() }
    catch (error) { if (forbidden(error)) this.disconnect(); throw error }
  }
  start = () => {
    if (this.active) return
    this.active = true
    this.scheduleState(); void this.connect()
    this.correction = setInterval(() => {
      if (typeof document === 'undefined' || document.visibilityState === 'visible') this.scheduleState()
    }, 60_000)
  }
  stop = () => {
    this.active = false; this.selection += 1; this.listGeneration += 1
    this.disconnect(); clearInterval(this.correction); this.resetListRefresh()
    this.cache.clear(); this.events.clear()
  }
  open = () => {
    if (this.view.open) return
    this.patch({ open: true })
    void this.loadList(1, this.view.unreadOnly, true)
    if (this.view.connection === 'offline' && !this.reconnect) this.retry()
  }

  close = () => {
    this.listGeneration += 1
    this.resetListRefresh()
    this.clearSelection()
    this.patch({ open: false, loading: false })
  }

  clearSelection = () => {
    this.selection += 1
    this.patch({ selectedId: '', detail: null, detailLoading: false, detailError: '' })
  }

  setUnreadOnly = (value: boolean) => {
    if (value === this.view.unreadOnly) return
    this.resetListRefresh()
    void this.loadList(1, value, true)
  }

  retryList = () => {
    this.resetListRefresh()
    void this.loadList(this.retryPage, this.view.unreadOnly, true)
  }

  loadMore = () => {
    const { open, loading, error, updated, pageNum, pageSize, total, unreadOnly } = this.view
    if (!open || loading || error || updated || !pageNum || pageNum * pageSize >= total) return
    void this.loadList(pageNum + 1, unreadOnly)
  }

  private async loadList(page: number, unreadOnly: boolean, force = false) {
    const generation = ++this.listGeneration
    const queryChanged = unreadOnly !== this.view.unreadOnly
    if (queryChanged) {
      this.clearSelection()
      this.listedVersion = ''
    }
    this.retryPage = page
    this.patch({
      unreadOnly, loading: true, error: '',
      ...(queryChanged ? { rows: [], total: 0, pageNum: 0 } : {})
    })
    const isCurrent = () => generation === this.listGeneration && this.view.open
    let restart = false
    try {
      const key = `list:${page}:${unreadOnly}`
      const data = await this.request(key, () => this.api.list(page, unreadOnly ? false : undefined), force)
      if (!isCurrent()) return
      const order = compareNotificationVersion(data.stateVersion, this.view.stateVersion)
      if (order === 'older' || order === 'conflict') {
        this.listedVersion = ''
        this.retryPage = 1
        this.refreshPending = false
        const delay = LIST_REVISION_RETRY_DELAYS[this.staleListRetries++]
        if (delay === undefined) {
          clearTimeout(this.refreshTimer)
          this.refreshTimer = undefined
          this.patch({ updated: true, error: '消息列表暂未同步，请重试' })
        } else {
          this.scheduleList(delay)
        }
      } else if (page > 1 && data.stateVersion !== this.listedVersion) {
        // Offset pages are only contiguous when they belong to the same server snapshot.
        restart = true
      } else {
        this.listedVersion = data.stateVersion
        this.staleListRetries = 0
        this.refreshPending = false
        const rows = new Map((page === 1 ? [] : this.view.rows).map(row => [row.id, row]))
        for (const row of data.data) rows.set(row.id, row)
        this.patch({ rows: Array.from(rows.values()), total: data.total, pageNum: page, pageSize: data.pageSize, updated: false })
      }
    } catch (error) {
      if (isCurrent()) this.patch({ error: errorText(error) })
      if (forbidden(error)) this.disconnect()
    } finally {
      if (isCurrent()) {
        this.patch({ loading: false })
        if (restart) void this.loadList(1, unreadOnly, true)
        else if (this.refreshPending) {
          this.refreshPending = false
          this.scheduleList()
        }
      }
    }
  }

  private resetListRefresh() {
    clearTimeout(this.refreshTimer)
    this.refreshTimer = undefined
    this.refreshPending = false
    this.staleListRetries = 0
  }

  private scheduleList(delay = 120) {
    this.patch({ updated: true })
    if (!this.view.open || this.refreshTimer) return
    this.refreshTimer = setTimeout(() => {
      this.refreshTimer = undefined
      if (this.listedVersion && ['newer', 'equal'].includes(compareNotificationVersion(this.listedVersion, this.view.stateVersion))) {
        this.patch({ updated: false })
        return
      }
      if (this.view.loading) { this.refreshPending = true; return }
      if (this.view.open) void this.loadList(1, this.view.unreadOnly, true)
    }, delay)
  }

  select = async (id: string) => {
    if (!this.view.open || (this.view.selectedId === id && !this.view.detailError)) return
    const selection = ++this.selection
    this.patch({ selectedId: id, detail: null, detailLoading: true, detailError: '' })
    try {
      const detail = await this.request(`detail:${id}`, () => this.api.detail(id), true)
      if (selection !== this.selection || !this.view.open) return
      if (detail.id !== id) throw new Error('通知正文与所选消息不一致')
      this.patch({ detail, detailLoading: false })
    } catch (error) {
      if (selection === this.selection) this.patch({ detailError: errorText(error), detailLoading: false })
    }
  }
  /** Called by the view after the matching body is committed, never by a detail fetch alone. */
  markDisplayed = async (id: string) => {
    if (!this.view.open || this.view.selectedId !== id || this.view.detail?.id !== id || this.view.detail.isRead) return
    try {
      const before = this.view.stateVersion
      const state = await this.request(`read:${id}`, () => this.api.read(id), true, false)
      this.adopt(state); this.cache.clear()
      this.patch({ rows: this.view.rows.map(row => row.id === id ? { ...row, isRead: true } : row),
        ...(this.view.detail?.id === id ? { detail: { ...this.view.detail, isRead: true } } : {}) })
      if (before.split(':')[0] !== state.stateVersion.split(':')[0]) this.scheduleList()
      else if (!this.view.unreadOnly) this.listedVersion = state.stateVersion
    } catch (error) { if (this.view.selectedId === id) this.patch({ detailError: errorText(error) }) }
  }
  readAll = async () => {
    if (this.view.readAllBusy) return
    this.patch({ readAllBusy: true, error: '' })
    try {
      const state = await this.request('read-all', this.api.readAll, true, false)
      this.adopt(state); this.cache.clear()
      // Concurrent publications can remain unread: reload authority instead of setting every row read.
      if (this.view.open) await this.loadList(1, this.view.unreadOnly, true)
    } catch (error) {
      this.patch({ error: `${errorText(error)}；已重新核对未读状态，可稍后手动重试` })
      this.scheduleState() // An unknown all-read result must never be replayed automatically.
    } finally { this.patch({ readAllBusy: false }) }
  }
  acceptEvent = (event: NotificationEvent) => {
    if (!this.alive()) return
    this.lastFrame = Date.now()
    if (event.eventType === 'HEARTBEAT_ACK') return
    if (event.eventType === 'SESSION_CLOSED') { this.disconnect(); return }
    if (this.events.has(event.eventId)) return
    this.events.add(event.eventId)
    if (this.events.size > 256) this.events.delete(this.events.values().next().value!)
    if (event.eventType === 'STATE_SYNC') { this.attempts = 0; this.patch({ connection: 'online' }) }
    const changed = event.stateVersion && event.unreadCount != null
      ? this.adopt({ stateVersion: event.stateVersion, unreadCount: event.unreadCount }) : false
    if (event.eventType === 'NOTIFICATION_WITHDRAWN' && event.message?.id) {
      const id = event.message.id
      if (id === this.view.selectedId) this.clearSelection()
      this.patch({ rows: this.view.rows.filter(row => row.id !== id) })
    }
    if (changed) this.scheduleList()
  }
  private disconnect() {
    clearInterval(this.heartbeat); clearTimeout(this.reconnect)
    this.reconnect = undefined
    const socket = this.socket; this.socket = null
    if (socket) { socket.onclose = null; socket.close() }
    this.patch({ connection: 'offline' })
  }
  retry = () => { this.attempts = 0; this.scheduleState(); void this.connect() }
  private async connect() {
    if (!this.alive() || this.socket || this.opening) return
    this.opening = true; this.patch({ connection: 'connecting' })
    let base = 1000, max = 30000
    try {
      const ticket = await this.request('ticket', () => this.api.ticket(this.view.stateVersion), true, false)
      if (!this.alive()) return
      base = Math.max(1000, ticket.reconnectBaseMillis); max = Math.max(base, Math.min(60_000, ticket.reconnectMaxMillis))
      const socket = this.socketFactory(ticket); this.socket = socket; this.lastFrame = Date.now()
      socket.onmessage = message => {
        if (this.socket !== socket || !this.alive()) return
        try { this.acceptEvent(JSON.parse(String(message.data)) as NotificationEvent) } catch { this.scheduleState() }
      }
      socket.onopen = () => {
        if (this.socket !== socket) return
        this.heartbeat = setInterval(() => {
          if (Date.now() - this.lastFrame > Math.max(10, ticket.idleTimeoutSeconds) * 1000) { socket.close(); return }
          if (socket.readyState === 1) socket.send(JSON.stringify({ eventType: 'HEARTBEAT', nonce: String(Date.now()) }))
        }, Math.max(1, ticket.heartbeatSeconds) * 1000)
      }
      socket.onclose = () => {
        if (this.socket !== socket) return
        this.socket = null; clearInterval(this.heartbeat); this.queueReconnect(base, max)
      }
    } catch (error) { if (forbidden(error)) this.disconnect(); else this.queueReconnect(base, max) }
    finally { this.opening = false }
  }
  private queueReconnect(base: number, max: number) {
    this.patch({ connection: 'offline' })
    if (!this.alive() || this.attempts >= 5 || this.reconnect) return
    const wait = Math.min(max, base * 2 ** this.attempts++) * (0.8 + Math.random() * 0.4)
    this.reconnect = setTimeout(() => { this.reconnect = undefined; void this.connect() }, wait)
  }
}
