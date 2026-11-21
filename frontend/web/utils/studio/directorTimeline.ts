import { normalizeDirectorPathClips } from './directorPathClips'
import { applyDirectorPaths } from './directorFollowConstraints'
import { evaluateDirectorActorActions, normalizeDirectorActionClips } from './directorActorActions'
import { Euler, Quaternion } from 'three'
import type {
  DirectorCameraKeyframeValue,
  DirectorCameraTimelineTrack,
  DirectorKeyframeTransformValue,
  DirectorObjectTimelineKeyframe,
  DirectorObjectTimelineTrack,
  DirectorPoseKeyframeValue,
  DirectorPoseTimelineTrack,
  DirectorSceneData,
  DirectorTimeline,
  DirectorTimelineKeyframe,
  DirectorTransform,
  DirectorTransformTuple
} from '@/types/studio'
import { studioId } from './studioGraph'

export const DEFAULT_DIRECTOR_TIMELINE_DURATION = 10
const KEYFRAME_TIME_TOLERANCE = 0.001

export function createDefaultDirectorTimeline(
  duration = DEFAULT_DIRECTOR_TIMELINE_DURATION
): DirectorTimeline {
  return {
    duration,
    currentTime: 0,
    tracks: [],
    cameraTracks: [],
    pathClips: [],
    fps: 30,
    poseTracks: []
  }
}

export function createDirectorKeyframeId(prefix = 'kf'): string {
  return studioId(prefix)
}

export function sortDirectorKeyframes<K extends { time: number }>(keyframes: K[]): K[] {
  return [...keyframes].sort((left, right) => left.time - right.time)
}

function clampTime(time: number, duration: number) {
  return Math.max(0, Math.min(time, duration))
}

function lerp(a: number, b: number, t: number) {
  return a + (b - a) * t
}

function lerpTuple(a: DirectorTransformTuple, b: DirectorTransformTuple, t: number): DirectorTransformTuple {
  return [lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)]
}

interface TimedValue<V> {
  time: number
  value: V
}

function evaluateKeyframes<V>(
  keyframes: TimedValue<V>[],
  time: number,
  lerpFn: (a: V, b: V, t: number) => V
): V | null {
  if (!keyframes.length) return null
  if (time <= keyframes[0].time) return keyframes[0].value
  const last = keyframes[keyframes.length - 1]
  if (time >= last.time) return last.value
  for (let index = 0; index < keyframes.length - 1; index += 1) {
    const left = keyframes[index]
    const right = keyframes[index + 1]
    if (time >= left.time && time <= right.time) {
      const span = right.time - left.time
      const progress = span === 0 ? 0 : (time - left.time) / span
      return lerpFn(left.value, right.value, progress)
    }
  }
  return last.value
}

const _qa = new Quaternion()
const _qb = new Quaternion()
const _qr = new Quaternion()
const _tmpEuler = new Euler()

function slerpRotation(
  a: DirectorTransformTuple,
  b: DirectorTransformTuple,
  t: number
): DirectorTransformTuple {
  if (a[0] === b[0] && a[1] === b[1] && a[2] === b[2]) return [a[0], a[1], a[2]]
  _qa.setFromEuler(_tmpEuler.set(a[0], a[1], a[2]))
  _qb.setFromEuler(_tmpEuler.set(b[0], b[1], b[2]))
  _qr.slerpQuaternions(_qa, _qb, t)
  _tmpEuler.setFromQuaternion(_qr)
  return [_tmpEuler.x, _tmpEuler.y, _tmpEuler.z]
}

function lerpTransformValue(
  a: DirectorKeyframeTransformValue,
  b: DirectorKeyframeTransformValue,
  t: number
): DirectorKeyframeTransformValue {
  return {
    position: lerpTuple(a.position, b.position, t),
    rotation: slerpRotation(a.rotation, b.rotation, t),
    scale: lerpTuple(a.scale, b.scale, t)
  }
}

function lerpCameraValue(
  a: DirectorCameraKeyframeValue,
  b: DirectorCameraKeyframeValue,
  t: number
): DirectorCameraKeyframeValue {
  return {
    position: lerpTuple(a.position, b.position, t),
    target: lerpTuple(a.target, b.target, t),
    fov: lerp(a.fov, b.fov, t),
    roll: lerp(a.roll ?? 0, b.roll ?? 0, t),
  }
}

