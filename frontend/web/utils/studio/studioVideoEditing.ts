import type { StudioLayerSplitRect, StudioVideoClipRange } from '@/types/studio'
import { moveStudioVideoClipRange, normalizeStudioVideoClipRange } from './studioVideoClip'

export function editVideoRange(range: StudioVideoClipRange, mode: 'start' | 'end' | 'move', seconds: number, snap: boolean) {
  const next = snap ? Math.round(seconds) : seconds
  if (mode === 'move') return moveStudioVideoClipRange(range, next)
  const min = Math.min(0.5, range.durationSeconds)
  return normalizeStudioVideoClipRange({
    ...range,
    startSeconds: mode === 'start' ? Math.min(next, range.endSeconds - min) : range.startSeconds,
    endSeconds: mode === 'end' ? Math.max(next, range.startSeconds + min) : range.endSeconds
  })
}

/** H.264 输出偶数像素，边界始终位于源画面内。与裁剪框尺寸提示共用。 */
export function videoCropPixels(rect: StudioLayerSplitRect, width: number, height: number) {
  const even = (n: number) => Math.floor(n / 2) * 2
  const x = Math.max(0, Math.min(even(width) - 2, even(rect.x * width)))
  const y = Math.max(0, Math.min(even(height) - 2, even(rect.y * height)))
  return {
    x, y,
    width: Math.max(2, Math.min(even(width) - x, even(rect.w * width))),
    height: Math.max(2, Math.min(even(height) - y, even(rect.h * height)))
  }
}

export function videoEditorKeyRange(range: StudioVideoClipRange, key: string, step: number, snap: boolean) {
  if (key === 'ArrowLeft' || key === 'ArrowRight') {
    return editVideoRange(range, 'move', range.startSeconds + (key === 'ArrowLeft' ? -step : step), snap)
  }
  const delta = key === 'ArrowUp' ? step : -step
  const quantize = (n: number) => snap ? Math.round(n) : n
  const startSeconds = Math.max(0, quantize(range.startSeconds - delta / 2))
  const endSeconds = Math.min(range.durationSeconds, quantize(range.endSeconds + delta / 2))
  if (endSeconds - startSeconds < Math.min(0.5, range.durationSeconds)) return range
  return normalizeStudioVideoClipRange({ ...range, startSeconds, endSeconds })
}
