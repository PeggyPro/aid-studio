import type { BillingQuoteRequest, UserModelListItem } from '@/types/business-api'
import type { StudioCanvasNode } from '@/types/studio'
import { canvasVideoUsesFirstFrame, resolveCanvasVideoCapability } from '@/utils/studio/canvasGenerationModels'
import { subjectEditPrompt } from '@/utils/studio/studioVideoSubject'
import { studioCanvasImageParameters, studioCanvasImagePrompt, studioCanvasVideoParameters } from './studioCanvasGenerationParameters'

/**
 * 视频按时长和分辨率计价时，空提示词无法通过媒体校验。
 * 选型阶段用固定短句占位，用户输入后改用真实提示词重新报价。
 */
const VIDEO_SELECTION_PROMPT = '生成'

export function buildStudioCanvasLiveQuote(input: {
  node: StudioCanvasNode
  modelCode: string
  model?: UserModelListItem | null
  prompt: string
  promptOptional?: boolean
}): BillingQuoteRequest | null {
  const modelCode = input.modelCode.trim()
  if (!modelCode) return null
  const kind = input.node.data.kind
  if (kind === 'image') return imageQuote(input.node, modelCode, input.prompt, input.model)
  if (kind === 'video') return videoQuote(input.node, modelCode, input.prompt, input.promptOptional === true, input.model)
  if (kind === 'text') return textQuote(modelCode, input.prompt)
  return null
}

function imageQuote(node: StudioCanvasNode, modelCode: string, prompt: string, model?: UserModelListItem | null): BillingQuoteRequest {
  const settings = studioCanvasImageParameters(node, model)
  const resolvedPrompt = studioCanvasImagePrompt(node, prompt)
  const referenceImageCount = imageReferenceCount(node)
  const generateMode = referenceImageCount <= 0
    ? 'TEXT_TO_IMAGE'
    : node.data.mediaUrl
      ? 'IMAGE_EDIT'
      : 'IMAGE_TO_IMAGE'
  const count = settings.expectedImageCount
  const { size, aspectRatio } = settings
  return {
    quoteType: 'MEDIA_IMAGE_PRICING',
    payload: {
      modelCode,
      generateMode,
      ...(resolvedPrompt.trim() ? { prompt: resolvedPrompt } : {}),
      ...(size ? { size, resolution: size } : {}),
      ...(aspectRatio ? { aspectRatio } : {}),
      imageCount: count,
      expectedImageCount: count,
      referenceImageCount
    }
  }
}

function videoQuote(
  node: StudioCanvasNode,
  modelCode: string,
  prompt: string,
  promptOptional: boolean,
  model?: UserModelListItem | null
): BillingQuoteRequest | null {
  const resolvedPrompt = resolveVideoPrompt(node, prompt, promptOptional)
  if (resolvedPrompt == null) return null
  const options = node.data.generationOptions ?? {}
  const settings = studioCanvasVideoParameters(node, resolvedPrompt, model)
  const capabilityCode = videoCapability(node, model)
  const durationSeconds = videoDurationSeconds(node) ?? settings.durationSeconds
  const { aspectRatio } = settings
  const imageUrls = canvasVideoUsesFirstFrame(capabilityCode) ? [...new Set((node.data.composerRefs ?? [])
    .filter((ref) => ref.mediaKind === 'image' && ref.previewUrl)
    .map((ref) => ref.previewUrl!.trim())
    .filter(Boolean))] : []
  // The workspace restores reference URLs from registered resources, while frame
  // URLs in generationOptions are intentionally not persisted. Use the first
  // connected image as the same default first frame used by submission.
  const selectedFirst = options.startFrameUrl?.trim()
  const firstFrame = selectedFirst && imageUrls.includes(selectedFirst) ? selectedFirst : imageUrls[0]
  const selectedLast = options.endFrameEnabled ? options.endFrameUrl?.trim() : undefined
  const lastFrame = selectedLast && selectedLast !== firstFrame && imageUrls.includes(selectedLast)
    ? selectedLast : undefined
  const referenceImages = imageUrls.filter((url) => url !== firstFrame && url !== lastFrame)
  const quoteOptions = {
    ...settings.parameters,
    ...(lastFrame ? { lastFrameImageUrl: lastFrame } : {}),
    ...(referenceImages.length ? { referenceImages } : {})
  }
  return {
    quoteType: 'MEDIA_VIDEO',
    payload: {
      modelName: modelCode,
      capabilityCode,
      ...(settings.audio !== undefined ? { audio: settings.audio } : {}),
      ...(settings.prompt ? { prompt: settings.prompt } : {}),
      ...(firstFrame ? { imageUrl: firstFrame } : {}),
      ...(durationSeconds !== undefined ? { durationSeconds } : {}),
      ...(aspectRatio ? { aspectRatio } : {}),
      ...(Object.keys(quoteOptions).length ? { options: quoteOptions } : {})
    }
  }
}

function textQuote(modelCode: string, prompt: string): BillingQuoteRequest | null {
  const text = prompt.trim()
  if (!text) return null
  return {
    quoteType: 'MEDIA_TEXT',
    payload: {
      modelName: modelCode,
      prompt
    }
  }
}

function resolveVideoPrompt(node: StudioCanvasNode, prompt: string, promptOptional: boolean): string | null {
  const typed = prompt.trim()
  const edit = node.data.videoSubjectEdit
  if (edit) {
    const composed = [subjectEditPrompt(edit), typed].filter(Boolean).join('\n')
    if (composed) return composed
    return promptOptional ? '' : null
  }
  if (node.data.videoPurpose === 'reshoot') return typed || '按原片段内容重拍。'
  if (typed) return prompt
  if (promptOptional) return ''
  return VIDEO_SELECTION_PROMPT
}

function videoCapability(node: StudioCanvasNode, model?: UserModelListItem | null): string {
  const edit = node.data.videoSubjectEdit
  if (edit) return edit.mode === 'matting' ? 'portrait_foreground' : 'video_edit'
  if (node.data.videoPurpose === 'reshoot') return 'video_edit'
  if (node.data.videoPurpose === 'continuation') return 'video_extend'
  return resolveCanvasVideoCapability({ node, model })
}

function videoDurationSeconds(node: StudioCanvasNode): number | undefined {
  const clip = node.data.videoSubjectEdit?.range
    ?? (node.data.videoPurpose === 'reshoot' ? node.data.videoToolOptions?.clipRange : undefined)
  if (clip) {
    const seconds = Math.ceil(clip.endSeconds - clip.startSeconds)
    if (Number.isFinite(seconds) && seconds >= 1) return seconds
  }
  return undefined
}

function imageReferenceCount(node: StudioCanvasNode): number {
  const keys = new Set<string>()
  const resourceId = (value: unknown) => /^\d+$/.test(String(value ?? '')) && String(value) !== '0' ? String(value) : undefined
  const primary = resourceId(node.data.canvasPrimaryResourceId)
  if (node.data.mediaUrl) keys.add(primary ? `resource:${primary}` : `url:${node.data.mediaUrl}`)
  for (const ref of node.data.composerRefs ?? []) {
    if (ref.mediaKind !== 'image') continue
    const id = resourceId(ref.assetId) ?? (ref.previewUrl === node.data.mediaUrl ? primary : undefined)
    keys.add(id ? `resource:${id}` : ref.nodeId ? `node:${ref.nodeId}` : ref.previewUrl ? `url:${ref.previewUrl}` : `ref:${ref.id}`)
  }
  return keys.size
}
