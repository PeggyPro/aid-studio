import type { DirectorActorAction, DirectorActorActionClip, DirectorObject, DirectorSceneData } from '@/types/studio'
import { buildDirectorPosePresets } from '@/components/studio/director/directorPosePresets'
import { sampleDirectorImportedAction, validDirectorActionAsset } from './directorActionLibrary'
import { studioId } from './studioGraph'

export const DIRECTOR_ACTOR_ACTIONS: Array<{ key: DirectorActorAction; label: string; description: string }> = [
  { key: 'stand', label: '站立呼吸', description: '原地轻微呼吸与重心变化' },
  { key: 'walk', label: '行走', description: '双腿与手臂循环，走位由轨迹控制' },
  { key: 'run', label: '跑步', description: '跑步摆臂、屈膝与身体起伏' },
  { key: 'crouch', label: '蹲下', description: '过渡到蹲姿并保持' },
  { key: 'crouch_cry', label: '蹲地哭泣', description: '蹲姿、低头与持续抽泣起伏' },
  { key: 'sit', label: '坐下', description: '过渡到坐姿并保持' },
  { key: 'wave', label: '招手', description: '原地持续挥手' },
  { key: 'nod', label: '点头', description: '缓慢点头回应' },
  { key: 'shake_head', label: '摇头', description: '自然摇头表达否定' },
  { key: 'look_around', label: '环顾', description: '左右转头观察周围' },
  { key: 'talk', label: '交谈', description: '自然手势与头部回应' },
  { key: 'bow', label: '鞠躬', description: '缓慢俯身致意后回正' },
  { key: 'think', label: '思考', description: '手扶下颌，轻微呼吸' },
  { key: 'phone', label: '看手机', description: '低头查看手机与手部轻动' },
  { key: 'arms_crossed', label: '抱臂', description: '双臂交叉，保持站姿' },
  { key: 'hands_hips', label: '叉腰', description: '双手叉腰，轻微重心变化' },
  { key: 'reach', label: '伸手', description: '伸出右手示意' },
  { key: 'cheer', label: '欢呼', description: '举起双臂表达喜悦' },
  { key: 'stretch', label: '伸展', description: '双臂向上舒展身体' },
  { key: 'kneel', label: '跪坐', description: '双膝着地，身体保持直立' },
  { key: 'march', label: '高抬腿踏步', description: '交替抬膝，配合反向摆臂' },
  { key: 'tiptoe', label: '踮脚行走', description: '脚踝提起，轻步迈腿与摆臂' },
  { key: 'jump', label: '原地起跳', description: '屈膝蓄力、腾空，再缓冲落地' },
  { key: 'squat_repeat', label: '连续蹲起', description: '完整下蹲和起身，双臂前伸平衡' },
  { key: 'lunge', label: '弓步训练', description: '交替弓步下压与回正' },
  { key: 'punch', label: '交替出拳', description: '左右交替伸臂出拳，随后收回防守' },
  { key: 'kick', label: '抬腿前踢', description: '提膝、向前踢出，再收腿落地' },
  { key: 'throw', label: '挥臂投掷', description: '手臂后摆蓄力，俯身向前投出' },
  { key: 'push', label: '双手推举', description: '双臂前推伸展，身体随重心前倾' },
  { key: 'pull', label: '双手拉拽', description: '双臂伸出后收肘，配合身体后仰' },
  { key: 'dance', label: '律动舞步', description: '交替屈膝、摆臂与上身律动' },
  { key: 'wave_both', label: '双手挥舞', description: '双臂抬起，双手交替挥动' },
  { key: 'pose', label: '当前自定义姿态', description: '记录当前摆姿，作为一个动作片段' }
]
const presets = buildDirectorPosePresets()
const actionNames = new Set([...DIRECTOR_ACTOR_ACTIONS.map(item => item.key), 'imported'])
const finite = (value: unknown, min: number, max: number): value is number => typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max
const frame = (time: number) => Math.round(time * 30) / 30
const smooth = (t: number) => { const x = Math.max(0, Math.min(1, t)); return x * x * (3 - 2 * x) }
export const directorActionLabel = (action: DirectorActorAction) => DIRECTOR_ACTOR_ACTIONS.find(item => item.key === action)?.label ?? (action === 'imported' ? '导入动作' : action)

