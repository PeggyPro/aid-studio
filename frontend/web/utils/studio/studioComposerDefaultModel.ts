import type { UserModelListItem } from '@/types/business-api'
import type { StudioNodeData } from '@/types/studio'
import {
  resolveBatchStoryboardVideoAgentBizCategories
} from '@/utils/creationModeUiRules'
import { fetchAgentDefaultModelCode } from '@/utils/extractAgentBiz'
import {
  FORM_IMAGE_SCENE_CODE_BY_TYPE,
  resolveProjectGenModelCode,
  STORYBOARD_GEN_CONFIG_SCENE_CODES
} from '@/utils/projectGenConfig'
import { isStudioFlowStoryboardStillNode } from '@/utils/studio/studioFlowStoryboardMedia'

/** 节点对应的生成配置 / 出片智能体 sceneCode，用于默认模型。 */
export function resolveStudioComposerGenSceneCode(
  data: Pick<StudioNodeData, 'kind' | 'mediaKind' | 'imagePurpose' | 'flowBinding'>,
  creationMode?: string | null
): string {
  const binding = data.flowBinding
  if (isStudioFlowStoryboardStillNode(data)) {
    return STORYBOARD_GEN_CONFIG_SCENE_CODES.image
  }
  if (
    binding?.step === 'storyboard-video'
    || (data.kind === 'video' && binding?.entityType === 'storyboard')
  ) {
    return resolveBatchStoryboardVideoAgentBizCategories(creationMode)[0] || ''
  }
  const assetType = binding?.assetType
  if (assetType === 'character' || assetType === 'scene' || assetType === 'prop') {
    return FORM_IMAGE_SCENE_CODE_BY_TYPE[assetType] || ''
  }
  if (data.kind === 'video' || data.mediaKind === 'video') {
    return resolveBatchStoryboardVideoAgentBizCategories(creationMode)[0] || ''
  }
  if (data.kind === 'image' || data.mediaKind === 'image') {
    return STORYBOARD_GEN_CONFIG_SCENE_CODES.image
  }
  if (
    data.kind === 'text'
    || data.kind === 'storyboard_script'
    || data.kind === 'note'
    || data.mediaKind === 'text'
  ) {
    return STORYBOARD_GEN_CONFIG_SCENE_CODES.script
  }
  return ''
}

/** 优先使用业务配置；否则按服务端优先级选模型，同级优先免费、再选较低展示价格。 */
export function pickStudioComposerDefaultModel(
  options: UserModelListItem[],
  preferredCodes: Array<string | null | undefined> = []
): UserModelListItem | undefined {
  if (!options.length) return undefined
  for (const raw of preferredCodes) {
    const code = String(raw || '').trim()
    if (!code) continue
    const hit = options.find((item) => String(item.modelCode || '').trim() === code)
    if (hit) return hit
  }
  return options.reduce((best, item) => {
    const priority = Number(item.priority ?? 0)
    const bestPriority = Number(best.priority ?? 0)
    if (priority !== bestPriority) return priority > bestPriority ? item : best
    if (Boolean(item.isFree) !== Boolean(best.isFree)) return item.isFree ? item : best
    const price = item.costCredits ?? Number.POSITIVE_INFINITY
    const bestPrice = best.costCredits ?? Number.POSITIVE_INFINITY
    return price < bestPrice ? item : best
  })
}

/** 当前值是否落在真实模型池中（占位假名不算）。 */
export function isStudioComposerModelInOptions(
  options: UserModelListItem[],
  value?: string | null
): boolean {
  const code = String(value || '').trim()
  if (!code || !options.length) return false
  return options.some((item) => String(item.modelCode || '').trim() === code)
}

/** 自由画布 URL/本地残留的 projectId 属于 canvas 工作区，不是 aid_project。 */
export function shouldResolveStudioPreferredModelFromProject(options: {
  studioMode?: string | null
  projectId?: number | null
  sceneCode?: string | null
}): boolean {
  if (options.studioMode === 'free') return false
  const sceneCode = String(options.sceneCode || '').trim()
  const projectId = Number(options.projectId)
  return Boolean(sceneCode && Number.isFinite(projectId) && projectId > 0)
}

function isMissingProjectConfigError(error: unknown): boolean {
  const record = error !== null && typeof error === 'object'
    ? error as { msg?: unknown; message?: unknown }
    : null
  const msg = String(record?.msg ?? record?.message ?? '').trim()
  return msg.includes('项目不存在') || msg.includes('无权限访问') || msg.includes('不存在或无权限')
}

/** 生成配置 modelCode → 智能体默认 modelCode。 */
export async function resolveStudioPreferredModelCode(options: {
  projectId?: number | null
  episodeId?: number | null
  sceneCode?: string | null
}): Promise<string> {
  const sceneCode = String(options.sceneCode || '').trim()
  const projectId = Number(options.projectId)
  if (!sceneCode || !Number.isFinite(projectId) || projectId <= 0) return ''

  try {
    const fromConfig = await resolveProjectGenModelCode(projectId, sceneCode)
    if (fromConfig) return fromConfig
  } catch (error) {
    if (isMissingProjectConfigError(error)) return ''
  }

  const episodeId = Number(options.episodeId)
  try {
    return await fetchAgentDefaultModelCode({
      bizCategoryCode: sceneCode,
      projectId,
      ...(Number.isFinite(episodeId) && episodeId >= 0 ? { episodeId } : {})
    })
  } catch {
    return ''
  }
}

export function resolveStudioComposerModelFromNode(
  options: UserModelListItem[],
  node: { data: Pick<StudioNodeData, 'model'> },
  preferredCodes: Array<string | null | undefined> = []
): UserModelListItem | undefined {
  const current = String(node.data.model || '').trim()
  if (current && isStudioComposerModelInOptions(options, current)) {
    return options.find((item) => String(item.modelCode || '').trim() === current)
  }
  return pickStudioComposerDefaultModel(options, preferredCodes)
}
