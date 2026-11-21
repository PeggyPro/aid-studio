import { Euler, MathUtils, Vector3 } from 'three'
import type { DirectorPathClip, DirectorTransformTuple as Vec } from '@/types/studio'
import { retimeDirectorClip } from './directorPathClips'
import { studioId } from './studioGraph'

export function insertDirectorCameraClip(
  clips: DirectorPathClip[],
  next: DirectorPathClip,
  mode: 'replace' | 'append',
): DirectorPathClip[] {
  if (mode === 'replace') {
    return [
      ...clips.filter(
        (clip) => clip.targetId !== next.targetId || clip.start >= next.end || clip.end <= next.start,
      ),
      next,
    ]
  }
  const sameCamera = clips.filter((clip) => clip.targetId === next.targetId)
  const written = sameCamera.length
    ? retimeDirectorClip(next, Math.max(...sameCamera.map((clip) => clip.end)), Math.max(...sameCamera.map((clip) => clip.end)) + next.end - next.start)
    : next
  return [...clips, written]
}

export function transformDirectorPathClip(
  clip: DirectorPathClip,
  op: { position?: Vec; rotationDeg?: Vec; scale?: Vec },
): DirectorPathClip {
  const positions = clip.waypoints.map((point) => new Vector3().fromArray(point.position))
  const min = positions.reduce((result, point) => result.min(point), positions[0].clone())
  const max = positions.reduce((result, point) => result.max(point), positions[0].clone())
  const center = min.add(max).multiplyScalar(0.5)
  const scale = new Vector3().fromArray(op.scale ?? [1, 1, 1])
  const rotation = new Euler(...(op.rotationDeg ?? [0, 0, 0]).map(MathUtils.degToRad) as Vec, 'XYZ')
  const translation = new Vector3().fromArray(op.position ?? [0, 0, 0])
  const transform = (value: Vec): Vec =>
    new Vector3()
      .fromArray(value)
      .sub(center)
      .multiply(scale)
      .applyEuler(rotation)
      .add(center)
      .add(translation)
      .toArray()
      .map((number) => Number(number.toFixed(6))) as Vec
  const oldHeight = clip.waypoints[0].position[1]
  const newHeight = transform(clip.waypoints[0].position)[1]
  const transformPen = (value: Vec): Vec => {
    const next = transform([value[0], value[1] + oldHeight, value[2]])
    return [next[0], next[1] - newHeight, next[2]]
  }
  return {
    ...clip,
    grounded: false,
    penAnchors: clip.penAnchors?.map((anchor) => ({
      p: transformPen(anchor.p),
      ...(anchor.hIn ? { hIn: transformPen(anchor.hIn) } : {}),
      ...(anchor.hOut ? { hOut: transformPen(anchor.hOut) } : {}),
    })),
    waypoints: clip.waypoints.map((point) => ({
      ...point,
      position: transform(point.position),
      lookAt: point.lookAt ? transform(point.lookAt) : undefined,
    })),
  }
}

export function retimeSelectedClip(clip: DirectorPathClip, duration: number): DirectorPathClip {
  const safeDuration = Math.max(0.2, Number.isFinite(duration) ? duration : 0.2)
  return retimeDirectorClip(clip, clip.start, clip.start + safeDuration)
}

export function discardVirtualCameraClips(
  clips: DirectorPathClip[],
  sessionId: string,
  cameraId: string,
): DirectorPathClip[] {
  return clips.filter(
    (clip) =>
      clip.source !== 'virtual-camera' || clip.sessionId !== sessionId || clip.targetId !== cameraId,
  )
}

export function bakeRecordedCameraClip({
  cameraId,
  sessionId,
  start,
  samples,
}: {
  cameraId: string
  sessionId: string
  start: number
  samples: Array<{ time: number; position: Vec; lookAt: Vec; fov: number; roll?: number }>
}): DirectorPathClip {
  if (samples.length < 2) throw new Error('录制过短')
  const sorted = [...samples].sort((left, right) => left.time - right.time)
  const firstTime = sorted[0].time
  const lastTime = sorted.at(-1)!.time
  if (!Number.isFinite(firstTime) || !Number.isFinite(lastTime) || lastTime <= firstTime) throw new Error('录制过短')
  const safeStart = Math.max(0, start)
  return {
    id: studioId('path'),
    kind: 'path',
    targetKind: 'camera',
    targetId: cameraId,
    start: safeStart,
    end: safeStart + lastTime - firstTime,
    grounded: false,
    source: 'virtual-camera',
    sessionId,
    createdAt: new Date().toISOString(),
    waypoints: sorted.map((sample) => ({
      id: studioId('waypoint'),
      time: safeStart + sample.time - firstTime,
      position: [...sample.position],
      lookAt: [...sample.lookAt],
      fov: sample.fov,
      roll: sample.roll,
    })),
  }
}
