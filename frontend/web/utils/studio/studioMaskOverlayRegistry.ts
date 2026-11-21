import type { StudioImageMaskOverlayHandle } from '@/components/studio/canvas/StudioImageMaskOverlay'

const handles = new Map<string, StudioImageMaskOverlayHandle>()
const listeners = new Set<() => void>()

function emit() {
  listeners.forEach((listener) => listener())
}

export function registerStudioMaskOverlay(nodeId: string, handle: StudioImageMaskOverlayHandle | null) {
  if (handle) handles.set(nodeId, handle)
  else handles.delete(nodeId)
  emit()
}

export function notifyStudioMaskOverlay() {
  emit()
}

export function getStudioMaskOverlay(nodeId: string): StudioImageMaskOverlayHandle | null {
  return handles.get(nodeId) ?? null
}

export function subscribeStudioMaskOverlay(listener: () => void): () => void {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}
