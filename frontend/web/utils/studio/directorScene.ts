import { PREVIZ_HUMAN_HEIGHT_METERS } from '@/utils/previz/sceneUnits'
import { DIRECTOR_PROP_HEIGHT, directorActorHeight } from './directorSceneUnits'
import { pruneDirectorPaths } from './directorPathClips'
import { normalizeDirectorPathPlane } from './directorPathDrawing'
import { DEFAULT_DIRECTOR_SCENE } from '@/config/studioMeta'
import { createDirectorEnvironmentLights, DIRECTOR_ENVIRONMENT_COLORS, type DirectorEnvironment, type DirectorWeather } from './directorEnvironment'
import {
  DIRECTOR_VIEWPORT_GROUND_OPACITY,
  DIRECTOR_VIEWPORT_GEOMETRY_COLOR,
  resolveDirectorCharacterColor
} from '@/components/studio/director/directorViewportTheme'
import { DIRECTOR_PANORAMA_DEFAULT_RADIUS } from '@/components/studio/director/directorPanorama'
import type {
  DirectorAspectRatio,
  DirectorCamera,
  DirectorCameraBookmark,
  DirectorLight,
  DirectorObject,
  DirectorPropShape,
  DirectorSceneData,
  DirectorTransform,
  StudioNodeData
} from '@/types/studio'
import { studioId } from './studioGraph'
import { resolveDirectorActiveCameraId } from '@/components/studio/director/directorCameraState'
import { restoreDirectorModelAsset } from './directorModelCatalog'
import { normalizeDirectorLensSpec } from './directorCameraCatalog'
import { DEFAULT_DIRECTOR_OBSERVER_CAMERA, normalizeDirectorCamera, resolveDirectorObserverCamera } from './directorCameraPose'
import { buildDirectorPosePresets } from '@/components/studio/director/directorPosePresets'
import {
  createDefaultDirectorTimeline,
  normalizeDirectorTimeline,
  removeDirectorTimelineTracksForObjects
} from './directorTimeline'

export {
  applyDirectorTimelineAt,
  sampleDirectorTransform,
  normalizeDirectorTimeline,
  createDefaultDirectorTimeline,
  countDirectorTimelineKeyframes
} from './directorTimeline'

export const DIRECTOR_JOINT_GROUPS = [
  {
    label: '躯干与头部',
    joints: ['neck', 'headX', 'headY', 'spine', 'chest']
  },
  {
    label: '左臂',
    joints: ['leftShoulder', 'leftElbow', 'leftWrist', 'leftThumb', 'leftFingers']
  },
  {
    label: '右臂',
    joints: ['rightShoulder', 'rightElbow', 'rightWrist', 'rightThumb', 'rightFingers']
  },
  {
    label: '腿部',
    joints: ['leftHip', 'leftKnee', 'leftAnkle', 'rightHip', 'rightKnee', 'rightAnkle']
  }
] as const

export const DIRECTOR_JOINT_LABELS: Record<string, string> = {
  neck: '颈部侧倾',
  headX: '头部俯仰',
  headY: '头部转向',
  spine: '腰部弯曲',
  chest: '胸腔转向',
  leftShoulder: '左肩',
  leftElbow: '左肘',
  leftWrist: '左腕',
  leftThumb: '左拇指',
  leftFingers: '左手指',
  rightShoulder: '右肩',
  rightElbow: '右肘',
  rightWrist: '右腕',
  rightThumb: '右拇指',
  rightFingers: '右手指',
  leftHip: '左髋',
  leftKnee: '左膝',
  leftAnkle: '左踝',
  rightHip: '右髋',
  rightKnee: '右膝',
  rightAnkle: '右踝'
}

export const DIRECTOR_POSE_PRESETS: Record<string, Record<string, number>> = buildDirectorPosePresets()

