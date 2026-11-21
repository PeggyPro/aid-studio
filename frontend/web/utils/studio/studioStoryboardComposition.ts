import type { StudioNodeData } from '@/types/studio'

export type StudioStoryboardCompositionCategory =
  | 'narrative'
  | 'texture'
  | 'space'
  | 'setting'

export type StudioStoryboardCompositionRoute =
  | 'setting_edit'
  | 'multi_view'
  | 'nine_grid'
  | 'edit'

export interface StudioStoryboardCompositionItem {
  id: string
  label: string
  category: StudioStoryboardCompositionCategory
  showInHeader: boolean
  description: string
  promptTemplate: string
  imagePurpose: NonNullable<StudioNodeData['imagePurpose']>
  generateRoute: StudioStoryboardCompositionRoute
  subtitle?: string
  /** 设定类辅助：scene | character | prop；非设定类省略 */
  settingSubject?: 'scene' | 'character' | 'prop'
  /** 可映射时写入 generationOptions.benchPreset */
  benchPreset?: 'three_view' | 'multi_camera' | 'quad_grid'
}

export const STUDIO_STORYBOARD_COMPOSITION_CATEGORY_LABEL: Record<
  StudioStoryboardCompositionCategory,
  string
> = {
  narrative: '分镜叙事',
  texture: '质感调节',
  space: '空间与机位',
  setting: '设定图'
}

/**
 * 分镜构图功能目录（头栏子集 + Composer 双栏全量）。
 * 生成路由与 prompt 集中在此，UI 只读配置，禁止在组件内复制一份「差不多」的清单。
 */
