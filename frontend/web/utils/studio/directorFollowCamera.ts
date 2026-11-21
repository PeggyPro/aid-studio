import { Vector3 } from 'three'
import type { DirectorSceneData, DirectorPathClip } from '@/types/studio'
import { createDirectorCameraBookmark } from './directorScene'
import { directorAnchor } from './directorFollowConstraints'
import { applyDirectorTimelineAt, normalizeDirectorTimeline } from './directorTimeline'
import { studioId } from './studioGraph'

export function createDirectorFollowCamera(scene: DirectorSceneData, actorId: string | null, start: number) {
  const timeline = normalizeDirectorTimeline(scene.timeline)
  const evaluated = applyDirectorTimelineAt(scene, start)
  const actor =
    evaluated.objects.find((o) => o.id === actorId && o.kind === 'actor') ??
    evaluated.objects.find((o) => o.kind === 'actor')
  if (!actor) throw new Error('请选择人物作为跟踪目标')
  const active = evaluated.cameraBookmarks?.find((b) => b.id === scene.activeCameraId)
  const camera = active?.camera ?? evaluated.camera
  const bookmark = createDirectorCameraBookmark(
    (scene.cameraBookmarks?.length ?? 0) + 1,
    '',
    camera,
    undefined,
    active?.lens ?? scene.mainCameraLens,
  )
  const offset = new Vector3(...camera.position)
    .sub(new Vector3(...directorAnchor(actor)))
    .applyAxisAngle(new Vector3(0, 1, 0), -actor.transform.rotation[1])
    .toArray()
  const end = Math.max(start + 0.2, Math.min(timeline.duration, start + 3))
  const point = { position: camera.position, lookAt: camera.target, fov: camera.fov }
  const clip: DirectorPathClip = {
    id: studioId('follow'),
    kind: 'follow_relative',
    targetKind: 'camera',
    targetId: bookmark.id,
    start,
    end,
    createdAt: new Date().toISOString(),
    waypoints: [
      { ...point, id: studioId('wp'), time: start },
      { ...point, id: studioId('wp'), time: end },
    ],
    follow: { targetObjectId: actor.id, anchor: 'chest', continuousLookAt: true, offset },
  }
  return {
    bookmark,
    scene: {
      ...scene,
      cameraBookmarks: [...(scene.cameraBookmarks ?? []), bookmark],
      activeCameraId: bookmark.id,
      timeline: {
        ...timeline,
        duration: Math.max(timeline.duration, end),
        pathClips: [...(timeline.pathClips ?? []), clip],
      },
    },
  }
}
