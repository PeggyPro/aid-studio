import type { DirectorCamera, DirectorObject } from '@/types/studio'
import { directorActorHeight } from './directorSceneUnits'

const RAD_TO_DEG = 180 / Math.PI
const DEG_TO_RAD = Math.PI / 180

export const DEFAULT_DIRECTOR_OBSERVER_CAMERA: DirectorCamera = {
  position: [6.4, 2.85, 8.2],
  target: [0, 1.05, 1.9],
  fov: 50,
}

export interface DirectorCameraOrientation {
  yaw: number
  pitch: number
  roll: number
  distance: number
}

export function resolveDirectorCameraOrientation(camera: DirectorCamera): DirectorCameraOrientation {
  const dx = camera.target[0] - camera.position[0]
  const dy = camera.target[1] - camera.position[1]
  const dz = camera.target[2] - camera.position[2]
  const distance = Math.max(0.1, Math.hypot(dx, dy, dz))
  const horizontal = Math.max(0.000001, Math.hypot(dx, dz))
  return {
    yaw: roundAngle(Math.atan2(dx, -dz) * RAD_TO_DEG),
    pitch: roundAngle(Math.atan2(dy, horizontal) * RAD_TO_DEG),
    roll: roundAngle(camera.roll ?? 0),
    distance,
  }
}

export function updateDirectorCameraOrientation(
  camera: DirectorCamera,
  patch: Partial<Pick<DirectorCameraOrientation, 'yaw' | 'pitch' | 'roll'>>,
): DirectorCamera {
  const current = resolveDirectorCameraOrientation(camera)
  const yaw = (patch.yaw ?? current.yaw) * DEG_TO_RAD
  const pitch = Math.min(89, Math.max(-89, patch.pitch ?? current.pitch)) * DEG_TO_RAD
  const distance = current.distance
  const cosPitch = Math.cos(pitch)
  const target: DirectorCamera['target'] = [
    camera.position[0] + Math.sin(yaw) * cosPitch * distance,
    camera.position[1] + Math.sin(pitch) * distance,
    camera.position[2] - Math.cos(yaw) * cosPitch * distance,
  ].map((value) => Number(value.toFixed(6))) as DirectorCamera['target']
  return {
    ...camera,
    target,
    roll: roundAngle(patch.roll ?? current.roll),
  }
}

export function normalizeDirectorCamera(camera: DirectorCamera): DirectorCamera {
  const normalized: DirectorCamera = {
    ...camera,
    position: camera.position.map(roundPosition) as DirectorCamera['position'],
    target: camera.target.map(roundPosition) as DirectorCamera['target'],
    fov: Number(camera.fov.toFixed(4)),
  }
  if (camera.roll !== undefined) normalized.roll = roundAngle(camera.roll)
  return normalized
}

/** Keep the editor observer outside physical camera rigs when opening a scene. */
export function resolveDirectorObserverCamera(
  saved: DirectorCamera | undefined,
  filmingCameras: DirectorCamera[],
): DirectorCamera {
  const normalized = normalizeDirectorCamera(saved ?? DEFAULT_DIRECTOR_OBSERVER_CAMERA)
  const insideCameraRig = filmingCameras.some((camera) => cameraPositionDistance(normalized, camera) < 0.9)
  return insideCameraRig
    ? structuredClone(DEFAULT_DIRECTOR_OBSERVER_CAMERA)
    : normalized
}

export function resolveDirectorAimTarget(object: DirectorObject): DirectorCamera['target'] {
  const [x, y, z] = object.transform.position
  const height = object.kind === 'actor'
    ? directorActorHeight(object) * 0.85
    : Math.max(0.1, object.transform.scale[1]) * 0.5
  return [x, Number((y + height).toFixed(6)), z]
}

function roundAngle(value: number): number {
  return Number(value.toFixed(2))
}

function roundPosition(value: number): number {
  return Number(value.toFixed(6))
}

function cameraPositionDistance(left: DirectorCamera, right: DirectorCamera): number {
  return Math.hypot(
    left.position[0] - right.position[0],
    left.position[1] - right.position[1],
    left.position[2] - right.position[2],
  )
}