export function cloneDirectorPoseValue(pose: DirectorPoseKeyframeValue): DirectorPoseKeyframeValue {
  return { ...pose }
}

function lerpPoseValue(
  a: DirectorPoseKeyframeValue,
  b: DirectorPoseKeyframeValue,
  t: number
): DirectorPoseKeyframeValue {
  const keys = new Set([...Object.keys(a), ...Object.keys(b)])
  const next: DirectorPoseKeyframeValue = {}
  keys.forEach((key) => {
    next[key] = lerp(a[key] ?? 0, b[key] ?? 0, t)
  })
  return next
}

export function evaluateDirectorTrackAtTime(
  track: DirectorObjectTimelineTrack,
  time: number
): DirectorKeyframeTransformValue | null {
  return evaluateKeyframes(track.keyframes, time, lerpTransformValue)
}

export function evaluateDirectorCameraTrackAtTime(
  track: DirectorCameraTimelineTrack,
  time: number
): DirectorCameraKeyframeValue | null {
  return evaluateKeyframes(track.keyframes, time, lerpCameraValue)
}

export function evaluateDirectorPoseTrackAtTime(
  track: DirectorPoseTimelineTrack,
  time: number
): DirectorPoseKeyframeValue | null {
  return evaluateKeyframes(track.keyframes, time, lerpPoseValue)
}

export function transformToKeyframeValue(transform: DirectorTransform): DirectorKeyframeTransformValue {
  return {
    position: [...transform.position] as DirectorTransformTuple,
    rotation: [...transform.rotation] as DirectorTransformTuple,
    scale: [...transform.scale] as DirectorTransformTuple
  }
}

export function keyframeValueToTransform(value: DirectorKeyframeTransformValue): DirectorTransform {
  return {
    position: [...value.position] as DirectorTransformTuple,
    rotation: [...value.rotation] as DirectorTransformTuple,
    scale: [...value.scale] as DirectorTransformTuple
  }
}

function legacyKeyframesToTracks(
  keyframes: DirectorTimelineKeyframe[]
): DirectorObjectTimelineTrack[] {
  const byObject = new Map<string, DirectorObjectTimelineKeyframe[]>()
  for (const keyframe of keyframes) {
    const list = byObject.get(keyframe.objectId) ?? []
    list.push({
      id: keyframe.id,
      time: keyframe.time,
      value: transformToKeyframeValue(keyframe.transform)
    })
    byObject.set(keyframe.objectId, list)
  }
  return [...byObject.entries()].map(([objectId, frames]) => ({
    objectId,
    keyframes: sortDirectorKeyframes(frames)
  }))
}

function normalizePoseTracks(
  input: DirectorTimeline | null | undefined,
  duration: number
): DirectorPoseTimelineTrack[] {
  if (!Array.isArray(input?.poseTracks) || !input.poseTracks.length) return []
  return input.poseTracks.map((track) => ({
    objectId: track.objectId,
    keyframes: sortDirectorKeyframes(
      (track.keyframes ?? []).map((keyframe) => ({
        id: keyframe.id,
        time: clampTime(keyframe.time, duration),
        value: cloneDirectorPoseValue(keyframe.value ?? {})
      }))
    )
  })).filter((track) => track.keyframes.length > 0)
}