export const DIRECTOR_CAMERA_PRESETS: Record<string, DirectorCamera> = {
  当前视角: { position: [0, 1.55, 5.4], target: [0, 1.05, 0], fov: 50 },
  正面中景: { position: [0, 2.5, 7.2], target: [0, 1.35, 0], fov: 42 },
  正面特写: { position: [0, 2.15, 3.5], target: [0, 1.65, 0], fov: 36 },
  正面全景: { position: [0, 3.3, 11.5], target: [0, 1.25, 0], fov: 46 },
  侧面跟拍: { position: [8.5, 2.7, 0], target: [0, 1.35, 0], fov: 42 },
  侧面近景: { position: [4.2, 2.2, 0], target: [0, 1.55, 0], fov: 36 },
  背面中景: { position: [0, 2.6, -7.2], target: [0, 1.35, 0], fov: 42 },
  背面全景: { position: [0, 3.4, -11.5], target: [0, 1.25, 0], fov: 46 },
  '45° 斜拍': { position: [6.2, 3.1, 6.2], target: [0, 1.45, 0], fov: 40 },
  低角度仰拍: { position: [4.8, 0.7, 5.5], target: [0, 1.85, 0], fov: 48 },
  低角度广角: { position: [5.5, 0.55, 6.8], target: [0, 1.65, 0], fov: 64 },
  过肩镜头: { position: [2.2, 2.0, 3.2], target: [-0.5, 1.55, -0.6], fov: 38 },
  主观镜头: { position: [0, 1.72, 1.4], target: [0, 1.62, -3], fov: 52 },
  荷兰角: { position: [5.8, 3.2, 6.5], target: [0, 1.4, 0], fov: 44 }
}

export const DIRECTOR_ASPECT_RATIOS: Array<{ value: DirectorAspectRatio; label: string; ratio: number | null }> = [
  { value: 'adaptive', label: '自适应', ratio: null },
  { value: '21:9', label: '21:9', ratio: 21 / 9 },
  { value: '16:9', label: '16:9', ratio: 16 / 9 },
  { value: '4:3', label: '4:3', ratio: 4 / 3 },
  { value: '1:1', label: '1:1', ratio: 1 },
  { value: '3:4', label: '3:4', ratio: 3 / 4 },
  { value: '9:16', label: '9:16', ratio: 9 / 16 }
]

export const DIRECTOR_PROP_SHAPES: Array<{ value: DirectorPropShape; label: string }> = [
  { value: 'box', label: '立方体' },
  { value: 'sphere', label: '球体' },
  { value: 'cylinder', label: '圆柱体' },
  { value: 'torus', label: '环状体' },
  { value: 'cone', label: '圆锥' },
  { value: 'pyramid', label: '棱锥' },
  { value: 'capsule', label: '胶囊体' }
]

export function cloneDirectorScene(scene: DirectorSceneData): DirectorSceneData {
  return structuredClone(scene)
}

export function createDefaultDirectorScene(sceneId = studioId('director-scene')): DirectorSceneData {
  const scene = cloneDirectorScene(DEFAULT_DIRECTOR_SCENE)
  scene.id = sceneId
  scene.environmentPreset ??= 'studio'
  scene.gridVisible ??= true
  scene.gridSnap ??= false
  scene.groundVisible ??= true
  scene.groundOpacity ??= DIRECTOR_VIEWPORT_GROUND_OPACITY
  scene.aspectRatio ??= '16:9'
  scene.viewMode ??= 'director'
  scene.directorCamera ??= structuredClone(DEFAULT_DIRECTOR_OBSERVER_CAMERA)
  scene.cameraBookmarks ??= []
  scene.camera = normalizeDirectorCamera(scene.camera)
  scene.modelAssets ??= []
  scene.panoramaHistory ??= []
  scene.panoramaYaw ??= 0
  scene.panoramaRadius ??= DIRECTOR_PANORAMA_DEFAULT_RADIUS
  scene.captures ??= []
  scene.timeline ??= createDefaultDirectorTimeline()
  scene.timeline = normalizeDirectorTimeline(scene.timeline)
  scene.viewportPrefs ??= { shading: 'solid', cameraPreset: 'persp', showNameplates: false, timelineOpen: false, showMonitorPip: true }
  scene.prompt ??= ''
  scene.sourceNodeIds ??= []
  scene.updatedAt = new Date().toISOString()
  return scene
}

