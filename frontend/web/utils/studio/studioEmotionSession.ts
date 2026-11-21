import { studioId } from './studioGraph'
import type { StudioEmotionFace, StudioEmotionFaceRect, StudioEmotionSession } from '@/types/studio'
import { emotionGridPoint } from './studioEmotionGrid'

export function normalizeEmotionRect(rect: StudioEmotionFaceRect): StudioEmotionFaceRect | null {
  if (![rect.x, rect.y, rect.w, rect.h].every(Number.isFinite)) return null
  const x = Math.max(0, Math.min(1, Math.min(rect.x, rect.x + rect.w)))
  const y = Math.max(0, Math.min(1, Math.min(rect.y, rect.y + rect.h)))
  const right = Math.max(0, Math.min(1, Math.max(rect.x, rect.x + rect.w)))
  const bottom = Math.max(0, Math.min(1, Math.max(rect.y, rect.y + rect.h)))
  return right - x >= 0.015 && bottom - y >= 0.015 ? { x, y, w: right - x, h: bottom - y } : null
}
export function createEmotionSession(nodeId: string, sourceUrl: string): StudioEmotionSession {
  return {
    id: studioId('emotion'),
    nodeId,
    sourceUrl,
    phase: 'picking',
    selectedFaceId: null,
    manualMode: false,
    quality: '',
    count: 1,
    detectionStatus: 'pending',
    faces: []
  }
}
export function patchEmotionSession(
  current: StudioEmotionSession,
  patch: Partial<
    Pick<
      StudioEmotionSession,
      'faces' | 'selectedFaceId' | 'phase' | 'manualMode' | 'detectionStatus' | 'quality' | 'count'
    >
  >
): StudioEmotionSession {
  const faces = (patch.faces ?? current.faces)
    .flatMap((face) => {
      const rect = normalizeEmotionRect(face.rect)
      const { affinity, arousal } = emotionGridPoint(face.affinity, face.arousal)
      return rect ? [{ ...face, rect, affinity, arousal }] : []
    })
    .filter((face, index, all) => all.findIndex((item) => item.id === face.id) === index)
  const selectedFaceId = patch.selectedFaceId === undefined ? current.selectedFaceId : patch.selectedFaceId
  const selected = faces.some((face) => face.id === selectedFaceId)
  return {
    ...current,
    ...patch,
    faces,
    selectedFaceId: selected ? selectedFaceId : null,
    phase: selected ? (patch.phase ?? current.phase) : 'picking'
  }
}

// Lazy local inference: source images never leave the browser for face detection.
export async function detectEmotionFaces(
  sourceUrl: string,
  signal?: AbortSignal,
  onProgress?: (faces: StudioEmotionFace[]) => void
): Promise<StudioEmotionFace[] | null> {
  try {
    const { detectLocalEmotionFaces } = await import('./studioEmotionDetection')
    return await detectLocalEmotionFaces(sourceUrl, signal, onProgress)
  } catch {
    return null
  }
}
