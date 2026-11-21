import { Euler, Quaternion } from 'three'
import type {
  DirectorPathClip,
  DirectorPathWaypoint,
  DirectorTransformTuple as Vec,
  DirectorSceneData,
} from '@/types/studio'
import { studioId } from './studioGraph'

export const mixVec = (a: Vec, b: Vec, t: number): Vec => a.map((n, i) => n + (b[i] - n) * t) as Vec
export const distance = (a: Vec, b: Vec) => Math.hypot(...a.map((n, i) => n - b[i]))
export const validVec = (v: unknown): v is Vec =>
  Array.isArray(v) && v.length === 3 && v.every((n) => typeof n === 'number' && Number.isFinite(n))

export function normalizeDirectorPathClips(input: unknown, duration: number): DirectorPathClip[] {
  if (!Array.isArray(input)) return []
  return input.flatMap((raw: DirectorPathClip) => {
    if (
      !raw ||
      !['path', 'follow_relative', 'follow_track', 'follow_static'].includes(raw.kind) ||
      !['object', 'camera'].includes(raw.targetKind) ||
      !raw.targetId
    )
      return []
    const start = Math.max(0, Math.min(duration, raw.start))
    const end = Math.max(0, Math.min(duration, raw.end))
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return []
    const points = (Array.isArray(raw.waypoints) ? raw.waypoints : [])
      .filter(
        (p) =>
          p &&
          Number.isFinite(p.time) &&
          validVec(p.position) &&
          (!p.rotation || validVec(p.rotation)) &&
          (!p.lookAt || validVec(p.lookAt)),
      )
      .map((p) => ({ ...p, time: Math.max(start, Math.min(end, p.time)) }))
      .sort((a, b) => a.time - b.time)
    const waypoints = points.filter((p, i) => i === points.length - 1 || p.time !== points[i + 1].time)
    if (waypoints.length < 2) return []
    if (raw.kind !== 'path' && (!raw.follow?.targetObjectId || (raw.follow.offset && !validVec(raw.follow.offset))))
      return []
    return [
      {
        ...raw,
        penAnchors: Array.isArray(raw.penAnchors) && raw.penAnchors.length <= 200 && raw.penAnchors.every(
          (anchor) => anchor && validVec(anchor.p) && (!anchor.hIn || validVec(anchor.hIn)) && (!anchor.hOut || validVec(anchor.hOut)),
        ) ? raw.penAnchors : undefined,
        start,
        end,
        waypoints,
        ...(raw.kind === 'follow_static'
          ? {
              follow: {
                ...raw.follow!,
                lockedPosition: validVec(raw.follow?.lockedPosition)
                  ? raw.follow!.lockedPosition
                  : waypoints[0].position,
              },
            }
          : {}),
      },
    ]
  })
}

export function activeDirectorClip(clips: DirectorPathClip[], id: string, t: number) {
  return clips
    .filter((c) => c.targetId === id && t >= c.start && t <= c.end)
    .sort(
      (a, b) => Number(b.kind !== 'path') - Number(a.kind !== 'path') || b.start - a.start || a.id.localeCompare(b.id),
    )[0]
}

export function sampleDirectorPath(clip: DirectorPathClip, time: number): DirectorPathWaypoint {
  const points = clip.waypoints
  if (time <= points[0].time) return points[0]
  const right = points.findIndex((p) => p.time > time)
  if (right < 0) return points[points.length - 1]
  const a = points[right - 1],
    b = points[right]
  const t = (time - a.time) / Math.max(0.00001, b.time - a.time)
  let rotation = a.rotation ?? b.rotation
  if (a.rotation && b.rotation) {
    const q = new Quaternion()
      .setFromEuler(new Euler(...a.rotation))
      .slerp(new Quaternion().setFromEuler(new Euler(...b.rotation)), t)
    const e = new Euler().setFromQuaternion(q)
    rotation = [e.x, e.y, e.z]
  }
  return {
    ...a,
    time,
    position: mixVec(a.position, b.position, t),
    rotation,
    lookAt: a.lookAt && b.lookAt ? mixVec(a.lookAt, b.lookAt, t) : (a.lookAt ?? b.lookAt),
    fov: a.fov !== undefined && b.fov !== undefined ? a.fov + (b.fov - a.fov) * t : (a.fov ?? b.fov),
    roll: a.roll !== undefined && b.roll !== undefined ? a.roll + (b.roll - a.roll) * t : (a.roll ?? b.roll),
  }
}

