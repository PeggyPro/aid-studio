import type { NotificationPage, NotificationState, NotificationTicket, StudioNotificationDetail } from '@/types/notification'
import { canvasApiPost } from '@/utils/canvas/canvasApiClient'
import { unwrap } from './shared'

const ROOT = '/api/user/notification'

function notificationPost<T>(path: string, data: unknown = {}) {
  return canvasApiPost<T>(`${ROOT}/${path}`, data, { keepSessionOnUnauthorized: true, requestTimeoutMs: 12_000 })
}

export const studioNotificationApi = {
  list: (pageNum: number, isRead?: boolean): Promise<NotificationPage> => notificationPost('list', {
    pageNum, pageSize: 20, ...(isRead === undefined ? {} : { isRead })
  }).then(result => result as unknown as NotificationPage),
  detail: (id: string) => notificationPost<StudioNotificationDetail>('detail', { id }).then(unwrap),
  unread: () => notificationPost<NotificationState>('unread', {}).then(unwrap),
  read: (id: string) => notificationPost<NotificationState>('read', { id }).then(unwrap),
  readAll: () => notificationPost<NotificationState>('read-all', {}).then(unwrap),
  ticket: (knownStateVersion: string) => notificationPost<NotificationTicket>('ticket', {
    ...(knownStateVersion ? { knownStateVersion } : {})
  }).then(unwrap)
}