/** Accepts new tracks schema or legacy flat keyframes and always returns canonical timeline. */
export function normalizeDirectorTimeline(input?: DirectorTimeline | null): DirectorTimeline {
  if (!input) return createDefaultDirectorTimeline()
  const duration = Math.max(0.1, Number(input.duration) || DEFAULT_DIRECTOR_TIMELINE_DURATION)
  const currentTime = clampTime(Number(input.currentTime) || 0, duration)
  const hasTracks = Array.isArray(input.tracks) && input.tracks.length > 0
  const hasLegacy = Array.isArray(input.keyframes) && input.keyframes.length > 0
  const tracks = hasTracks
    ? input.tracks.map((track) => ({
        objectId: track.objectId,
        keyframes: sortDirectorKeyframes(
          (track.keyframes ?? []).map((keyframe) => ({
            id: keyframe.id,
            time: clampTime(keyframe.time, duration),
            value: {
              position: [...keyframe.value.position] as DirectorTransformTuple,
              rotation: [...keyframe.value.rotation] as DirectorTransformTuple,
              scale: [...keyframe.value.scale] as DirectorTransformTuple
            }
          }))
        )
      })).filter((track) => track.keyframes.length > 0)
    : hasLegacy
      ? legacyKeyframesToTracks(input.keyframes ?? [])
      : []
  const cameraTracks = Array.isArray(input.cameraTracks) && input.cameraTracks.length > 0
    ? input.cameraTracks.map((track) => ({
        cameraId: track.cameraId,
        keyframes: sortDirectorKeyframes(
          (track.keyframes ?? []).map((keyframe) => ({
            id: keyframe.id,
            time: clampTime(keyframe.time, duration),
            value: {
              position: [...keyframe.value.position] as DirectorTransformTuple,
              target: [...keyframe.value.target] as DirectorTransformTuple,
              fov: keyframe.value.fov,
              roll: keyframe.value.roll ?? 0,
            }
          }))
        )
      })).filter((track) => track.keyframes.length > 0)
    : []
  return {
    duration,
    currentTime,
    ...(Array.isArray(input.laneIds)
      ? { laneIds: [...new Set(input.laneIds.filter((id): id is string => typeof id === 'string'))] }
      : {}),
    tracks,
    cameraTracks,
    ...(Array.isArray(input.actionClips) ? { actionClips: normalizeDirectorActionClips(input.actionClips, duration) } : {}),
    pathClips: normalizeDirectorPathClips(input.pathClips, duration),
    fps: 30,
    poseTracks: normalizePoseTracks(input, duration)
  }
}

/** The editable ruler may extend beyond the authored animation; playback must not. */
export function getDirectorMotionEnd(timeline: DirectorTimeline): number {
  const endTimes = [
    ...timeline.tracks.flatMap((track) => track.keyframes.map((frame) => frame.time)),
    ...timeline.cameraTracks.flatMap((track) => track.keyframes.map((frame) => frame.time)),
    ...(timeline.poseTracks ?? []).flatMap((track) => track.keyframes.map((frame) => frame.time)),
    ...(timeline.pathClips ?? []).map((clip) => clip.end),
    ...(timeline.actionClips ?? []).map((clip) => clip.end)
  ]
  return endTimes.length ? Math.min(timeline.duration, Math.max(0.1, ...endTimes)) : timeline.duration
}

export function countDirectorTimelineKeyframes(timeline: DirectorTimeline): number {
  const objectCount = timeline.tracks.reduce((sum, track) => sum + track.keyframes.length, 0)
  const cameraCount = timeline.cameraTracks.reduce((sum, track) => sum + track.keyframes.length, 0)
  const poseCount = (timeline.poseTracks ?? []).reduce((sum, track) => sum + track.keyframes.length, 0)
  return objectCount + cameraCount + poseCount + (timeline.pathClips ?? []).reduce((sum, clip) => sum + clip.waypoints.length, 0)
}

export function setDirectorTimelineDuration(timeline: DirectorTimeline, duration: number): DirectorTimeline {
  const nextDuration = Math.max(0.1, duration)
  const clampFrames = <K extends { time: number }>(keyframes: K[]) =>
    sortDirectorKeyframes(keyframes.map((keyframe) => ({ ...keyframe, time: clampTime(keyframe.time, nextDuration) })))
  const normalized = normalizeDirectorTimeline(timeline)
  return {
    ...normalized,
    duration: nextDuration,
    pathClips: normalizeDirectorPathClips(normalized.pathClips, nextDuration),
    ...(normalized.actionClips ? { actionClips: normalizeDirectorActionClips(normalized.actionClips, nextDuration) } : {}),
    currentTime: clampTime(normalized.currentTime, nextDuration),
    tracks: normalized.tracks.map((track) => ({ ...track, keyframes: clampFrames(track.keyframes) })),
    cameraTracks: normalized.cameraTracks.map((track) => ({ ...track, keyframes: clampFrames(track.keyframes) })),
    poseTracks: normalized.poseTracks.map((track) => ({ ...track, keyframes: clampFrames(track.keyframes) }))
  }
}

