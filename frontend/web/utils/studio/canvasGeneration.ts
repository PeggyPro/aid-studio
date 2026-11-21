import type { CanvasMediaType } from '@/types/canvas-api'
import type {
  UserGenerationKind,
  UserGenerationNodeReference,
  UserGenerationReference,
  UserGenerationReferenceRole,
  UserImageGenerationRequest,
  UserTextGenerationRequest,
  UserVideoGenerationRequest
} from '@/types/generation-api'
import type { StudioCanvasNode, StudioComposerRef } from '@/types/studio'
import type { UserModelListItem } from '@/types/business-api'
import { resolveCapabilityRecord } from '@/utils/modelCapability'
import { canvasVideoCapabilityLabel, canvasVideoUsesFirstFrame } from './canvasGenerationModels'
import { studioCanvasImageParameters, studioCanvasImagePrompt, studioCanvasVideoParameters } from './studioCanvasGenerationParameters'

export type CanvasGenerationMediaType = CanvasMediaType | 'DOCUMENT'

export interface CanvasGenerationReferenceCandidate {
  resourceId: string
  title: string
  mediaType: CanvasGenerationMediaType
  role?: UserGenerationReferenceRole
}

export function canvasGenerationReferenceRole(kind: UserGenerationKind, node: StudioCanvasNode, ref: StudioComposerRef, mediaType: string) {
  if (ref.inputRole === 'first_frame' || ref.inputRole === 'last_frame') {
    if (kind !== 'video' || mediaType !== 'IMAGE') throw new Error('首尾帧引用必须是视频生成使用的图片素材')
    return ref.inputRole === 'first_frame' ? 'FIRST_FRAME' : 'LAST_FRAME'
  }
  if (ref.inputRole) return 'REFERENCE'
  const options = node.data.generationOptions ?? {}
  return kind === 'video' && mediaType === 'IMAGE'
    ? ref.previewUrl && ref.previewUrl === options.startFrameUrl ? 'FIRST_FRAME'
      : options.endFrameEnabled && ref.previewUrl && ref.previewUrl === options.endFrameUrl ? 'LAST_FRAME' : 'REFERENCE'
    : 'REFERENCE'
}

export function ensureCanvasGenerationFirstFrame(references: UserGenerationReference[], candidates: CanvasGenerationReferenceCandidate[]) {
  if (references.some(ref => ref.role === 'FIRST_FRAME')) return
  const first = references.find(ref => ref.role !== 'LAST_FRAME'
    && candidates.some(candidate => candidate.resourceId === ref.resourceId && candidate.mediaType === 'IMAGE'))
  if (first) first.role = 'FIRST_FRAME'
  else if (references.some(ref => ref.role === 'LAST_FRAME')) throw new CanvasGenerationInputError('已指定尾帧，还需要指定首帧图片')
}

const VIDEO_INPUT_NAMES: Record<string, string> = {
  image: '普通参考图',
  video: '参考视频',
  audio: '参考音频',
  firstframe: '首帧图片',
  lastframe: '尾帧图片'
}

const REFERENCE_LIMIT_MODE_LABELS: Record<string, string> = {
  image_to_video: '首帧',
  last_frame_to_video: '尾帧',
  image_to_image: '图生图',
  text_to_image: '文生图'
}

export class CanvasGenerationInputError extends Error {}

function canvasGenerationSceneRule(capability: Record<string, unknown>, code: string) {
  const scene = code.replace(/_([a-z])/g, (_, letter: string) => letter.toUpperCase())
  return (capability.sceneRules as Record<string, Record<string, unknown>> | undefined)?.[scene]
}

export function validateCanvasGenerationReferenceLimits(
  model: UserModelListItem | undefined,
  code: string,
  references: UserGenerationReference[],
  candidates: CanvasGenerationReferenceCandidate[]
) {
  if (!model || !references.length) return
  const capability = resolveCapabilityRecord(model)
  const option = model.availableCapabilities?.find(item => item.code === code)
  const modeCode = option?.generateMode || code
  const sceneRule = canvasGenerationSceneRule(capability, modeCode)
  const defaultCode = model.capabilityCode || model.generateMode
  // A multi-mode catalog row exposes root limits for its default capability.
  const root = !defaultCode || defaultCode === code ? capability : undefined
  const types = new Map(candidates.map(item => [item.resourceId, item.mediaType]))
  const counts = references.reduce((totals, reference) => {
    const type = types.get(reference.resourceId)
    if (type === 'IMAGE' || type === 'VIDEO' || type === 'AUDIO') totals[type]++
    return totals
  }, { IMAGE: 0, VIDEO: 0, AUDIO: 0 })
  const limits = [
    ['maxReferenceImages', counts.IMAGE, '参考图', '张'],
    ['maxReferenceVideos', counts.VIDEO, '参考视频', '个'],
    ['maxReferenceAudios', counts.AUDIO, '参考音频', '个'],
    ['maxReferenceMaterials', counts.IMAGE + counts.VIDEO + counts.AUDIO, '参考素材', '个']
  ] as const
  for (const [field, count, label, unit] of limits) {
    const supported = [root?.[field], sceneRule?.[field], option?.presentation?.[field]]
      .filter((limit): limit is number => typeof limit === 'number' && Number.isInteger(limit) && limit >= 0)
    if (!supported.length) continue
    const max = Math.min(...supported)
    if (count <= max) continue
    const displayLabel = canvasVideoCapabilityLabel(modeCode, option?.label)
    const mode = REFERENCE_LIMIT_MODE_LABELS[modeCode] ?? (displayLabel === modeCode ? '当前' : displayLabel)
    const modeLabel = mode.endsWith('模式') ? mode : `${mode}模式`
    throw new CanvasGenerationInputError(max === 0
      ? `${modeLabel}下，该模型不支持${label}，请移除该素材或切换模式、模型`
      : `${modeLabel}下，该模型最多支持 ${max} ${unit}${label}，已选 ${count} ${unit}，请移除多余素材或切换模式、模型`)
  }
}

