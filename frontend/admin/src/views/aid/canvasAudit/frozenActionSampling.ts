// Pure absolute-time sampling only; no editor, execution or persistence operations.
import { buildDirectorPosePresets } from './frozenActionPresets';
type DirectorActorActionClip = any;
type DirectorActionAsset = any;
type DirectorObject = any;
const presets = buildDirectorPosePresets();
const smooth = (t: number) => {
  const x = Math.max(0, Math.min(1, t));
  return x * x * (3 - 2 * x);
};
export function sampleDirectorImportedAction(
  asset: DirectorActionAsset,
  time: number,
  intensity = 1
): Record<string, number> {
  const t = asset.loop ? time % asset.duration : Math.min(time, asset.duration);
  let lo = 0,
    hi = asset.frames.length - 1;
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1;
    if (asset.frames[mid].time <= t) lo = mid;
    else hi = mid;
  }
  const a = asset.frames[lo],
    b = asset.frames[hi],
    w = Math.max(0, Math.min(1, (t - a.time) / (b.time - a.time)));
  return Object.fromEntries(
    [...new Set([...Object.keys(a.pose), ...Object.keys(b.pose)])].map((k) => [
      k,
      ((a.pose[k] ?? 0) * (1 - w) + (b.pose[k] ?? 0) * w) * intensity
    ])
  );
}
export function sampleDirectorActorAction(clip: DirectorActorActionClip, time: number): Record<string, number> {
  const t = Math.max(0, Math.min(time, clip.end) - clip.start) * clip.speed;
  const amount = clip.intensity;
  if (clip.action === 'imported' && clip.motion) return sampleDirectorImportedAction(clip.motion, t, amount);
  if (clip.action === 'pose') return { ...clip.pose };
  const cycle = t * Math.PI * 2 * 0.6;
  const swing = Math.sin(cycle),
    pulse = (1 - Math.cos(cycle)) / 2;
  if (clip.action === 'march' || clip.action === 'tiptoe') {
    const high = clip.action === 'march';
    return {
      leftHip: swing * (high ? 58 : 18) * amount,
      rightHip: -swing * (high ? 58 : 18) * amount,
      leftKnee: Math.max(0, swing) * (high ? 92 : 22) * amount,
      rightKnee: Math.max(0, -swing) * (high ? 92 : 22) * amount,
      leftShoulder: -swing * 32 * amount,
      rightShoulder: swing * 32 * amount,
      leftElbow: 40,
      rightElbow: 40,
      leftAnkle: high ? 0 : -26,
      rightAnkle: high ? 0 : -26,
      rootHeight: (high ? Math.abs(swing) * 0.025 : 0.05 + Math.abs(swing) * 0.02) * amount
    };
  }
  if (clip.action === 'jump') {
    const phase = t % 2,
      crouch = phase < 0.5 ? Math.sin((phase / 0.5) * Math.PI) : 0;
    const flight = phase >= 0.5 && phase < 1.3 ? Math.sin(((phase - 0.5) / 0.8) * Math.PI) : 0;
    const landing = phase >= 1.3 && phase < 1.7 ? Math.sin(((phase - 1.3) / 0.4) * Math.PI) : 0;
    const bend = (crouch * 55 + landing * 38) * amount;
    return {
      rootHeight: (flight * 0.38 - crouch * 0.2 - landing * 0.13) * amount,
      leftHip: bend,
      rightHip: bend,
      leftKnee: bend * 1.35,
      rightKnee: bend * 1.35,
      leftShoulder: (flight * 135 - crouch * 25) * amount,
      rightShoulder: (flight * 135 - crouch * 25) * amount,
      leftElbow: 15,
      rightElbow: 15,
      spine: -crouch * 12 * amount
    };
  }
  if (clip.action === 'squat_repeat')
    return {
      rootHeight: -0.55 * pulse * amount,
      spine: -15 * pulse * amount,
      leftHip: pulse * 95 * amount,
      rightHip: pulse * 95 * amount,
      leftKnee: pulse * 115 * amount,
      rightKnee: pulse * 115 * amount,
      leftAnkle: pulse * 22 * amount,
      rightAnkle: pulse * 22 * amount,
      leftShoulder: pulse * 55 * amount,
      rightShoulder: pulse * 55 * amount
    };
  if (clip.action === 'lunge')
    return {
      rootHeight: -0.28 * Math.abs(swing) * amount,
      leftHip: swing * 55 * amount,
      rightHip: -swing * 55 * amount,
      leftKnee: Math.abs(swing) * 75 * amount,
      rightKnee: Math.abs(swing) * 75 * amount,
      leftShoulder: -swing * 25 * amount,
      rightShoulder: swing * 25 * amount,
      spine: -Math.abs(swing) * 10 * amount
    };
  if (clip.action === 'punch') {
    const left = Math.max(0, swing) * amount,
      right = Math.max(0, -swing) * amount;
    return {
      leftShoulder: 25 + 60 * left,
      rightShoulder: 25 + 60 * right,
      leftElbow: 90 - 80 * left,
      rightElbow: 90 - 80 * right,
      chest: swing * 12 * amount,
      leftKnee: 12,
      rightKnee: 12,
      leftFingers: 60,
      rightFingers: 60
    };
  }
  if (clip.action === 'kick')
    return {
      rightHip: pulse * 85 * amount,
      rightKnee: Math.max(0, swing) * 75 * amount,
      leftShoulder: pulse * 30 * amount,
      rightShoulder: -pulse * 25 * amount,
      spine: pulse * 8 * amount,
      leftKnee: 8
    };
  if (clip.action === 'throw')
    return {
      rightShoulder: (-45 + 145 * pulse) * amount,
      rightElbow: (85 - 72 * pulse) * amount,
      leftShoulder: pulse * 35 * amount,
      chest: swing * 16 * amount,
      spine: -pulse * 15 * amount,
      headX: pulse * 8 * amount
    };
  if (clip.action === 'push' || clip.action === 'pull') {
    const extension = clip.action === 'push' ? pulse : 1 - pulse;
    return {
      leftShoulder: (35 + 45 * extension) * amount,
      rightShoulder: (35 + 45 * extension) * amount,
      leftElbow: (95 - 85 * extension) * amount,
      rightElbow: (95 - 85 * extension) * amount,
      spine: (clip.action === 'push' ? -12 : 10) * pulse * amount,
      leftKnee: 10 + pulse * 15,
      rightKnee: 10 + pulse * 15
    };
  }
  if (clip.action === 'dance')
    return {
      leftHip: swing * 22 * amount,
      rightHip: -swing * 22 * amount,
      leftKnee: 12 + Math.max(0, swing) * 28 * amount,
      rightKnee: 12 + Math.max(0, -swing) * 28 * amount,
      leftShoulder: (45 + swing * 40) * amount,
      rightShoulder: (45 - swing * 40) * amount,
      leftElbow: 65,
      rightElbow: 65,
      chest: swing * 15 * amount,
      rootHeight: -0.06 * pulse * amount
    };
  if (clip.action === 'wave_both')
    return {
      leftShoulder: 110 + swing * 15 * amount,
      rightShoulder: 110 - swing * 15 * amount,
      leftElbow: 65,
      rightElbow: 65,
      leftWrist: swing * 30 * amount,
      rightWrist: -swing * 30 * amount,
      chest: swing * 4 * amount
    };
  if (clip.action === 'walk' || clip.action === 'run') {
    const running = clip.action === 'run';
    const phase = t * Math.PI * 2 * (running ? 1.35 : 0.85);
    const swing = Math.sin(phase) * amount;
    return {
      leftHip: swing * (running ? 42 : 25),
      rightHip: -swing * (running ? 42 : 25),
      leftKnee: 5 + Math.max(0, -swing) * (running ? 65 : 32),
      rightKnee: 5 + Math.max(0, swing) * (running ? 65 : 32),
      leftShoulder: -swing * (running ? 40 : 22),
      rightShoulder: swing * (running ? 40 : 22),
      leftElbow: running ? 65 : 18,
      rightElbow: running ? 65 : 18,
      spine: running ? -8 : -2,
      chest: Math.sin(phase) * 3 * amount,
      rootHeight: Math.abs(Math.sin(phase)) * (running ? 0.08 : 0.025) * amount
    };
  }
  if (clip.action === 'crouch' || clip.action === 'crouch_cry') {
    const sob = clip.action === 'crouch_cry' ? Math.sin(t * Math.PI * 2 * 0.8) * amount : 0;
    return {
      ...presets['蹲下'],
      rootHeight: -0.68 + sob * 0.005,
      headX: 28 + sob * 3,
      chest: sob * 2,
      leftShoulder: 35 + sob * 3,
      rightShoulder: 35 - sob * 3,
      leftElbow: 120,
      rightElbow: 120,
      leftWrist: -18,
      rightWrist: -18
    };
  }
  if (clip.action === 'sit') return { ...presets['坐姿'] };
  if (clip.action === 'wave') return { ...presets['招手'], rightWrist: Math.sin(t * Math.PI * 2 * 0.75) * 24 * amount };
  const slow = Math.sin(t * Math.PI * 2 * 0.35) * amount;
  const gesture = (1 - Math.cos(t * Math.PI * 2 * 0.3)) * 0.5 * amount;
  if (clip.action === 'nod') return { headX: gesture * 22 };
  if (clip.action === 'shake_head') return { headY: slow * 24 };
  if (clip.action === 'look_around') return { headY: Math.sin(t * Math.PI * 2 * 0.16) * 48 * amount, chest: slow * 4 };
  if (clip.action === 'talk')
    return {
      headX: slow * 5,
      chest: slow * 3,
      rightShoulder: 22 + gesture * 22,
      rightElbow: 58 + slow * 15,
      leftShoulder: 12,
      leftElbow: 25
    };
  if (clip.action === 'bow')
    return { spine: -gesture * 40, leftHip: gesture * 30, rightHip: gesture * 30, headX: gesture * 16 };
  if (clip.action === 'cheer' || clip.action === 'stretch')
    return {
      leftShoulder: 150 + slow * 8,
      rightShoulder: 150 - slow * 8,
      leftElbow: 15,
      rightElbow: 15,
      chest: slow * 3
    };
  const preset = (
    {
      think: '思考',
      phone: '看手机',
      arms_crossed: '抱臂',
      hands_hips: '叉腰',
      reach: '伸手',
      kneel: '双膝跪'
    } as Record<string, string>
  )[clip.action];
  if (preset)
    return { ...presets[preset], ...(clip.action === 'kneel' ? { rootHeight: -0.78 } : {}), chest: slow * 1.5 };
  const breath = Math.sin(t * Math.PI * 2 * 0.3) * amount;
  return { chest: breath * 2, leftShoulder: breath, rightShoulder: -breath, rootHeight: breath * 0.006 };
}

function mixPose(from: Record<string, number>, to: Record<string, number>, weight: number): Record<string, number> {
  return Object.fromEntries(
    [...new Set([...Object.keys(from), ...Object.keys(to)])].map((joint) => [
      joint,
      (from[joint] ?? 0) * (1 - weight) + (to[joint] ?? 0) * weight
    ])
  );
}

export function evaluateDirectorActorActions(
  object: DirectorObject,
  clips: DirectorActorActionClip[],
  time: number,
  basePose: Record<string, number>,
  authoredPose?: Record<string, number> | null
): DirectorObject {
  if (object.kind !== 'actor') return object;
  const actions = clips.filter((clip) => clip.actorId === object.id);
  let index = -1;
  for (let i = actions.length - 1; i >= 0; i--)
    if (actions[i].start <= time) {
      index = i;
      break;
    }
  if (index < 0) return object;
  const clip = actions[index];
  const previous = actions[index - 1];
  const before = previous ? sampleDirectorActorAction(previous, previous.end) : basePose;
  const current = sampleDirectorActorAction(clip, time);
  const weight = clip.blendIn > 0 ? smooth((time - clip.start) / clip.blendIn) : 1;
  return { ...object, pose: { ...mixPose(before, current, weight), ...authoredPose } };
}
