'use client'

import { createContext, createElement, useContext, useEffect, useMemo, useSyncExternalStore, type ReactNode } from 'react'
import { StudioNotificationModal } from '@/components/studio/shell/StudioNotificationModal'
import { studioNotificationApi } from '@/utils/business/notification'
import { StudioNotificationClient } from '@/utils/studio/studioNotificationClient'
import { useCanvasGatewayIdentity } from './useCanvasGatewayIdentity'
import { canvasQuoteIdentity } from '@/utils/canvas/canvasQuote'
import { canvasCollaborationWebsocketUrl } from '@/utils/canvas/canvasCollaborationSocket'
import { resolveCanvasApiUrl } from '@/utils/canvas/canvasApiClient'

const NotificationContext = createContext<{ client: StudioNotificationClient; connected: boolean } | null>(null)

export function StudioNotificationProvider({ children }: { children: ReactNode }) {
  const { identity, connected } = useCanvasGatewayIdentity()
  const client = useMemo(() => new StudioNotificationClient(studioNotificationApi,
    () => connected && canvasQuoteIdentity() === identity, ticket => {
      if (ticket.websocketPath !== '/ws/notification') throw new Error('通知通道地址无效')
      const url = process.env.NEXT_PUBLIC_CANVAS_API_BASE_URL
        ? new URL(resolveCanvasApiUrl(ticket.websocketPath), location.href) : new URL(canvasCollaborationWebsocketUrl(ticket.websocketPath))
      url.protocol = url.protocol === 'https:' || url.protocol === 'wss:' ? 'wss:' : 'ws:'
      return new WebSocket(url.toString(), ticket.protocols)
    }), [identity, connected])
  useEffect(() => {
    // Strict Mode's probe cannot consume a one-use ticket or duplicate bootstrap reads.
    const timer = setTimeout(() => { if (connected) client.start() }, 0)
    const visible = () => { if (connected && document.visibilityState === 'visible') client.retry() }
    document.addEventListener('visibilitychange', visible)
    return () => { clearTimeout(timer); client.stop(); document.removeEventListener('visibilitychange', visible) }
  }, [client, connected])
  const value = useMemo(() => ({ client, connected }), [client, connected])
  return createElement(NotificationContext.Provider, { value }, children, createElement(NotificationHost, { client }))
}

function NotificationHost({ client }: { client: StudioNotificationClient }) {
  const state = useSyncExternalStore(client.subscribe, client.getSnapshot, client.getSnapshot)
  return createElement(StudioNotificationModal, { client, state })
}

export function useStudioNotifications() {
  const context = useContext(NotificationContext)
  if (!context) throw new Error('StudioNotificationProvider is missing')
  return context
}

export function useUnreadStudioNotifications() {
  const { client } = useStudioNotifications()
  return useSyncExternalStore(client.subscribe, () => client.getSnapshot().unreadCount, () => 0)
}