export function prepareCanvasVideoGenerationReferences(
  capabilityCode: string,
  model: UserModelListItem | undefined,
  references: UserGenerationReference[],
  candidates: CanvasGenerationReferenceCandidate[]
) {
  if (!capabilityCode) throw new CanvasGenerationInputError('请添加所选模型需要的素材，或切换视频模型')
  if (canvasVideoUsesFirstFrame(capabilityCode)) ensureCanvasGenerationFirstFrame(references, candidates)
  validateCanvasGenerationReferenceLimits(model, capabilityCode, references, candidates)

  const inputs = canvasGenerationSceneRule(resolveCapabilityRecord(model), capabilityCode)?.allowedInputs
  if (!Array.isArray(inputs) || !inputs.length) return

  const allowed = new Set(inputs.map(input => {
    const name = input.trim().toLowerCase().replace(/[_-]/g, '')
    if (/^start(frame|image)$/.test(name)) return 'firstframe'
    if (/^end(frame|image)$/.test(name)) return 'lastframe'
    return name.replace(/^(?:reference)?(image|video|audio)s?$/, '$1')
  }))
  for (const ref of references) {
    const mediaType = candidates.find(candidate => candidate.resourceId === ref.resourceId)?.mediaType.toLowerCase()
    const isFrame = ref.role === 'FIRST_FRAME' || ref.role === 'LAST_FRAME'
    const input = isFrame ? ref.role.toLowerCase().replace('_', '') : mediaType
    if (!input || allowed.has(input) || (isFrame && allowed.has('image'))) continue
    throw new CanvasGenerationInputError(
      `当前${canvasVideoCapabilityLabel(capabilityCode)}模式不支持${VIDEO_INPUT_NAMES[input] ?? '该素材'}，请移除该素材或切换模式、模型`
    )
  }
}

export function buildCanvasTextNodeReferences(input: {
  prompt: string
  candidates: Array<{ objectId: string; expectedVersion: number; title: string }>
  usedMentions?: string[]
}): { prompt: string; nodeReferences: UserGenerationNodeReference[] } {
  const usedIds = new Set<string>()
  const usedMentions = new Set(input.usedMentions ?? [])
  const aliases = new Map<string, string>()
  const nodeReferences: UserGenerationNodeReference[] = []
  for (const candidate of input.candidates) {
    if (usedIds.has(candidate.objectId)) continue
    usedIds.add(candidate.objectId)
    const mention = uniqueMention(candidate.title, nodeReferences.length + 1, usedMentions)
    nodeReferences.push({ objectId: candidate.objectId, expectedVersion: candidate.expectedVersion, mention })
    if (!aliases.has(candidate.title)) aliases.set(candidate.title, mention)
    if (nodeReferences.length >= 20) break
  }
  let prompt = input.prompt
  for (const [title, mention] of [...aliases.entries()].sort(([a], [b]) => b.length - a.length)) {
    if (title && title !== mention) prompt = prompt.split(`@${title}`).join(`@${mention}`)
  }
  return { prompt, nodeReferences }
}

export function normalizeCanvasGenerationMention(value: string, fallbackIndex = 1): string {
  const normalized = value.normalize('NFKC')
    .replace(/[^\p{L}\p{N}_-]+/gu, '_')
    .replace(/^[_-]+|[_-]+$/g, '')
  return normalized.slice(0, 32) || `素材${fallbackIndex}`
}

function uniqueMention(value: string, index: number, used: Set<string>): string {
  const base = normalizeCanvasGenerationMention(value, index)
  let mention = base
  let suffix = 2
  while (used.has(mention)) {
    const tail = `_${suffix}`
    mention = `${base.slice(0, Math.max(1, 32 - tail.length))}${tail}`
    suffix += 1
  }
  used.add(mention)
  return mention
}

