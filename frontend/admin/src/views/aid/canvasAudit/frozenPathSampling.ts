// Pure path/follow evaluation for read-only fixed scenes.
import { Euler, Quaternion, Vector3 } from 'three';
type Vec = [number, number, number];
type DirectorObject = any;
type DirectorSceneData = any;
type DirectorCamera = any;
interface DirectorPathWaypoint {
  time: number;
  position: Vec;
  rotation?: Vec;
  lookAt?: Vec;
  fov?: number;
  roll?: number;
  [key: string]: any;
}
interface DirectorPathClip {
  id: string;
  targetId: string;
  kind: string;
  start: number;
  end: number;
  waypoints: DirectorPathWaypoint[];
  [key: string]: any;
}
const mixVec = (a: Vec, b: Vec, t: number): Vec => a.map((v, i) => v + (b[i] - v) * t) as Vec;
export function activeDirectorClip(clips: DirectorPathClip[], id: string, t: number) {
  return clips
    .filter((c) => c.targetId === id && t >= c.start && t <= c.end)
    .sort(
      (a, b) => Number(b.kind !== 'path') - Number(a.kind !== 'path') || b.start - a.start || a.id.localeCompare(b.id)
    )[0];
}

export function sampleDirectorPath(clip: DirectorPathClip, time: number): DirectorPathWaypoint {
  const points = clip.waypoints;
  if (time <= points[0].time) return points[0];
  const right = points.findIndex((p) => p.time > time);
  if (right < 0) return points[points.length - 1];
  const a = points[right - 1],
    b = points[right];
  const t = (time - a.time) / Math.max(0.00001, b.time - a.time);
  let rotation = a.rotation ?? b.rotation;
  if (a.rotation && b.rotation) {
    const q = new Quaternion()
      .setFromEuler(new Euler(...a.rotation))
      .slerp(new Quaternion().setFromEuler(new Euler(...b.rotation)), t);
    const e = new Euler().setFromQuaternion(q);
    rotation = [e.x, e.y, e.z];
  }
  return {
    ...a,
    time,
    position: mixVec(a.position, b.position, t),
    rotation,
    lookAt: a.lookAt && b.lookAt ? mixVec(a.lookAt, b.lookAt, t) : (a.lookAt ?? b.lookAt),
    fov: a.fov !== undefined && b.fov !== undefined ? a.fov + (b.fov - a.fov) * t : (a.fov ?? b.fov),
    roll: a.roll !== undefined && b.roll !== undefined ? a.roll + (b.roll - a.roll) * t : (a.roll ?? b.roll)
  };
}

export function directorAnchor(object: DirectorObject, anchor = 'chest'): Vec {
  return [
    object.transform.position[0],
    object.transform.position[1] +
      (anchor === 'origin'
        ? 0
        : (object.actorMeta?.heightMeters ?? 1.7) * (anchor === 'head' ? 0.95 : 0.7) +
          (object.pose.rootHeight ?? 0) * object.transform.scale[1]),
    object.transform.position[2]
  ];
}
export function evaluateDirectorFollow(
  clip: DirectorPathClip,
  objects: DirectorObject[],
  time: number,
  base: DirectorCamera
): DirectorCamera {
  const p = sampleDirectorPath(clip, time);
  let camera: DirectorCamera = {
    position: p.position,
    target: p.lookAt ?? base.target,
    fov: p.fov ?? base.fov,
    roll: p.roll ?? base.roll ?? 0
  };
  if (!p.lookAt && p.rotation) {
    camera.target = new Vector3(0, 0, -1)
      .applyEuler(new Euler(...p.rotation))
      .add(new Vector3(...p.position))
      .toArray();
  }
  const follow = clip.follow;
  const actor = follow && objects.find((o) => o.id === follow.targetObjectId);
  if (!follow || !actor) return camera;
  const anchor = directorAnchor(actor, follow.anchor);
  if (clip.kind === 'follow_relative') {
    const offset = new Vector3(...(follow.offset ?? [0, 1, 3])).applyAxisAngle(
      new Vector3(0, 1, 0),
      actor.transform.rotation[1]
    );
    camera = { ...camera, position: offset.add(new Vector3(...anchor)).toArray() };
  }
  if (clip.kind === 'follow_static') camera.position = follow.lockedPosition ?? clip.waypoints[0].position;
  if (follow.continuousLookAt || clip.kind === 'follow_static') camera.target = anchor;
  return camera;
}

/** Receives the legacy evaluated scene; object motion is resolved before camera constraints. */
export function applyDirectorPaths(
  scene: DirectorSceneData,
  source: DirectorSceneData,
  time: number
): DirectorSceneData {
  const clips = source.timeline?.pathClips ?? [];
  const objects = scene.objects.map((object) => {
    const clip = activeDirectorClip(clips, object.id, time);
    if (!clip) return object;
    const p = sampleDirectorPath(clip, time);
    const transform = {
      ...object.transform,
      position: p.position,
      rotation: p.rotation ?? source.objects.find((o) => o.id === object.id)!.transform.rotation
    };
    const pose = { ...object.pose };
    const locomotion = object.actorMeta?.locomotion;
    const next = sampleDirectorPath(clip, Math.min(clip.end, time + 0.02));
    const previous = sampleDirectorPath(clip, Math.max(clip.start, time - 0.02));
    // Vertical lift is not walking. Preserve explicit pose during lifts; gait
    // is driven only by distance travelled across the horizontal ground plane.
    const speed =
      Math.hypot(next.position[0] - previous.position[0], next.position[2] - previous.position[2]) /
      Math.max(0.001, next.time - previous.time);
    if (
      locomotion &&
      locomotion !== 'idle' &&
      speed > 0.05 &&
      !source.timeline?.actionClips?.some((action) => action.actorId === object.id)
    ) {
      const multiplier = locomotion === 'run' ? 1.4 : 1;
      const frequency = Math.min(
        locomotion === 'run' ? 1.35 : 0.85,
        Math.max(0.3, speed / (locomotion === 'run' ? 2.2 : 1.4))
      );
      const phase = (time - clip.start) * 2 * Math.PI * frequency;
      const protectedJoints = new Set(
        (source.timeline?.poseTracks ?? [])
          .filter((t) => t.objectId === object.id)
          .flatMap((t) => t.keyframes.flatMap((k) => Object.keys(k.value)))
      );
      for (const [joint, value] of Object.entries({
        leftHip: Math.sin(phase) * 25 * multiplier,
        rightHip: -Math.sin(phase) * 25 * multiplier,
        leftKnee: Math.max(0, -Math.sin(phase)) * 30 * multiplier,
        rightKnee: Math.max(0, Math.sin(phase)) * 30 * multiplier
      })) {
        if (!protectedJoints.has(joint)) pose[joint] = value;
      }
    }
    return { ...object, transform, pose };
  });
  const cameraAt = (id: string, base: DirectorCamera) => {
    const clip = activeDirectorClip(clips, id, time);
    return clip ? evaluateDirectorFollow(clip, objects, time, base) : base;
  };
  return {
    ...scene,
    objects,
    camera: cameraAt('main-camera', scene.camera),
    cameraBookmarks: scene.cameraBookmarks?.map((b) => ({ ...b, camera: cameraAt(b.id, b.camera) }))
  };
}
