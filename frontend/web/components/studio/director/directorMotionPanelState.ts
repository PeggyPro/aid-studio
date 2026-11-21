import type { DirectorPathClip, DirectorSceneData, DirectorViewportPrefs } from '@/types/studio'
import { insertDirectorCameraClip } from '@/utils/studio/directorPathClipOps'
import { createDirectorPresetClip, type DirectorCameraMovePreset } from '@/utils/studio/directorPathPresets'
import { normalizeDirectorTimeline } from '@/utils/studio/directorTimeline'
import { isDirectorCameraLocked, resolveDirectorCamera } from './directorCameraState'

export type DirectorPathDrawMode = Exclude<NonNullable<DirectorViewportPrefs['pathDrawMode']>, false | 'point'>

export function addDirectorPresetToScene(
  scene: DirectorSceneData,
  cameraId: string,
  preset: DirectorCameraMovePreset,
  mode: 'replace' | 'append',
  duration: number,
  start: number,
): DirectorSceneData {
  if (isDirectorCameraLocked(scene, cameraId)) throw new Error('请先解锁机位')
  const camera = resolveDirectorCamera(scene, cameraId)
  if (!camera) throw new Error('请先选中机位')
  const timeline = normalizeDirectorTimeline(scene.timeline)
  const clip = createDirectorPresetClip({ cameraId, camera, preset, duration, start })
  const pathClips = insertDirectorCameraClip(timeline.pathClips ?? [], clip, mode)
  return {
    ...scene,
    updatedAt: new Date().toISOString(),
    timeline: {
      ...timeline,
      duration: Math.max(timeline.duration, ...pathClips.map((item) => item.end)),
      pathClips,
    },
  }
}

export function startDirectorCameraPathDrawing(
  scene: DirectorSceneData,
  cameraId: string,
  mode: DirectorPathDrawMode,
): DirectorSceneData {
  if (!resolveDirectorCamera(scene, cameraId)) throw new Error('请先选中机位')
  if (isDirectorCameraLocked(scene, cameraId)) throw new Error('请先解锁后再绘制轨道')
  return {
    ...scene,
    viewMode: 'director',
    viewportPrefs: { ...scene.viewportPrefs!, pathDrawMode: mode },
  }
}

export function updateDirectorCameraClip(scene: DirectorSceneData, clip: DirectorPathClip): DirectorSceneData {
  const timeline = normalizeDirectorTimeline(scene.timeline)
  if (isDirectorCameraLocked(scene, clip.targetId)) throw new Error('请先解锁后再编辑轨道')
  return {
    ...scene,
    updatedAt: new Date().toISOString(),
    timeline: {
      ...timeline,
      duration: Math.max(timeline.duration, clip.end),
      pathClips: (timeline.pathClips ?? []).map((item) => item.id === clip.id ? clip : item),
    },
  }
}
