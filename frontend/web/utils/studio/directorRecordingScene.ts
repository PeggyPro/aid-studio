import type { DirectorPathClip, DirectorSceneData } from '@/types/studio'
import { hydrateDirectorRecordedClip, sceneWithPersistedDirectorTakes, type DirectorRecordingSample, type DirectorRecordingView } from './directorRecordingPersistence'

/** Save only a registry projection of the active camera's authoritative definition; keep live editing state separate. */
export function directorRecordingSaveScene(local: DirectorSceneData, authoritative: DirectorSceneData, cameraId: string): DirectorSceneData {
  if (local.id !== authoritative.id) throw new Error('场景身份已变化，请重新读取')
  const next = sceneWithPersistedDirectorTakes(local, authoritative)
  if (cameraId === 'main-camera') return {
    ...next, camera: structuredClone(authoritative.camera),
    mainCameraLens: authoritative.mainCameraLens && structuredClone(authoritative.mainCameraLens),
    mainCameraLocked: authoritative.mainCameraLocked,
  }
  const camera = authoritative.cameraBookmarks?.find(item => item.id === cameraId)
  if (!camera || !local.cameraBookmarks?.some(item => item.id === cameraId)) throw new Error('原录制机位已不存在，请保留草稿')
  return { ...next, cameraBookmarks: next.cameraBookmarks?.map(item => item.id === cameraId ? {
    ...item, camera: structuredClone(camera.camera), lens: camera.lens && structuredClone(camera.lens), locked: camera.locked,
  } : item) }
}

const sameDuration = (start: number, end: number, durationMs: number) => Number.isFinite(start) && Number.isFinite(end)
  && start >= 0 && start <= 86400 && end <= 87000 && end > start && Math.abs((end - start) * 1000 - durationMs) < 0.001
const vector = (value: unknown) => Array.isArray(value) && value.length === 3 && value.every(Number.isFinite)

/** Verify the immutable take and current registry reference before changing any local timeline. */
export function restoreAppliedDirectorRecording(local: DirectorSceneData, cameraId: string, applied: DirectorRecordingView,
  detail: { revision: number; scene: DirectorSceneData }, durable: { view: DirectorRecordingView; samples: DirectorRecordingSample[] }): DirectorSceneData {
  const view = durable.view, samples = durable.samples
  if (detail.scene.id !== local.id || applied.state !== 'APPLIED' || view.state !== 'APPLIED' || !applied.sceneRevision
    || !Number.isInteger(detail.revision) || detail.revision < applied.sceneRevision || applied.clipId !== `take_${applied.takeId}`
    || !/^[a-f0-9]{64}$/.test(applied.contentHash ?? '')
    || (['takeId', 'contextVersion', 'clipId', 'sceneRevision', 'frameCount', 'durationMs', 'contentHash', 'durableFrameSeq', 'expectedChunkNo'] as const)
      .some(key => view[key] !== applied[key])
    || samples.length < 2 || samples.length !== applied.frameCount || applied.durableFrameSeq !== samples.length - 1
    || samples[0].tMs !== 0 || samples.at(-1)!.tMs !== applied.durationMs
    || samples.some((sample, i) => !Number.isInteger(sample.tMs) || (i > 0 && sample.tMs <= samples[i - 1].tMs)
      || !vector(sample.position) || !vector(sample.target) || sample.fov < 1 || sample.fov > 179 || Math.abs(sample.roll) > 360
      || ![sample.tMs, sample.fov, sample.roll, ...sample.position, ...sample.target].every(Number.isFinite)))
    throw new Error('录制帧数据或场景身份已变化，本地场景已保留，请重新读取')
  const references = detail.scene.timeline?.pathClips?.filter(clip => clip.id === applied.clipId || clip.takeId === applied.takeId) ?? []
  const metadata = references[0]
  const identity = (clip: DirectorPathClip) => clip.id === applied.clipId && clip.takeId === applied.takeId
    && clip.source === 'virtual-camera' && clip.targetKind === 'camera' && clip.targetId === cameraId
  if (references.length !== 1 || !identity(metadata) || !sameDuration(metadata.start, metadata.end, applied.durationMs))
    throw new Error('权威录制片段已删除或改变，本地场景已保留，请重新读取')
  const localClips = local.timeline?.pathClips ?? []
  const existing = localClips.filter(clip => clip.id === applied.clipId || clip.takeId === applied.takeId)
  if (existing.length > 1) throw new Error('本地录制引用重复，场景已保留')
  if (existing.length) {
    const clip = existing[0]
    if (!identity(clip) || !sameDuration(clip.start, clip.end, applied.durationMs) || !Array.isArray(clip.waypoints) || clip.waypoints.length < 2
      || clip.waypoints.some((point, i) => !Number.isFinite(point.time) || point.time < clip.start - 0.000001 || point.time > clip.end + 0.000001
        || (i > 0 && point.time <= clip.waypoints[i - 1].time)
        || !vector(point.position) || (point.lookAt && !vector(point.lookAt)) || (point.rotation && !vector(point.rotation))
        || (point.fov != null && (!Number.isFinite(point.fov) || point.fov < 1 || point.fov > 179))
        || (point.roll != null && (!Number.isFinite(point.roll) || Math.abs(point.roll) > 360))))
      throw new Error('本地录制片段已改变或不完整，场景已保留，请先核对编辑')
    // Existing waypoints, timing and transforms are authored data, not a disposable playback cache.
    return { ...local, revision: detail.revision, timeline: { ...local.timeline!,
      duration: Math.max(local.timeline?.duration ?? 10, detail.scene.timeline?.duration ?? 10, clip.end) } }
  }
  const next = hydrateDirectorRecordedClip(local, cameraId, applied, samples, metadata)
  return { ...next, revision: detail.revision, timeline: { ...next.timeline!,
    duration: Math.max(next.timeline?.duration ?? 10, detail.scene.timeline?.duration ?? 10) } }
}
