import type {
  DirectorSceneData,
  StudioNodeKind,
  StudioSkill,
  StudioTemplate,
  StudioZone
} from '@/types/studio'
import { DEFAULT_DIRECTOR_OBSERVER_CAMERA } from '@/utils/studio/directorCameraPose'
import { createDirectorEnvironmentLights, DIRECTOR_ENVIRONMENT_COLORS } from '@/utils/studio/directorEnvironment'

export interface StudioNodeMeta {
  label: string
  shortLabel: string
  description: string
  color: string
  generatable: boolean
  defaultPrompt: string
}

export const STUDIO_NODE_META: Record<StudioNodeKind, StudioNodeMeta> = {
  image: {
    label: '图片',
    shortLabel: '图',
    description: '角色、场景、道具与分镜画面',
    color: '#72a6ba',
    generatable: true,
    defaultPrompt: ''
  },
  video: {
    label: '视频',
    shortLabel: '视',
    description: '镜头运动与动态画面结果',
    color: '#7d91c7',
    generatable: true,
    defaultPrompt: '生成动作连贯、运镜稳定、主体一致的电影感视频。'
  },
  smart_edit: {
    label: '剪辑成片',
    shortLabel: '剪',
    description: '把已有镜头剪成片',
    color: '#6b8f71',
    generatable: false,
    defaultPrompt: '描述想剪成什么效果'
  },
  frame_breakdown: {
    label: '逐帧拉片',
    shortLabel: '析',
    description: '按分镜、动态与音乐反向拆解成片',
    color: '#5da7b2',
    generatable: false,
    defaultPrompt: ''
  },
  text: {
    label: '文本',
    shortLabel: '文',
    description: '可整理与生成的文本内容',
    color: '#8f8bd9',
    generatable: true,
    defaultPrompt: '整理并扩写这段内容，使表达准确、结构清晰。'
  },
  storyboard_script: {
    label: '分镜脚本',
    shortLabel: '镜',
    description: '镜头拆解、调度与叙事结构',
    color: '#8f8bd9',
    generatable: true,
    defaultPrompt: '把故事拆解为节奏清晰、可执行的分镜脚本。'
  },
  director: {
    label: '3D预演',
    shortLabel: '预',
    description: '3D 白模预演、机位、骨骼与灯光',
    color: '#b98270',
    generatable: true,
    defaultPrompt: '基于上传参考生成导演构图预览。'
  },
  style_library: {
    label: '风格库',
    shortLabel: '风',
    description: '向图片节点提供风格参考',
    color: '#9b85b3',
    generatable: false,
    defaultPrompt: ''
  },
  official_asset: {
    label: '官方素材',
    shortLabel: '材',
    description: '向图片节点提供素材参考',
    color: '#b59a6f',
    generatable: false,
    defaultPrompt: ''
  },
  voice: {
    label: '音频',
    shortLabel: '音',
    description: '对白、旁白、音乐与声音素材',
    color: '#6fa99e',
    generatable: true,
    defaultPrompt: '生成自然清晰、情绪准确的音频。'
  },
  note: {
    label: '备注',
    shortLabel: '注',
    description: '创作提示与协作说明',
    color: '#818b99',
    generatable: true,
    defaultPrompt: '整理这条备注，使其成为清晰、可执行的文本。'
  }
}

export const DEFAULT_STUDIO_ZONES: StudioZone[] = [
  {
    id: 'global-setting',
    title: '01 项目配置',
    caption: '创作约束与全局设置',
    color: '#8d96a5',
    position: { x: 0, y: 0 },
    width: 330,
    height: 690,
    collapsed: false
  },
  {
    id: 'story-script',
    title: '02 剧本创作',
    caption: '故事与对白',
    color: '#8d96a5',
    position: { x: 370, y: 0 },
    width: 330,
    height: 690,
    collapsed: false
  },
  {
    id: 'scene-character',
    title: '03 素材准备',
    caption: '角色、场景与道具',
    color: '#8d96a5',
    position: { x: 740, y: 0 },
    width: 620,
    height: 690,
    collapsed: false
  },
  {
    id: 'storyboard-script',
    title: '04 分镜脚本',
    caption: '导演调度与镜头拆分',
    color: '#8d96a5',
    position: { x: 1400, y: 0 },
    width: 620,
    height: 690,
    collapsed: false
  },
  {
    id: 'storyboard-video',
    title: '05 分镜视频',
    caption: '画面与视频结果',
    color: '#8d96a5',
    position: { x: 2060, y: 0 },
    width: 620,
    height: 690,
    collapsed: false
  },
  {
    id: 'dubbing',
    title: '06 音画同步',
    caption: '配音、字幕与声音',
    color: '#8d96a5',
    position: { x: 2720, y: 0 },
    width: 330,
    height: 690,
    collapsed: false
  },
  {
    id: 'preview',
    title: '07 成品预览',
    caption: '交付检查与成片衔接',
    color: '#8d96a5',
    position: { x: 3090, y: 0 },
    width: 330,
    height: 690,
    collapsed: false
  }
]

