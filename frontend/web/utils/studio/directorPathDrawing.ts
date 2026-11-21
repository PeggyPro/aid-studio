import { Plane, Vector3 } from 'three'
import type { DirectorCamera, DirectorPathDrawPlane, DirectorSceneData, DirectorTransformTuple } from '@/types/studio'
import { applyDirectorTimelineAt } from './directorTimeline'

export const DIRECTOR_PATH_PLANES = [
  { value: 'xz', label: 'XZ · 水平移动' },
  { value: 'xy', label: 'XY · 正视升降' },
  { value: 'yz', label: 'YZ · 侧视升降' },
] satisfies Array<{ value: DirectorPathDrawPlane; label: string }>

export function normalizeDirectorPathPlane(value: unknown): DirectorPathDrawPlane {
  return value === 'xy' || value === 'yz' ? value : 'xz'
}

/** Resolve once at the playhead: a raised actor must not snap back to y=0. */
export function directorPathDrawingTarget(scene: DirectorSceneData, id: string | null, time: number) {
  const object = scene.objects.find((item) => item.id === id)
  const bookmark = scene.cameraBookmarks?.find((camera) => camera.id === id)
  if (!object && !bookmark && id !== 'main-camera') throw new Error('请先选中一个人物、物件或机位')
  if (object?.locked || bookmark?.locked || (id === 'main-camera' && scene.mainCameraLocked))
    throw new Error('请先解锁后再绘制轨道')
  const evaluated = applyDirectorTimelineAt(scene, time)
  const camera = id === 'main-camera' ? evaluated.camera : evaluated.cameraBookmarks?.find((item) => item.id === id)?.camera
  const position = camera?.position ?? evaluated.objects.find((item) => item.id === id)!.transform.position
  return { position: [...position] as DirectorTransformTuple, camera, time }
}

/** Drawing planes pass through the selected entity, not the world origin. */
export function directorPathDrawingPlane(plane: DirectorPathDrawPlane, origin: DirectorTransformTuple) {
  const normal = plane === 'xy' ? new Vector3(0, 0, 1) : plane === 'yz' ? new Vector3(1, 0, 0) : new Vector3(0, 1, 0)
  return new Plane().setFromNormalAndCoplanarPoint(normal, new Vector3(...origin))
}

export function directorPathDrawingView(plane: DirectorPathDrawPlane, origin: DirectorTransformTuple): DirectorCamera {
  const offset = plane === 'xy' ? [0, 0, 16] : plane === 'yz' ? [16, 0, 0] : [0, 20, 0.01]
  return { position: origin.map((value, i) => value + offset[i]) as DirectorTransformTuple, target: [...origin], fov: 48 }
}
