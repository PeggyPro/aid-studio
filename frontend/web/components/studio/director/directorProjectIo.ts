import { validateDirectorObjectExtras, validateDirectorBookmarkExtras, validateDirectorLensSpec, validateDirectorSceneExtras } from '@/utils/studio/directorPrevizValidation'
import { persistentDirectorScene } from '@/utils/studio/directorApi'
import type {
  DirectorCamera,
  DirectorCameraBookmark,
  DirectorCapture,
  DirectorLight,
  DirectorModelAsset,
  DirectorObject,
  DirectorSceneData,
  DirectorTimeline,
  DirectorTransform
} from '@/types/studio'
import { normalizeDirectorScene } from '@/utils/studio/directorScene'
import { DIRECTOR_ENVIRONMENTS, DIRECTOR_WEATHERS } from '@/utils/studio/directorEnvironment'
import { normalizeDirectorTimeline } from '@/utils/studio/directorTimeline'
import { restoreDirectorModelAsset } from '@/utils/studio/directorModelCatalog'

export const DIRECTOR_PROJECT_KIND = 'aid-director-project'
export const DIRECTOR_PROJECT_VERSION = 2
export const MAX_DIRECTOR_PROJECT_BYTES = 2 * 1024 * 1024

interface DirectorProjectEnvelope {
  kind: typeof DIRECTOR_PROJECT_KIND
  schemaVersion: 2 | 3
  version: typeof DIRECTOR_PROJECT_VERSION | 3
  exportedAt: string
  scene: DirectorSceneData
}

export function serializeDirectorProject(scene: DirectorSceneData): string {
  const multiPackage = new Set([...(scene.scenePackages ?? []).map(pkg => pkg.mediaId), ...(scene.scenePackage ? [scene.scenePackage.mediaId] : [])]).size > 1
  const version = multiPackage || scene.modelAssets?.some(asset => asset.format === 'glb' || asset.source === 'builtin') || scene.spaceGenJob?.submission || scene.actionLibrary?.length || scene.timeline?.actionClips?.some(c => c.action === 'imported' || !['stand','walk','run','crouch','crouch_cry','sit','wave','pose'].includes(c.action)) ? 3 : DIRECTOR_PROJECT_VERSION
  const envelope: DirectorProjectEnvelope = {
    kind: DIRECTOR_PROJECT_KIND,
    version,
    schemaVersion: version,
    exportedAt: new Date().toISOString(),
    scene: persistentDirectorScene(normalizeDirectorScene(scene))
  }
  const text = JSON.stringify(envelope, null, 2)
  if (new TextEncoder().encode(text).byteLength > MAX_DIRECTOR_PROJECT_BYTES) {
    throw new Error('导演工程内容超过 2MB，请先清理较大的截图记录')
  }
  return text
}

export function parseDirectorProjectText(text: string, maximumBytes = MAX_DIRECTOR_PROJECT_BYTES): DirectorSceneData {
  if (new TextEncoder().encode(text).byteLength > maximumBytes) {
    throw new Error('导演工程文件不能超过 2MB')
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    throw new Error('导演工程 JSON 格式无效')
  }
  const envelope = readRecord(parsed, '导演工程')
  if (envelope.kind !== DIRECTOR_PROJECT_KIND) throw new Error('不是有效的 AID 导演工程文件')
  if (![1, 2, 3].includes(Number(envelope.schemaVersion ?? envelope.version))) throw new Error(`暂不支持导演工程版本 ${String(envelope.version)}`)
  return normalizeDirectorScene(validateDirectorSceneExtras(envelope.scene, readScene(envelope.scene)))
}

export async function readDirectorProjectFile(file: File): Promise<DirectorSceneData> {
  if (file.size <= 0) throw new Error('导演工程文件内容为空')
  if (file.size > MAX_DIRECTOR_PROJECT_BYTES) throw new Error('导演工程文件不能超过 2MB')
  if (!file.name.toLowerCase().endsWith('.json')) throw new Error('请选择 JSON 格式的导演工程文件')
  return parseDirectorProjectText(await file.text())
}

