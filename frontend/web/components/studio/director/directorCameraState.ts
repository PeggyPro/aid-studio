import type { DirectorCamera, DirectorLensSpec, DirectorSceneData } from '@/types/studio'
import { normalizeDirectorLensSpec } from '@/utils/studio/directorCameraCatalog'

export interface DirectorCameraEntityState {
  id: string
  camera: DirectorCamera
  lens: DirectorLensSpec
  visible: boolean
  locked: boolean
}

export function listDirectorAuthoredCameras(scene: DirectorSceneData) {
  return [
    ...(scene.mainCameraPresent === false ? [] : [{
      id: 'main-camera',
      name: '主机位',
      camera: scene.camera,
      lens: scene.mainCameraLens,
      visible: scene.mainCameraVisible,
      locked: scene.mainCameraLocked,
    }]),
    ...(scene.cameraBookmarks ?? []),
  ]
}

export function resolveDirectorActiveCameraId(scene: DirectorSceneData): string {
  const activeId = scene.activeCameraId ?? 'main-camera'
  if (resolveDirectorCamera(scene, activeId)) return activeId
  if (scene.mainCameraPresent !== false) return 'main-camera'
  return scene.cameraBookmarks?.[0]?.id ?? ''
}

export function resolveDirectorActiveCamera(scene: DirectorSceneData): DirectorCamera {
  return resolveDirectorCamera(scene, resolveDirectorActiveCameraId(scene)) ?? scene.directorCamera ?? scene.camera
}

export function resolveDirectorCamera(scene: DirectorSceneData, cameraId: string): DirectorCamera | null {
  if (cameraId === 'main-camera') return scene.mainCameraPresent === false ? null : scene.camera
  return scene.cameraBookmarks?.find((item) => item.id === cameraId)?.camera ?? null
}

export function resolveDirectorMonitorCameraId(scene: DirectorSceneData, selectedCameraId: string | null): string {
  return selectedCameraId && resolveDirectorCamera(scene, selectedCameraId)
    ? selectedCameraId
    : resolveDirectorActiveCameraId(scene)
}

export function isDirectorCameraLocked(scene: DirectorSceneData, cameraId: string): boolean {
  return cameraId === 'main-camera'
    ? scene.mainCameraLocked === true
    : scene.cameraBookmarks?.find((item) => item.id === cameraId)?.locked === true
}

export function isDirectorActiveCameraLocked(scene: DirectorSceneData): boolean {
  return isDirectorCameraLocked(scene, resolveDirectorActiveCameraId(scene))
}

export function shouldEnableDirectorOrbitControls(
  scene: DirectorSceneData,
  viewMode: 'director' | 'camera'
): boolean {
  return viewMode === 'director' || !isDirectorActiveCameraLocked(scene)
}

export function activateDirectorCamera(scene: DirectorSceneData, cameraId: string): DirectorSceneData {
  if (!resolveDirectorCamera(scene, cameraId)) return scene
  return { ...scene, activeCameraId: cameraId }
}

export function updateDirectorCameraEntity(
  scene: DirectorSceneData,
  cameraId: string,
  camera: DirectorCamera
): DirectorSceneData {
  if (cameraId === 'main-camera') return { ...scene, camera }
  if (!scene.cameraBookmarks?.some((item) => item.id === cameraId)) return scene
  return {
    ...scene,
    cameraBookmarks: scene.cameraBookmarks.map((item) => item.id === cameraId ? { ...item, camera } : item)
  }
}

export function resolveDirectorCameraLens(scene: DirectorSceneData, cameraId: string): DirectorLensSpec {
  const lens = cameraId === 'main-camera'
    ? scene.mainCameraLens
    : scene.cameraBookmarks?.find((item) => item.id === cameraId)?.lens
  return normalizeDirectorLensSpec(lens)
}

export function updateDirectorCameraLens(
  scene: DirectorSceneData,
  cameraId: string,
  lens: DirectorLensSpec,
): DirectorSceneData {
  const normalized = normalizeDirectorLensSpec(lens)
  if (cameraId === 'main-camera') return { ...scene, mainCameraLens: normalized }
  if (!scene.cameraBookmarks?.some((item) => item.id === cameraId)) return scene
  return {
    ...scene,
    cameraBookmarks: scene.cameraBookmarks.map((item) => item.id === cameraId ? { ...item, lens: normalized } : item),
  }
}

export function resolveDirectorCameraAimObjectId(scene: DirectorSceneData, cameraId: string): string | undefined {
  return cameraId === 'main-camera'
    ? scene.mainCameraAimObjectId
    : scene.cameraBookmarks?.find((item) => item.id === cameraId)?.aimObjectId
}

export function updateDirectorCameraAimObjectId(
  scene: DirectorSceneData,
  cameraId: string,
  aimObjectId?: string,
): DirectorSceneData {
  if (cameraId === 'main-camera') return { ...scene, mainCameraAimObjectId: aimObjectId }
  if (!scene.cameraBookmarks?.some((item) => item.id === cameraId)) return scene
  return {
    ...scene,
    cameraBookmarks: scene.cameraBookmarks.map((item) => item.id === cameraId ? { ...item, aimObjectId } : item),
  }
}

/** Legacy workspaces may have copied an active bookmark into scene.camera. Render one rig per unique view. */
export function listDirectorCameraEntities(scene: DirectorSceneData): DirectorCameraEntityState[] {
  const entities = listDirectorAuthoredCameras(scene).map((camera) => ({
    id: camera.id,
    camera: camera.camera,
    lens: resolveDirectorCameraLens(scene, camera.id),
    visible: camera.visible !== false,
    locked: camera.locked === true,
  }))
  const activeId = resolveDirectorActiveCameraId(scene)
  const ordered = [...entities].sort((left, right) => Number(right.id === activeId) - Number(left.id === activeId))
  const seen = new Set<string>()
  return ordered.filter((entity) => {
    if (!entity.visible) return false
    const signature = JSON.stringify({ camera: entity.camera, lens: entity.lens })
    if (seen.has(signature)) return false
    seen.add(signature)
    return true
  })
}
