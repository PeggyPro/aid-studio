import type { StudioEmotionFaceRect } from '@/types/studio'

export interface StudioEmotionFaceLandmark {
  x: number
  y: number
}

export interface StudioEmotionFaceCandidate {
  rect: StudioEmotionFaceRect
  landmarks: StudioEmotionFaceLandmark[]
  score: number
  regionId: number
  imageAspectRatio: number
}

const relativePoint = (point: StudioEmotionFaceLandmark, rect: StudioEmotionFaceRect) => ({
  x: (point.x - rect.x) / rect.w,
  y: (point.y - rect.y) / rect.h
})

/**
 * BlazeFace returns six ordered facial landmarks. Validate their topology to
 * reduce false positives from hands, jewellery and high-contrast fabric.
 */
export function isPlausibleEmotionFace(candidate: StudioEmotionFaceCandidate) {
  const { rect, landmarks } = candidate
  if (!Number.isFinite(candidate.imageAspectRatio) || candidate.imageAspectRatio <= 0) return false
  if (!Number.isFinite(candidate.score)) return false
  const pixelAspect = (rect.w / rect.h) * candidate.imageAspectRatio
  if (landmarks.length < 6 || pixelAspect < 0.4 || pixelAspect > 1.8) return false
  if (!landmarks.every((point) => Number.isFinite(point.x) && Number.isFinite(point.y))) return false

  const points = landmarks.map((point) => relativePoint(point, rect))
  if (!points.slice(0, 4).every((point) => point.x >= -0.25 && point.x <= 1.25
    && point.y >= -0.25 && point.y <= 1.25)) return false
  const [leftEye, rightEye] = points.slice(0, 2).sort((a, b) => a.x - b.x)
  const dx = (rightEye.x - leftEye.x) * pixelAspect
  const dy = rightEye.y - leftEye.y
  const eyeDistance = Math.hypot(dx, dy)
  if (eyeDistance < 0.08 || eyeDistance > 0.9 * Math.max(1, pixelAspect)) return false
  // Compare facial topology along the eye line, allowing head roll and profile faces.
  const project = (point: StudioEmotionFaceLandmark) => {
    const x = (point.x - (leftEye.x + rightEye.x) / 2) * pixelAspect
    const y = point.y - (leftEye.y + rightEye.y) / 2
    return { x: (x * dx + y * dy) / eyeDistance, y: (y * dx - x * dy) / eyeDistance }
  }
  const nose = project(points[2]), mouth = project(points[3])
  return Math.abs(nose.x) <= Math.max(0.35, eyeDistance)
    && nose.y >= -0.12 && nose.y <= 0.65
    && Math.abs(mouth.x) <= Math.max(0.4, eyeDistance)
    && mouth.y >= nose.y - 0.03 && mouth.y >= 0.08 && mouth.y <= 0.9
}

const overlap = (a: StudioEmotionFaceRect, b: StudioEmotionFaceRect) => {
  const intersection =
    Math.max(0, Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x)) *
    Math.max(0, Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y))
  return intersection / Math.min(a.w * a.h, b.w * b.h)
}

/** Keep a plausible model detection, or corroborate a weaker one across tiles. */
export function selectEmotionFaceRects(candidates: StudioEmotionFaceCandidate[]) {
  const clusters: StudioEmotionFaceCandidate[][] = []
  for (const candidate of candidates.filter(isPlausibleEmotionFace).sort((a, b) => b.score - a.score)) {
    const cluster = clusters.find((items) => items.some((item) => overlap(item.rect, candidate.rect) > 0.5))
    if (cluster) cluster.push(candidate)
    else clusters.push([candidate])
  }
  return clusters
    .filter((items) => {
      const regions = new Set(items.map((item) => item.regionId)).size
      return items[0].score >= 0.6 || (items[0].score >= 0.5 && regions >= 2)
    })
    .map((items) => items[0].rect)
}