export function downloadDirectorProject(scene: DirectorSceneData): void {
  const blob = new Blob([serializeDirectorProject(scene)], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${safeFileName(scene.name || 'director-project')}.aid-director.json`
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}

function readScene(value: unknown): DirectorSceneData {
  const scene = readRecord(value, 'scene')
  const id = readString(scene.id, 'scene.id')
  const objects = readArray(scene.objects, 'scene.objects', 2000).map(readObject)
  const lights = readArray(scene.lights, 'scene.lights', 128).map(readLight)
  const cameraBookmarks = readArray(scene.cameraBookmarks ?? [], 'scene.cameraBookmarks', 64).map(readBookmark)
  const modelAssets = readArray(scene.modelAssets ?? [], 'scene.modelAssets', 100).map(readModelAsset)
  const captures = readArray(scene.captures ?? [], 'scene.captures', 100).map(readCapture)
  const timeline = scene.timeline === undefined ? undefined : readTimeline(scene.timeline)
  assertUniqueIds(objects, 'scene.objects')
  assertUniqueIds(lights, 'scene.lights')
  assertUniqueIds(modelAssets, 'scene.modelAssets')
  assertUniqueIds(captures, 'scene.captures')
  assertUniqueIds(cameraBookmarks, 'scene.cameraBookmarks')
  if (cameraBookmarks.some((item) => item.id === 'main-camera')) throw new Error('scene.cameraBookmarks 不能使用保留 ID main-camera')
  const entityIds = new Set(['main-camera', ...objects.map((item) => item.id)])
  cameraBookmarks.forEach((item) => {
    if (entityIds.has(item.id)) throw new Error(`场景实体 ID 重复：${item.id}`)
    entityIds.add(item.id)
  })
  const assetIds = new Set(modelAssets.map((item) => item.id))
  objects.forEach((object) => {
    if (object.modelAssetId && !assetIds.has(object.modelAssetId)) throw new Error(`对象 ${object.id} 引用了不存在的模型资产`)
  })
  const objectIds = new Set(objects.map((item) => item.id))
  const mainCameraPresent = scene.mainCameraPresent === false ? false : undefined
  const cameraIds = new Set([...(mainCameraPresent === false ? [] : ['main-camera']), ...cameraBookmarks.map((item) => item.id)])
  timeline?.tracks.forEach((track) => {
    if (!objectIds.has(track.objectId)) throw new Error(`物体轨道 ${track.objectId} 引用了不存在的对象`)
  })
  timeline?.cameraTracks.forEach((track) => {
    if (!cameraIds.has(track.cameraId)) throw new Error(`相机轨道 ${track.cameraId} 引用了不存在的机位`)
  })
  timeline?.poseTracks.forEach((track) => {
    if (!objectIds.has(track.objectId)) throw new Error(`姿态轨道 ${track.objectId} 引用了不存在的对象`)
  })
  const activeCameraId = readOptionalString(scene.activeCameraId, 'scene.activeCameraId')
  return {
    id,
    name: readString(scene.name, 'scene.name'),
    objects,
    lights,
    camera: readCamera(scene.camera, 'scene.camera'),
    environmentColor: readString(scene.environmentColor, 'scene.environmentColor'),
    environmentPreset: readOptionalEnum(scene.environmentPreset, DIRECTOR_ENVIRONMENTS, 'scene.environmentPreset'),
    weather: readOptionalEnum(scene.weather, DIRECTOR_WEATHERS, 'scene.weather'),
    gridVisible: readOptionalBoolean(scene.gridVisible, 'scene.gridVisible'),
    gridSnap: readOptionalBoolean(scene.gridSnap, 'scene.gridSnap'),
    groundVisible: readOptionalBoolean(scene.groundVisible, 'scene.groundVisible'),
    groundOpacity: readOptionalNumberInRange(scene.groundOpacity, 'scene.groundOpacity', 0, 1),
    aspectRatio: readOptionalEnum(scene.aspectRatio, ['adaptive', '21:9', '16:9', '4:3', '1:1', '3:4', '9:16'], 'scene.aspectRatio'),
    viewMode: readOptionalEnum(scene.viewMode, ['director', 'camera'], 'scene.viewMode'),
    directorCamera: scene.directorCamera === undefined ? undefined : readCamera(scene.directorCamera, 'scene.directorCamera'),
    cameraBookmarks,
    modelAssets,
    mainCameraPresent,
    mainCameraVisible: readOptionalBoolean(scene.mainCameraVisible, 'scene.mainCameraVisible'),
    mainCameraLocked: readOptionalBoolean(scene.mainCameraLocked, 'scene.mainCameraLocked'),
    mainCameraLens: scene.mainCameraLens === undefined ? undefined : validateDirectorLensSpec(scene.mainCameraLens),
    mainCameraAimObjectId: readOptionalString(scene.mainCameraAimObjectId, 'scene.mainCameraAimObjectId'),
    activeCameraId: activeCameraId && cameraIds.has(activeCameraId) ? activeCameraId : cameraIds.values().next().value,
    panoramaUrl: readOptionalMediaUrl(scene.panoramaUrl, 'scene.panoramaUrl'),
    panoramaHistory: readArray(scene.panoramaHistory ?? [], 'scene.panoramaHistory', 24).map((item, index) => readMediaUrl(item, `scene.panoramaHistory[${index}]`)),
    panoramaYaw: readOptionalNumberInRange(scene.panoramaYaw, 'scene.panoramaYaw', -360, 360),
    panoramaRadius: readOptionalNumberInRange(scene.panoramaRadius, 'scene.panoramaRadius', 12, 200),
    captures,
    timeline,
    prompt: readOptionalString(scene.prompt, 'scene.prompt'),
    sourceNodeIds: readArray(scene.sourceNodeIds ?? [], 'scene.sourceNodeIds', 2000).map((item, index) => readString(item, `scene.sourceNodeIds[${index}]`)),
    previewImageUrl: readOptionalMediaUrl(scene.previewImageUrl, 'scene.previewImageUrl'),
    updatedAt: readString(scene.updatedAt, 'scene.updatedAt')
  }
}

function readObject(value: unknown, index: number): DirectorObject {
  const object = readRecord(value, `scene.objects[${index}]`)
  const kind = readEnum(object.kind, ['actor', 'prop', 'scenery'], `scene.objects[${index}].kind`)
  return {
    ...validateDirectorObjectExtras(object),
    id: readString(object.id, `scene.objects[${index}].id`),
    kind,
    name: readString(object.name, `scene.objects[${index}].name`),
    color: readString(object.color, `scene.objects[${index}].color`),
    shape: readOptionalEnum(object.shape, ['box', 'sphere', 'cylinder', 'torus', 'cone', 'pyramid', 'capsule'], `scene.objects[${index}].shape`),
    sourceNodeId: readOptionalString(object.sourceNodeId, `scene.objects[${index}].sourceNodeId`),
    referenceImageUrl: readOptionalMediaUrl(object.referenceImageUrl, `scene.objects[${index}].referenceImageUrl`),
    modelAssetId: readOptionalString(object.modelAssetId, `scene.objects[${index}].modelAssetId`),
    crowdGroupId: readOptionalString(object.crowdGroupId, `scene.objects[${index}].crowdGroupId`),
    visible: readOptionalBoolean(object.visible, `scene.objects[${index}].visible`),
    locked: readOptionalBoolean(object.locked, `scene.objects[${index}].locked`),
    transform: readTransform(object.transform, `scene.objects[${index}].transform`),
    pose: readNumberRecord(object.pose, `scene.objects[${index}].pose`, -360, 360)
  }
}

function readLight(value: unknown, index: number): DirectorLight {
  const light = readRecord(value, `scene.lights[${index}]`)
  return {
    id: readString(light.id, `scene.lights[${index}].id`),
    kind: readEnum(light.kind, ['ambient', 'directional', 'point', 'spot'], `scene.lights[${index}].kind`),
    name: readString(light.name, `scene.lights[${index}].name`),
    color: readString(light.color, `scene.lights[${index}].color`),
    intensity: readNumberInRange(light.intensity, `scene.lights[${index}].intensity`, 0, 1000),
    position: readTupleInRange(light.position, `scene.lights[${index}].position`, -100_000, 100_000),
    enabled: readBoolean(light.enabled, `scene.lights[${index}].enabled`)
  }
}

function readBookmark(value: unknown, index: number): DirectorCameraBookmark {
  const bookmark = readRecord(value, `scene.cameraBookmarks[${index}]`)
  return {
    ...validateDirectorBookmarkExtras(bookmark),
    id: readString(bookmark.id, `scene.cameraBookmarks[${index}].id`),
    name: readString(bookmark.name, `scene.cameraBookmarks[${index}].name`),
    camera: readCamera(bookmark.camera, `scene.cameraBookmarks[${index}].camera`),
    preset: readOptionalString(bookmark.preset, `scene.cameraBookmarks[${index}].preset`),
    thumbnailUrl: readOptionalMediaUrl(bookmark.thumbnailUrl, `scene.cameraBookmarks[${index}].thumbnailUrl`),
    visible: readOptionalBoolean(bookmark.visible, `scene.cameraBookmarks[${index}].visible`),
    locked: readOptionalBoolean(bookmark.locked, `scene.cameraBookmarks[${index}].locked`),
    createdAt: readOptionalString(bookmark.createdAt, `scene.cameraBookmarks[${index}].createdAt`)
  }
}

function readModelAsset(value: unknown, index: number): DirectorModelAsset {
  const asset = readRecord(value, `scene.modelAssets[${index}]`)
  if (asset.resourceId && asset.bundle) {
    const bundle = readRecord(asset.bundle, 'model.bundle')
    const resourceUrl = (value: unknown) => { const url = readString(value, 'model.resourceUrl'); if (!/^https?:\/\//.test(url) && !/^\/(?!\/)/.test(url)) throw new Error('模型资源地址无效'); return url }
    return {
      id: readString(asset.id, 'model.id'), name: readString(asset.name, 'model.name'), fileName: readString(asset.fileName, 'model.fileName'),
      url: resourceUrl(asset.url), format: readEnum(asset.format, ['glb','gltf','fbx','obj'], 'model.format'),
      source: readEnum(asset.source, ['local','library','builtin'], 'model.source'), kind: readEnum(asset.kind, ['actor','prop'], 'model.kind'),
      resourceId: readString(asset.resourceId, 'model.resourceId'), createdAt: readString(asset.createdAt, 'model.createdAt'),
      byteSize: readOptionalNumberInRange(asset.byteSize, 'model.byteSize', 1, 64*1024*1024),
      bundle: {
        format: readEnum(bundle.format, ['glb','gltf','fbx','obj'], 'bundle.format'), rootPath: readString(bundle.rootPath, 'bundle.rootPath'),
        files: readArray(bundle.files, 'bundle.files', 256).map(item=>{const file=readRecord(item,'bundle.file');return {path:readString(file.path,'bundle.path'),resourceId:readString(file.resourceId,'bundle.resourceId'),resourceUrl:resourceUrl(file.resourceUrl)}}),
        references: readArray(bundle.references, 'bundle.references', 1024).map(item=>{const ref=readRecord(item,'bundle.reference');return {ownerPath:readString(ref.ownerPath,'bundle.ownerPath'),uri:readString(ref.uri,'bundle.uri'),path:readString(ref.path,'bundle.path')}})
      }
    }
  }
  if (asset.source === 'builtin') {
    // Rebind the stable catalog identity to this deployment, never trust an imported URL.
    return restoreDirectorModelAsset({
      id: readString(asset.id, `scene.modelAssets[${index}].id`), source: 'builtin',
      format: readEnum(asset.format, ['glb'], `scene.modelAssets[${index}].format`),
      kind: readEnum(asset.kind, ['prop'], `scene.modelAssets[${index}].kind`),
      name: '', fileName: '', url: '', createdAt: readString(asset.createdAt, `scene.modelAssets[${index}].createdAt`)
    })
  }
  return {
    id: readString(asset.id, `scene.modelAssets[${index}].id`),
    name: readString(asset.name, `scene.modelAssets[${index}].name`),
    fileName: readString(asset.fileName, `scene.modelAssets[${index}].fileName`),
    url: readRemoteUrl(asset.url, `scene.modelAssets[${index}].url`),
    format: readEnum(asset.format, ['fbx', 'obj', 'glb'], `scene.modelAssets[${index}].format`),
    kind: readEnum(asset.kind, ['actor', 'prop'], `scene.modelAssets[${index}].kind`),
    source: readEnum(asset.source, ['local', 'library'], `scene.modelAssets[${index}].source`),
    byteSize: readOptionalNumberInRange(asset.byteSize, `scene.modelAssets[${index}].byteSize`, 1, 20 * 1024 * 1024),
    createdAt: readString(asset.createdAt, `scene.modelAssets[${index}].createdAt`)
  }
}

function readCapture(value: unknown, index: number): DirectorCapture {
  const capture = readRecord(value, `scene.captures[${index}]`)
  return {
    id: readString(capture.id, `scene.captures[${index}].id`),
    name: readString(capture.name, `scene.captures[${index}].name`),
    mediaUrl: readMediaUrl(capture.mediaUrl, `scene.captures[${index}].mediaUrl`),
    camera: readCamera(capture.camera, `scene.captures[${index}].camera`),
    createdAt: readString(capture.createdAt, `scene.captures[${index}].createdAt`)
  }
}

function readTimeline(value: unknown): DirectorTimeline {
  const timeline = readRecord(value, 'scene.timeline')
  const duration = readNumberInRange(timeline.duration, 'scene.timeline.duration', 0.01, 86_400)
  const currentTime = readNumberInRange(timeline.currentTime ?? 0, 'scene.timeline.currentTime', 0, duration)
  const hasTracks = (Array.isArray(timeline.tracks) && timeline.tracks.length > 0)
    || (Array.isArray(timeline.cameraTracks) && timeline.cameraTracks.length > 0)
    || (Array.isArray(timeline.poseTracks) && timeline.poseTracks.length > 0)

  if (hasTracks) {
    const tracks = readArray(timeline.tracks ?? [], 'scene.timeline.tracks', 2_000).map((value, index) => {
      const track = readRecord(value, `scene.timeline.tracks[${index}]`)
      const keyframes = readArray(track.keyframes, `scene.timeline.tracks[${index}].keyframes`, 10_000).map((item, keyIndex) => {
        const keyframe = readRecord(item, `scene.timeline.tracks[${index}].keyframes[${keyIndex}]`)
        const keyValue = readRecord(keyframe.value, `scene.timeline.tracks[${index}].keyframes[${keyIndex}].value`)
        return {
          id: readString(keyframe.id, `scene.timeline.tracks[${index}].keyframes[${keyIndex}].id`),
          time: readNumberInRange(keyframe.time, `scene.timeline.tracks[${index}].keyframes[${keyIndex}].time`, 0, duration),
          value: {
            position: readTupleInRange(keyValue.position, `scene.timeline.tracks[${index}].keyframes[${keyIndex}].value.position`, -100_000, 100_000),
            rotation: readTupleInRange(keyValue.rotation, `scene.timeline.tracks[${index}].keyframes[${keyIndex}].value.rotation`, -10_000, 10_000),
            scale: readTupleInRange(keyValue.scale, `scene.timeline.tracks[${index}].keyframes[${keyIndex}].value.scale`, 0.0001, 1000)
          }
        }
      })
      assertUniqueIds(keyframes, `scene.timeline.tracks[${index}].keyframes`)
      return {
        objectId: readString(track.objectId, `scene.timeline.tracks[${index}].objectId`),
        keyframes
      }
    })
    const cameraTracks = readArray(timeline.cameraTracks ?? [], 'scene.timeline.cameraTracks', 256).map((value, index) => {
      const track = readRecord(value, `scene.timeline.cameraTracks[${index}]`)
      const keyframes = readArray(track.keyframes, `scene.timeline.cameraTracks[${index}].keyframes`, 10_000).map((item, keyIndex) => {
        const keyframe = readRecord(item, `scene.timeline.cameraTracks[${index}].keyframes[${keyIndex}]`)
        const keyValue = readRecord(keyframe.value, `scene.timeline.cameraTracks[${index}].keyframes[${keyIndex}].value`)
        const fov = readNumber(keyValue.fov, `scene.timeline.cameraTracks[${index}].keyframes[${keyIndex}].value.fov`)
        if (fov <= 0 || fov >= 180) throw new Error(`scene.timeline.cameraTracks[${index}].keyframes[${keyIndex}].value.fov 必须在 0 到 180 之间`)
        return {
          id: readString(keyframe.id, `scene.timeline.cameraTracks[${index}].keyframes[${keyIndex}].id`),
          time: readNumberInRange(keyframe.time, `scene.timeline.cameraTracks[${index}].keyframes[${keyIndex}].time`, 0, duration),
          value: {
            position: readTupleInRange(keyValue.position, `scene.timeline.cameraTracks[${index}].keyframes[${keyIndex}].value.position`, -100_000, 100_000),
            target: readTupleInRange(keyValue.target, `scene.timeline.cameraTracks[${index}].keyframes[${keyIndex}].value.target`, -100_000, 100_000),
            fov,
            roll: readOptionalNumberInRange(keyValue.roll, `scene.timeline.cameraTracks[${index}].keyframes[${keyIndex}].value.roll`, -720, 720),
          }
        }
      })
      assertUniqueIds(keyframes, `scene.timeline.cameraTracks[${index}].keyframes`)
      return {
        cameraId: readString(track.cameraId, `scene.timeline.cameraTracks[${index}].cameraId`),
        keyframes
      }
    })
    const poseTracks = readArray(timeline.poseTracks ?? [], 'scene.timeline.poseTracks', 2_000).map((value, index) => {
      const track = readRecord(value, `scene.timeline.poseTracks[${index}]`)
      const keyframes = readArray(track.keyframes, `scene.timeline.poseTracks[${index}].keyframes`, 10_000).map((item, keyIndex) => {
        const keyframe = readRecord(item, `scene.timeline.poseTracks[${index}].keyframes[${keyIndex}]`)
        return {
          id: readString(keyframe.id, `scene.timeline.poseTracks[${index}].keyframes[${keyIndex}].id`),
          time: readNumberInRange(keyframe.time, `scene.timeline.poseTracks[${index}].keyframes[${keyIndex}].time`, 0, duration),
          value: readNumberRecord(keyframe.value, `scene.timeline.poseTracks[${index}].keyframes[${keyIndex}].value`, -360, 360)
        }
      })
      assertUniqueIds(keyframes, `scene.timeline.poseTracks[${index}].keyframes`)
      return {
        objectId: readString(track.objectId, `scene.timeline.poseTracks[${index}].objectId`),
        keyframes
      }
    })
    return normalizeDirectorTimeline({ duration, currentTime, tracks, cameraTracks, poseTracks })
  }

  const keyframes = readArray(timeline.keyframes ?? [], 'scene.timeline.keyframes', 10_000).map((value, index) => {
    const keyframe = readRecord(value, `scene.timeline.keyframes[${index}]`)
    return {
      id: readString(keyframe.id, `scene.timeline.keyframes[${index}].id`),
      objectId: readString(keyframe.objectId, `scene.timeline.keyframes[${index}].objectId`),
      time: readNumberInRange(keyframe.time, `scene.timeline.keyframes[${index}].time`, 0, duration),
      transform: readTransform(keyframe.transform, `scene.timeline.keyframes[${index}].transform`)
    }
  })
  assertUniqueIds(keyframes, 'scene.timeline.keyframes')
  return normalizeDirectorTimeline({ duration, currentTime, keyframes, tracks: [], cameraTracks: [], poseTracks: [] })
}

function readTransform(value: unknown, path: string): DirectorTransform {
  const transform = readRecord(value, path)
  return {
    position: readTupleInRange(transform.position, `${path}.position`, -100_000, 100_000),
    rotation: readTupleInRange(transform.rotation, `${path}.rotation`, -10_000, 10_000),
    scale: readTupleInRange(transform.scale, `${path}.scale`, 0.0001, 1000)
  }
}

function readCamera(value: unknown, path: string): DirectorCamera {
  const camera = readRecord(value, path)
  const fov = readNumber(camera.fov, `${path}.fov`)
  if (fov <= 0 || fov >= 180) throw new Error(`${path}.fov 必须在 0 到 180 之间`)
  return {
    position: readTupleInRange(camera.position, `${path}.position`, -100_000, 100_000),
    target: readTupleInRange(camera.target, `${path}.target`, -100_000, 100_000),
    fov,
    roll: readOptionalNumberInRange(camera.roll, `${path}.roll`, -720, 720),
  }
}

function readRecord(value: unknown, path: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${path} 必须是对象`)
  return value as Record<string, unknown>
}

