import { Vector3 } from 'three'
import type { PrevizCommand, PrevizNode, PrevizScene, Vec3 } from '@/types/previz'
import { applyCommands, aspectNumber, createNode, uid } from './core'
import { glbObjectsForNode } from './glbObjects'
import { glbObject } from './glbObjectOverrides'
import { visibleObjectBounds } from './objectBounds'
import { buildRuntime, dataUrlBytes, inspectGlb } from './runtime'
import { PREVIZ_DIRECTOR_VIEW_ID } from './nativeCameras'

export type FollowCameraSubject = { nodeId: string; objectId?: string }
export type FollowCameraOptions = {
  subject: FollowCameraSubject
  view?: Pick<PrevizNode, 'localTransform' | 'camera'>
  shotId?: string
  signal: AbortSignal
  onProgress?: (progress: number) => void
  assertCurrent?: () => void
}

/** Offer only stable identities within the adopted scope. Never guess a subject from its name. */
export function followCameraSubjects(scene: PrevizScene, node: PrevizNode, selectedObjectId?: string) {
  const asset = scene.assets.find(asset => asset.id === node.assetId)
  const parts = glbObjectsForNode(node, asset)
  const byId = new Map(parts.map(part => [part.id, part]))
  const renderable = new Set<string>()
  for (const part of parts.filter(part => part.mesh)) {
    let current: typeof part | undefined = part
    while (current) { renderable.add(current.id); current = current.parentId ? byId.get(current.parentId) : undefined }
  }
  const choices = parts.filter(part => !part.bone && renderable.has(part.id))
  const items: { key: string; label: string; objectId?: string }[] = [{ key: '@whole', label: `整个对象 · ${node.name}` }, ...choices.map(part => ({ key: `part:${part.id}`, label: part.name, objectId: part.id }))]
  if (selectedObjectId && choices.some(part => part.id === selectedObjectId)) return { items, suggested: `part:${selectedObjectId}` }
  if (node.kind !== 'GLB' || !asset) return { items, suggested: '@whole' }
  // Root motion belongs to the whole instance. Otherwise inspect the selected native clip's
  // translation channels and local object tracks, excluding bones and out-of-scope objects.
  if (scene.timeline.tracks.some(track => track.nodeId === node.id && !track.objectId && track.channel === 'position')) return { items, suggested: '@whole' }
  const document = inspectGlb(dataUrlBytes(asset.dataUrl)) as {
    animations?: { channels?: { target?: { node?: number; path?: string } }[] }[]
  }
  const moving = new Set(scene.timeline.tracks.filter(track => track.nodeId === node.id && track.objectId && track.channel === 'position').map(track => track.objectId!))
  const clips = node.assetPlayback ? [document.animations?.[node.assetPlayback.clipIndex]] : document.animations ?? []
  for (const clip of clips) for (const channel of clip?.channels ?? []) {
    if (channel.target?.path !== 'translation') continue
    const part = choices.find(part => part.index === channel.target?.node)
    if (part) moving.add(part.id)
  }
  const roots = choices.filter(part => {
    if (!moving.has(part.id)) return false
    let parent = part.parentId ? byId.get(part.parentId) : undefined
    while (parent) { if (moving.has(parent.id)) return false; parent = parent.parentId ? byId.get(parent.parentId) : undefined }
    return true
  })
  return { items, suggested: roots.length === 1 ? `part:${roots[0].id}` : choices.length <= 1 ? choices[0] ? `part:${choices[0].id}` : '@whole' : '' }
}

/** Bake actual evaluated world-space motion into the existing editable camera track contract.
 * The isolated runtime includes native clips, parents, scopes, skinning and editor overrides.
 * It never seeks/mutates the live viewport or creates a partial camera in the authored scene. */
