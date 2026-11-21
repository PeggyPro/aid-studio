import type { DirectorSceneData, DirectorTimeline } from '@/types/studio'
import { listDirectorAuthoredCameras } from './directorCameraState'

export interface DirectorTimelineLane {
  id: string
  kind: 'camera' | 'object' | 'pose' | 'action'
  targetId: string
  label: string
}

export function availableDirectorTimelineLanes(scene: DirectorSceneData): DirectorTimelineLane[] {
  return [
    ...listDirectorAuthoredCameras(scene).map((camera) => ({ id: `camera:${camera.id}`, kind: 'camera' as const, targetId: camera.id, label: camera.name })),
    ...scene.objects.map((object) => ({ id: `object:${object.id}`, kind: 'object' as const, targetId: object.id, label: object.name })),
    ...scene.objects.filter((object) => object.kind === 'actor').map((object) => ({ id: `pose:${object.id}`, kind: 'pose' as const, targetId: object.id, label: `${object.name} · 姿态` })),
    ...scene.objects.filter((object) => object.kind === 'actor').map((object) => ({ id: `action:${object.id}`, kind: 'action' as const, targetId: object.id, label: `${object.name} · 动作` })),
  ]
}

export function buildDirectorTimelineLanes(scene: DirectorSceneData): DirectorTimelineLane[] {
  const timeline = scene.timeline
  const recorded = new Set([
    ...(timeline?.laneIds ?? []),
    ...(timeline?.cameraTracks ?? []).filter((track) => track.keyframes.length).map((track) => `camera:${track.cameraId}`),
    ...(timeline?.tracks ?? []).filter((track) => track.keyframes.length).map((track) => `object:${track.objectId}`),
    ...(timeline?.poseTracks ?? []).filter((track) => track.keyframes.length).map((track) => `pose:${track.objectId}`),
    ...(timeline?.actionClips ?? []).map(clip => `action:${clip.actorId}`),
    ...(timeline?.pathClips ?? []).map((clip) => `${clip.targetKind === 'camera' ? 'camera' : 'object'}:${clip.targetId}`),
  ])
  return availableDirectorTimelineLanes(scene).filter((lane) => lane.id === 'camera:main-camera' || recorded.has(lane.id))
}

export function timeAtTimelinePixel(pixel: number, pixelsPerSecond: number, duration: number): number {
  return Math.min(duration, Math.max(0, pixel / pixelsPerSecond))
}

export function removeDirectorTimelineLane(timeline: DirectorTimeline, laneId: string): DirectorTimeline {
  if (laneId === 'camera:main-camera') return timeline
  const [kind, ...parts] = laneId.split(':')
  const targetId = parts.join(':')
  return {
    ...timeline,
    laneIds: (timeline.laneIds ?? []).filter((id) => id !== laneId),
    tracks: kind === 'object' ? timeline.tracks.filter((track) => track.objectId !== targetId) : timeline.tracks,
    cameraTracks: kind === 'camera' ? timeline.cameraTracks.filter((track) => track.cameraId !== targetId) : timeline.cameraTracks,
    poseTracks: kind === 'pose' ? timeline.poseTracks.filter((track) => track.objectId !== targetId) : timeline.poseTracks,
    pathClips: kind === 'pose' || kind === 'action' ? timeline.pathClips : (timeline.pathClips ?? []).filter((clip) => clip.targetId !== targetId),
    ...(timeline.actionClips ? { actionClips: kind === 'action' ? timeline.actionClips.filter(clip => clip.actorId !== targetId) : timeline.actionClips } : {}),
  }
}