function readArray(value: unknown, path: string, max: number): unknown[] {
  if (!Array.isArray(value)) throw new Error(`${path} 必须是数组`)
  if (value.length > max) throw new Error(`${path} 最多包含 ${max} 项`)
  return value
}

function readString(value: unknown, path: string): string {
  if (typeof value !== 'string' || !value.trim() || value.length > 2_000_000) throw new Error(`${path} 必须是有效文本`)
  return value
}

function readOptionalString(value: unknown, path: string): string | undefined {
  if (value === undefined) return undefined
  if (typeof value !== 'string' || value.length > 2_000_000) throw new Error(`${path} 必须是有效文本`)
  return value
}

function readNumber(value: unknown, path: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error(`${path} 必须是有限数值`)
  return value
}

function readNumberInRange(value: unknown, path: string, minimum: number, maximum: number): number {
  const number = readNumber(value, path)
  if (number < minimum || number > maximum) throw new Error(`${path} 必须在 ${minimum} 到 ${maximum} 之间`)
  return number
}

function readOptionalNumberInRange(value: unknown, path: string, minimum: number, maximum: number): number | undefined {
  if (value === undefined) return undefined
  const number = readNumber(value, path)
  if (number < minimum || number > maximum) throw new Error(`${path} 必须在 ${minimum} 到 ${maximum} 之间`)
  return number
}