export const STUDIO_STORYBOARD_COMPOSITIONS: readonly StudioStoryboardCompositionItem[] = [
  {
    id: 'blocking_storyboard',
    label: '调度故事板',
    category: 'narrative',
    showInHeader: false,
    description: '点击生成，基于当前图生成带运镜与景别调度的分镜组向整图。',
    promptTemplate:
      '基于参考图生成调度故事板整图：包含多个连贯镜头格，体现运镜、景别与机位调度变化，保持角色身份、服装与场景风格严格一致，电影分镜感，清晰可读的画格分区',
    imagePurpose: 'storyboard',
    generateRoute: 'edit',
    subtitle: '生成带叙事轨迹的分镜组',
    benchPreset: 'multi_camera'
  },
  {
    id: 'storyboard',
    label: '故事板',
    category: 'narrative',
    showInHeader: false,
    description: '点击生成，基于当前图快速展开相关镜头故事板。',
    promptTemplate:
      '基于参考图生成轻量故事板整图：展开若干关联镜头，保持主体与光影风格一致，构图清晰，适合剧情预览',
    imagePurpose: 'storyboard',
    generateRoute: 'edit',
    subtitle: '快速展开相关镜头',
    benchPreset: 'quad_grid'
  },
  {
    id: 'storyboard_25',
    label: '25宫格连贯分镜',
    category: 'narrative',
    showInHeader: true,
    description: '点击生成，基于当前图生成约 25 个不同角度、景别的连贯分镜整图。',
    promptTemplate:
      '基于参考图生成 5×5 共约 25 宫格连贯分镜整图：从远景到特写、多角度多景别完整拆解同一场景，画格均匀清晰，主体身份与环境风格全程一致，仅输出一张整图不拆分',
    imagePurpose: 'storyboard',
    generateRoute: 'nine_grid',
    subtitle: '多角度多景别连贯拆解',
    benchPreset: 'multi_camera'
  },
  {
    id: 'plot_quad_grid',
    label: '剧情推演四宫格',
    category: 'narrative',
    showInHeader: true,
    description: '点击生成，按时间线推演 4 个剧情节拍的四宫格整图。',
    promptTemplate:
      '基于参考图生成剧情推演四宫格整图：按时间线排布准备→推进→高潮→结果四个连贯画格，叙事清楚，角色与场景一致性强，仅输出一张四宫格整图',
    imagePurpose: 'storyboard',
    generateRoute: 'nine_grid',
    subtitle: '时间线四节拍推演',
    benchPreset: 'quad_grid'
  },
  {
    id: 'frame_plus_3s',
    label: '画面推演 - 3秒后',
    category: 'narrative',
    showInHeader: true,
    description: '点击生成，推演当前画面约 3 秒后可能发生的下一刻。',
    promptTemplate:
      '基于参考图推演约 3 秒后的下一刻画面：动作与情节自然延续，保持角色身份、服装、场景与光影风格严格一致，单图成片',
    imagePurpose: 'storyboard',
    generateRoute: 'edit',
    subtitle: '下一刻画面'
  },
  {
    id: 'frame_minus_5s',
    label: '画面推演 - 5秒前',
    category: 'narrative',
    showInHeader: true,
    description: '点击生成，推演当前画面约 5 秒前可能发生的上一刻。',
    promptTemplate:
      '基于参考图推演约 5 秒前的上一刻画面：作为当前帧的合理前序，保持角色身份、服装、场景与光影风格严格一致，单图成片',
    imagePurpose: 'storyboard',
    generateRoute: 'edit',
    subtitle: '上一刻画面'
  },
  {
    id: 'portrait_texture',
    label: '人像质感调节',
    category: 'texture',
    showInHeader: false,
    description: '点击生成，强化肤质与人物光影，构图与身份基本不变。',
    promptTemplate:
      '基于参考图做人像质感调节：强化肤质细腻度、人物光影层次与细节清晰度，不改变构图、姿态、服装与角色身份，电影人像质感',
    imagePurpose: 'character',
    generateRoute: 'edit',
    subtitle: '强化肤质与人物光影'
  },
  {
    id: 'cinematic_lighting',
    label: '电影级光影校正',
    category: 'texture',
    showInHeader: true,
    description: '点击生成，统一为电影感色调与光影层次。',
    promptTemplate:
      '基于参考图做电影级光影校正：统一色调与明暗层次，增强电影感光影，不改变主体构图与身份细节，保持场景结构完整',
    imagePurpose: 'generic',
    generateRoute: 'edit',
    subtitle: '统一色调与层次'
  },
  {
    id: 'panorama_720',
    label: '720全景',
    category: 'space',
    showInHeader: false,
    description: '点击生成，基于当前图生成可环视的 720/等距柱状全景图。',
    promptTemplate:
      '基于参考图生成 720 全景图：等距柱状投影、可环视的空间关系完整，环境连贯无硬接缝，主体与氛围风格一致，适合后续全景浏览',
    imagePurpose: 'scene',
    generateRoute: 'edit',
    subtitle: 'AI 生成环视全景图'
  },
  {
    id: 'multi_camera_9',
    label: '多机位九宫格',
    category: 'space',
    showInHeader: true,
    description: '点击生成，直接基于当前图生成约 9 个不同的角度、景别视角；支持模型图像分辨率。',
    promptTemplate:
      '基于参考图生成多机位九宫格整图：约 9 个不同机位与景别（平视/俯仰/特写/全景等），保持主体身份严格一致，画格清晰均匀，仅输出一张九宫格整图',
    imagePurpose: 'storyboard',
    generateRoute: 'multi_view',
    subtitle: '生成多视角机位图',
    benchPreset: 'multi_camera'
  },
  {
    id: 'character_face_three_view',
    label: '角色脸部三视图',
    category: 'setting',
    showInHeader: true,
    description: '点击生成，基于当前图生成角色脸部标准多视图设定。',
    promptTemplate:
      '基于参考图生成角色脸部三视图设定图：正面、侧面等面部标准视图，五官发型配色严格一致，背景干净，比例准确，适合锁定面部一致性',
    imagePurpose: 'character',
    generateRoute: 'setting_edit',
    subtitle: '面部标准多视图',
    settingSubject: 'character',
    benchPreset: 'three_view'
  },
  {
    id: 'character_setting',
    label: '角色设定图',
    category: 'setting',
    showInHeader: true,
    description: '点击生成，角色主视觉与设定拆解，保持身份一致。',
    promptTemplate:
      '基于参考图生成角色设定图：主视觉与外形、服装、配色设定拆解，保持角色身份、五官、发型、服装、体型严格一致，背景干净，细节清晰',
    imagePurpose: 'character',
    generateRoute: 'setting_edit',
    subtitle: '角色主视觉与设定拆解',
    settingSubject: 'character'
  },
  {
    id: 'character_three_view',
    label: '角色三视图',
    category: 'setting',
    showInHeader: true,
    description: '点击生成，全身正面、侧面、背面完整三视图。',
    promptTemplate:
      '基于参考图生成角色全身三视图：正面、侧面、背面完整设定，身份五官发型服装体型配色严格一致，比例准确，背景干净',
    imagePurpose: 'character',
    generateRoute: 'setting_edit',
    subtitle: '正侧背完整角色设定',
    settingSubject: 'character',
    benchPreset: 'three_view'
  },
  {
    id: 'scene_setting',
    label: '场景设定图',
    category: 'setting',
    showInHeader: true,
    description: '点击生成，建立环境构图、空间结构与氛围设定。',
    promptTemplate:
      '基于参考图生成场景设定图：保持空间结构、时代环境、材质、光线和视觉风格统一，不新增无关角色，构图清晰适合作为环境设定参考',
    imagePurpose: 'scene',
    generateRoute: 'setting_edit',
    subtitle: '环境构图与氛围',
    settingSubject: 'scene',
    benchPreset: 'quad_grid'
  },
  {
    id: 'prop_setting',
    label: '道具设定图',
    category: 'setting',
    showInHeader: true,
    description: '点击生成，对物体/道具做多视角产品级设定。',
    promptTemplate:
      '基于参考图生成道具设定图：保持道具结构、材质、比例、功能细节与磨损特征严格一致，多视角清晰展示，背景干净，适合锁定道具一致性',
    imagePurpose: 'prop',
    generateRoute: 'setting_edit',
    subtitle: '物体多视角设定',
    settingSubject: 'prop'
  }
]

