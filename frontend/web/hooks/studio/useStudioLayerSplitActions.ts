'use client'

import { useCallback } from 'react'
import { message } from 'antd'
import { useCreationStore } from '@/stores/creation'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { createStudioTask, useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'
import { userLayerMerge, userLayerSplit } from '@/utils/business/media'
import { placeStudioNodeWithoutOverlap, toStudioNodeRect } from '@/utils/studio/studioNodePlacement'
import { studioNodeToComposerRef } from '@/utils/studio/studioReferencePicker'
import { buildLayerExtractNodeData } from '@/utils/studio/studioLayerSeparation'
import { buildLayerSplitRequest } from '@/utils/studio/studioLayerSplit'
import { applyLayerSplitSuccess } from '@/utils/studio/studioLayerSplitSubmit'
import { studioId } from '@/utils/studio/studioGraph'
import { recoverStudioRemoteTask } from '@/utils/studio/studioRemoteTaskRecovery'
import { requireSafeStudioMediaUrl, studioRemoteMediaUrlProblem } from '@/utils/mediaUrlPolicy'
import { fetchMediaBlob } from '@/utils/mediaFetch'
import { loadStudioCanvasImage } from '@/utils/studio/studioCanvasImageLoad'
import { createStudioOperationInputHash, isStudioOperationInputCurrent } from '@/utils/studio/studioOperationRun'
import { runStudioRequestSingleFlight } from '@/utils/studio/studioRequestSingleFlight'

async function mapWithConcurrency<T, R>(
  items: T[],
  concurrency: number,
  mapper: (item: T, index: number) => Promise<R>
): Promise<R[]> {
  const result = new Array<R>(items.length)
  let cursor = 0
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (cursor < items.length) {
      const index = cursor++
      result[index] = await mapper(items[index]!, index)
    }
  }))
  return result
}