function validClip(value: unknown): value is DirectorActorActionClip {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const clip = value as DirectorActorActionClip
  return typeof clip.id === 'string' && Boolean(clip.id) && clip.id.length < 256
    && typeof clip.actorId === 'string' && Boolean(clip.actorId) && clip.actorId.length < 256
    && actionNames.has(clip.action) && finite(clip.start, 0, 86400) && finite(clip.end, 0, 86400)
    && clip.end - clip.start >= 1 / 30 - 1e-6 && finite(clip.speed, .25, 3) && finite(clip.intensity, 0, 1)
    && finite(clip.blendIn, 0, Math.min(2, clip.end - clip.start) + 1e-6)
    && (clip.action !== 'imported' || validDirectorActionAsset(clip.motion))
    && (clip.action !== 'pose' || (clip.pose != null && typeof clip.pose === 'object' && !Array.isArray(clip.pose)))
    && (clip.pose === undefined || clip.pose != null && typeof clip.pose === 'object' && !Array.isArray(clip.pose)
      && Object.entries(clip.pose).length <= 64 && Object.entries(clip.pose).every(([joint, value]) =>
      joint.length < 64 && finite(value, joint === 'rootHeight' ? -1.5 : -180, joint === 'rootHeight' ? .5 : 180)))
}

/** Canonical normalization is pure; malformed imported clips are rejected separately. */
export function normalizeDirectorActionClips(value: unknown, duration: number): DirectorActorActionClip[] {
  if (!Array.isArray(value)) return []
  return value.filter(validClip).filter(clip => clip.start <= duration - 1 / 30 + 1e-6).map(clip => ({
    id: clip.id, actorId: clip.actorId, action: clip.action, start: clip.start, end: Math.min(duration, clip.end),
    speed: clip.speed, intensity: clip.intensity, blendIn: Math.min(clip.blendIn, duration - clip.start),
    ...(clip.pose ? { pose: { ...clip.pose } } : {}),
    ...(clip.action === 'imported' ? { motion: clip.motion, assetId: clip.assetId, assetName: clip.assetName } : {})
  })).sort((a, b) => a.actorId.localeCompare(b.actorId) || a.start - b.start || a.id.localeCompare(b.id))
}

export function validateDirectorActionClips(value: unknown, scene: DirectorSceneData): DirectorActorActionClip[] {
  if (!Array.isArray(value) || value.length > 2000 || !value.every(validClip)) throw new Error('人物动作片段数据无效')
  const actors = new Set(scene.objects.filter(object => object.kind === 'actor').map(object => object.id))
  const ids = new Set<string>()
  const ends = new Map<string, number>()
  const clips = normalizeDirectorActionClips(value, scene.timeline?.duration ?? 10)
  if (clips.length !== value.length) throw new Error('人物动作片段超出时间轴范围')
  for (const clip of clips) {
    if (!actors.has(clip.actorId) || ids.has(clip.id)) throw new Error('人物动作引用不存在或片段ID重复')
    if (clip.start < (ends.get(clip.actorId) ?? 0) - 1e-6) throw new Error('同一人物的动作片段不能重叠')
    const original = value.find(item => item.id === clip.id)!
    if (original.end > (scene.timeline?.duration ?? 10) + 1e-6) throw new Error('人物动作片段超出时间轴范围')
    ends.set(clip.actorId, clip.end); ids.add(clip.id)
  }
  return clips
}

