import type { CanvasPublicationSubmit, CanvasResource } from '@/types/canvas-api'
import { durableCanvasResource } from './canvasPublicationDraft'

export interface CanvasPublicationPendingSubmission {
  request: CanvasPublicationSubmit
  video: CanvasResource
  cover: CanvasResource
}

const memory = new Map<string, CanvasPublicationPendingSubmission>()
const keyFor = (ownerId: string, projectId: string) => `aid:canvas:publication:${ownerId}:${projectId}`

/** Persist the exact request before sending: a timeout/reload must replay the same body and operationId. */
export const canvasPublicationSubmission = {
  read(ownerId: string, projectId: string): CanvasPublicationPendingSubmission | null {
    const key = keyFor(ownerId, projectId)
    try {
      const raw = sessionStorage.getItem(key)
      if (raw) {
        const value = JSON.parse(raw) as CanvasPublicationPendingSubmission
        if (value.request?.projectId === projectId && value.request.operationId && value.video?.resourceId && value.cover?.resourceId) return value
      }
    } catch { /* storage may be unavailable; preserve in-memory recovery */ }
    return memory.get(key) || null
  },
  write(ownerId: string, value: CanvasPublicationPendingSubmission) {
    value = { ...value, video: durableCanvasResource(value.video), cover: durableCanvasResource(value.cover) }
    const key = keyFor(ownerId, value.request.projectId)
    memory.set(key, value)
    try { sessionStorage.setItem(key, JSON.stringify(value)) } catch { /* memory fallback */ }
  },
  clear(ownerId: string, projectId: string) {
    const key = keyFor(ownerId, projectId)
    memory.delete(key)
    try { sessionStorage.removeItem(key) } catch { /* memory fallback */ }
  }
}
