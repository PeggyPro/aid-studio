export const DIRECTOR_LIVE_PREVIEW_FPS = 30

/** Keep the display-clock remainder so a slightly early RAF does not skip a full frame. */
export function createDirectorPreviewFrameClock(fps = DIRECTOR_LIVE_PREVIEW_FPS) {
  const period = 1000 / fps
  let nextFrame: number | undefined
  return (now: number) => {
    const deadline = nextFrame ?? now
    if (now + 0.75 < deadline) return false
    nextFrame = deadline + Math.max(1, Math.floor((now - deadline) / period) + 1) * period
    return true
  }
}