/** Absolute timeline sampling, with no accumulated frame delta or mutable animation mixer. */
export function sampleDirectorActorAction(clip: DirectorActorActionClip, time: number): Record<string, number> {
  const t = Math.max(0, Math.min(time, clip.end) - clip.start) * clip.speed
  const amount = clip.intensity
  if (clip.action === 'imported' && clip.motion) return sampleDirectorImportedAction(clip.motion, t, amount)
  if (clip.action === 'pose') return { ...clip.pose }
  const cycle = t * Math.PI * 2 * .6
  const swing = Math.sin(cycle), pulse = (1 - Math.cos(cycle)) / 2
  if (clip.action === 'march' || clip.action === 'tiptoe') {
    const high = clip.action === 'march'
    return { leftHip: swing * (high ? 58 : 18) * amount, rightHip: -swing * (high ? 58 : 18) * amount,
      leftKnee: Math.max(0, swing) * (high ? 92 : 22) * amount, rightKnee: Math.max(0, -swing) * (high ? 92 : 22) * amount,
      leftShoulder: -swing * 32 * amount, rightShoulder: swing * 32 * amount,
      leftElbow: 40, rightElbow: 40, leftAnkle: high ? 0 : -26, rightAnkle: high ? 0 : -26,
      rootHeight: (high ? Math.abs(swing) * .025 : .05 + Math.abs(swing) * .02) * amount }
  }
  if (clip.action === 'jump') {
    const phase = t % 2, crouch = phase < .5 ? Math.sin(phase / .5 * Math.PI) : 0
    const flight = phase >= .5 && phase < 1.3 ? Math.sin((phase - .5) / .8 * Math.PI) : 0
    const landing = phase >= 1.3 && phase < 1.7 ? Math.sin((phase - 1.3) / .4 * Math.PI) : 0
    const bend = (crouch * 55 + landing * 38) * amount
    return { rootHeight: (flight * .38 - crouch * .2 - landing * .13) * amount,
      leftHip: bend, rightHip: bend, leftKnee: bend * 1.35, rightKnee: bend * 1.35,
      leftShoulder: (flight * 135 - crouch * 25) * amount, rightShoulder: (flight * 135 - crouch * 25) * amount,
      leftElbow: 15, rightElbow: 15, spine: -crouch * 12 * amount }
  }
  if (clip.action === 'squat_repeat') return { rootHeight: -.55 * pulse * amount, spine: -15 * pulse * amount,
    leftHip: pulse * 95 * amount, rightHip: pulse * 95 * amount, leftKnee: pulse * 115 * amount, rightKnee: pulse * 115 * amount,
    leftAnkle: pulse * 22 * amount, rightAnkle: pulse * 22 * amount, leftShoulder: pulse * 55 * amount, rightShoulder: pulse * 55 * amount }
  if (clip.action === 'lunge') return { rootHeight: -.28 * Math.abs(swing) * amount,
    leftHip: swing * 55 * amount, rightHip: -swing * 55 * amount, leftKnee: Math.abs(swing) * 75 * amount, rightKnee: Math.abs(swing) * 75 * amount,
    leftShoulder: -swing * 25 * amount, rightShoulder: swing * 25 * amount, spine: -Math.abs(swing) * 10 * amount }
  if (clip.action === 'punch') {
    const left = Math.max(0, swing) * amount, right = Math.max(0, -swing) * amount
    return { leftShoulder: 25 + 60 * left, rightShoulder: 25 + 60 * right, leftElbow: 90 - 80 * left, rightElbow: 90 - 80 * right,
      chest: swing * 12 * amount, leftKnee: 12, rightKnee: 12, leftFingers: 60, rightFingers: 60 }
  }
  if (clip.action === 'kick') return { rightHip: pulse * 85 * amount, rightKnee: Math.max(0, swing) * 75 * amount,
    leftShoulder: pulse * 30 * amount, rightShoulder: -pulse * 25 * amount, spine: pulse * 8 * amount, leftKnee: 8 }
  if (clip.action === 'throw') return { rightShoulder: (-45 + 145 * pulse) * amount, rightElbow: (85 - 72 * pulse) * amount,
    leftShoulder: pulse * 35 * amount, chest: swing * 16 * amount, spine: -pulse * 15 * amount, headX: pulse * 8 * amount }
  if (clip.action === 'push' || clip.action === 'pull') {
    const extension = clip.action === 'push' ? pulse : 1 - pulse
    return { leftShoulder: (35 + 45 * extension) * amount, rightShoulder: (35 + 45 * extension) * amount,
      leftElbow: (95 - 85 * extension) * amount, rightElbow: (95 - 85 * extension) * amount,
      spine: (clip.action === 'push' ? -12 : 10) * pulse * amount, leftKnee: 10 + pulse * 15, rightKnee: 10 + pulse * 15 }
  }
  if (clip.action === 'dance') return { leftHip: swing * 22 * amount, rightHip: -swing * 22 * amount,
    leftKnee: 12 + Math.max(0, swing) * 28 * amount, rightKnee: 12 + Math.max(0, -swing) * 28 * amount,
    leftShoulder: (45 + swing * 40) * amount, rightShoulder: (45 - swing * 40) * amount,
    leftElbow: 65, rightElbow: 65, chest: swing * 15 * amount, rootHeight: -.06 * pulse * amount }
  if (clip.action === 'wave_both') return { leftShoulder: 110 + swing * 15 * amount, rightShoulder: 110 - swing * 15 * amount,
    leftElbow: 65, rightElbow: 65, leftWrist: swing * 30 * amount, rightWrist: -swing * 30 * amount, chest: swing * 4 * amount }
  if (clip.action === 'walk' || clip.action === 'run') {
    const running = clip.action === 'run'
    const phase = t * Math.PI * 2 * (running ? 1.35 : .85)
    const swing = Math.sin(phase) * amount
    return {
      leftHip: swing * (running ? 42 : 25), rightHip: -swing * (running ? 42 : 25),
      leftKnee: 5 + Math.max(0, -swing) * (running ? 65 : 32), rightKnee: 5 + Math.max(0, swing) * (running ? 65 : 32),
      leftShoulder: -swing * (running ? 40 : 22), rightShoulder: swing * (running ? 40 : 22),
      leftElbow: running ? 65 : 18, rightElbow: running ? 65 : 18,
      spine: running ? -8 : -2, chest: Math.sin(phase) * 3 * amount,
      rootHeight: Math.abs(Math.sin(phase)) * (running ? .08 : .025) * amount
    }
  }
  if (clip.action === 'crouch' || clip.action === 'crouch_cry') {
    const sob = clip.action === 'crouch_cry' ? Math.sin(t * Math.PI * 2 * .8) * amount : 0
    return { ...presets['蹲下'], rootHeight: -.68 + sob * .005, headX: 28 + sob * 3,
      chest: sob * 2, leftShoulder: 35 + sob * 3, rightShoulder: 35 - sob * 3,
      leftElbow: 120, rightElbow: 120, leftWrist: -18, rightWrist: -18 }
  }
  if (clip.action === 'sit') return { ...presets['坐姿'] }
  if (clip.action === 'wave') return { ...presets['招手'], rightWrist: Math.sin(t * Math.PI * 2 * .75) * 24 * amount }
  const slow = Math.sin(t * Math.PI * 2 * .35) * amount
  const gesture = (1 - Math.cos(t * Math.PI * 2 * .3)) * .5 * amount
  if (clip.action === 'nod') return { headX: gesture * 22 }
  if (clip.action === 'shake_head') return { headY: slow * 24 }
  if (clip.action === 'look_around') return { headY: Math.sin(t * Math.PI * 2 * .16) * 48 * amount, chest: slow * 4 }
  if (clip.action === 'talk') return { headX: slow * 5, chest: slow * 3, rightShoulder: 22 + gesture * 22, rightElbow: 58 + slow * 15, leftShoulder: 12, leftElbow: 25 }
  if (clip.action === 'bow') return { spine: -gesture * 40, leftHip: gesture * 30, rightHip: gesture * 30, headX: gesture * 16 }
  if (clip.action === 'cheer' || clip.action === 'stretch') return { leftShoulder: 150 + slow * 8, rightShoulder: 150 - slow * 8, leftElbow: 15, rightElbow: 15, chest: slow * 3 }
  const preset = ({ think: '思考', phone: '看手机', arms_crossed: '抱臂', hands_hips: '叉腰', reach: '伸手', kneel: '双膝跪' } as Record<string, string>)[clip.action]
  if (preset) return { ...presets[preset], ...(clip.action === 'kneel' ? { rootHeight: -.78 } : {}), chest: slow * 1.5 }
  const breath = Math.sin(t * Math.PI * 2 * .3) * amount
  return { chest: breath * 2, leftShoulder: breath, rightShoulder: -breath, rootHeight: breath * .006 }
}

