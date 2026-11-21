import type { StudioVideoClipRange } from '@/types/studio'

export const STUDIO_VIDEO_CLIP_MIN_SECONDS = 0.5

export function createStudioVideoClipRange(
  durationSeconds = 10,
  preferredSeconds?: number
): StudioVideoClipRange {
  const duration = Number.isFinite(durationSeconds) && durationSeconds > 0 ? durationSeconds : 10
  const span = Math.min(duration, Math.max(
    STUDIO_VIDEO_CLIP_MIN_SECONDS,
    preferredSeconds ?? duration
  ))
  return { startSeconds: 0, endSeconds: span, durationSeconds: duration }
}

export function normalizeStudioVideoClipRange(
  range: StudioVideoClipRange,
  durationSeconds = range.durationSeconds
): StudioVideoClipRange {
  const duration = Number.isFinite(durationSeconds) && durationSeconds > 0
    ? durationSeconds
    : Math.max(range.endSeconds, 1)
  const start = Math.min(duration, Math.max(0, Number(range.startSeconds) || 0))
  const end = Math.min(duration, Math.max(start, Number(range.endSeconds) || start))
  if (end - start >= Math.min(STUDIO_VIDEO_CLIP_MIN_SECONDS, duration)) {
    return { startSeconds: start, endSeconds: end, durationSeconds: duration }
  }
  const minimum = Math.min(STUDIO_VIDEO_CLIP_MIN_SECONDS, duration)
  const nextEnd = Math.min(duration, start + minimum)
  return {
    startSeconds: Math.max(0, nextEnd - minimum),
    endSeconds: nextEnd,
    durationSeconds: duration
  }
}

export function moveStudioVideoClipRange(
  range: StudioVideoClipRange,
  startSeconds: number
): StudioVideoClipRange {
  const normalized = normalizeStudioVideoClipRange(range)
  const span = normalized.endSeconds - normalized.startSeconds
  const start = Math.min(Math.max(0, startSeconds), Math.max(0, normalized.durationSeconds - span))
  return { ...normalized, startSeconds: start, endSeconds: start + span }
}

export function formatStudioVideoTime(seconds: number): string {
  const total = Math.max(0, Number.isFinite(seconds) ? seconds : 0)
  const minutes = Math.floor(total / 60)
  const wholeSeconds = Math.floor(total % 60)
  return `${String(minutes).padStart(2, '0')}:${String(wholeSeconds).padStart(2, '0')}`
}

export function formatStudioVideoClipRange(range: StudioVideoClipRange): string {
  return `${formatStudioVideoTime(range.startSeconds)}–${formatStudioVideoTime(range.endSeconds)}`
}