/** 为旧工作区补齐导演台新增字段，保留已存在的对象、机位与引用。 */
export function normalizeDirectorScene(input: DirectorSceneData): DirectorSceneData {
  const defaults = createDefaultDirectorScene(input.id)
  const mainCamera = normalizeDirectorCamera(input.camera ?? defaults.camera)
  const cameraBookmarks = (input.cameraBookmarks ?? []).map((bookmark) => ({
    ...bookmark,
    camera: normalizeDirectorCamera(bookmark.camera),
    lens: bookmark.lens ? normalizeDirectorLensSpec(bookmark.lens) : undefined,
    visible: bookmark.visible ?? true,
    locked: bookmark.locked ?? false
  }))
  const directorCamera = resolveDirectorObserverCamera(
    input.directorCamera ?? defaults.directorCamera,
    [...(input.mainCameraPresent === false ? [] : [mainCamera]), ...cameraBookmarks.map((bookmark) => bookmark.camera)],
  )
  const scene: DirectorSceneData = {
    ...defaults,
    ...cloneDirectorScene(input),
    weather: input.environmentPreset === 'studio' ? undefined : input.weather,
    viewportPrefs: { ...defaults.viewportPrefs!, ...input.viewportPrefs, pathDrawMode: false, pathDrawPlane: normalizeDirectorPathPlane(input.viewportPrefs?.pathDrawPlane) },
    objects: (input.objects ?? defaults.objects).filter(o => o.kind !== "scenery" || o.scenery).map((object) => ({
      ...object,
      kind: ['actor', 'prop', 'scenery'].includes(object.kind) ? object.kind : 'prop' as const,
      ...(object.kind === 'actor' ? { actorMeta: { heightMeters: Math.min(2.2, Math.max(0.8, directorActorHeight(object))), locomotion: 'idle' as const, identifyColor: object.color, ...object.actorMeta } } : {}),
      visible: object.visible ?? true,
      locked: object.locked ?? false
    })),
    lights: input.lights ?? defaults.lights,
    camera: mainCamera,
    directorCamera,
    mainCameraPresent: input.mainCameraPresent,
    mainCameraLens: input.mainCameraLens ? normalizeDirectorLensSpec(input.mainCameraLens) : undefined,
    mainCameraAimObjectId: input.mainCameraAimObjectId,
    cameraBookmarks,
    modelAssets: (input.modelAssets ?? []).map(restoreDirectorModelAsset),
    actionLibrary: input.actionLibrary ?? [],
    panoramaHistory: input.panoramaHistory ?? [],
    panoramaYaw: input.panoramaYaw ?? defaults.panoramaYaw,
    panoramaRadius: input.panoramaRadius ?? defaults.panoramaRadius,
    captures: input.captures ?? [],
    timeline: normalizeDirectorTimeline(input.timeline ?? defaults.timeline),
    sourceNodeIds: input.sourceNodeIds ?? []
  }
  scene.activeCameraId = resolveDirectorActiveCameraId(scene) || undefined
  if (!scene.activeCameraId) scene.viewMode = 'director'
  return pruneDirectorPaths(scene)
}

/** 以场景中心为基准，后续对象在中心附近网格排布。 */
export function resolveDirectorObjectSpawnPosition(index: number, kind: 'actor' | 'prop', shape: DirectorPropShape = 'box'): [number, number, number] {
  const y = kind === 'prop' ? DIRECTOR_PROP_HEIGHT[shape] / 2 : 0
  if (index <= 1) return [0, y, 0]
  const slot = index - 1
  const column = slot % 3
  const row = Math.floor(slot / 3)
  const x = (column - 1) * 1.15
  const z = row * 1.15
  return [x, y, z]
}

