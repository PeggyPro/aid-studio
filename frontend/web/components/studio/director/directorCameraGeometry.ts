import { Matrix4, Quaternion, Vector3 } from 'three'
import type { Object3D } from 'three'
import type { DirectorCamera, DirectorLensSpec } from '@/types/studio'
import {
  resolveDirectorCameraVisualProfile,
  type DirectorCameraVisualPart,
} from './directorCameraVisual'

export type DirectorCameraPoint = [number, number, number]
export type DirectorCameraLine = { part: DirectorCameraVisualPart; points: DirectorCameraPoint[] }

export const DIRECTOR_CAMERA_VISUAL_SCALE = 1
export const DIRECTOR_CAMERA_FRUSTUM_DEPTH = 1.8

const CAMERA_ASPECT_RATIO = 16 / 9
const CAMERA_WORLD_UP = new Vector3(0, 1, 0)
const CAMERA_LOCAL_FORWARD = new Vector3(0, 0, 1)
const DEFAULT_CAMERA: DirectorCamera = { position: [0, 0, 0], target: [0, 0, 1], fov: 50 }

export function getDirectorCameraQuaternion(camera: DirectorCamera): Quaternion {
  const origin = new Vector3(...camera.position)
  const direction = new Vector3(...camera.target).sub(origin)
  if (direction.lengthSq() === 0) return new Quaternion()
  const forward = direction.normalize()
  const up = Math.abs(forward.dot(CAMERA_WORLD_UP)) > 0.999 ? new Vector3(0, 0, 1) : CAMERA_WORLD_UP
  const rotation = new Quaternion().setFromRotationMatrix(new Matrix4().lookAt(origin, origin.clone().sub(forward), up))
  if (camera.roll) rotation.multiply(new Quaternion().setFromAxisAngle(CAMERA_LOCAL_FORWARD, camera.roll * Math.PI / 180))
  return rotation
}

export function getDirectorCameraRigPosition(camera: DirectorCamera): DirectorCameraPoint {
  return [...camera.position]
}

/** The rig pivots at the optical position, so changing the target rotates the model in place. */
export function applyDirectorCameraRigTransform(object: Object3D, camera: DirectorCamera): void {
  object.position.fromArray(getDirectorCameraRigPosition(camera))
  object.quaternion.copy(getDirectorCameraQuaternion(camera))
}

export function getDirectorCameraFromRig(
  rigPosition: DirectorCameraPoint,
  quaternion: Quaternion,
  targetDistance: number,
  fov: number
): DirectorCamera {
  const forward = new Vector3(0, 0, 1).applyQuaternion(quaternion).normalize()
  const viewPosition = new Vector3(...rigPosition)
  const target = viewPosition.clone().add(forward.multiplyScalar(Math.max(targetDistance, 0.1)))
  const base = getDirectorCameraQuaternion({ position: toTuple(viewPosition), target: toTuple(target), fov })
  const localRotation = base.invert().multiply(quaternion.clone())
  const roll = normalizeDegrees(2 * Math.atan2(localRotation.z, localRotation.w) * 180 / Math.PI)
  return { position: toTuple(viewPosition), target: toTuple(target), fov, roll }
}

