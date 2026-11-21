export interface StudioNotification {
  id: string
  messageType: 'SYSTEM'
  title: string
  summary: string
  publishTime: string
  isRead: boolean
  readTime: string | null
}

export interface StudioNotificationDetail extends StudioNotification {
  content: string
  contentFormat: 'PLAIN_TEXT' | 'MARKDOWN'
}

export interface NotificationState {
  unreadCount: number
  stateVersion: string
}

export interface NotificationPage {
  data: StudioNotification[]
  total: number
  pageNum: number
  pageSize: number
  stateVersion: string
}

export interface NotificationTicket {
  websocketPath: string
  protocols: string[]
  heartbeatSeconds: number
  idleTimeoutSeconds: number
  reconnectBaseMillis: number
  reconnectMaxMillis: number
}

export interface NotificationEvent extends Partial<NotificationState> {
  eventId: string
  eventType: 'STATE_SYNC' | 'NOTIFICATION_PUBLISHED' | 'NOTIFICATION_WITHDRAWN' | 'NOTIFICATION_STATE_CHANGED' | 'HEARTBEAT_ACK' | 'SESSION_CLOSED'
  message?: { id?: string; nonce?: string } | null
}
