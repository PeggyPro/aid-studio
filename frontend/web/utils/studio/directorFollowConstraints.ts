import { directorActorHeight } from '@/utils/studio/directorSceneUnits'
import { Vector3, Euler } from 'three'
import type {
  DirectorSceneData,
  DirectorCamera,
  DirectorObject,
  DirectorPathClip,
  DirectorTransformTuple as Vec,
} from '@/types/studio'
import { activeDirectorClip, sampleDirectorPath } from './directorPathClips'

export function directorAnchor(object: DirectorObject, anchor = 'chest'): Vec {
  return [
    object.transform.position[0],
    object.transform.position[1] +
      (anchor === 'origin' ? 0 : directorActorHeight(object) * (anchor === 'head' ? 0.95 : 0.7)
        + (object.pose.rootHeight ?? 0) * object.transform.scale[1]),
    object.transform.position[2],
  ]
}
export function evaluateDirectorFollow(
  clip: DirectorPathClip,
  objects: DirectorObject[],
  time: number,
  base: DirectorCamera,
): DirectorCamera {
  const p = sampleDirectorPath(clip, time)
  let camera: DirectorCamera = { position: p.position, target: p.lookAt ?? base.target, fov: p.fov ?? base.fov, roll: p.roll ?? base.roll ?? 0 }
  if (!p.lookAt && p.rotation) {
    camera.target = new Vector3(0, 0, -1)
      .applyEuler(new Euler(...p.rotation))
      .add(new Vector3(...p.position))
      .toArray()
  }
  const follow = clip.follow
  const actor = follow && objects.find((o) => o.id === follow.targetObjectId)
  if (!follow || !actor) return camera
  const anchor = directorAnchor(actor, follow.anchor)
  if (clip.kind === 'follow_relative') {
    const offset = new Vector3(...(follow.offset ?? [0, 1, 3])).applyAxisAngle(
      new Vector3(0, 1, 0),
      actor.transform.rotation[1],
    )
    camera = { ...camera, position: offset.add(new Vector3(...anchor)).toArray() }
  }
  if (clip.kind === 'follow_static') camera.position = follow.lockedPosition ?? clip.waypoints[0].position
  if (follow.continuousLookAt || clip.kind === 'follow_static') camera.target = anchor
  return camera
}

/** Director actors face +Z at zero yaw. Hold the incoming heading through stops and lifts. */
function actorPathHeading(clip: DirectorPathClip, time: number) {
  let heading: number | undefined
  for (let i = 1; i < clip.waypoints.length; i++) {
    const a = clip.waypoints[i - 1]
    const b = clip.waypoints[i]
    const dx = b.position[0] - a.position[0]
    const dz = b.position[2] - a.position[2]
    if (Math.hypot(dx, dz) <= 0.000001) continue
    if (time < a.time && heading !== undefined) break
    heading = Math.atan2(dx, dz)
    if (time < b.time) break
  }
  return heading
}

/** Receives the legacy evaluated scene; object motion is resolved before camera constraints. */
export function applyDirectorPaths(
  scene: DirectorSceneData,
  source: DirectorSceneData,
  time: number,
): DirectorSceneData {
  const clips = source.timeline?.pathClips ?? []
  const objects = scene.objects.map((object) => {
    const clip = activeDirectorClip(clips, object.id, time)
    if (!clip) return object
    const p = sampleDirectorPath(clip, time)
    const heading = object.kind === 'actor' && !p.rotation ? actorPathHeading(clip, time) : undefined
    const [pitch, yaw, roll] = object.transform.rotation
    const transform = {
      ...object.transform,
      position: p.position,
      rotation: p.rotation ?? [pitch, heading ?? yaw, roll] as Vec,
    }
    const pose = { ...object.pose }
    const locomotion = object.actorMeta?.locomotion
    const next = sampleDirectorPath(clip, Math.min(clip.end, time + 0.02))
    const previous = sampleDirectorPath(clip, Math.max(clip.start, time - 0.02))
    // Vertical lift is not walking. Preserve explicit pose during lifts; gait
    // is driven only by distance travelled across the horizontal ground plane.
    const speed = Math.hypot(next.position[0] - previous.position[0], next.position[2] - previous.position[2])
      / Math.max(0.001, next.time - previous.time)
    if (locomotion && locomotion !== 'idle' && speed > 0.05 && !source.timeline?.actionClips?.some(action => action.actorId === object.id)) {
      const multiplier = locomotion === 'run' ? 1.4 : 1
      const frequency = Math.min(locomotion === 'run' ? 1.35 : .85, Math.max(.3, speed / (locomotion === 'run' ? 2.2 : 1.4)))
      const phase = (time - clip.start) * 2 * Math.PI * frequency
      const protectedJoints = new Set(
        (source.timeline?.poseTracks ?? [])
          .filter((t) => t.objectId === object.id)
          .flatMap((t) => t.keyframes.flatMap((k) => Object.keys(k.value))),
      )
      for (const [joint, value] of Object.entries({
        leftHip: Math.sin(phase) * 25 * multiplier,
        rightHip: -Math.sin(phase) * 25 * multiplier,
        leftKnee: Math.max(0, -Math.sin(phase)) * 30 * multiplier,
        rightKnee: Math.max(0, Math.sin(phase)) * 30 * multiplier,
      })) {
        if (!protectedJoints.has(joint)) pose[joint] = value
      }
    }
    return { ...object, transform, pose }
  })
  const cameraAt = (id: string, base: DirectorCamera) => {
    const clip = activeDirectorClip(clips, id, time)
    return clip ? evaluateDirectorFollow(clip, objects, time, base) : base
  }
  return {
    ...scene,
    objects,
    camera: cameraAt('main-camera', scene.camera),
    cameraBookmarks: scene.cameraBookmarks?.map((b) => ({ ...b, camera: cameraAt(b.id, b.camera) })),
  }
}