const BY_ID = new Map(STUDIO_STORYBOARD_COMPOSITIONS.map((item) => [item.id, item]))

/** 头栏展示顺序（与规格 §6.1 锁定一致，不依赖目录声明顺序） */
const HEADER_ORDER = [
  'scene_setting',
  'character_setting',
  'character_three_view',
  'character_face_three_view',
  'prop_setting',
  'multi_camera_9',
  'plot_quad_grid',
  'storyboard_25',
  'cinematic_lighting',
  'frame_plus_3s',
  'frame_minus_5s'
] as const

export function getStudioStoryboardComposition(
  id: string | undefined | null
): StudioStoryboardCompositionItem | undefined {
  if (!id) return undefined
  return BY_ID.get(id)
}

export function listStudioStoryboardCompositionHeaderItems(): StudioStoryboardCompositionItem[] {
  return HEADER_ORDER.map((id) => BY_ID.get(id)).filter(
    (item): item is StudioStoryboardCompositionItem => Boolean(item?.showInHeader)
  )
}

export function groupStudioStoryboardCompositionsByCategory(): Record<
  StudioStoryboardCompositionCategory,
  StudioStoryboardCompositionItem[]
> {
  const groups: Record<StudioStoryboardCompositionCategory, StudioStoryboardCompositionItem[]> = {
    narrative: [],
    texture: [],
    space: [],
    setting: []
  }
  for (const item of STUDIO_STORYBOARD_COMPOSITIONS) {
    groups[item.category].push(item)
  }
  return groups
}

export function buildStudioStoryboardCompositionPrompt(
  item: StudioStoryboardCompositionItem,
  userText: string
): string {
  const extra = userText.trim()
  if (!extra) return item.promptTemplate
  return `${item.promptTemplate}。补充要求：${extra}`
}

export function buildStudioStoryboardCompositionNodePatch(
  item: StudioStoryboardCompositionItem
): Partial<StudioNodeData> {
  const generationOptions = item.benchPreset
    ? { benchPreset: item.benchPreset }
    : undefined
  return {
    storyboardCompositionId: item.id,
    imagePurpose: item.imagePurpose,
    ...(generationOptions ? { generationOptions } : {})
  }
}

/**
 * 头栏选功能后写入派生节点的字段。
 * 必须 status=empty：deriveImageNode 默认 queued 会被节点卡当成「生成中」。
 */
export function buildStudioStoryboardCompositionDerivedUpdate(
  item: StudioStoryboardCompositionItem
): Partial<StudioNodeData> {
  return {
    ...buildStudioStoryboardCompositionNodePatch(item),
    prompt: '',
    status: 'empty',
    resultSummary: undefined,
    errorMessage: undefined
  }
}

export function resolveStudioStoryboardCompositionReferenceUrl(
  node: Pick<StudioNodeData, 'mediaUrl' | 'composerRefs'>
): string {
  const fromMedia = String(node.mediaUrl || '').trim()
  if (fromMedia) return fromMedia
  for (const ref of node.composerRefs ?? []) {
    const url = String(ref.previewUrl || '').trim()
    if (url) return url
  }
  return ''
}