export function upsertDirectorObjectKeyframe(
  timeline: DirectorTimeline,
  objectId: string,
  time: number,
  value: DirectorKeyframeTransformValue,
  keyframeId: string = createDirectorKeyframeId()
): DirectorTimeline {
  const base = normalizeDirectorTimeline(timeline)
  const safeTime = clampTime(time, base.duration)
  let touchedExisting = false
  const tracks = base.tracks.map((track) => {
    if (track.objectId !== objectId) return track
    const index = track.keyframes.findIndex((item) => Math.abs(item.time - safeTime) < KEYFRAME_TIME_TOLERANCE)
    if (index >= 0) {
      touchedExisting = true
      return {
        ...track,
        keyframes: track.keyframes.map((item, itemIndex) =>
          itemIndex === index ? { ...item, value: structuredClone(value) } : item
        )
      }
    }
    return {
      ...track,
      keyframes: sortDirectorKeyframes([
        ...track.keyframes,
        { id: keyframeId, time: safeTime, value: structuredClone(value) }
      ])
    }
  })
  if (touchedExisting || tracks.some((track) => track.objectId === objectId)) {
    return { ...base, tracks, currentTime: safeTime }
  }
  return {
    ...base,
    currentTime: safeTime,
    tracks: [
      ...base.tracks,
      { objectId, keyframes: [{ id: keyframeId, time: safeTime, value: structuredClone(value) }] }
    ]
  }
}

export function removeDirectorObjectKeyframe(
  timeline: DirectorTimeline,
  objectId: string,
  keyframeId: string
): DirectorTimeline {
  const base = normalizeDirectorTimeline(timeline)
  return {
    ...base,
    tracks: base.tracks
      .map((track) =>
        track.objectId === objectId
          ? { ...track, keyframes: track.keyframes.filter((item) => item.id !== keyframeId) }
          : track
      )
      .filter((track) => track.keyframes.length > 0)
  }
}

export function updateDirectorObjectKeyframeValue(
  timeline: DirectorTimeline,
  objectId: string,
  keyframeId: string,
  value: DirectorKeyframeTransformValue
): DirectorTimeline {
  const base = normalizeDirectorTimeline(timeline)
  return {
    ...base,
    tracks: base.tracks.map((track) =>
      track.objectId === objectId
        ? {
            ...track,
            keyframes: track.keyframes.map((item) =>
              item.id === keyframeId ? { ...item, value: structuredClone(value) } : item
            )
          }
        : track
    )
  }
}

export function upsertDirectorCameraKeyframe(
  timeline: DirectorTimeline,
  cameraId: string,
  time: number,
  value: DirectorCameraKeyframeValue,
  keyframeId: string = createDirectorKeyframeId('cam-kf')
): DirectorTimeline {
  const base = normalizeDirectorTimeline(timeline)
  const safeTime = clampTime(time, base.duration)
  let touchedExisting = false
  const cameraTracks = base.cameraTracks.map((track) => {
    if (track.cameraId !== cameraId) return track
    const index = track.keyframes.findIndex((item) => Math.abs(item.time - safeTime) < KEYFRAME_TIME_TOLERANCE)
    if (index >= 0) {
      touchedExisting = true
      return {
        ...track,
        keyframes: track.keyframes.map((item, itemIndex) =>
          itemIndex === index ? { ...item, value: structuredClone(value) } : item
        )
      }
    }
    return {
      ...track,
      keyframes: sortDirectorKeyframes([
        ...track.keyframes,
        { id: keyframeId, time: safeTime, value: structuredClone(value) }
      ])
    }
  })
  if (touchedExisting || cameraTracks.some((track) => track.cameraId === cameraId)) {
    return { ...base, cameraTracks, currentTime: safeTime }
  }
  return {
    ...base,
    currentTime: safeTime,
    cameraTracks: [
      ...base.cameraTracks,
      { cameraId, keyframes: [{ id: keyframeId, time: safeTime, value: structuredClone(value) }] }
    ]
  }
}

export function removeDirectorCameraKeyframe(
  timeline: DirectorTimeline,
  cameraId: string,
  keyframeId: string
): DirectorTimeline {
  const base = normalizeDirectorTimeline(timeline)
  return {
    ...base,
    cameraTracks: base.cameraTracks
      .map((track) =>
        track.cameraId === cameraId
          ? { ...track, keyframes: track.keyframes.filter((item) => item.id !== keyframeId) }
          : track
      )
      .filter((track) => track.keyframes.length > 0)
  }
}

