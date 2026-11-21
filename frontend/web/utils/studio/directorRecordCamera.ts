import type { DirectorSceneData, DirectorPathClip } from '@/types/studio'
import { applyDirectorTimelineAt } from './directorTimeline'

export function resolveDirectorRecordCamera(scene: DirectorSceneData, time: number) {
  const evaluated = applyDirectorTimelineAt(scene, time)
  const cameraIds = new Set(['main-camera', ...(scene.cameraBookmarks ?? []).map((b) => b.id)])
  const clip = (scene.timeline?.pathClips ?? [])
    .filter((c) => c.targetKind === 'camera' && cameraIds.has(c.targetId) && time >= c.start && time <= c.end)
    .sort((a, b) => b.start - a.start || a.id.localeCompare(b.id))[0]
  const id = clip?.targetId ?? scene.activeCameraId ?? 'main-camera'
  return evaluated.cameraBookmarks?.find((b) => b.id === id)?.camera ?? evaluated.camera
}
export function bakeDirectorFollow(scene: DirectorSceneData, clip: DirectorPathClip): DirectorPathClip {
  const count = Math.ceil((clip.end - clip.start) * 30)
  if (count > 20000) throw new Error('运动关键帧过多，请缩短跟拍片段')
  const waypoints = Array.from({ length: count + 1 }, (_, i) => {
    const time = Math.min(clip.end, clip.start + i / 30)
    const evaluated = applyDirectorTimelineAt(scene, time)
    const camera = evaluated.cameraBookmarks?.find((b) => b.id === clip.targetId)?.camera ?? evaluated.camera
    return { id: `${clip.id}-bake-${i}`, time, position: camera.position, lookAt: camera.target, fov: camera.fov }
  })
  const { follow: _follow, ...path } = clip
  return { ...path, kind: 'path', grounded: false, waypoints }
}
