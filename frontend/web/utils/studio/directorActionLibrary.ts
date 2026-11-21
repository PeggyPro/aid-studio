import type { DirectorActionAsset } from '@/types/directorPreviz'
import { studioId } from './studioGraph'

export const DIRECTOR_ACTION_JOINTS = new Set([
  'rootHeight',
  'spine',
  'chest',
  'neck',
  'headX',
  'headY',
  'leftShoulder',
  'rightShoulder',
  'leftElbow',
  'rightElbow',
  'leftWrist',
  'rightWrist',
  'leftHip',
  'rightHip',
  'leftKnee',
  'rightKnee',
  'leftAnkle',
  'rightAnkle',
  'leftThumb',
  'rightThumb',
  'leftFingers',
  'rightFingers'
])
export function validDirectorActionAsset(value: unknown): value is DirectorActionAsset {
  if (!value || typeof value !== 'object') return false
  const a = value as DirectorActionAsset
  return (
    typeof a.id === 'string' &&
    /^[a-zA-Z0-9_-]{1,128}$/.test(a.id) &&
    typeof a.name === 'string' &&
    a.name.trim().length > 0 &&
    a.name.length <= 80 &&
    ['aid', 'bvh'].includes(a.source) &&
    typeof a.loop === 'boolean' &&
    Number.isFinite(a.duration) &&
    a.duration >= 1 / 30 &&
    a.duration <= 60 &&
    Array.isArray(a.frames) &&
    a.frames.length >= 2 &&
    a.frames.length <= 1801 &&
    a.frames[0]?.time === 0 &&
    Math.abs((a.frames.at(-1)?.time ?? NaN) - a.duration) < 1e-5 &&
    a.frames.every(
      (f, i) =>
        f &&
        Number.isFinite(f.time) &&
        f.time >= 0 &&
        f.time <= a.duration &&
        (!i || f.time > a.frames[i - 1].time) &&
        f.pose &&
        typeof f.pose === 'object' &&
        !Array.isArray(f.pose) &&
        Object.keys(f.pose).length > 0 &&
        Object.entries(f.pose).every(
          ([key, v]) =>
            DIRECTOR_ACTION_JOINTS.has(key) &&
            Number.isFinite(v) &&
            v >= (key === 'rootHeight' ? -1.5 : -180) &&
            v <= (key === 'rootHeight' ? 0.5 : 180)
        )
    )
  )
}
export function validateDirectorActionLibrary(value: unknown): DirectorActionAsset[] {
  if (
    !Array.isArray(value) ||
    value.length > 32 ||
    !value.every(validDirectorActionAsset) ||
    new Set(value.map((a) => a.id)).size !== value.length
  )
    throw new Error('动作库数据无效，最多32条、每条60秒')
  return value
}
export function sampleDirectorImportedAction(
  asset: DirectorActionAsset,
  time: number,
  intensity = 1
): Record<string, number> {
  const t = asset.loop ? time % asset.duration : Math.min(time, asset.duration)
  let lo = 0,
    hi = asset.frames.length - 1
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1
    if (asset.frames[mid].time <= t) lo = mid
    else hi = mid
  }
  const a = asset.frames[lo],
    b = asset.frames[hi],
    w = Math.max(0, Math.min(1, (t - a.time) / (b.time - a.time)))
  return Object.fromEntries(
    [...new Set([...Object.keys(a.pose), ...Object.keys(b.pose)])].map((k) => [
      k,
      ((a.pose[k] ?? 0) * (1 - w) + (b.pose[k] ?? 0) * w) * intensity
    ])
  )
}
export async function readDirectorActionFile(file: File): Promise<DirectorActionAsset> {
  if (!file.size || file.size > 8 * 1024 * 1024) throw new Error('请选择8MB以内的BVH或AID动作JSON文件')
  const text = await file.text()
  if (/\.bvh$/i.test(file.name)) return importBvh(text, file.name)
  if (!/\.json$/i.test(file.name)) throw new Error('支持BVH与AID动作JSON；不支持直接导入FBX')
  const data = JSON.parse(text)
  if (data.kind !== 'aid-director-action' || data.version !== 1)
    throw new Error('请选择AID动作文件；可下载模板查看关节格式')
  const result = {
    id: studioId('motion'),
    source: 'aid',
    name: data.action?.name,
    duration: data.action?.duration,
    loop: data.action?.loop,
    frames: data.action?.frames
  }
  if (!validDirectorActionAsset(result)) throw new Error('动作帧、关节或时长无效（2–1801帧，最长60秒）')
  return result
}
async function importBvh(text: string, name: string): Promise<DirectorActionAsset> {
  // Preflight before the recursive BVH parser: bound depth, joints and sample count.
  const count = Number(text.match(/Frames:\s*(\d+)/i)?.[1]),
    step = Number(text.match(/Frame Time:\s*([\d.e+-]+)/i)?.[1])
  let depth = 0,
    maxDepth = 0
  for (const c of text) {
    if (c === '{') maxDepth = Math.max(maxDepth, ++depth)
    if (c === '}') depth--
    if (depth < 0) throw new Error('BVH层级无效')
  }
  if (
    depth !== 0 ||
    maxDepth > 64 ||
    (text.match(/\b(?:JOINT|ROOT)\b/g)?.length ?? 0) > 128 ||
    !Number.isInteger(count) ||
    count < 2 ||
    count > 18000 ||
    !Number.isFinite(step) ||
    step <= 0 ||
    (count - 1) * step > 60
  )
    throw new Error('BVH须为60秒以内的人形骨架，最多128个关节')
  const [{ BVHLoader }, THREE] = await Promise.all([
    import('three/examples/jsm/loaders/BVHLoader.js'),
    import('three')
  ])
  let parsed: ReturnType<InstanceType<typeof BVHLoader>['parse']>
  try {
    parsed = new BVHLoader().parse(text)
  } catch {
    throw new Error('BVH格式不完整，请使用标准分行的人形BVH文件')
  }
  const { clip, skeleton } = parsed
  const root = skeleton.bones[0],
    mixer = new THREE.AnimationMixer(root)
  mixer.clipAction(clip).setLoop(THREE.LoopOnce, 1).play().clampWhenFinished = true
  const aliases: Record<string, string[]> = {
    headX: ['head'],
    spine: ['spine', 'lowerback'],
    chest: ['spine1', 'chest', 'upperback'],
    leftShoulder: ['leftarm', 'leftshoulder', 'larm'],
    rightShoulder: ['rightarm', 'rightshoulder', 'rarm'],
    leftElbow: ['leftforearm', 'leftelbow', 'lforearm'],
    rightElbow: ['rightforearm', 'rightelbow', 'rforearm'],
    leftWrist: ['lefthand'],
    rightWrist: ['righthand'],
    leftHip: ['leftupleg', 'leftthigh', 'lthigh'],
    rightHip: ['rightupleg', 'rightthigh', 'rthigh'],
    leftKnee: ['leftleg', 'leftshin', 'lshin'],
    rightKnee: ['rightleg', 'rightshin', 'rshin'],
    leftAnkle: ['leftfoot'],
    rightAnkle: ['rightfoot']
  }
  const boneName = (s: string) =>
    s
      .toLowerCase()
      .replace(/^mixamorig[:_]?/, '')
      .replace(/[^a-z]/g, '')
  const mapped = Object.entries(aliases)
    .map(([key, names]) => ({ key, bone: skeleton.bones.find((b) => names.includes(boneName(b.name))) }))
    .filter((v) => v.bone)
  if (
    mapped.length < 6 ||
    !mapped.some((v) => v.key === 'leftHip') ||
    !mapped.some((v) => v.key === 'rightHip')
  )
    throw new Error('未识别到标准人形BVH骨架，请使用Hips/LeftUpLeg等命名')
  const duration = (count - 1) * step,
    frames = []
  const clamp = (v: number, min = -180, max = 180) => Math.max(min, Math.min(max, v))
  root.updateMatrixWorld(true)
  const bounds = new THREE.Box3()
  for (const bone of skeleton.bones) bounds.expandByPoint(bone.getWorldPosition(new THREE.Vector3()))
  const size = bounds.getSize(new THREE.Vector3()).y
  mixer.setTime(0)
  const restY = root.position.y,
    scale = size > 0 ? 2 / size : 1
  for (let i = 0, n = Math.ceil(duration * 30); i <= n; i++) {
    const time = i === n ? duration : (duration * i) / n
    mixer.setTime(time)
    const pose: Record<string, number> = { rootHeight: clamp((root.position.y - restY) * scale, -1.5, 0.5) }
    for (const { key, bone } of mapped) {
      const e = new THREE.Euler().setFromQuaternion(bone!.quaternion, 'YXZ')
      const angle = THREE.MathUtils.radToDeg(e.x)
      pose[key] = clamp(
        key.includes('Knee') || key.includes('Elbow') ? Math.abs(angle) : key === 'spine' ? -angle : angle
      )
      if (key === 'headX') pose.headY = clamp(THREE.MathUtils.radToDeg(e.y))
    }
    frames.push({ time, pose })
  }
  mixer.stopAllAction()
  mixer.uncacheRoot(root)
  const asset: DirectorActionAsset = {
    id: studioId('motion'),
    name: name.replace(/\.bvh$/i, '').slice(0, 80) || '导入动作',
    source: 'bvh',
    duration,
    loop: true,
    frames
  }
  if (!validDirectorActionAsset(asset)) throw new Error('BVH动作转换失败')
  return asset
}
export function downloadDirectorActionTemplate() {
  const url = URL.createObjectURL(
    new Blob(
      [
        JSON.stringify(
          {
            kind: 'aid-director-action',
            version: 1,
            action: {
              name: '缓慢点头',
              duration: 3,
              loop: true,
              frames: [
                { time: 0, pose: { headX: 0 } },
                { time: 1.5, pose: { headX: 25 } },
                { time: 3, pose: { headX: 0 } }
              ]
            }
          },
          null,
          2
        )
      ],
      { type: 'application/json' }
    )
  )
  const a = document.createElement('a')
  a.href = url
  a.download = '点头.aid-action.json'
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
