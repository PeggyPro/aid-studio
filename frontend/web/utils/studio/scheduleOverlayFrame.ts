type OverlayFrameRef = { current: number | null }

/**
 * A frame may be requested by an observer that is about to be replaced. Keep the
 * newest callback for each frame ref so an already queued animation frame can
 * never commit geometry calculated from an obsolete React render.
 */
const pendingOverlayUpdates = new WeakMap<OverlayFrameRef, () => void>()

export function scheduleOverlayFrame(
  frameRef: OverlayFrameRef,
  update: () => void
): void {
  pendingOverlayUpdates.set(frameRef, update)
  if (frameRef.current != null) return
  frameRef.current = globalThis.requestAnimationFrame(() => {
    frameRef.current = null
    const latestUpdate = pendingOverlayUpdates.get(frameRef)
    pendingOverlayUpdates.delete(frameRef)
    latestUpdate?.()
  })
}

export function cancelOverlayFrame(frameRef: OverlayFrameRef): void {
  if (frameRef.current != null) globalThis.cancelAnimationFrame(frameRef.current)
  frameRef.current = null
  pendingOverlayUpdates.delete(frameRef)
}