function supportsReference(kind: UserGenerationKind, mediaType: CanvasGenerationMediaType): boolean {
  if (kind === 'image') return mediaType === 'IMAGE'
  if (kind === 'video') return mediaType === 'IMAGE' || mediaType === 'VIDEO' || mediaType === 'AUDIO'
  return true
}

/**
 * 将 Composer 的展示标题转换为服务端允许的显式别名，并按资源编号去重。
 * 工作区 NODE 最多持久化 20 个 resourceIds，因此提交也只消费同一组可恢复资源。
 */
export function buildCanvasGenerationReferences(input: {
  kind: UserGenerationKind
  prompt: string
  candidates: CanvasGenerationReferenceCandidate[]
}): { prompt: string; references: UserGenerationReference[] } {
  const usedIds = new Set<string>()
  const usedMentions = new Set<string>()
  const titleAliases = new Map<string, string>()
  const references: UserGenerationReference[] = []

  for (const candidate of input.candidates) {
    const resourceId = String(candidate.resourceId || '').trim()
    if (!/^\d+$/.test(resourceId) || resourceId === '0' || usedIds.has(resourceId)) continue
    if (!supportsReference(input.kind, candidate.mediaType)) continue
    usedIds.add(resourceId)
    const mention = uniqueMention(candidate.title, references.length + 1, usedMentions)
    if (!titleAliases.has(candidate.title)) titleAliases.set(candidate.title, mention)
    const requestedRole = candidate.role ?? 'REFERENCE'
    const role = input.kind === 'video' && candidate.mediaType === 'IMAGE'
      ? requestedRole
      : 'REFERENCE'
    references.push({ resourceId, mention, role })
    if (references.length >= 20) break
  }

  let prompt = input.prompt
  const aliases = [...titleAliases.entries()].sort(([left], [right]) => right.length - left.length)
  for (const [title, mention] of aliases) {
    if (!title || title === mention) continue
    prompt = prompt.split(`@${title}`).join(`@${mention}`)
  }
  return { prompt, references }
}

function compactParameters(entries: Array<[string, unknown]>): Record<string, unknown> | undefined {
  const value = Object.fromEntries(entries.filter(([, item]) => item !== undefined && item !== null && item !== ''))
  return Object.keys(value).length ? value : undefined
}

export function buildCanvasGenerationRequest(input: {
  kind: UserGenerationKind
  projectId: string
  workspaceId: string
  clientRequestId: string
  canvasNode?: UserTextGenerationRequest['canvasNode']
  prompt: string
  node: StudioCanvasNode
  model?: UserModelListItem | null
  references: UserGenerationReference[]
  nodeReferences?: UserGenerationNodeReference[]
  capabilityCode?: string
  session?: UserTextGenerationRequest['session']
}): UserTextGenerationRequest | UserImageGenerationRequest | UserVideoGenerationRequest {
  const common = {
    modelName: input.node.data.model || undefined,
    capabilityCode: input.capabilityCode,
    projectId: input.projectId,
    workspaceId: input.workspaceId,
    clientRequestId: input.clientRequestId,
    canvasNode: input.canvasNode
  }
  if (input.kind === 'text') {
    return {
      ...common,
      prompt: input.prompt,
      ...(input.node.data.imageDescribe ? { funcCode: 'canvas.text.image_describe' } : { session: input.session }),
      references: input.references.length ? input.references : undefined,
      nodeReferences: !input.node.data.imageDescribe && input.nodeReferences?.length ? input.nodeReferences : undefined,
      resultFormat: 'TEXT',
      reasoningEnabled: false,
      includeReasoning: false
    }
  }
  if (input.kind === 'image') {
    const settings = studioCanvasImageParameters(input.node, input.model)
    return {
      ...common,
      prompt: studioCanvasImagePrompt(input.node, input.prompt),
      nodeReferences: input.nodeReferences?.length ? input.nodeReferences : undefined,
      negativePrompt: input.node.data.negativePrompt || undefined,
      size: settings.size,
      expectedImageCount: settings.expectedImageCount,
      references: input.references.length ? input.references : undefined,
      parameters: compactParameters([
        ['aspectRatio', settings.aspectRatio],
        ['styleId', settings.styleId]
      ])
    }
  }
  if (input.kind === 'audio') return { ...common, prompt: input.prompt }
  const settings = studioCanvasVideoParameters(input.node, input.prompt, input.model)
  return {
    ...common,
    ...settings,
    nodeReferences: input.nodeReferences?.length ? input.nodeReferences : undefined,
    references: input.references.length ? input.references : undefined,
  }
}

export function canvasGenerationTaskMessage(task: {
  userMessage?: string | null
  errorMessage?: string | null
}, fallback = '生成失败'): string {
  return String(task.userMessage || task.errorMessage || fallback).trim() || fallback
}
