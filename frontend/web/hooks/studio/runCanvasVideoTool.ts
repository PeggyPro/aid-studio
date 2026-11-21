import { message } from 'antd'
import type { CanvasProjectWorkspaceRuntime } from './useCanvasProjectWorkspace'
import type { RunStudioVideoOperationInput } from './useStudioVideoOperation'
import { canvasUserModelListByFuncCodes } from '@/utils/business/model'
import { useStudioUiStore } from '@/stores/studioUi'
import { modelForCapability, modelSupportsCapability } from '@/utils/modelCapability'
import { resolveVideoGenerationRequestSettings } from '@/utils/videoGenerationRequest'
import type { UserModelListItem } from '@/types/business-api'
import { studioNodeToComposerRef } from '@/utils/studio/studioReferencePicker'
import { canvasQuoteVideoTool } from '@/utils/business/canvas'
import { selectedSubject } from '@/utils/studio/studioVideoSubject'

type Route = { funcCode: string; capabilityCode: string; operation: string }

const ROUTES: Record<string, Route> = {
  ENHANCE: { funcCode: 'video_enhance', capabilityCode: 'video_enhance', operation: 'ENHANCE' },
  DEPTH_CAPTURE: { funcCode: 'video_depth_map', capabilityCode: 'video_depth_map', operation: 'DEPTH_CAPTURE' },
  REMOVE_SUBTITLE_AUTO: { funcCode: 'video_subtitle_remove', capabilityCode: 'video_subtitle_remove', operation: 'SUBTITLE_AUTO' },
  REMOVE_SUBTITLE_REGION: { funcCode: 'video_subtitle_remove', capabilityCode: 'video_subtitle_remove', operation: 'SUBTITLE_REGION' },
  RESHOOT: { funcCode: 'canvas.video.reshoot', capabilityCode: 'video_edit', operation: 'RESHOOT' },
  CONTINUE: { funcCode: 'canvas.video.continue', capabilityCode: 'video_extend', operation: 'CONTINUE' },
  SUBJECT_REMOVE: { funcCode: 'canvas.video.subject_edit', capabilityCode: 'video_edit', operation: 'SUBJECT_REMOVE' },
  SUBJECT_MODIFY: { funcCode: 'canvas.video.subject_edit', capabilityCode: 'video_edit', operation: 'SUBJECT_MODIFY' },
  SUBJECT_REPLACE: { funcCode: 'canvas.video.subject_edit', capabilityCode: 'video_edit', operation: 'SUBJECT_REPLACE' },
  SUBJECT_MATTING: { funcCode: 'video_portrait_segmentation', capabilityCode: 'portrait_foreground', operation: 'PORTRAIT_FOREGROUND' },
  VOICE_ONLY: { funcCode: 'audio_voice_separation', capabilityCode: 'voice_only', operation: 'VOICE_ONLY' },
  BACKGROUND_ONLY: { funcCode: 'audio_voice_separation', capabilityCode: 'background_only', operation: 'BACKGROUND_ONLY' }
}

export function canvasVideoToolRoute(operation: string): Route | null {
  return ROUTES[operation] ?? null
}

async function videoDurationMs(url: string): Promise<number> {
  return new Promise((resolve, reject) => {
    const video = document.createElement('video')
    const timeout = window.setTimeout(() => {
      cleanup()
      reject(new Error('读取源视频时长超时，请检查素材地址'))
    }, 15_000)
    const cleanup = () => {
      window.clearTimeout(timeout)
      video.onloadedmetadata = null
      video.onerror = null
      video.removeAttribute('src')
      video.load()
    }
    video.preload = 'metadata'
    video.onloadedmetadata = () => {
      const duration = Math.floor(video.duration * 1000)
      cleanup()
      if (!Number.isFinite(duration) || duration < 1000) reject(new Error('无法读取源视频时长'))
      else resolve(duration)
    }
    video.onerror = () => { cleanup(); reject(new Error('源视频无法读取，请检查素材地址')) }
    video.src = url
  })
}

export async function canvasVideoToolParameters(input: RunStudioVideoOperationInput): Promise<Record<string, unknown>> {
  switch (input.operation) {
    case 'ENHANCE':
      if (input.interpolationMode !== 'high_quality' && (input.slowMotionFactor ?? 1) > 1) {
        throw new Error('慢放需要先开启高质量补帧')
      }
      return {
        targetResolution: input.resolution === '1080' ? '1080p' : input.resolution === '720' ? '720p' : input.resolution,
        interpolationMode: input.interpolationMode === 'high_quality' ? 'HIGH_QUALITY' : 'NONE',
        ...(input.interpolationMode === 'high_quality' && input.frameRate ? { targetFps: input.frameRate } : {}),
        slowMotionFactor: input.slowMotionFactor ?? 1
      }
    case 'REMOVE_SUBTITLE_AUTO': return { eraseMode: 'AUTO', subtitleModel: 'standard' }
    case 'REMOVE_SUBTITLE_REGION': {
      if (!input.regions?.length) throw new Error('请先框选字幕区域')
      const endMs = (await videoDurationMs(input.source.data.mediaUrl || '')) - 1
      return {
        eraseMode: 'CUSTOM', subtitleModel: 'area',
        areas: [{ beginMs: 0, endMs, boxes: input.regions.map(({ rect }) => ({
          x1: rect.x, y1: rect.y, x2: rect.x + rect.w, y2: rect.y + rect.h
        })) }]
      }
    }
    case 'RESHOOT':
      if (!input.clipRange) throw new Error('请先选择重拍片段')
      return { ...input.generationSettings?.options }
    case 'CONTINUE': return { ...input.generationSettings?.options }
    case 'SUBJECT_REMOVE':
    case 'SUBJECT_MODIFY':
    case 'SUBJECT_REPLACE':
      return {}
    default: return {}
  }
}

