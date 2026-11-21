import * as THREE from 'three'
import type { DirectorCamera } from '@/types/studio'

const DIRECTOR_FOCUS_PADDING = 1.18
const DIRECTOR_FOCUS_MIN_DISTANCE = 1.25
const DIRECTOR_WORLD_UP = new THREE.Vector3(0, 1, 0)

interface DirectorFrontFocusOptions {
  bounds: THREE.Box3
  worldQuaternion: THREE.Quaternion
  fov: number
  aspect: number
}

/** Compute a centered full-body view from the object's local front (+Z). */
export function resolveDirectorFrontFocusCamera({
  bounds,
  worldQuaternion,
  fov,
  aspect
}: DirectorFrontFocusOptions): DirectorCamera | null {
  if (bounds.isEmpty()) return null

  const center = bounds.getCenter(new THREE.Vector3())
  const front = new THREE.Vector3(0, 0, 1).applyQuaternion(worldQuaternion)
  front.y = 0
  if (front.lengthSq() < 1e-6) front.set(0, 0, 1)
  front.normalize()

  const right = new THREE.Vector3().crossVectors(DIRECTOR_WORLD_UP, front)
  if (right.lengthSq() < 1e-6) right.set(1, 0, 0)
  right.normalize()

  let halfWidth = 0
  let halfHeight = 0
  let halfDepth = 0
  for (const x of [bounds.min.x, bounds.max.x]) {
    for (const y of [bounds.min.y, bounds.max.y]) {
      for (const z of [bounds.min.z, bounds.max.z]) {
        const offset = new THREE.Vector3(x, y, z).sub(center)
        halfWidth = Math.max(halfWidth, Math.abs(offset.dot(right)))
        halfHeight = Math.max(halfHeight, Math.abs(offset.dot(DIRECTOR_WORLD_UP)))
        halfDepth = Math.max(halfDepth, Math.abs(offset.dot(front)))
      }
    }
  }

  const safeFov = THREE.MathUtils.clamp(Number.isFinite(fov) ? fov : 50, 1, 179)
  const safeAspect = Math.max(Number.isFinite(aspect) ? aspect : 1, 0.1)
  const verticalFov = THREE.MathUtils.degToRad(safeFov)
  const horizontalFov = 2 * Math.atan(Math.tan(verticalFov / 2) * safeAspect)
  const fitDistance = Math.max(
    halfHeight / Math.max(Math.tan(verticalFov / 2), 1e-6),
    halfWidth / Math.max(Math.tan(horizontalFov / 2), 1e-6)
  )
  const distance = Math.max(
    DIRECTOR_FOCUS_MIN_DISTANCE,
    halfDepth + fitDistance * DIRECTOR_FOCUS_PADDING
  )
  const position = center.clone().addScaledVector(front, distance)

  return {
    position: position.toArray() as [number, number, number],
    target: center.toArray() as [number, number, number],
    fov: safeFov
  }
}

/** Frame the selected camera and its subject from behind the filming direction. */
export function resolveDirectorCameraRigFocusCamera({
  bounds,
  worldQuaternion,
  fov,
  aspect,
}: DirectorFrontFocusOptions): DirectorCamera | null {
  if (bounds.isEmpty()) return null

  const center = bounds.getCenter(new THREE.Vector3())
  const radius = Math.max(bounds.getBoundingSphere(new THREE.Sphere()).radius, 0.22)
  const safeFov = THREE.MathUtils.clamp(Number.isFinite(fov) ? fov : 50, 20, 80)
  const safeAspect = Math.max(Number.isFinite(aspect) ? aspect : 1, 0.1)
  const verticalFov = THREE.MathUtils.degToRad(safeFov)
  const horizontalFov = 2 * Math.atan(Math.tan(verticalFov / 2) * safeAspect)
  const limitingHalfAngle = Math.max(0.1, Math.min(verticalFov, horizontalFov) / 2)
  const distance = Math.max(2.4, radius * 1.32 / Math.sin(limitingHalfAngle))
  const viewDirection = new THREE.Vector3(0.62, 0.38, -1.55)
    .normalize()
    .applyQuaternion(worldQuaternion)

  return {
    position: center.clone().addScaledVector(viewDirection, distance).toArray() as [number, number, number],
    target: center.toArray() as [number, number, number],
    fov: safeFov,
  }
}