export function updateDirectorCameraKeyframeValue(
  timeline: DirectorTimeline,
  cameraId: string,
  keyframeId: string,
  value: DirectorCameraKeyframeValue
): DirectorTimeline {
  const base = normalizeDirectorTimeline(timeline)
  return {
    ...base,
    cameraTracks: base.cameraTracks.map((track) =>
      track.cameraId === cameraId
        ? {
            ...track,
            keyframes: track.keyframes.map((item) =>
              item.id === keyframeId ? { ...item, value: structuredClone(value) } : item
            )
          }
        : track
    )
  }
}

export function upsertDirectorPoseKeyframe(
  timeline: DirectorTimeline,
  objectId: string,
  time: number,
  value: DirectorPoseKeyframeValue,
  keyframeId: string = createDirectorKeyframeId('pose-kf')
): DirectorTimeline {
  const base = normalizeDirectorTimeline(timeline)
  const safeTime = clampTime(time, base.duration)
  let touchedExisting = false
  const poseTracks = base.poseTracks.map((track) => {
    if (track.objectId !== objectId) return track
    const index = track.keyframes.findIndex((item) => Math.abs(item.time - safeTime) < KEYFRAME_TIME_TOLERANCE)
    if (index >= 0) {
      touchedExisting = true
      return {
        ...track,
        keyframes: track.keyframes.map((item, itemIndex) =>
          itemIndex === index ? { ...item, value: cloneDirectorPoseValue(value) } : item
        )
      }
    }
    return {
      ...track,
      keyframes: sortDirectorKeyframes([
        ...track.keyframes,
        { id: keyframeId, time: safeTime, value: cloneDirectorPoseValue(value) }
      ])
    }
  })
  if (touchedExisting || poseTracks.some((track) => track.objectId === objectId)) {
    return { ...base, poseTracks, currentTime: safeTime }
  }
  return {
    ...base,
    currentTime: safeTime,
    poseTracks: [
      ...base.poseTracks,
      { objectId, keyframes: [{ id: keyframeId, time: safeTime, value: cloneDirectorPoseValue(value) }] }
    ]
  }
}

export function removeDirectorPoseKeyframe(
  timeline: DirectorTimeline,
  objectId: string,
  keyframeId: string
): DirectorTimeline {
  const base = normalizeDirectorTimeline(timeline)
  return {
    ...base,
    poseTracks: base.poseTracks
      .map((track) =>
        track.objectId === objectId
          ? { ...track, keyframes: track.keyframes.filter((item) => item.id !== keyframeId) }
          : track
      )
      .filter((track) => track.keyframes.length > 0)
  }
}

export function updateDirectorPoseKeyframeValue(
  timeline: DirectorTimeline,
  objectId: string,
  keyframeId: string,
  value: DirectorPoseKeyframeValue
): DirectorTimeline {
  const base = normalizeDirectorTimeline(timeline)
  return {
    ...base,
    poseTracks: base.poseTracks.map((track) =>
      track.objectId === objectId
        ? {
            ...track,
            keyframes: track.keyframes.map((item) =>
              item.id === keyframeId ? { ...item, value: cloneDirectorPoseValue(value) } : item
            )
          }
        : track
    )
  }
}

export function clearDirectorTimelineKeyframes(timeline: DirectorTimeline): DirectorTimeline {
  return {
    ...normalizeDirectorTimeline(timeline),
    currentTime: 0,
    tracks: [],
    cameraTracks: [],
    pathClips: [],
    fps: 30,
    poseTracks: []
  }
}

export function removeDirectorTimelineTracksForObjects(
  timeline: DirectorTimeline,
  objectIds: string[]
): DirectorTimeline {
  const idSet = new Set(objectIds)
  const base = normalizeDirectorTimeline(timeline)
  return {
    ...base,
    pathClips: (base.pathClips ?? []).filter(c => !idSet.has(c.targetId) && !idSet.has(c.follow?.targetObjectId ?? "")),
    ...(base.actionClips ? { actionClips: base.actionClips.filter(clip => !idSet.has(clip.actorId)) } : {}),
    laneIds: base.laneIds?.filter(id => !idSet.has(id.slice(id.indexOf(':') + 1))),
    tracks: base.tracks.filter((track) => !idSet.has(track.objectId)),
    cameraTracks: base.cameraTracks.filter((track) => !idSet.has(track.cameraId)),
    poseTracks: base.poseTracks.filter((track) => !idSet.has(track.objectId))
  }
}

