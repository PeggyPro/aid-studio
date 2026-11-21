'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { studioEditorSourceChanged } from '@/utils/studio/studioEditorSource'
import { message } from 'antd'
import { useCanvasGatewayIdentity } from './useCanvasGatewayIdentity'
import { canvasQuoteIdentity } from '@/utils/canvas/canvasQuote'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { useStudioUiStore } from '@/stores/studioUi'
import type { UserModelListByFuncGroupVO } from '@/types/business-api'
import type { StudioCanvasNode, StudioImagePurpose } from '@/types/studio'
import type { CanvasImageEditInput } from './useCanvasProjectWorkspace'
import { canvasUserModelListByFuncCodes } from '@/utils/business/model'
import { runStudioRequestSingleFlight } from '@/utils/studio/studioRequestSingleFlight'
import { studioNodeToComposerRef } from '@/utils/studio/studioReferencePicker'
import { findModelCapability, modelSupportsCapability } from '@/utils/modelCapability'

export const IMAGE_EDIT_FUNC_CODES = [
  'canvas.image.redraw', 'canvas.image.erase', 'canvas.image.expand', 'canvas.image.relight',
  'canvas.image.touch_edit', 'canvas.image.emotion', 'canvas.image.portrait_texture',
  'canvas.image.multi_view', 'canvas.image.style_apply'
] as const

type EditFuncCode = typeof IMAGE_EDIT_FUNC_CODES[number]
type PendingEdit = { funcCode: EditFuncCode; capabilityCode: CanvasImageEditInput['capabilityCode']; modelCode: string; purpose: StudioImagePurpose; label: string; prompt: string; expectedImageCount?: number }