export function useStudioLayerSplitActions(sourceNode: StudioCanvasNode) {
  const canvasRuntime = useCanvasProjectRuntime()
  const projectId = useCreationStore((state) => state.currentProjectId)
  const addNode = useStudioUiStore((state) => state.addNode)
  const addConnection = useStudioUiStore((state) => state.addConnection)
  const selectNodes = useStudioUiStore((state) => state.selectNodes)
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const closeLayerSplitSession = useStudioUiStore((state) => state.closeLayerSplitSession)

  const focusNode = useCallback((nodeId: string) => {
    window.dispatchEvent(new CustomEvent('studio:focus-node', { detail: { nodeId } }))
  }, [])

  const startSplit = useCallback(async () => {
    if (!sourceNode.data.mediaUrl) {
      message.warning('请先上传或生成源图片')
      return
    }
    const mediaProblem = studioRemoteMediaUrlProblem(sourceNode.data.mediaUrl)
    if (mediaProblem) {
      message.warning(mediaProblem)
      return
    }
    const session = useStudioUiStore.getState().layerSplitSession
    if (!session || session.nodeId !== sourceNode.id) {
      message.warning('请先进入图层分离')
      return
    }

    const body = buildLayerSplitRequest({
      image: sourceNode.data.mediaUrl,
      projectId: projectId && projectId > 0 ? projectId : undefined,
      regions: session.regions,
      draftPrompt: session.draftPrompt
    })

    const occupied = useStudioUiStore.getState().nodes.map((item) => toStudioNodeRect(item))
    const desired = { x: sourceNode.position.x + 310, y: sourceNode.position.y }
    const position = placeStudioNodeWithoutOverlap(desired, { width: 240, height: 180 }, occupied)
    const resultId = addNode('image', position, {
      title: '图层分离',
      subtitle: `由 ${sourceNode.data.title} 分离`,
      zoneId: sourceNode.data.zoneId,
      status: 'generating',
      mediaKind: 'image',
      imagePurpose: 'layer_separation',
      prompt: sourceNode.data.prompt,
      storyboardId: sourceNode.data.storyboardId,
      composerHidden: true,
      composerRefs: [studioNodeToComposerRef(sourceNode)],
      imageToolOptions: { sourceNodeId: sourceNode.id },
      resultSummary: '图层分离中'
    })
    addConnection({ source: sourceNode.id, target: resultId, sourceHandle: null, targetHandle: null }, 'reference')
    selectNodes([resultId])
    closeLayerSplitSession()
    window.requestAnimationFrame(() => focusNode(resultId))

    const scopeKey = useStudioUiStore.getState().scopeKey
    const operationRunId = studioId('layer-split-run')
    useStudioUiStore.getState().updateNodeData(resultId, { operationRunId }, false)
    useStudioUiStore.getState().updateNodeData(sourceNode.id, { operationRunId }, false)
    const inputNodeIds = [sourceNode.id, resultId]
    const inputSnapshotHash = createStudioOperationInputHash(scopeKey, inputNodeIds)
    try {
      const data = await userLayerSplit(body)
      const store = useStudioUiStore.getState()
      if (
        store.scopeKey !== scopeKey ||
        !store.nodes.some((item) => item.id === resultId && item.data.operationRunId === operationRunId) ||
        !store.nodes.some((item) => item.id === sourceNode.id && item.data.operationRunId === operationRunId) ||
        !isStudioOperationInputCurrent(scopeKey, inputSnapshotHash, inputNodeIds)
      ) {
        const currentResult = store.nodes.find((item) => item.id === resultId)
        if (currentResult?.data.operationRunId === operationRunId) {
          store.updateNodeData(resultId, {
            operationRunId: undefined,
            status: currentResult.data.mediaUrl ? 'success' : 'empty',
            resultSummary: '输入已变更，旧任务结果未覆盖当前内容'
          }, false)
        }
        const currentSource = store.nodes.find((item) => item.id === sourceNode.id)
        if (currentSource?.data.operationRunId === operationRunId) {
          store.updateNodeData(sourceNode.id, { operationRunId: undefined }, false)
        }
        return
      }

      if (!data.backgroundUrl || !Array.isArray(data.layers) || data.layers.length === 0) {
        if (data.taskId) {
          const remoteTaskId = Number(data.taskId)
          const studioTaskId = `studio-layer-split-${remoteTaskId}`
          const task = createStudioTask(`${sourceNode.data.title} · 图层分离`, {
            id: studioTaskId,
            nodeId: resultId,
            targetNodeIds: [sourceNode.id, resultId],
            runId: operationRunId,
            remoteTaskId,
            resultKind: 'layer_split',
            inputSnapshotHash,
            inputNodeIds,
            stage: '任务已提交',
            progress: 0,
            status: 'running'
          })
          store.upsertTask(task)
          store.updateNodeData(resultId, {
            status: 'generating',
            resultSummary: '图层分离中',
            imageToolOptions: {
              ...(store.nodes.find((item) => item.id === resultId)?.data.imageToolOptions ?? {}),
              sourceNodeId: sourceNode.id
            }
          })
          message.info('图层分离任务已提交，请稍候')
          void recoverStudioRemoteTask(task).catch(() => {
            if (useStudioUiStore.getState().scopeKey === scopeKey) {
              useStudioUiStore.getState().patchTask(studioTaskId, {
                status: 'waiting', stage: '恢复连接失败，可稍后返回重试'
              })
            }
          })
          return
        }
        store.updateNodeData(resultId, {
          status: 'failed',
          errorMessage: '图层分离未返回结果',
          resultSummary: '图层分离失败'
        })
        message.error('图层分离未返回结果')
        return
      }

      let normalizedData = {
        ...data,
        backgroundUrl: requireSafeStudioMediaUrl(data.backgroundUrl),
        layers: data.layers.map((layer) => ({
          ...layer,
          mediaUrl: requireSafeStudioMediaUrl(layer.mediaUrl)
        }))
      }
      if (canvasRuntime) {
        const register = async (url: string, name: string) => {
          const blob = await fetchMediaBlob(url)
          if (!blob) throw new Error('\u56fe\u5c42\u7ed3\u679c\u8bfb\u53d6\u5931\u8d25')
          const file = new File([blob], name + '.png', { type: blob.type || 'image/png' })
          return canvasRuntime.uploadResource(file, 'IMAGE')
        }
        const background = normalizedData.backgroundResourceId
          ? null
          : await register(normalizedData.backgroundUrl, 'layer-background')
        const layers = await mapWithConcurrency(normalizedData.layers, 3, async (layer, index) => {
          if (layer.resourceId) return layer
          const resource = await register(layer.mediaUrl, 'layer-' + index)
          return { ...layer, resourceId: String(resource.resourceId), mediaUrl: resource.resourceUrl }
        })
        let canvasWidth = normalizedData.canvasWidth
        let canvasHeight = normalizedData.canvasHeight
        if (!canvasWidth || !canvasHeight) {
          const image = await loadStudioCanvasImage(background?.resourceUrl || normalizedData.backgroundUrl)
          try {
            canvasWidth = image.width
            canvasHeight = image.height
          } finally {
            image.dispose()
          }
        }
        normalizedData = {
          ...normalizedData,
          backgroundResourceId: normalizedData.backgroundResourceId || String(background?.resourceId || ''),
          backgroundUrl: background?.resourceUrl || normalizedData.backgroundUrl,
          canvasWidth,
          canvasHeight,
          layers
        }
      }
      const { sourcePatch, resultPatch } = applyLayerSplitSuccess({
        sourceNodeId: sourceNode.id,
        resultNodeId: resultId,
        data: normalizedData
      })
      store.updateNodeData(sourceNode.id, { ...sourcePatch, operationRunId: undefined })
      store.updateNodeData(resultId, { ...resultPatch, operationRunId: undefined })
      store.selectNodes([resultId])
      if (canvasRuntime) await canvasRuntime.saveNow()
      message.success('\u56fe\u5c42\u5206\u79bb\u5b8c\u6210')
    } catch (error: unknown) {
      const store = useStudioUiStore.getState()
      if (
        store.scopeKey !== scopeKey ||
        !store.nodes.some((item) => item.id === resultId && item.data.operationRunId === operationRunId)
      ) return
      const errorMessage = (error as Error)?.message || '图层分离失败，请重试'
      store.updateNodeData(resultId, {
        status: 'failed',
        operationRunId: undefined,
        errorMessage,
        resultSummary: '图层分离失败'
      })
      const latestSource = store.nodes.find((item) => item.id === sourceNode.id)
      if (latestSource?.data.operationRunId === operationRunId) {
        store.updateNodeData(sourceNode.id, { operationRunId: undefined }, false)
      }
      message.error(errorMessage)
    }
  }, [
    addConnection,
    addNode,
    canvasRuntime,
    closeLayerSplitSession,
    focusNode,
    projectId,
    selectNodes,
    sourceNode
  ])

  const cancelSplit = useCallback((resultNodeId: string) => {
    updateNodeData(resultNodeId, {
      status: 'failed',
      errorMessage: '已取消',
      resultSummary: '已取消'
    })
  }, [updateNodeData])

  const mergeLayers = useCallback(async (resultNode: StudioCanvasNode) => {
    const state = resultNode.data.layerSeparation
    if (!state || state.layers.length === 0) {
      message.warning('\u6ca1\u6709\u53ef\u5408\u5e76\u7684\u56fe\u5c42')
      return false
    }
    const operationKey = `${useStudioUiStore.getState().scopeKey}:${resultNode.id}:layer-merge`
    return runStudioRequestSingleFlight(operationKey, async () => {
      try {
        if (canvasRuntime) {
          if (!canvasRuntime.canMutate) throw new Error('\u5f53\u524d\u753b\u5e03\u4e0d\u53ef\u7f16\u8f91')
          const data = await canvasRuntime.mergeImageLayers(resultNode.id)
          const current = useStudioUiStore.getState().nodes.find((item) => item.id === resultNode.id)
          if (!current?.data.layerSeparation) throw new Error('\u539f\u5206\u5c42\u8282\u70b9\u5df2\u5220\u9664')
          const occupied = useStudioUiStore.getState().nodes.map((item) => toStudioNodeRect(item))
          const desired = { x: current.position.x + 310, y: current.position.y }
          const position = placeStudioNodeWithoutOverlap(desired, { width: 240, height: 180 }, occupied)
          const newId = addNode('image', position, {
            title: current.data.title + ' \u00b7 \u5408\u5e76',
            subtitle: '\u7531 ' + current.data.title + ' \u5408\u5e76',
            zoneId: current.data.zoneId,
            storyboardId: current.data.storyboardId,
            status: 'success',
            mediaKind: 'image',
            imagePurpose: 'generic',
            prompt: current.data.prompt,
            composerHidden: true,
            mediaUrl: data.resourceUrl,
            canvasResourceIds: [String(data.resourceId)],
            canvasPrimaryResourceId: String(data.resourceId),
            resultSummary: '\u56fe\u5c42\u5df2\u5408\u5e76'
          })
          addConnection({ source: current.id, target: newId, sourceHandle: null, targetHandle: null }, 'reference')
          selectNodes([newId])
          window.requestAnimationFrame(() => focusNode(newId))
          await canvasRuntime.saveNow()
          message.success('\u5408\u5e76\u5b8c\u6210')
          return true
        }
        const data = await userLayerMerge({
          backgroundUrl: state.backgroundUrl,
          layers: state.layers.map((layer) => ({
            id: layer.id,
            mediaUrl: layer.mediaUrl,
            transform: {
              rect: { ...layer.transform.rect },
              rotationDeg: layer.transform.rotationDeg,
              flipX: layer.transform.flipX,
              flipY: layer.transform.flipY
            }
          }))
        })
        if (!data.mediaUrl) throw new Error('\u5408\u5e76\u672a\u8fd4\u56de\u56fe\u7247')
        updateNodeData(resultNode.id, {
          mediaUrl: data.mediaUrl,
          mediaKind: 'image',
          status: 'success',
          layerSeparation: undefined,
          imagePurpose: 'generic',
          composerHidden: true,
          resultSummary: '\u56fe\u5c42\u5df2\u5408\u5e76',
          errorMessage: undefined
        })
        message.success('\u5408\u5e76\u5b8c\u6210')
        return true
      } catch (error: unknown) {
        message.error((error as Error)?.message || '\u5408\u5e76\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5')
        return false
      }
    })
  }, [addConnection, addNode, canvasRuntime, focusNode, selectNodes, updateNodeData])

  const extractLayerToNode = useCallback(async (resultNode: StudioCanvasNode, layerId: string) => {
    const layer = resultNode.data.layerSeparation?.layers.find((item) => item.id === layerId)
    if (!layer?.mediaUrl) return null
    if (canvasRuntime && !layer.resourceId) {
      message.error('\u5f53\u524d\u56fe\u5c42\u5c1a\u672a\u7ed1\u5b9a\u753b\u5e03\u8d44\u6e90\uff0c\u8bf7\u91cd\u65b0\u5206\u5c42')
      return null
    }
    const operationKey = `${useStudioUiStore.getState().scopeKey}:${resultNode.id}:${layerId}:layer-extract`
    return runStudioRequestSingleFlight(operationKey, async () => {
      try {
        if (canvasRuntime && !canvasRuntime.canMutate) throw new Error('\u5f53\u524d\u753b\u5e03\u4e0d\u53ef\u7f16\u8f91')
        const occupied = useStudioUiStore.getState().nodes.map((item) => toStudioNodeRect(item))
        const desired = { x: resultNode.position.x + 310, y: resultNode.position.y }
        const position = placeStudioNodeWithoutOverlap(desired, { width: 240, height: 180 }, occupied)
        const newId = addNode('image', position, {
          ...buildLayerExtractNodeData(layer),
          zoneId: resultNode.data.zoneId,
          storyboardId: resultNode.data.storyboardId,
          subtitle: '\u7531 ' + resultNode.data.title + ' \u62d6\u51fa'
        })
        addConnection({ source: resultNode.id, target: newId, sourceHandle: null, targetHandle: null }, 'reference')
        selectNodes([newId])
        window.requestAnimationFrame(() => focusNode(newId))
        if (canvasRuntime) await canvasRuntime.saveNow()
        return newId
      } catch (error: unknown) {
        message.error((error as Error)?.message || '\u56fe\u5c42\u63d0\u53d6\u5931\u8d25')
        return null
      }
    })
  }, [addConnection, addNode, canvasRuntime, focusNode, selectNodes])

  return {
    startSplit,
    cancelSplit,
    mergeLayers,
    extractLayerToNode
  }
}
