import { Euler, Quaternion } from 'three';
import { evaluateDirectorActorActions } from './frozenActionSampling';
import { applyDirectorPaths } from './frozenPathSampling';
const actions = new Set(
  'stand walk run crouch crouch_cry sit wave pose nod shake_head look_around talk bow think phone arms_crossed hands_hips reach cheer stretch kneel march tiptoe jump squat_repeat lunge punch kick throw push pull dance wave_both imported'.split(
    ' '
  )
);
const lerp = (a: number, b: number, t: number) => a + (b - a) * t;
const tuple = (a: number[], b: number[], t: number) => a.map((v, i) => lerp(v, b[i], t));
function sample(frames: any[], time: number, kind: 'transform' | 'pose' | 'camera'): any {
  const ordered = [...frames].sort((a, b) => a.time - b.time);
  if (!ordered.length) return null;
  if (time <= ordered[0].time) return ordered[0].value;
  if (time >= ordered[ordered.length - 1].time) return ordered[ordered.length - 1].value;
  const right = ordered.findIndex((f) => f.time > time),
    a = ordered[right - 1],
    b = ordered[right],
    t = (time - a.time) / (b.time - a.time);
  if (kind === 'pose')
    return Object.fromEntries(
      [...new Set([...Object.keys(a.value), ...Object.keys(b.value)])].map((key) => [
        key,
        lerp(a.value[key] || 0, b.value[key] || 0, t)
      ])
    );
  if (kind === 'camera')
    return {
      position: tuple(a.value.position, b.value.position, t),
      target: tuple(a.value.target, b.value.target, t),
      fov: lerp(a.value.fov, b.value.fov, t),
      roll: lerp(a.value.roll || 0, b.value.roll || 0, t)
    };
  const ea = new Euler(...(a.value.rotation as [number, number, number])),
    eb = new Euler(...(b.value.rotation as [number, number, number]));
  const rotation = new Euler().setFromQuaternion(
    new Quaternion().setFromEuler(ea).slerp(new Quaternion().setFromEuler(eb), t)
  );
  return {
    position: tuple(a.value.position, b.value.position, t),
    scale: tuple(a.value.scale, b.value.scale, t),
    rotation: [rotation.x, rotation.y, rotation.z]
  };
}
/** Evaluate a clone of the fixed scene at its saved time without modifying its document. */
export function evaluateFrozenTimeline(input: Record<string, any>, at = input.timeline?.currentTime || 0) {
  const source = structuredClone(input),
    timeline = source.timeline || { duration: 0 };
  const time = Math.max(0, Math.min(Number(at), Number(timeline.duration || 0)));
  const clips = (timeline.actionClips || [])
    .map((clip: any) => {
      if (!actions.has(clip.action)) throw new Error('固定场景含不支持的动作类型');
      if (
        !Number.isFinite(clip.speed) ||
        clip.speed < 0.25 ||
        clip.speed > 3 ||
        !Number.isFinite(clip.intensity) ||
        clip.intensity < 0 ||
        clip.intensity > 1 ||
        !Number.isFinite(clip.blendIn) ||
        clip.blendIn < 0 ||
        clip.blendIn > Math.min(2, clip.end - clip.start) + 1e-6
      )
        throw new Error('动作片段速度、强度或过渡字段无效');
      if (clip.action === 'imported') {
        const motion = clip.motion || source.actionLibrary?.find((a: any) => a.id === clip.assetId);
        if (motion?.frames?.length < 2 || !motion?.frames || !motion.duration)
          throw new Error('导入动作缺少完整固定帧');
        return { ...clip, motion };
      }
      return clip;
    })
    .sort((a: any, b: any) => a.actorId.localeCompare(b.actorId) || a.start - b.start || a.id.localeCompare(b.id));
  for (const path of timeline.pathClips || [])
    if (!['path', 'follow_relative', 'follow_track', 'follow_static'].includes(path.kind) || !path.waypoints?.length)
      throw new Error('固定场景含不支持的路径类型或空路径');
  const paths = (timeline.pathClips || []).map((path: any) => {
    const start = Math.max(0, Math.min(timeline.duration, path.start)),
      end = Math.max(0, Math.min(timeline.duration, path.end));
    const points = path.waypoints
      .map((point: any) => ({ ...point, time: Math.max(start, Math.min(end, point.time)) }))
      .sort((a: any, b: any) => a.time - b.time);
    const waypoints = points.filter(
      (point: any, index: number) => index === points.length - 1 || point.time !== points[index + 1].time
    );
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start || waypoints.length < 2)
      throw new Error('固定路径时间或路径点无效');
    return { ...path, start, end, waypoints };
  });
  source.timeline = { ...timeline, pathClips: paths };
  const objects = (source.objects || []).map((object: any) => {
    const transformTrack = timeline.tracks?.find((t: any) => t.objectId === object.id),
      poseTrack = timeline.poseTracks?.find((t: any) => t.objectId === object.id);
    const transform = transformTrack ? sample(transformTrack.keyframes, time, 'transform') : null,
      pose = poseTrack ? sample(poseTrack.keyframes, time, 'pose') : null;
    const evaluated = { ...object, pose: pose || object.pose || {}, transform: transform || object.transform };
    return evaluateDirectorActorActions(evaluated, clips, time, object.pose || {}, pose);
  });
  const cameraAt = (id: string, base: any) => {
    const track = timeline.cameraTracks?.find((t: any) => t.cameraId === id);
    return track ? sample(track.keyframes, time, 'camera') || base : base;
  };
  return applyDirectorPaths(
    {
      ...source,
      objects,
      camera: cameraAt('main-camera', source.camera),
      cameraBookmarks: source.cameraBookmarks?.map((b: any) => ({ ...b, camera: cameraAt(b.id, b.camera) })),
      timeline: { ...timeline, pathClips: paths, currentTime: time }
    },
    source,
    time
  );
}
