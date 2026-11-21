'use client'

import { useEffect, useMemo, useRef } from 'react'
import type { CanvasProjectWorkspaceRuntime } from './useCanvasProjectWorkspace'
import type { RunStudioVideoOperationInput } from './useStudioVideoOperation'
import { canvasQuoteVideoTool } from '@/utils/business/canvas'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'
import { useCanvasQuoteReferencePreparation } from './useCanvasQuoteReferencePreparation'
import { canvasVideoToolRequest, canvasVideoToolRoute } from './runCanvasVideoTool'
import { useCanvasQuote } from './useCanvasQuote'
import { canvasResourceMatchesNodeMedia } from '@/utils/canvas/canvasCurrentResultResource'

export function useCanvasVideoToolQuote(
  input: RunStudioVideoOperationInput | null,
  runtime: CanvasProjectWorkspaceRuntime | null
) {
  const workspaceId = runtime?.project?.workspace?.workspaceId
  const ownerNodeId = useStudioUiStore(state => state.activeNodeId)
  const sourceResource = input && runtime?.nodeResources(input.source.id).find(resource => resource.mediaType === 'VIDEO' && canvasResourceMatchesNodeMedia(resource, input.source.data))
  const sourceVersion = input && runtime?.getNodeVersion(input.source.id)
  const preparation = useCanvasQuoteReferencePreparation(input?.source ?? { id: '', data: {} } as StudioCanvasNode, runtime, {
    onlySelf: true, ownerNodeId: ownerNodeId ?? '', auto: Boolean(input && (!sourceResource || !sourceVersion))
  })
  const key = useMemo(() => input && workspaceId ? JSON.stringify({
    workspaceId,
    sourceId: input.source.id,
    sourceVersion,
    sourceUrl: input.source.data.mediaUrl,
    sourceResourceId: sourceResource?.resourceId,
    operation: input.operation,
    modelCode: input.modelCode,
    generationSettings: input.generationSettings,
    resolution: input.resolution,
    interpolationMode: input.interpolationMode,
    frameRate: input.frameRate,
    slowMotionFactor: input.slowMotionFactor,
    regions: input.regions,
    clipRange: input.clipRange,
    prompt: input.prompt,
    subjectEdits: input.subjectEdits
  }) : '', [input, sourceResource?.resourceId, sourceVersion, workspaceId])
  const stableInput = useMemo(() => input, [key]) // eslint-disable-line react-hooks/exhaustive-deps -- key is the serialized quote input
  const runtimeRef = useRef(runtime)
  useEffect(() => { runtimeRef.current = runtime }, [runtime])

  const quote = useCanvasQuote('video-tool', key ? JSON.parse(key) as Record<string, unknown> : null, async (_request, signal) => {
    const currentRuntime = runtimeRef.current
    const source = stableInput
    if (!source || !currentRuntime || !workspaceId || !source.modelCode || !sourceVersion
      || String(currentRuntime.project?.workspace?.workspaceId) !== String(workspaceId)
      || currentRuntime.getNodeVersion(source.source.id) !== sourceVersion) throw new Error('源视频或画布已变化，请重新报价')
    signal.throwIfAborted()
    const route = canvasVideoToolRoute(source.operation)
    if (!route) throw new Error('当前视频工具暂不支持报价')
    if (!sourceResource || sourceResource.mediaType !== 'VIDEO') throw new Error('原视频尚未准备好，请先补齐引用后报价')
    const replacementIds = source.operation === 'SUBJECT_REPLACE'
      ? (source.subjectEdits ?? []).map(mark => mark.replacementResourceId) : []
    if (replacementIds.some(id => !id)) throw new Error('请重新登记主体替换参考图')
    const imageIds = [...new Set(replacementIds.filter((id): id is string => Boolean(id)))]
    const request = await canvasVideoToolRequest(source, source.modelCode, route.capabilityCode, imageIds)
    signal.throwIfAborted()
    const registeredSourceVersion = currentRuntime.getNodeVersion(source.source.id)
    if (!registeredSourceVersion || registeredSourceVersion !== sourceVersion) throw new Error('源视频已变化，请重新报价')
    return canvasQuoteVideoTool({
      workspaceId, resourceIds: [sourceResource.resourceId, ...imageIds],
      videoTool: { operation: route.operation, sourceObjectId: source.source.id,
        sourceExpectedVersion: registeredSourceVersion, sourceResourceId: sourceResource.resourceId },
      request
    }, signal)
  }, key && !sourceVersion ? '源视频节点尚未保存' : key && !input?.modelCode ? '请先选择视频模型'
    : key && !sourceResource ? '原视频尚未准备好，请先补齐引用后报价' : '')
  return { ...quote, quote: preparation.busy || preparation.error ? null : quote.quote,
    loading: preparation.busy || quote.loading,
    status: preparation.busy ? 'LOADING' as const : preparation.error ? 'BLOCKED' as const : quote.status,
    error: preparation.error || quote.error,
    preparingReferences: preparation.busy }
}