export async function createFollowCamera(scene: PrevizScene, options: FollowCameraOptions) {
  const check = () => { options.signal.throwIfAborted(); options.assertCurrent?.() }
  check()
  const source = scene.nodes.find(node => node.id === options.subject.nodeId)
  if (!source || ['CAMERA', 'LIGHT', 'PLANE'].includes(source.kind)) throw new Error('请先选择需要跟随的模型或角色')
  if (scene.timeline.frameCount > 1800) throw new Error('跟随机位支持最长 60 秒，请先调整预演时长')
  if (options.subject.objectId && !followCameraSubjects(scene, source).items.some(item => item.objectId === options.subject.objectId)) throw new Error('跟随对象已变化或不在当前模型范围内')
  const runtime = await buildRuntime(scene, { batch: false, skipEnvironment: true })
  try {
    check()
    const root = runtime.objects.get(source.id)!
    const subject = options.subject.objectId ? glbObject(root, options.subject.objectId) : root
    if (!subject) throw new Error('无法读取所选模型内部对象，请重新选择')
    const centers: Vec3[] = []
    let radius = 0
    for (let frame = 0; frame < scene.timeline.frameCount; frame++) {
      check()
      runtime.frame(frame)
      for (let ancestor = subject; ancestor; ancestor = ancestor.parent!) if (!ancestor.visible) throw new Error('跟随对象已隐藏，请先显示该对象')
      const bounds = visibleObjectBounds(subject)
      if (bounds.isEmpty()) throw new Error('所选对象没有可见模型，请选择角色或物体')
      const center = bounds.getCenter(new Vector3()), size = bounds.getSize(new Vector3())
      if (![...center.toArray(), ...size.toArray()].every(Number.isFinite)) throw new Error('模型运动坐标无效，无法生成跟随机位')
      centers.push(center.toArray())
      radius = Math.max(radius, size.length() / 2)
      if (frame % 12 === 0) {
        options.onProgress?.(frame / scene.timeline.frameCount)
        await new Promise<void>(resolve => setTimeout(resolve, 0))
      }
    }
    check()
    const first = new Vector3(...centers[0])
    if (!centers.some(center => first.distanceToSquared(new Vector3(...center)) > 0.000001)) throw new Error('当前时长内未检测到位移，请选择实际移动的角色或内部对象')
    const direction = options.view?.camera
      ? new Vector3(...options.view.localTransform.position).sub(new Vector3(...options.view.camera.target))
      : new Vector3(1, 0.6, 1.5)
    if (!direction.toArray().every(Number.isFinite) || direction.lengthSq() < 0.000001) direction.set(1, 0.6, 1.5)
    // Fit the subject's largest pose in either landscape or portrait without zoom pumping.
    const fov = Math.max(15, Math.min(90, options.view?.camera?.fov ?? 45))
    const halfFov = Math.min(fov * Math.PI / 360, Math.atan(Math.tan(fov * Math.PI / 360) * aspectNumber(scene.aspectRatio)))
    const distance = Math.max(1, radius / Math.sin(halfFov) * 1.15)
    if (!Number.isFinite(distance) || distance + radius >= 10000) throw new Error('模型尺寸超出机位范围，请先检查模型单位和缩放')
    const offset = direction.normalize().multiplyScalar(distance)
    const label = options.subject.objectId ? glbObjectsForNode(source, scene.assets.find(asset => asset.id === source.assetId)).find(part => part.id === options.subject.objectId)?.name : source.name
    const camera = createNode('CAMERA', `${label || source.name} · 跟随机位`.slice(0, 120))
    camera.camera = { ...camera.camera!, fov, far: Math.min(10000, Math.max(200, (distance + radius) * 4)), target: centers[0] }
    camera.localTransform.position = first.add(offset).toArray()
    const tracks = (['position', 'target'] as const).map(channel => ({
      id: uid(), nodeId: camera.id, channel, interpolation: 'linear' as const,
      keys: centers.map((value, frame) => ({ frame, value: channel === 'target' ? value : new Vector3(...value).add(offset).toArray() as Vec3 })),
    }))
    const commands: PrevizCommand[] = [{ type: 'node.add', node: camera }, {
      type: 'timeline.replace', timeline: { ...scene.timeline, tracks: [...scene.timeline.tracks, ...tracks] },
      shots: scene.shots.map(shot => shot.id === options.shotId && shot.cameraId === PREVIZ_DIRECTOR_VIEW_ID ? { ...shot, cameraId: camera.id } : shot),
    }]
    applyCommands(scene, commands) // Validate the complete transaction before exposing it to the editor.
    check()
    options.onProgress?.(1)
    return { cameraId: camera.id, commands }
  } finally { runtime.dispose() }
}
