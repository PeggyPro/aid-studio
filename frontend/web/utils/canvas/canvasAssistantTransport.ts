import { canvasCollaborationWebsocketUrl } from './canvasCollaborationSocket'
import type { AssistantTransport } from '@/utils/business/assistant'
import { canvasApiAuthHeaders, canvasApiBaseUrl, canvasApiPost, hasCanvasGatewaySession } from './canvasApiClient'

let previousAuthorization = ''
let authGeneration = 0

/** Opaque in-memory identity generation; no token is written into cache/storage keys. */
export const canvasAssistantTransport: AssistantTransport = {
  kind: 'CANVAS',
  websocketUrl: canvasCollaborationWebsocketUrl,
  storageNamespace: () => { try { return canvasApiBaseUrl() } catch { return 'unconfigured' } },
  identityKey: () => {
    const mainToken = typeof window === 'undefined' ? '' : localStorage.getItem('token') || ''
    const authorization = hasCanvasGatewaySession(mainToken) ? canvasApiAuthHeaders().Authorization || '' : ''
    if (authorization !== previousAuthorization) { previousAuthorization = authorization; authGeneration += 1 }
    let base = ''
    try { base = canvasApiBaseUrl() } catch { base = 'unconfigured' }
    return 'canvas:' + base + ':' + authGeneration
  },
  post: (path, body) => canvasApiPost(path, body, { requestTimeoutMs: 30000 })
}