function readBoolean(value: unknown, path: string): boolean {
  if (typeof value !== 'boolean') throw new Error(`${path} 必须是布尔值`)
  return value
}

function readOptionalBoolean(value: unknown, path: string): boolean | undefined {
  return value === undefined ? undefined : readBoolean(value, path)
}

function readTuple(value: unknown, path: string): [number, number, number] {
  const items = readArray(value, path, 3)
  if (items.length !== 3) throw new Error(`${path} 必须包含 3 个数值`)
  return items.map((item, index) => readNumber(item, `${path}[${index}]`)) as [number, number, number]
}

function readTupleInRange(value: unknown, path: string, minimum: number, maximum: number): [number, number, number] {
  return readTuple(value, path).map((item, index) => readNumberInRange(item, `${path}[${index}]`, minimum, maximum)) as [number, number, number]
}

function readNumberRecord(value: unknown, path: string, minimum: number, maximum: number): Record<string, number> {
  const record = readRecord(value, path)
  if (Object.keys(record).length > 100) throw new Error(`${path} 字段过多`)
  return Object.fromEntries(Object.entries(record).map(([key, item]) => [key, readNumberInRange(item, `${path}.${key}`, minimum, maximum)]))
}

function readEnum<const T extends string>(value: unknown, values: readonly T[], path: string): T {
  if (typeof value !== 'string' || !values.includes(value as T)) throw new Error(`${path} 取值无效`)
  return value as T
}