export function canvasVideoToolWithModelSettings(input: RunStudioVideoOperationInput, model: UserModelListItem): RunStudioVideoOperationInput {
  if (input.operation !== 'RESHOOT' && input.operation !== 'CONTINUE') return input
  const capability = input.operation === 'RESHOOT' ? 'video_edit' : 'video_extend'
  const settings = input.generationSettings
  const generationSettings = resolveVideoGenerationRequestSettings({
    quality: settings?.options.resolution, duration: settings?.durationSeconds, aspectRatio: settings?.aspectRatio
  }, modelForCapability(model, capability)!)
  if (settings && JSON.stringify(settings) !== JSON.stringify(generationSettings)) throw new Error('模型参数已变化，请重新选择规格并确认报价')
  return { ...input, generationSettings }
}

/** 预览、确认与提交均从同一字段构造请求，避免报价使用服务端默认规格。 */
export async function canvasVideoToolRequest(input: RunStudioVideoOperationInput, modelCode: string, capabilityCode: string, imageIds: string[]) {
  const settings = input.operation === 'RESHOOT' || input.operation === 'CONTINUE' ? input.generationSettings : undefined
  return {
    modelName: modelCode, capabilityCode, prompt: canvasVideoToolPrompt(input, imageIds),
    ...(settings?.durationSeconds != null ? { durationSeconds: settings.durationSeconds } : {}),
    ...(settings?.aspectRatio ? { aspectRatio: settings.aspectRatio } : {}),
    options: await canvasVideoToolParameters(input)
  }
}

export function canvasVideoToolPrompt(input: RunStudioVideoOperationInput, imageIds: string[]): string {
  const promptBase = input.operation === 'SUBJECT_MATTING' ? '' : input.operation === 'RESHOOT' && input.clipRange
    ? (() => {
        const start = Math.floor(input.clipRange!.startSeconds)
        const end = Math.ceil(input.clipRange!.endSeconds)
        if (end <= start) throw new Error('重拍片段至少需要覆盖一整秒')
        return `仅编辑@video1第${start}秒至第${end}秒的片段，其余时段保持不变。${input.prompt?.trim() || '按原片段内容重拍。'}`
      })()
    : input.operation === 'CONTINUE'
      ? `在@video1结尾继续生成后续片段。${input.prompt?.trim() || ''}`
    : input.prompt?.trim() ?? ''
  const subjectMarks = input.operation.startsWith('SUBJECT_') && input.operation !== 'SUBJECT_MATTING'
    ? (input.subjectEdits ?? []).map((mark, index) => {
        const subject = selectedSubject(mark)
        if (!subject || !mark.confirmed) throw new Error('主体标记已变化，请重新确认')
        const { x, y, w, h } = subject.rect
        const action = input.operation === 'SUBJECT_REMOVE' ? '删除该主体并补全背景'
          : input.operation === 'SUBJECT_MODIFY' ? `按要求修改：${mark.instruction.trim()}`
            : `替换为参考图片${imageIds.indexOf(mark.replacementResourceId || '') + 1}中的主体`
        if (input.operation === 'SUBJECT_MODIFY' && !mark.instruction.trim()) throw new Error('请填写主体修改要求')
        return `目标${index + 1}：${mark.frameSeconds.toFixed(2)}秒画面中的${subject.label}，范围约为左${Math.round(x * 100)}%、上${Math.round(y * 100)}%、宽${Math.round(w * 100)}%、高${Math.round(h * 100)}%；${action}。`
      }).join('\n') : ''
  return [promptBase, subjectMarks].filter(Boolean).join('\n')
}