export function createDirectorActor(index: number, preset = '标准角色'): DirectorObject {
  const scale = preset === '儿童' ? 0.72 : preset === '少年' ? 0.86 : preset === '宽厚' ? 1.15 : preset === '纤细' ? 0.9 : 1
  return {
    id: studioId('actor'),
    kind: 'actor',
    actorMeta: { heightMeters: PREVIZ_HUMAN_HEIGHT_METERS * scale, bodyPreset: preset, locomotion: 'idle' },
    name: `${preset} ${index}`,
    color: resolveDirectorCharacterColor(index),
    visible: true,
    locked: false,
    transform: {
      position: resolveDirectorObjectSpawnPosition(index, 'actor'),
      rotation: [0, 0, 0],
      scale: [scale, scale, scale]
    },
    pose: {}
  }
}

export function createDirectorProp(index: number, shape: DirectorPropShape = index % 3 === 0 ? 'sphere' : index % 2 === 0 ? 'cylinder' : 'box'): DirectorObject {
  return {
    id: studioId('prop'),
    kind: 'prop',
    name: `${DIRECTOR_PROP_SHAPES.find((item) => item.value === shape)?.label ?? '道具'} ${index}`,
    color: DIRECTOR_VIEWPORT_GEOMETRY_COLOR,
    visible: true,
    locked: false,
    shape,
    transform: {
      position: resolveDirectorObjectSpawnPosition(index, 'prop', shape),
      rotation: [0, 0, 0],
      scale: [1, 1, 1]
    },
    pose: {}
  }
}

export function createDirectorActorGroup(
  startIndex: number,
  rows: number,
  columns: number,
  spacing: number
): DirectorObject[] {
  const safeRows = Math.min(8, Math.max(1, Math.round(rows)))
  const safeColumns = Math.min(8, Math.max(1, Math.round(columns)))
  const safeSpacing = Math.min(4, Math.max(0.6, spacing))
  const crowdGroupId = studioId('crowd')
  return Array.from({ length: safeRows * safeColumns }, (_, offset) => {
    const row = Math.floor(offset / safeColumns)
    const column = offset % safeColumns
    const actor = createDirectorActor(startIndex + offset, '群演')
    actor.name = `群演 ${startIndex + offset}`
    actor.crowdGroupId = crowdGroupId
    actor.color = ['#37c874', '#ffcc4a', '#ff6d6d', '#4f8cff', '#ab5be8'][offset % 5]
    actor.transform.position = [
      (column - (safeColumns - 1) / 2) * safeSpacing,
      0,
      (row - (safeRows - 1) / 2) * safeSpacing
    ]
    return actor
  })
}

export function createDirectorCameraBookmark(
  index: number,
  name: string,
  camera: DirectorCamera,
  preset?: string,
  lens?: DirectorCameraBookmark['lens'],
): DirectorCameraBookmark {
  return {
    id: studioId('camera'),
    name: name || `机位 ${index}`,
    camera: normalizeDirectorCamera(structuredClone(camera)),
    lens: normalizeDirectorLensSpec(lens),
    preset,
    visible: true,
    locked: false,
    createdAt: new Date().toISOString()
  }
}

export function resolveDirectorAspectRatio(value: DirectorAspectRatio | undefined): number | null {
  const option = DIRECTOR_ASPECT_RATIOS.find((item) => item.value === (value ?? '16:9'))
  return option ? option.ratio : 16 / 9
}

export function removeDirectorObjects(scene: DirectorSceneData, objectIds: string[]): DirectorSceneData {
  const idSet = new Set(objectIds)
  return {
    ...scene,
    objects: scene.objects.filter((item) => !idSet.has(item.id)),
    mainCameraAimObjectId: idSet.has(scene.mainCameraAimObjectId ?? '') ? undefined : scene.mainCameraAimObjectId,
    cameraBookmarks: scene.cameraBookmarks?.map((camera) =>
      idSet.has(camera.aimObjectId ?? '') ? { ...camera, aimObjectId: undefined } : camera
    ),
    timeline: scene.timeline
      ? removeDirectorTimelineTracksForObjects(normalizeDirectorTimeline(scene.timeline), objectIds)
      : undefined,
    updatedAt: new Date().toISOString()
  }
}