function readOptionalEnum<const T extends string>(value: unknown, values: readonly T[], path: string): T | undefined {
  return value === undefined ? undefined : readEnum(value, values, path)
}

function assertUniqueIds(items: Array<{ id: string }>, path: string): void {
  const ids = new Set<string>()
  items.forEach((item, index) => {
    if (ids.has(item.id)) throw new Error(`${path}[${index}].id 重复：${item.id}`)
    ids.add(item.id)
  })
}

function readRemoteUrl(value: unknown, path: string): string {
  const raw = readString(value, path).trim()
  if (raw.length > 8192) throw new Error(`${path} URL 过长`)
  let parsed: URL
  try {
    parsed = new URL(raw)
  } catch {
    throw new Error(`${path} 必须是有效的 HTTP(S) URL`)
  }
  if ((parsed.protocol !== 'http:' && parsed.protocol !== 'https:') || parsed.username || parsed.password) {
    throw new Error(`${path} 必须是无用户凭据的 HTTP(S) URL`)
  }
  return raw
}

function readMediaUrl(value: unknown, path: string): string {
  if (typeof value === 'string' && /^data:image\/(?:png|jpe?g|webp|gif);base64,[a-z0-9+/=\s]+$/i.test(value)) return value
  if (typeof value === 'string' && /^data:image\/svg\+xml(?:;charset=utf-8)?,/i.test(value)) {
    if (value.length > 1_000_000) throw new Error(`${path} SVG 数据过大`)
    let svg: string
    try {
      svg = decodeURIComponent(value.slice(value.indexOf(',') + 1))
    } catch {
      throw new Error(`${path} SVG 数据无效`)
    }
    if (!/^\s*<svg\b/i.test(svg) || /<(?:script|foreignObject)\b|\son\w+\s*=|(?:href|src)\s*=\s*["'](?:javascript:|https?:|data:)|url\((?!#)/i.test(svg)) {
      throw new Error(`${path} SVG 数据包含不安全内容`)
    }
    return value
  }
  return readRemoteUrl(value, path)
}

function readOptionalMediaUrl(value: unknown, path: string): string | undefined {
  return value === undefined ? undefined : readMediaUrl(value, path)
}

function safeFileName(value: string): string {
  return value.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '-').trim().slice(0, 80) || 'director-project'
}