/** 画布任务链路负责资源、锁、幂等和刷新恢复；不使用旧的 studio/video/operation 占位接口。 */
export async function runCanvasVideoTool(
  input: RunStudioVideoOperationInput,
  runtime: CanvasProjectWorkspaceRuntime
): Promise<boolean> {
  const route = canvasVideoToolRoute(input.operation)
  if (!route) { message.error('当前视频操作尚未接入画布任务'); return false }
  const projectId = runtime.project?.projectId
  if (!projectId) { message.error('请先打开画布项目'); return false }
  const initial = useStudioUiStore.getState().nodes.find((item) => item.id === input.targetNodeId)
  if (!initial || initial.data.videoToolOptions?.sourceNodeId !== input.source.id) {
    message.error('视频结果节点或源节点已变化，请重新发起')
    return false
  }
  const scopeKey = useStudioUiStore.getState().scopeKey
  const targetPrompt = initial.data.prompt
  const targetEdit = JSON.stringify(initial.data.videoSubjectEdit)
  const isCurrent = () => {
    const state = useStudioUiStore.getState()
    const source = state.nodes.find((item) => item.id === input.source.id)
    const target = state.nodes.find((item) => item.id === input.targetNodeId)
    return state.scopeKey === scopeKey && source?.data.mediaUrl === input.source.data.mediaUrl
      && Boolean(source?.data.mediaUrl) && target?.data.videoToolOptions?.sourceNodeId === input.source.id
      && target.data.prompt === targetPrompt && JSON.stringify(target.data.videoSubjectEdit) === targetEdit
  }
  if (!isCurrent()) { message.error('视频结果节点或源视频已变化，请重新发起'); return false }
  runtime.reserveGenerationNode(input.targetNodeId)
  try {
  const groups = await canvasUserModelListByFuncCodes([route.funcCode], String(projectId))
  const group = groups.find((item) => item.funcCode === route.funcCode)
  const models = (group?.models ?? []).filter((item) => modelSupportsCapability(item, route.capabilityCode))
  const requested = String(input.modelCode || '').trim()
  const selected = requested
    ? models.find((item) => item.modelCode === requested)
    : models.find((item) => item.modelCode === group?.defaultModelCode) ?? models[0]
  if (!selected) {
    throw new Error(requested ? '所选模型未绑定此视频工具，请重新选择' : '此视频工具暂未配置可用模型')
  }
  const sourceResource = await runtime.resolveResourceReference(studioNodeToComposerRef(input.source), { isCurrent })
  if (sourceResource.mediaType !== 'VIDEO') throw new Error('源视频资源类型错误')
  const replacementIds = input.operation === 'SUBJECT_REPLACE'
    ? (input.subjectEdits ?? []).map((mark) => mark.replacementResourceId)
    : []
  if (replacementIds.some((id) => !id)) throw new Error('请重新登记主体替换参考图')
  const imageIds = [...new Set(replacementIds.filter((id): id is string => Boolean(id)))]
  const node = useStudioUiStore.getState().nodes.find((item) => item.id === input.targetNodeId)
  if (!node || !isCurrent() || (node.data.model !== initial.data.model && node.data.model !== selected.modelCode)) {
    throw new Error('视频结果节点或源节点已变化，请重新发起')
  }
  useStudioUiStore.getState().updateNodeData(input.targetNodeId, {
    model: selected.modelCode, modelName: selected.modelName,
    modelCostCredits: selected.costCredits, modelIsFree: selected.isFree
  })
  const request = await canvasVideoToolRequest(canvasVideoToolWithModelSettings(input, selected), selected.modelCode, route.capabilityCode, imageIds)
  const workspaceId = runtime.project?.workspace?.workspaceId
  const sourceExpectedVersion = runtime.getNodeVersion(input.source.id)
  if (!workspaceId || !sourceExpectedVersion) throw new Error('源视频节点尚未保存')
  if (!isCurrent()) throw new Error('视频工具输入已变化，请重新报价')
  await canvasQuoteVideoTool({
    workspaceId,
    resourceIds: [sourceResource.resourceId, ...imageIds],
    videoTool: {
      operation: route.operation,
      sourceObjectId: input.source.id,
      sourceExpectedVersion,
      sourceResourceId: sourceResource.resourceId
    },
    request
  })
  const quotedTarget = useStudioUiStore.getState().nodes.find((item) => item.id === input.targetNodeId)
  if (!isCurrent() || quotedTarget?.data.model !== selected.modelCode
    || runtime.getNodeVersion(input.source.id) !== sourceExpectedVersion) {
    throw new Error('视频工具输入已变化，请重新报价')
  }
  return await runtime.generateNode('video', input.targetNodeId, targetPrompt, {
    videoTool: {
      sourceNodeId: input.source.id,
      sourceExpectedVersion,
      sourceResourceId: String(sourceResource.resourceId),
      modelCode: selected.modelCode,
      funcCode: route.funcCode,
      capabilityCode: route.capabilityCode,
      operation: route.operation,
      requestPrompt: request.prompt,
      parameters: request.options,
      durationSeconds: request.durationSeconds,
      aspectRatio: request.aspectRatio,
      referenceImageResourceIds: imageIds
    }
  })
  } catch (error) {
    const reason = error instanceof Error ? error.message : '视频工具提交失败'
    if (useStudioUiStore.getState().scopeKey === scopeKey
      && useStudioUiStore.getState().nodes.some((node) => node.id === input.targetNodeId)) {
      useStudioUiStore.getState().updateNodeData(input.targetNodeId, {
        status: 'failed', errorMessage: reason, resultSummary: '提交失败'
      })
    }
    message.error(reason)
    return false
  } finally {
    runtime.releaseGenerationNode(input.targetNodeId)
  }
}