export function getDirectorCameraWireframeLines(
  camera: DirectorCamera = DEFAULT_CAMERA,
  lens?: Partial<DirectorLensSpec>,
): DirectorCameraLine[] {
  const profile = resolveDirectorCameraVisualProfile(lens)
  const [bodyWidth, bodyHeight, bodyDepth] = profile.bodySize
  const bodyCenter: DirectorCameraPoint = [0, 0, profile.bodyCenterZ]
  const bodyFrontZ = profile.bodyCenterZ + bodyDepth / 2
  const lensTipZ = bodyFrontZ + profile.lensLength
  const focalBandZ = bodyFrontZ + profile.lensLength * 0.58
  const bodyDetails = createBodyDetailLines(profile.bodyDetail, profile.bodySize, profile.bodyCenterZ)

  return [
    ...withPart('body', createBoxLines(bodyCenter, profile.bodySize)),
    ...withPart('body', bodyDetails),
    ...withPart('lens', createLensBarrelLines(bodyFrontZ, lensTipZ, profile.lensRadiusX, profile.lensRadiusY)),
    ...withPart('focal', [
      createRingLine(focalBandZ, profile.lensRadiusX * 1.04, profile.lensRadiusY * 1.04),
      [[0, bodyHeight / 2 + 0.035, bodyFrontZ], [0, bodyHeight / 2 + 0.035, lensTipZ]],
    ]),
    ...withPart('aperture', [
      createRingLine(lensTipZ + 0.003, profile.apertureRadius, profile.apertureRadius),
      [[-profile.apertureRadius, 0, lensTipZ + 0.003], [profile.apertureRadius, 0, lensTipZ + 0.003]],
      [[0, -profile.apertureRadius, lensTipZ + 0.003], [0, profile.apertureRadius, lensTipZ + 0.003]],
    ]),
    ...withPart('reel', createAccessoryLines(profile.bodyDetail, bodyWidth, bodyHeight, profile.bodyCenterZ)),
    ...withPart('frustum', createFrustumLines(camera.fov, lensTipZ)),
    { part: 'target', points: [[0, 0, lensTipZ], [0, 0, DIRECTOR_CAMERA_FRUSTUM_DEPTH]] },
  ]
}

