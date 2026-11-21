import type { DirectorCamera, DirectorCapture, DirectorSceneData } from '@/types/studio'
import { studioId } from '@/utils/studio/studioGraph'
import { listDirectorAuthoredCameras } from './directorCameraState'

export const DIRECTOR_BATCH_SHOT_LIMIT = 12

export interface DirectorShotTarget {
  id: string
  name: string
  camera: DirectorCamera
}

export function listDirectorBatchShotTargets(scene: DirectorSceneData): DirectorShotTarget[] {
  return listDirectorAuthoredCameras(scene).filter((camera) => camera.visible !== false)
    .map(({ id, name, camera }) => ({ id, name, camera: structuredClone(camera) }))
}

export function resolveDirectorBatchShotPlan(
  scene: DirectorSceneData,
  selectedIds?: string[] | null
): { targets: DirectorShotTarget[]; omitted: number } {
  const available = listDirectorBatchShotTargets(scene)
  const requested = (selectedIds ?? []).filter(Boolean)
  const matched = requested.length
    ? available.filter((item) => requested.includes(item.id))
    : available
  const targets = matched.slice(0, DIRECTOR_BATCH_SHOT_LIMIT)
  return { targets, omitted: Math.max(0, matched.length - targets.length) }
}

export function resolveDirectorBatchShotTargets(
  scene: DirectorSceneData,
  selectedIds?: string[] | null
): DirectorShotTarget[] {
  return resolveDirectorBatchShotPlan(scene, selectedIds).targets
}

export function resolveDirectorShotCanvasTitle(captureName: string): string {
  return `${captureName.replace(/ 构图 \d+$/, '')} 构图参考`
}

export function createDirectorShotCapture(target: DirectorShotTarget, mediaUrl: string, index: number): DirectorCapture {
  return {
    id: studioId('capture'),
    name: `${target.name} 构图 ${String(index).padStart(2, '0')}`,
    mediaUrl,
    camera: structuredClone(target.camera),
    createdAt: new Date().toISOString()
  }
}

export function appendDirectorShotCaptures(
  scene: DirectorSceneData,
  captures: DirectorCapture[]
): DirectorSceneData {
  if (!captures.length) return scene
  const nextCaptures = [...(scene.captures ?? []), ...captures]
  const previewImageUrl = captures.at(-1)?.mediaUrl ?? scene.previewImageUrl
  return {
    ...scene,
    captures: nextCaptures,
    previewImageUrl,
    updatedAt: new Date().toISOString()
  }
}