function mixPose(from: Record<string, number>, to: Record<string, number>, weight: number): Record<string, number> {
  return Object.fromEntries([...new Set([...Object.keys(from), ...Object.keys(to)])].map(joint => [joint,
    (from[joint] ?? 0) * (1 - weight) + (to[joint] ?? 0) * weight]))
}

export function evaluateDirectorActorActions(object: DirectorObject, clips: DirectorActorActionClip[], time: number,
  basePose: Record<string, number>, authoredPose?: Record<string, number> | null): DirectorObject {
  if (object.kind !== 'actor') return object
  const actions = clips.filter(clip => clip.actorId === object.id)
  const index = actions.findLastIndex(clip => clip.start <= time)
  if (index < 0) return object
  const clip = actions[index]
  const previous = actions[index - 1]
  const before = previous ? sampleDirectorActorAction(previous, previous.end) : basePose
  const current = sampleDirectorActorAction(clip, time)
  const weight = clip.blendIn > 0 ? smooth((time - clip.start) / clip.blendIn) : 1
  return { ...object, pose: { ...mixPose(before, current, weight), ...authoredPose } }
}

function editableActor(scene: DirectorSceneData, actorId: string) {
  const actor = scene.objects.find(object => object.id === actorId && object.kind === 'actor')
  if (!actor) throw new Error('人物不存在，无法修改动作')
  if (actor.locked) throw new Error('请先解锁人物再编辑动作')
  return actor
}