export function getDirectorCameraHitArea(
  camera: DirectorCamera = DEFAULT_CAMERA,
  lens?: Partial<DirectorLensSpec>,
): { size: DirectorCameraPoint; position: DirectorCameraPoint } {
  const points = getDirectorCameraWireframeLines(camera, lens)
    .filter((line) => !['frustum', 'target'].includes(line.part))
    .flatMap((line) => line.points)
  const xs = points.map((point) => point[0])
  const ys = points.map((point) => point[1])
  const zs = points.map((point) => point[2])
  const min = [Math.min(...xs), Math.min(...ys), Math.min(...zs)] as DirectorCameraPoint
  const max = [Math.max(...xs), Math.max(...ys), Math.max(...zs)] as DirectorCameraPoint
  return {
    size: [max[0] - min[0] + 0.12, max[1] - min[1] + 0.12, max[2] - min[2] + 0.12],
    position: [(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2]
  }
}

function createFrustumLines(fov: number, lensTipZ: number): DirectorCameraPoint[][] {
  const depth = DIRECTOR_CAMERA_FRUSTUM_DEPTH
  const safeFov = Math.max(8, Math.min(120, Number.isFinite(fov) ? fov : 50))
  const halfHeight = Math.tan((safeFov * Math.PI / 180) / 2) * (depth - lensTipZ)
  const halfWidth = halfHeight * CAMERA_ASPECT_RATIO
  const origin: DirectorCameraPoint = [0, 0, lensTipZ]
  const topLeft: DirectorCameraPoint = [-halfWidth, halfHeight, depth]
  const topRight: DirectorCameraPoint = [halfWidth, halfHeight, depth]
  const bottomRight: DirectorCameraPoint = [halfWidth, -halfHeight, depth]
  const bottomLeft: DirectorCameraPoint = [-halfWidth, -halfHeight, depth]
  return [
    [origin, topLeft], [origin, topRight], [origin, bottomRight], [origin, bottomLeft],
    [topLeft, topRight], [topRight, bottomRight], [bottomRight, bottomLeft], [bottomLeft, topLeft]
  ]
}

function createBodyDetailLines(
  detail: 'reels' | 'monitor' | 'handle',
  size: DirectorCameraPoint,
  centerZ: number,
): DirectorCameraPoint[][] {
  const [width, height, depth] = size
  if (detail === 'monitor') {
    return createBoxLines([0, height * 0.72, centerZ - depth * 0.06], [width * 0.86, height * 0.24, depth * 0.42])
  }
  if (detail === 'handle') {
    const y = height / 2 + 0.08
    return [[[-width * 0.32, y - 0.05, centerZ - depth * 0.24], [-width * 0.32, y, centerZ + depth * 0.2], [width * 0.32, y, centerZ + depth * 0.2], [width * 0.32, y - 0.05, centerZ - depth * 0.24]]]
  }
  return []
}

function createAccessoryLines(
  detail: 'reels' | 'monitor' | 'handle',
  bodyWidth: number,
  bodyHeight: number,
  centerZ: number,
): DirectorCameraPoint[][] {
  if (detail !== 'reels') return []
  const x = bodyWidth / 2 + 0.018
  const radius = bodyHeight * 0.23
  return [
    createSideCircleLine(x, bodyHeight * 0.52, centerZ - bodyHeight * 0.23, radius),
    createSideCircleLine(x, bodyHeight * 0.52, centerZ + bodyHeight * 0.23, radius),
  ]
}

function createBoxLines(center: DirectorCameraPoint, size: DirectorCameraPoint): DirectorCameraPoint[][] {
  const [cx, cy, cz] = center
  const [width, height, depth] = size
  const [x0, x1] = [cx - width / 2, cx + width / 2]
  const [y0, y1] = [cy - height / 2, cy + height / 2]
  const [z0, z1] = [cz - depth / 2, cz + depth / 2]
  const c = { bbl: [x0, y0, z0], bbr: [x1, y0, z0], btl: [x0, y1, z0], btr: [x1, y1, z0], fbl: [x0, y0, z1], fbr: [x1, y0, z1], ftl: [x0, y1, z1], ftr: [x1, y1, z1] } as Record<string, DirectorCameraPoint>
  return [
    [c.bbl, c.bbr], [c.bbr, c.btr], [c.btr, c.btl], [c.btl, c.bbl],
    [c.fbl, c.fbr], [c.fbr, c.ftr], [c.ftr, c.ftl], [c.ftl, c.fbl],
    [c.bbl, c.fbl], [c.bbr, c.fbr], [c.btr, c.ftr], [c.btl, c.ftl]
  ]
}

function createLensBarrelLines(backZ: number, frontZ: number, radiusX: number, radiusY: number): DirectorCameraPoint[][] {
  const backX = radiusX * 0.72
  const backY = radiusY * 0.72
  const rings = [
    createRingLine(backZ, backX, backY),
    createRingLine(frontZ, radiusX, radiusY),
  ]
  const connectors = ([-1, 1] as const).flatMap((xSign) => ([-1, 1] as const).map((ySign) => [
    [xSign * backX, ySign * backY, backZ] as DirectorCameraPoint,
    [xSign * radiusX, ySign * radiusY, frontZ] as DirectorCameraPoint,
  ]))
  return [...rings, ...connectors]
}

function createRingLine(z: number, radiusX: number, radiusY: number): DirectorCameraPoint[] {
  return Array.from({ length: 33 }, (_, index) => {
    const angle = Math.PI * 2 * index / 32
    return [Math.cos(angle) * radiusX, Math.sin(angle) * radiusY, z]
  })
}

function createSideCircleLine(x: number, y: number, z: number, radius: number): DirectorCameraPoint[] {
  return Array.from({ length: 33 }, (_, index) => {
    const angle = Math.PI * 2 * index / 32
    return [x, y + Math.cos(angle) * radius, z + Math.sin(angle) * radius]
  })
}

function withPart(part: DirectorCameraLine['part'], lines: DirectorCameraPoint[][]): DirectorCameraLine[] {
  return lines.map((points) => ({ part, points }))
}

function toTuple(vector: Vector3): DirectorCameraPoint {
  return vector.toArray().map((value) => Number(value.toFixed(6))) as DirectorCameraPoint
}

function normalizeDegrees(value: number): number {
  const normalized = ((value + 180) % 360 + 360) % 360 - 180
  return Number(normalized.toFixed(2))
}