/** Sample object transform from flat or track list (legacy helper + track-aware). */
export function sampleDirectorTransform(
  keyframes: DirectorTimelineKeyframe[] | DirectorObjectTimelineKeyframe[],
  time: number
): DirectorTransform | null {
  if (!keyframes.length) return null
  const first = keyframes[0] as DirectorTimelineKeyframe | DirectorObjectTimelineKeyframe
  if ('transform' in first) {
    const track: DirectorObjectTimelineTrack = {
      objectId: 'legacy',
      keyframes: (keyframes as DirectorTimelineKeyframe[]).map((item) => ({
        id: item.id,
        time: item.time,
        value: transformToKeyframeValue(item.transform)
      }))
    }
    const value = evaluateDirectorTrackAtTime(track, time)
    return value ? keyframeValueToTransform(value) : null
  }
  const track: DirectorObjectTimelineTrack = {
    objectId: 'sampled',
    keyframes: keyframes as DirectorObjectTimelineKeyframe[]
  }
  const value = evaluateDirectorTrackAtTime(track, time)
  return value ? keyframeValueToTransform(value) : null
}

export function applyDirectorTimelineAt(scene: DirectorSceneData, currentTime: number): DirectorSceneData {
  const timeline = normalizeDirectorTimeline(scene.timeline)
  const safeTime = clampTime(currentTime, timeline.duration)
  const trackMap = new Map(timeline.tracks.map((track) => [track.objectId, track]))
  const poseTrackMap = new Map(timeline.poseTracks.map((track) => [track.objectId, track]))
  const cameraTrackMap = new Map(timeline.cameraTracks.map((track) => [track.cameraId, track]))

  let nextCamera = scene.camera
  let nextBookmarks = scene.cameraBookmarks
  const mainTrack = cameraTrackMap.get('main-camera')
  if (mainTrack) {
    const cameraValue = evaluateDirectorCameraTrackAtTime(mainTrack, safeTime)
    if (cameraValue) {
      nextCamera = {
        position: [...cameraValue.position] as DirectorTransformTuple,
        target: [...cameraValue.target] as DirectorTransformTuple,
        fov: cameraValue.fov,
        roll: cameraValue.roll ?? 0,
      }
    }
  }
  if (scene.cameraBookmarks?.length) {
    nextBookmarks = scene.cameraBookmarks.map((bookmark) => {
      const track = cameraTrackMap.get(bookmark.id)
      if (!track) return bookmark
      const cameraValue = evaluateDirectorCameraTrackAtTime(track, safeTime)
      if (!cameraValue) return bookmark
      return {
        ...bookmark,
        camera: {
          position: [...cameraValue.position] as DirectorTransformTuple,
          target: [...cameraValue.target] as DirectorTransformTuple,
          fov: cameraValue.fov,
          roll: cameraValue.roll ?? 0,
        }
      }
    })
  }

  return applyDirectorPaths({
    ...scene,
    objects: scene.objects.map((object) => {
      const track = trackMap.get(object.id)
      const poseTrack = poseTrackMap.get(object.id)
      const transform = track ? evaluateDirectorTrackAtTime(track, safeTime) : null
      const pose = poseTrack ? evaluateDirectorPoseTrackAtTime(poseTrack, safeTime) : null
      const evaluated = !transform && !pose ? object : {
        ...object,
        ...(transform ? { transform: keyframeValueToTransform(transform) } : {}),
        ...(pose ? { pose: cloneDirectorPoseValue(pose) } : {})
      }
      return evaluateDirectorActorActions(evaluated, timeline.actionClips ?? [], safeTime, object.pose, pose)
    }),
    camera: nextCamera,
    cameraBookmarks: nextBookmarks,
    timeline: { ...timeline, currentTime: safeTime },
    updatedAt: new Date().toISOString()
  }, { ...scene, timeline }, safeTime)
}
