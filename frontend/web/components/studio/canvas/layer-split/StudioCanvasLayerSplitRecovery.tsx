'use client'

import { useEffect } from 'react'
import { message } from 'antd'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { useStudioUiStore } from '@/stores/studioUi'
import { canvasLayerSplitResult } from '@/utils/canvas/canvasLayerSplitResult'
import { applyLayerSplitSuccess } from '@/utils/studio/studioLayerSplitSubmit'

const inFlight = new Set<string>()

/** Materialize protected task results into resource-backed editable layers after task recovery. */
export function StudioCanvasLayerSplitRecovery() {
  const runtime = useCanvasProjectRuntime()
  const tasks = useStudioUiStore((state) => state.tasks)
  const scopeKey = useStudioUiStore((state) => state.scopeKey)
  const workspaceId = String(runtime?.project?.workspace.workspaceId || '')
  const loadNodeHistory = runtime?.loadNodeHistory
  const saveNow = runtime?.saveNow

  useEffect(() => {
    if (!runtime?.canEdit || !workspaceId || !loadNodeHistory || !saveNow) return
    const candidates = useStudioUiStore.getState().nodes
      .filter((node) => node.data.imagePurpose === 'layer_separation'
        && node.data.status === 'success' && node.data.canvasCurrentTaskId
        && !node.data.layerSeparation && node.data.imageToolOptions?.sourceNodeId)
      .map((node) => ({ id: node.id, taskId: node.data.canvasCurrentTaskId!, sourceNodeId: node.data.imageToolOptions!.sourceNodeId! }))
    for (const candidate of candidates) {
      const key = `${workspaceId}:${candidate.id}:${candidate.taskId}`
      if (inFlight.has(key)) continue
      inFlight.add(key)
      void (async () => {
        try {
          let data = null
          for (let attempt = 0; attempt < 4; attempt++) {
            const history = await loadNodeHistory(candidate.id)
            data = canvasLayerSplitResult(history, candidate.taskId)
            if (data) break
            await new Promise((resolve) => window.setTimeout(resolve, 2000))
          }
          if (!data) throw new Error('图层结果仍在持久化，请稍后重新进入画布恢复')
          const state = useStudioUiStore.getState()
          const node = state.nodes.find((item) => item.id === candidate.id)
          if (state.scopeKey !== scopeKey || !node || node.data.layerSeparation
            || node.data.canvasCurrentTaskId !== candidate.taskId) return
          const { resultPatch } = applyLayerSplitSuccess({
            sourceNodeId: candidate.sourceNodeId,
            resultNodeId: candidate.id,
            data
          })
          state.updateNodeData(candidate.id, resultPatch)
          await saveNow()
        } catch (error: unknown) {
          if (useStudioUiStore.getState().scopeKey === scopeKey) {
            message.error(error instanceof Error ? error.message : '图层结果恢复失败')
          }
        } finally {
          inFlight.delete(key)
        }
      })()
    }
  }, [loadNodeHistory, runtime?.canEdit, saveNow, scopeKey, tasks, workspaceId])

  return null
}