export function useCanvasImageEdit(sourceNode: StudioCanvasNode) {
  const { canvasCurrentResultId: resultId, canvasPrimaryResourceId: resourceId, mediaUrl } = sourceNode.data
  const expectedSource = useMemo(() => ({ resultId, resourceId, mediaUrl }), [resultId, resourceId, mediaUrl])
  const assertSourceCurrent = useCallback((source: StudioCanvasNode) => {
    const state = useStudioUiStore.getState()
    const session = state.imageEditSession ?? state.touchEditSession ?? state.layerSplitSession
    if (studioEditorSourceChanged(expectedSource, source)
      || session?.nodeId === source.id && studioEditorSourceChanged(session.sourceIdentity, source)) {
      throw new Error('原图已更新，请先确认当前编辑使用的素材')
    }
  }, [expectedSource])
  const runtime = useCanvasProjectRuntime()
  const { identity, connected } = useCanvasGatewayIdentity()
  const enabled = Boolean(runtime)
  const projectId = runtime?.project?.projectId
  const catalogKey = connected && projectId ? `${identity}:${projectId}` : ''
  const [catalogState, setCatalogState] = useState<{ key: string; groups: UserModelListByFuncGroupVO[]; error: string }>({ key: '', groups: [], error: '' })
  const catalog = useMemo(() => catalogKey && catalogState.key === catalogKey ? catalogState.groups : [], [catalogState, catalogKey])
  const loading = enabled && Boolean(catalogKey) && catalogState.key !== catalogKey
  const error = catalogKey && catalogState.key === catalogKey ? catalogState.error : ''
  const disabledReason = !connected && enabled ? '画布连接尚未就绪' : !runtime?.canEdit ? '当前账号只有查看权限'
    : !runtime.canUseFeature('canvas.generate') ? '当前账号没有画布生成权限'
      : runtime.isNodeBusy(sourceNode.id) ? '原图节点正在生成中' : ''

  useEffect(() => {
    if (!enabled || !projectId || !catalogKey) return
    let active = true
    void canvasUserModelListByFuncCodes(IMAGE_EDIT_FUNC_CODES, String(projectId)).then((groups) => {
      if (active) setCatalogState({ key: catalogKey, groups, error: '' })
    }).catch((reason: unknown) => {
      if (active) setCatalogState({ key: catalogKey, groups: [], error: reason instanceof Error ? reason.message : '图片工具模型目录加载失败' })
    })
    return () => { active = false }
  }, [enabled, projectId, catalogKey])

  const select = useCallback((funcCode: EditFuncCode, capabilityCode: CanvasImageEditInput['capabilityCode'], modelCode?: string) => {
    const group = catalog.find((item) => item.funcCode === funcCode)
    const models = (group?.models ?? []).filter((model) => modelSupportsCapability(model, capabilityCode))
    const model = modelCode
      ? models.find((item) => item.modelCode === modelCode)
      : models.find((item) => item.modelCode === group?.defaultModelCode) ?? models[0]
    const capability = findModelCapability(model, capabilityCode)
    return { models, model, capability }
  }, [catalog])

  const reserve = useCallback((input: PendingEdit) => {
    if (!runtime || disabledReason) throw new Error(disabledReason || '当前画布暂时不能编辑图片')
    const { model } = select(input.funcCode, input.capabilityCode, input.modelCode)
    if (!model) throw new Error('所选图片编辑模型已不可用，请重新选择')
    const store = useStudioUiStore.getState()
    const source = store.nodes.find((item) => item.id === sourceNode.id)
    if (!source?.data.mediaUrl || source.data.kind !== 'image') throw new Error('原图节点已失效')
    assertSourceCurrent(source)
    const resultId = store.deriveImageNode(source.id, source.data.generationOptions ?? {}, input.purpose, input.label, input.prompt)
    if (!resultId) throw new Error('无法创建图片编辑结果节点')
    store.updateNodeData(resultId, {
      model: model.modelCode, modelName: model.modelName,
      modelCostCredits: model.costCredits, modelIsFree: model.isFree,
      ...(input.expectedImageCount ? { generationOptions: { ...source.data.generationOptions, count: input.expectedImageCount } } : {}),
      imageToolOptions: { sourceNodeId: source.id, modelCode: model.modelCode }
    })
    // Own the target before mask upload / quote preflight can yield to autosave
    // or another node's terminal snapshot.
    runtime.reserveGenerationNode(resultId)
    return resultId
  }, [runtime, disabledReason, select, sourceNode.id, assertSourceCurrent])

  const submit = useCallback(async (input: Omit<CanvasImageEditInput, 'sourceNodeId' | 'funcCode'> & { funcCode: EditFuncCode; label: string; purpose: StudioImagePurpose; prompt: string; targetId?: string }) => {
    if (!runtime || disabledReason) {
      if (input.targetId) runtime?.releaseGenerationNode(input.targetId)
      message.warning(disabledReason || '当前画布暂时不能编辑图片')
      return false
    }
    const { model } = select(input.funcCode, input.capabilityCode, input.modelCode)
    if (!model) {
      if (input.targetId) runtime.releaseGenerationNode(input.targetId)
      message.warning('所选图片编辑模型已不可用，请重新选择')
      return false
    }
    const initialStore = useStudioUiStore.getState()
    const initialSource = initialStore.nodes.find((item) => item.id === sourceNode.id)
    if (!initialSource?.data.mediaUrl || initialSource.data.kind !== 'image') {
      if (input.targetId) runtime.releaseGenerationNode(input.targetId)
      message.error('原图节点已失效')
      return false
    }
    try { assertSourceCurrent(initialSource) }
    catch (reason) {
      if (input.targetId) runtime.releaseGenerationNode(input.targetId)
      message.error(reason instanceof Error ? reason.message : '原图已更新，请重新确认')
      return false
    }
    const resultId = input.targetId ?? reserve(input)
    // Submission identity belongs to the generated target. Two edits of the
    // same source/function are valid independent tasks and must not share one promise.
    const key = `${initialStore.scopeKey}:${resultId}:image-edit`
    return runStudioRequestSingleFlight(key, async () => {
      const store = useStudioUiStore.getState()
      const source = store.nodes.find((item) => item.id === sourceNode.id)
      if (!source?.data.mediaUrl || source.data.kind !== 'image') throw new Error('原图节点已失效')
      const target = store.nodes.find((item) => item.id === resultId)
      const targetSourceNodeId = target?.data.imageToolOptions?.sourceNodeId
        || target?.data.composerRefs?.find((ref) => ref.nodeId)?.nodeId
      if (!target || targetSourceNodeId !== source.id || target.data.model !== model.modelCode) {
        runtime.releaseGenerationNode(resultId)
        throw new Error('图片编辑结果节点已失效')
      }
      try {
        assertSourceCurrent(source)
        return await runtime.generateNode('image', resultId, input.prompt, {
          imageEdit: {
            sourceNodeId: source.id, modelCode: model.modelCode,
            funcCode: input.funcCode, capabilityCode: input.capabilityCode,
            imageEdit: input.imageEdit, expectedImageCount: input.expectedImageCount,
            size: input.size, parameters: input.parameters
          }
        })
      } catch (reason: unknown) {
        message.error(reason instanceof Error ? reason.message : '图片编辑提交失败')
        return false
      } finally {
        runtime.releaseGenerationNode(resultId)
      }
    })
  }, [runtime, disabledReason, select, sourceNode.id, reserve, assertSourceCurrent])

  const ensureSourceResource = useCallback(async () => {
    if (!runtime) throw new Error('画布工作区尚未就绪')
    if (!connected) throw new Error('画布连接尚未就绪')
    const current = useStudioUiStore.getState().nodes.find((item) => item.id === sourceNode.id)
    if (!current?.data.mediaUrl) throw new Error('原图节点已失效')
    assertSourceCurrent(current)
    const resource = await runtime.resolveResourceReference(studioNodeToComposerRef(current))
    if (canvasQuoteIdentity() !== identity) throw new Error('画布账号或连接已变化，请重新准备原图')
    const latest = useStudioUiStore.getState().nodes.find(item => item.id === sourceNode.id)
    if (!latest) throw new Error('原图节点已失效')
    assertSourceCurrent(latest)
    return resource
    // Re-run panel preparation after connection recovery, even when its project/source did not change.
  }, [runtime, sourceNode.id, identity, connected, assertSourceCurrent])

  const release = useCallback((nodeId: string) => runtime?.releaseGenerationNode(nodeId), [runtime])

  return { active: enabled, loading, error, disabledReason, select, reserve, release, submit, ensureSourceResource, project: runtime?.project, uploadResource: runtime?.uploadResource }
}