export function appendDirectorActorAction(scene: DirectorSceneData, actorId: string, action: DirectorActorAction,
  playhead: number, source?: DirectorActorActionClip): { scene: DirectorSceneData; clip: DirectorActorActionClip } {
  const actor = editableActor(scene, actorId)
  const actions = scene.timeline?.actionClips ?? []
  const start = frame(Math.max(playhead, 0, ...actions.filter(clip => clip.actorId === actorId).map(clip => clip.end)))
  const end = frame(start + (source ? source.end - source.start : 3))
  const clip: DirectorActorActionClip = { id: studioId('action'), actorId, action, start, end,
    speed: source?.speed ?? 1, intensity: source?.intensity ?? 1, blendIn: Math.min(source?.blendIn ?? .35, end - start),
    ...(action === 'imported' ? { motion: source?.motion, assetId: source?.assetId, assetName: source?.assetName } : {}),
    ...(action === 'pose' ? { pose: { ...(source?.pose ?? actor.pose) } } : {}) }
  const timeline = { ...scene.timeline!, duration: Math.max(scene.timeline?.duration ?? 10, end),
    actionClips: [...actions, clip], laneIds: [...new Set([...(scene.timeline?.laneIds ?? []), `action:${actorId}`])] }
  const next = { ...scene, timeline }
  timeline.actionClips = validateDirectorActionClips(timeline.actionClips, next)
  return { scene: next, clip }
}

export function updateDirectorActorAction(scene: DirectorSceneData, id: string, patch: Partial<Omit<DirectorActorActionClip, 'id' | 'actorId'>>): DirectorSceneData {
  const current = scene.timeline?.actionClips?.find(clip => clip.id === id)
  if (!current) throw new Error('动作片段已不存在')
  editableActor(scene, current.actorId)
  const next = { ...current, ...patch, id: current.id, actorId: current.actorId }
  next.start = frame(next.start); next.end = frame(next.end)
  next.blendIn = Math.min(next.blendIn, Math.max(0, next.end - next.start))
  if (next.action !== 'pose') delete next.pose
  if (next.action !== 'imported') { delete next.motion; delete next.assetId; delete next.assetName }
  if (!validClip(next)) throw new Error('请检查动作时间、过渡与表演参数；每段至少一帧')
  const updated = { ...scene, timeline: { ...scene.timeline!, duration: Math.max(scene.timeline!.duration, next.end), actionClips: scene.timeline!.actionClips!.map(clip => clip.id === id ? next : clip) } }
  updated.timeline.actionClips = validateDirectorActionClips(updated.timeline.actionClips, updated)
  return updated
}

export function removeDirectorActorAction(scene: DirectorSceneData, id: string): DirectorSceneData {
  const clip = scene.timeline?.actionClips?.find(item => item.id === id)
  if (!clip) return scene
  editableActor(scene, clip.actorId)
  return { ...scene, timeline: { ...scene.timeline!, actionClips: scene.timeline!.actionClips!.filter(item => item.id !== id) } }
}
