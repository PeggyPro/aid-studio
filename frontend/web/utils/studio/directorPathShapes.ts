import type { DirectorPathClip, DirectorTransformTuple as Vec } from '@/types/studio'
import { createDirectorPath } from './directorPathClips'

function decorateCameraPath(
  clip: DirectorPathClip,
  lookAt: Vec,
  grounded = true,
): DirectorPathClip {
  return {
    ...clip,
    grounded,
    source: 'draw',
    waypoints: clip.waypoints.map((point) => ({ ...point, lookAt: [...lookAt] as Vec })),
  }
}

export function createCircleCameraPath(
  center: Vec,
  radius: number,
  y: number,
  cameraId: string,
  start: number,
  lookKeep?: Vec,
): DirectorPathClip {
  if (!Number.isFinite(radius) || radius < 0.05) throw new Error('路径过短，请继续绘制')
  const points = Array.from({ length: 49 }, (_, index) => {
    const angle = (index / 48) * Math.PI * 2
    return [center[0] + Math.cos(angle) * radius, y, center[2] + Math.sin(angle) * radius] as Vec
  })
  points[points.length - 1] = [...points[0]] as Vec
  return decorateCameraPath(createDirectorPath(cameraId, 'camera', points, start, y), lookKeep ?? [center[0], y, center[2]])
}

export function createLineCameraPath(
  a: Vec,
  b: Vec,
  y: number,
  cameraId: string,
  start: number,
  lookAt: Vec,
): DirectorPathClip {
  return decorateCameraPath(
    createDirectorPath(cameraId, 'camera', [[a[0], y, a[2]], [b[0], y, b[2]]], start, y),
    lookAt,
  )
}

export function createRectCameraPath(
  minX: number,
  minZ: number,
  maxX: number,
  maxZ: number,
  y: number,
  cameraId: string,
  start: number,
): DirectorPathClip {
  const width = Math.abs(maxX - minX)
  const depth = Math.abs(maxZ - minZ)
  if (width < 0.05 || depth < 0.05) throw new Error('路径过短，请继续绘制')
  const perimeter = 2 * (width + depth)
  const count = Math.max(48, Math.round((perimeter / 2) * 30))
  const points: Vec[] = Array.from({ length: count + 1 }, (_, index) => {
    const traveled = (index / count) * perimeter
    if (traveled <= width) return [minX + traveled, y, minZ]
    if (traveled <= width + depth) return [maxX, y, minZ + traveled - width]
    if (traveled <= width * 2 + depth) return [maxX - (traveled - width - depth), y, maxZ]
    return [minX, y, maxZ - (traveled - width * 2 - depth)]
  })
  points[points.length - 1] = [...points[0]] as Vec
  return decorateCameraPath(
    createDirectorPath(cameraId, 'camera', points, start, y),
    [(minX + maxX) / 2, y, (minZ + maxZ) / 2],
  )
}

function bezierPoint(a: Vec, b: Vec, c: Vec, d: Vec, t: number): Vec {
  const mt = 1 - t
  return [0, 1, 2].map(
    (axis) => mt ** 3 * a[axis] + 3 * mt ** 2 * t * b[axis] + 3 * mt * t ** 2 * c[axis] + t ** 3 * d[axis],
  ) as Vec
}

export function bakeBezierCameraPath(
  anchors: Array<{ p: Vec; hIn?: Vec; hOut?: Vec }>,
  y: number,
  cameraId: string,
  start: number,
  lookAt: Vec,
): DirectorPathClip {
  if (anchors.length < 2) throw new Error('路径过短，请继续绘制')
  const points: Vec[] = []
  for (let index = 0; index < anchors.length - 1; index += 1) {
    const current = anchors[index]
    const next = anchors[index + 1]
    const controlA = current.hOut ?? current.p
    const controlB = next.hIn ?? next.p
    const roughLength = Math.hypot(next.p[0] - current.p[0], next.p[2] - current.p[2])
    const steps = Math.max(8, Math.ceil(roughLength * 30))
    for (let step = index === 0 ? 0 : 1; step <= steps; step += 1) {
      const point = bezierPoint(current.p, controlA, controlB, next.p, step / steps)
      points.push([point[0], y + point[1], point[2]])
    }
  }
  const clip = decorateCameraPath(createDirectorPath(cameraId, 'camera', points, start, y, true), lookAt, false)
  return { ...clip, penAnchors: anchors.map((anchor) => ({
    p: [...anchor.p] as Vec,
    ...(anchor.hIn ? { hIn: [...anchor.hIn] as Vec } : {}),
    ...(anchor.hOut ? { hOut: [...anchor.hOut] as Vec } : {}),
  })) }
}
