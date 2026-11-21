import { Vector3 } from 'three'
import type { DirectorCamera, DirectorPathClip, DirectorTransformTuple as Vec } from '@/types/studio'
import { studioId } from './studioGraph'

export type DirectorCameraMovePreset = NonNullable<DirectorPathClip['preset']>

function tuple(vector: Vector3): Vec {
  return vector.toArray().map((value) => Number(value.toFixed(6))) as Vec
}

export function createDirectorPresetClip({
  cameraId,
  camera,
  preset,
  start,
  duration,
}: {
  cameraId: string
  camera: Pick<DirectorCamera, 'position' | 'target' | 'fov' | 'roll'>
  preset: DirectorCameraMovePreset
  start: number
  duration: number
  source?: 'preset'
}): DirectorPathClip {
  const safeDuration = Math.max(0.2, Number.isFinite(duration) ? duration : 0.2)
  const safeStart = Math.max(0, Number.isFinite(start) ? start : 0)
  const count = Math.max(2, Math.round(safeDuration * 30) + 1)
  const position = new Vector3().fromArray(camera.position)
  const target = new Vector3().fromArray(camera.target)
  const offset = position.clone().sub(target)
  const distance = Math.max(0.0001, offset.length())
  const horizontalOffset = new Vector3(offset.x, 0, offset.z)
  const forward = target.clone().sub(position)
  const right = new Vector3(forward.x, 0, forward.z)
  if (right.lengthSq() < 0.000001) right.set(0, 0, -1)
  right.normalize().cross(new Vector3(0, 1, 0)).normalize()

  const waypoints = Array.from({ length: count }, (_, index) => {
    const progress = index / (count - 1)
    const nextPosition = position.clone()
    const nextTarget = target.clone()

    if (preset === 'orbit' || preset === 'half-orbit' || preset === 'spiral') {
      const radians = progress * Math.PI * (preset === 'half-orbit' ? 1 : 2)
      const rotated = horizontalOffset.clone().applyAxisAngle(new Vector3(0, 1, 0), radians)
      nextPosition.set(target.x + rotated.x, position.y, target.z + rotated.z)
      if (preset === 'spiral') nextPosition.y += progress * 2
    } else if (preset === 'dolly-in' || preset === 'dolly-out') {
      const endDistance = preset === 'dolly-in' ? Math.max(0.15, distance * 0.4) : distance * 2
      nextPosition.copy(target).add(offset.clone().setLength(distance + (endDistance - distance) * progress))
    } else if (preset === 'crane') {
      nextPosition.y += progress * 2
      nextTarget.y += progress * 2
    } else if (preset === 'truck') {
      const translation = right.clone().multiplyScalar(progress * 3)
      nextPosition.add(translation)
      nextTarget.add(translation)
    }

    if (preset === 'orbit' && index === count - 1) nextPosition.copy(position)
    return {
      id: studioId('waypoint'),
      time: safeStart + progress * safeDuration,
      position: tuple(nextPosition),
      lookAt: tuple(nextTarget),
      fov: camera.fov,
    }
  })

  return {
    id: studioId('path'),
    kind: 'path',
    targetKind: 'camera',
    targetId: cameraId,
    start: safeStart,
    end: safeStart + safeDuration,
    waypoints,
    grounded: false,
    source: 'preset',
    preset,
    createdAt: new Date().toISOString(),
  }
}
