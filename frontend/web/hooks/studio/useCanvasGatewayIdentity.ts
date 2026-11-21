'use client'

import { useSyncExternalStore } from 'react'
import { useUserStore } from '@/stores/user'
import { CANVAS_AUTH_CHANGED_EVENT } from '@/utils/canvas/canvasApiClient'
import { canvasQuoteConnected, canvasQuoteIdentity, invalidateStaleCanvasQuotes } from '@/utils/canvas/canvasQuote'

/** 与助手共用既有网关订阅，不以主站 ID 代替 Canvas actor。 */
function subscribe(listener: () => void) {
  const changed = () => { invalidateStaleCanvasQuotes(); listener() }
  const stop = useUserStore.subscribe(changed)
  window.addEventListener(CANVAS_AUTH_CHANGED_EVENT, changed)
  window.addEventListener('storage', changed)
  return () => { stop(); window.removeEventListener(CANVAS_AUTH_CHANGED_EVENT, changed); window.removeEventListener('storage', changed) }
}

export function useCanvasGatewayIdentity() {
  const identity = useSyncExternalStore(subscribe, canvasQuoteIdentity, () => 'canvas:server')
  const connected = useSyncExternalStore(subscribe, canvasQuoteConnected, () => false)
  return { identity, connected }
}