export function createDirectorPath(
  targetId: string,
  targetKind: 'object' | 'camera',
  points: Vec[],
  start: number,
  cameraHeightOrOptions: number | { space: 'world'; grounded?: boolean } = 0,
  preserveHeight = false,
): DirectorPathClip {
  if (
    !Number.isFinite(start) ||
    start < 0 ||
    !points.every(validVec) ||
    (typeof cameraHeightOrOptions === 'number' && !Number.isFinite(cameraHeightOrOptions))
  ) throw new Error('路径坐标或开始时间无效')
  const sampled =
    points.length > 500
      ? Array.from({ length: 500 }, (_, i) => points[Math.round((i * (points.length - 1)) / 499)])
      : points
  // 数字高度保持旧的水平绘制契约；新绘制显式传 world，避免 XY/YZ 升降轨迹被压回地面。
  const grounded = sampled
    .map((p) => typeof cameraHeightOrOptions === 'number'
      ? [p[0], preserveHeight ? p[1] : targetKind === 'camera' ? cameraHeightOrOptions : 0, p[2]] as Vec
      : [...p] as Vec)
    .filter((p, i, all) => i === 0 || distance(p, all[i - 1]) > 0.000001)
  const lengths = grounded.map((p, i) => (i ? distance(p, grounded[i - 1]) : 0))
  const length = lengths.reduce((a, b) => a + b, 0)
  if (grounded.length < 2 || length < 0.01) throw new Error('路径过短，请继续绘制')
  const end = start + Math.max(1, length / (targetKind === 'object' ? 1.2 : 2))
  let traveled = 0
  return {
    id: studioId('path'),
    kind: 'path',
    targetId,
    targetKind,
    start,
    end,
    grounded: typeof cameraHeightOrOptions === 'number' || Boolean(cameraHeightOrOptions.grounded),
    createdAt: new Date().toISOString(),
    waypoints: grounded.map((position, i) => {
      traveled += lengths[i]
      return { id: studioId('waypoint'), time: start + (traveled / length) * (end - start), position }
    }),
  }
}

/** Keep the first frame at the entity's evaluated position, including paths drawn elsewhere. */
export function connectDirectorPathStart(clip: DirectorPathClip, origin: Vec): DirectorPathClip {
  const first = clip.waypoints[0]
  if (!first || distance(first.position, origin) < 0.01) return clip
  const rebuilt = createDirectorPath(
    clip.targetId,
    clip.targetKind,
    [origin, ...clip.waypoints.map((point) => point.position)],
    clip.start,
    origin[1],
    Boolean(clip.penAnchors) || clip.grounded === false,
  )
  return {
    ...clip,
    end: rebuilt.end,
    penAnchors: clip.penAnchors
      ? [{ p: [origin[0], 0, origin[2]] }, ...clip.penAnchors]
      : undefined,
    waypoints: rebuilt.waypoints.map((point, index) => ({
      ...point,
      ...(index === 0 ? first : clip.waypoints[Math.min(index - 1, clip.waypoints.length - 1)]),
      id: point.id,
      time: point.time,
      position: point.position,
    })),
  }
}

/** Replace the motion from a newly drawn clip's start instead of resuming an older overlapping path. */
export function insertDirectorDrawnPath(clips: DirectorPathClip[], clip: DirectorPathClip): DirectorPathClip[] {
  const retained = clips.flatMap((old) => {
    if (old.id === clip.id) return []
    if (old.targetId !== clip.targetId || old.end <= clip.start || old.start >= clip.end) return [old]
    if (old.start >= clip.start) return []
    const endpoint = { ...sampleDirectorPath(old, clip.start), id: studioId('waypoint'), time: clip.start }
    return [{
      ...old,
      end: clip.start,
      waypoints: [...old.waypoints.filter((point) => point.time < clip.start), endpoint],
    }]
  })
  return [...retained, clip]
}

export function retimeDirectorClip(clip: DirectorPathClip, start: number, end: number): DirectorPathClip {
  const safeStart = Math.max(0, start),
    safeEnd = Math.max(safeStart + 0.2, end)
  return {
    ...clip,
    start: safeStart,
    end: safeEnd,
    waypoints: clip.waypoints.map((p) => ({
      ...p,
      time: safeStart + ((p.time - clip.start) / (clip.end - clip.start)) * (safeEnd - safeStart),
    })),
  }
}

export function pruneDirectorPaths(scene: DirectorSceneData): DirectorSceneData {
  const objects = new Set(scene.objects.map((o) => o.id))
  const actors = new Set(scene.objects.filter((o) => o.kind === 'actor').map((o) => o.id))
  const cameras = new Set([...(scene.mainCameraPresent === false ? [] : ['main-camera']), ...(scene.cameraBookmarks ?? []).map((c) => c.id)])
  return {
    ...scene,
    timeline: scene.timeline
      ? {
          ...scene.timeline,
          pathClips: (scene.timeline.pathClips ?? []).filter(
            (c) =>
              (c.targetKind === 'object' ? objects : cameras).has(c.targetId) &&
              (!c.follow || actors.has(c.follow.targetObjectId)),
          ),
        }
      : undefined,
  }
}