export function removeDirectorObject(scene: DirectorSceneData, objectId: string): DirectorSceneData {
  return removeDirectorObjects(scene, [objectId])
}

export function createDirectorLight(index: number, kind: 'point' | 'spot'): DirectorLight {
  return {
    id: studioId('light'),
    kind,
    name: `${kind === 'point' ? '点光' : '聚光'} ${index}`,
    color: '#fff0d0',
    intensity: 1.5,
    position: [2 + index * 0.4, 4, 2],
    enabled: true
  }
}

export function patchTransformAxis(
  transform: DirectorTransform,
  field: keyof DirectorTransform,
  axis: number,
  value: number
): DirectorTransform {
  const tuple = [...transform[field]] as [number, number, number]
  tuple[axis] = value
  return { ...transform, [field]: tuple }
}

export function applyDirectorLightPreset(
  scene: DirectorSceneData,
  preset: 'cinematic' | 'soft' | 'night'
): DirectorSceneData {
  const colors = preset === 'night'
    ? ['#6f8dff', '#5377ff', '#85b6ff', '#ed55c7']
    : preset === 'soft'
      ? ['#d6e8ff', '#fff1dd', '#cce4ff', '#ffd4df']
      : ['#9cc8ff', '#fff1d4', '#8bcaff', '#ff7ea8']
  const intensities = preset === 'night' ? [0.28, 1.4, 0.45, 2.8] : preset === 'soft' ? [0.85, 1.2, 0.95, 0.8] : [0.65, 2.2, 0.8, 2.4]
  const ids = ['ambient', 'key', 'fill', 'rim']
  return {
    ...scene,
    lights: scene.lights.map(light => {
      const index = ids.indexOf(light.id)
      return index === -1 ? light : { ...light, color: colors[index], intensity: intensities[index], enabled: true }
    }),
    updatedAt: new Date().toISOString()
  }
}

export function applyDirectorEnvironment(
  scene: DirectorSceneData,
  preset: DirectorEnvironment
): DirectorSceneData {
  const defaults = createDirectorEnvironmentLights(preset)
  const builtinIds = new Set(defaults.map(light => light.id))
  return {
    ...scene,
    environmentPreset: preset,
    environmentColor: DIRECTOR_ENVIRONMENT_COLORS[preset],
    weather: preset === 'studio' ? undefined : scene.weather,
    lights: [...defaults, ...scene.lights.filter(light => !builtinIds.has(light.id))],
    updatedAt: new Date().toISOString()
  }
}

export function applyDirectorWeather(scene: DirectorSceneData, weather: DirectorWeather): DirectorSceneData {
  const outdoor = (scene.environmentPreset ?? 'studio') === 'studio'
    ? applyDirectorEnvironment(scene, 'day')
    : scene
  return { ...outdoor, weather: weather === 'clear' ? undefined : weather, updatedAt: new Date().toISOString() }
}

/** 导演台节点卡片预览：优先节点 mediaUrl，其次场景快照 previewImageUrl。 */
export function resolveDirectorNodePreviewUrl(
  data: Pick<StudioNodeData, 'kind' | 'mediaUrl' | 'directorSceneId'>,
  scenes: Record<string, DirectorSceneData>
): string | undefined {
  if (data.kind !== 'director') return undefined
  const fromNode = String(data.mediaUrl || '').trim()
  if (fromNode) return fromNode
  const sceneId = data.directorSceneId
  if (!sceneId) return undefined
  const fromScene = String(scenes[sceneId]?.previewImageUrl || '').trim()
  return fromScene || undefined
}