export const STUDIO_TEMPLATES: StudioTemplate[] = [
  {
    id: 'short-drama',
    name: '短剧标准管线',
    description: '分镜脚本 → 角色/场景 → 导演台 → 分镜图 → 视频',
    nodes: [
      { kind: 'storyboard_script', title: '故事梗概', zoneId: 'story-script', position: { x: 405, y: 84 } },
      { kind: 'image', imagePurpose: 'character', title: '主要角色', zoneId: 'scene-character', position: { x: 778, y: 84 } },
      { kind: 'image', imagePurpose: 'scene', title: '核心场景', zoneId: 'scene-character', position: { x: 1030, y: 84 } },
      { kind: 'director', title: '导演台 · 主场景', zoneId: 'storyboard-script', position: { x: 1438, y: 360 } },
      { kind: 'image', imagePurpose: 'reference', title: '导演构图参考', zoneId: 'scene-character', position: { x: 1030, y: 360 } },
      {
        kind: 'image',
        imagePurpose: 'storyboard',
        title: '镜头 01 · 主图',
        zoneId: 'storyboard-video',
        position: { x: 1732, y: 84 },
        mediaKind: 'image'
      },
      {
        kind: 'video',
        title: '镜头 01 · 视频',
        zoneId: 'storyboard-video',
        position: { x: 1732, y: 360 },
        mediaKind: 'video'
      }
    ],
    edges: [
      { sourceIndex: 0, targetIndex: 1, kind: 'reference' },
      { sourceIndex: 0, targetIndex: 2, kind: 'reference' },
      { sourceIndex: 1, targetIndex: 3, kind: 'reference' },
      { sourceIndex: 2, targetIndex: 3, kind: 'reference' },
      { sourceIndex: 3, targetIndex: 4, kind: 'reference' },
      { sourceIndex: 4, targetIndex: 5, kind: 'reference' },
      { sourceIndex: 5, targetIndex: 6, kind: 'sequence' }
    ]
  },
  {
    id: 'asset-board',
    name: '角色场景资产板',
    description: '快速搭建角色、场景、道具与风格参考结构',
    nodes: [
      { kind: 'image', imagePurpose: 'character', title: '角色 A', zoneId: 'scene-character', position: { x: 778, y: 84 } },
      { kind: 'image', imagePurpose: 'scene', title: '场景 A', zoneId: 'scene-character', position: { x: 1030, y: 84 } },
      { kind: 'image', imagePurpose: 'prop', title: '关键道具', zoneId: 'scene-character', position: { x: 778, y: 360 } },
      { kind: 'style_library', title: '风格参考', zoneId: 'scene-character', position: { x: 1030, y: 360 } }
    ],
    edges: [
      { sourceIndex: 3, targetIndex: 0, kind: 'reference' },
      { sourceIndex: 3, targetIndex: 1, kind: 'reference' },
      { sourceIndex: 3, targetIndex: 2, kind: 'reference' }
    ]
  }
]

export const STUDIO_SKILLS: StudioSkill[] = [
  {
    id: 'skill-story-structure',
    name: '剧本拆镜',
    description: '从文本搭建可编辑的镜头结构，不自动计费生成',
    action: 'structure_only',
    source: 'preset'
  },
  {
    id: 'skill-assets',
    name: '资产准备',
    description: '生成角色、场景与道具节点结构',
    action: 'gen_assets',
    source: 'preset'
  },
  {
    id: 'skill-unit-chain',
    name: '镜头成链',
    description: '创建分镜图与视频结果链路',
    action: 'gen_unit_chain',
    source: 'preset'
  },
  {
    id: 'skill-diagnose',
    name: '画布体检',
    description: '定位空节点、失败结果和断开的生产链路',
    action: 'diagnose',
    source: 'preset'
  }
]

export const DEFAULT_DIRECTOR_SCENE: DirectorSceneData = {
  id: 'director-scene-default',
  name: '主场景调度',
  environmentColor: DIRECTOR_ENVIRONMENT_COLORS.studio,
  environmentPreset: 'studio',
  gridVisible: true,
  groundVisible: false,
  groundOpacity: 0.4,
  cameraBookmarks: [],
  sourceNodeIds: [],
  camera: {
    position: [0, 1.55, 5.4],
    target: [0, 1.05, 0],
    fov: 50
  },
  directorCamera: {
    ...DEFAULT_DIRECTOR_OBSERVER_CAMERA,
    position: [...DEFAULT_DIRECTOR_OBSERVER_CAMERA.position],
    target: [...DEFAULT_DIRECTOR_OBSERVER_CAMERA.target]
  },
  mainCameraPresent: false,
  objects: [],
  lights: createDirectorEnvironmentLights('studio'),
  updatedAt: new Date(0).toISOString()
}
