'use client'

import { useState, type MutableRefObject } from 'react'
import { message } from 'antd'
import { useCreationStore } from '~/stores/creation'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { type useStoryboardScriptBatchGenerate } from '~/composables/useStoryboardScriptBatchGenerate'
import { type useStoryboardImageBatchGenerate } from '~/composables/useStoryboardImageBatchGenerate'
import { type useStoryboardWorkbenchMutations } from '~/composables/useStoryboardWorkbenchMutations'
import {
  captureCreationLiveGenScope,
  matchesCreationLiveGenScope
} from '~/composables/useCreationLiveGenScopeGuard'
import { applyStoryboardScriptPanelsFromApi } from '~/composables/useCreateFlowStoryboardSync'
import {
  getPersistedStoryboardScriptPanels,
  mapStoryboardListRowToPanel
} from '~/utils/storyboardPanelMap'
import { userStoryboardList } from '~/utils/businessApi'
import {
  fetchProjectStoryboardRecords,
  groupStoryboardRecordsByStoryboardId,
  hydrateScriptPanelsWithImageRecords
} from '~/utils/storyboardRecordBatch'
import {
  hasPersistedStoryboardImageBatchGenWork,
  hasPersistedStoryboardScriptBatchGenWork
} from '~/utils/storyboardListBootstrap'
import { shouldSilentStoryboardBatchToast } from '~/utils/taskSseSilentDisconnect'
import { isStoryboardScriptFlowStepGenerating } from '~/utils/storyboardFlowStepLoading'
import { STORYBOARD_WORKBENCH_NEED_PROJECT_MSG } from '~/composables/useStoryboardWorkbenchMutations'
import { storyboardApiErr, type StoryboardPanel } from './storyboardScriptShared'

type ScriptGenerate = ReturnType<typeof useStoryboardScriptBatchGenerate>
type ImageBatchGenerate = ReturnType<typeof useStoryboardImageBatchGenerate>
type Workbench = ReturnType<typeof useStoryboardWorkbenchMutations>

export function useStoryboardScriptGenerationActions(opts: {
  panelsRef: MutableRefObject<StoryboardPanel[]>
  onChangeRef: MutableRefObject<(panels: StoryboardPanel[]) => void>
  onGenerationCompleteRef: MutableRefObject<(panels: StoryboardPanel[]) => void>
  scriptManualAgentModelPickRef: MutableRefObject<boolean>
  pageDisposedRef: MutableRefObject<boolean>
  generationStoppedRef: MutableRefObject<boolean>
  storyboardScriptGen: ScriptGenerate
  storyboardImageBatchGen: ImageBatchGenerate
  workbench: Workbench
  storyboardListSyncReadyRef: MutableRefObject<boolean>
}) {
  const {
    panelsRef,
    onChangeRef,
    onGenerationCompleteRef,
    scriptManualAgentModelPickRef,
    pageDisposedRef,
    generationStoppedRef,
    storyboardScriptGen,
    storyboardImageBatchGen,
    workbench,
    storyboardListSyncReadyRef
  } = opts
  const getStore = () => useCreationStore.getState()
  const [isResumingPartialFailed, setIsResumingPartialFailed] = useState(false)

  const hasOngoingStoryboardScriptGenWork = () => {
    const store = getStore()
    const route = getRouteLikeSnapshot()
    if (!storyboardListSyncReadyRef.current) {
      return (
        hasPersistedStoryboardScriptBatchGenWork(store, route) ||
        hasPersistedStoryboardImageBatchGenWork(store, route) ||
        isStoryboardScriptFlowStepGenerating(store, route)
      )
    }
    if (isStoryboardScriptFlowStepGenerating(store, route)) return true
    const taskId = Number(store.storyboardScriptActiveTaskId)
    return Number.isFinite(taskId) && taskId > 0
  }

  const prepareGeneratingProgress = () => {
    const scenes = getStore().formData.sceneCharacter?.scenes || []
    const total = Math.max(
      scenes.filter((scene: unknown) => String(scene ?? '').trim().length > 0).length,
      1
    )
    getStore().setStoryboardProgress(0, total)
  }

  const clearStoryboardScriptToEmptyState = () => {
    applyStoryboardScriptPanelsFromApi([])
    if (!getStore().isGeneratingStoryboard) getStore().setStoryboardProgress(0, 0)
  }

  const syncStoryboardScriptPanelsFromTaskResult = (next: StoryboardPanel[]) => {
    const persisted = getPersistedStoryboardScriptPanels(next)
    if (!persisted.length) {
      clearStoryboardScriptToEmptyState()
      onChangeRef.current([])
      return persisted
    }
    applyStoryboardScriptPanelsFromApi(next)
    onChangeRef.current(getStore().formData.storyboardScript.panels as StoryboardPanel[])
    return persisted
  }

  const refreshPanelsFromStoryboardListApi = async () => {
    const context = await workbench.getProjectEpisodeContext()
    if (!context) return
    try {
      const list = await userStoryboardList({
        projectId: context.projectId,
        episodeId: context.episodeId
      })
      const sorted = [...list].sort((left, right) => (left.sortOrder ?? 0) - (right.sortOrder ?? 0))
      let panels = sorted.map((row, index) => mapStoryboardListRowToPanel(row, index))
      try {
        const imageRows = await fetchProjectStoryboardRecords(context, 'image', { force: true })
        panels = hydrateScriptPanelsWithImageRecords(
          panels,
          groupStoryboardRecordsByStoryboardId(imageRows)
        )
      } catch {
        // 主列表映射仍可作为降级结果。
      }
      applyStoryboardScriptPanelsFromApi(panels)
      storyboardImageBatchGen.applyImmediatePanelLoadingRestore(
        getStore().formData.storyboardScript.panels as StoryboardPanel[]
      )
      onChangeRef.current(getStore().formData.storyboardScript.panels as StoryboardPanel[])
    } catch (error: unknown) {
      message.warning(`刷新分镜列表失败：${storyboardApiErr(error)}`)
    }
  }

  const startGeneration = async (options?: {
    sceneIds?: number[]
    manualAgentModelPick?: boolean
  }) => {
    const routeContext = captureCreationLiveGenScope()
    const context = await workbench.getProjectEpisodeContext()
    if (pageDisposedRef.current || !matchesCreationLiveGenScope(routeContext)) return
    if (!context) {
      message.warning(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
      return
    }
    generationStoppedRef.current = false
    getStore().setStoryboardGenerating(true)
    getStore().setStoryboardError(null)
    prepareGeneratingProgress()

    try {
      const result = await storyboardScriptGen.runBatchGenerate(panelsRef.current, {
        manualAgentModelPick:
          options?.manualAgentModelPick ?? scriptManualAgentModelPickRef.current,
        ...(options?.sceneIds?.length ? { sceneIds: options.sceneIds } : {})
      })
      if (pageDisposedRef.current) return
      if (!matchesCreationLiveGenScope(routeContext)) return
      if (result.ok) {
        syncStoryboardScriptPanelsFromTaskResult(result.panels)
        getStore().setStoryboardGenerating(false)
        message.success('分镜生成完成')
        onGenerationCompleteRef.current(result.panels)
        return
      }
      if (generationStoppedRef.current) {
        getStore().stopStoryboardGeneration()
        message.info('已停止生成')
        return
      }
      const taskStillRunning = shouldSilentStoryboardBatchToast(result.message)
      syncStoryboardScriptPanelsFromTaskResult(result.panels)
      if (taskStillRunning) {
        getStore().setStoryboardGenerating(true)
        getStore().setStoryboardError(null)
        return
      }
      getStore().setStoryboardError(result.message || '分镜生成失败，请稍后重试。')
      getStore().setStoryboardGenerating(false)
      getStore().setStoryboardProgress(0, 0)
      message.error(result.message || '分镜生成失败')
    } catch (error: unknown) {
      if (pageDisposedRef.current || !matchesCreationLiveGenScope(routeContext)) return
      const failMessage = error instanceof Error ? error.message : '分镜生成异常中断，请重试。'
      if (shouldSilentStoryboardBatchToast(failMessage)) {
        getStore().setStoryboardGenerating(true)
        getStore().setStoryboardError(null)
        return
      }
      if (!getPersistedStoryboardScriptPanels(panelsRef.current).length) {
        clearStoryboardScriptToEmptyState()
        onChangeRef.current([])
      }
      getStore().setStoryboardError(failMessage)
      getStore().setStoryboardGenerating(false)
      getStore().setStoryboardProgress(0, 0)
      message.error(failMessage)
    }
  }

  const stopGeneration = async () => {
    const scope = captureCreationLiveGenScope()
    generationStoppedRef.current = true
    await storyboardScriptGen.requestStop()
    if (pageDisposedRef.current || !matchesCreationLiveGenScope(scope)) return
    getStore().stopStoryboardGeneration()
    message.info('已停止生成')
  }

  const handleResumePartialFailed = async () => {
    const taskId = Number(getStore().storyboardScriptActiveTaskId)
    if (!Number.isFinite(taskId) || taskId <= 0) {
      message.warning('无可续生的任务')
      return
    }
    setIsResumingPartialFailed(true)
    const scope = captureCreationLiveGenScope()
    generationStoppedRef.current = false
    getStore().setStoryboardGenerating(true)
    getStore().setStoryboardError(null)
    try {
      const result = await storyboardScriptGen.resumePartialFailedGenerate(taskId, panelsRef.current)
      if (pageDisposedRef.current || !matchesCreationLiveGenScope(scope)) return
      syncStoryboardScriptPanelsFromTaskResult(result.panels)
      if (result.ok) {
        getStore().setStoryboardGenerating(false)
        message.success('分镜续生完成')
        onGenerationCompleteRef.current(result.panels)
      } else if (shouldSilentStoryboardBatchToast(result.message)) {
        getStore().setStoryboardGenerating(true)
        getStore().setStoryboardError(null)
      } else {
        const failMessage = result.message || '分镜续生失败，请重试。'
        getStore().setStoryboardError(failMessage)
        getStore().setStoryboardGenerating(false)
        message.warning(failMessage)
      }
    } catch (error: unknown) {
      if (pageDisposedRef.current || !matchesCreationLiveGenScope(scope)) return
      const errorMessage = error instanceof Error ? error.message : '续生失败'
      if (!getPersistedStoryboardScriptPanels(panelsRef.current).length) {
        clearStoryboardScriptToEmptyState()
        onChangeRef.current([])
      }
      getStore().setStoryboardError(errorMessage)
      getStore().setStoryboardGenerating(false)
      message.error(errorMessage)
    } finally {
      setIsResumingPartialFailed(false)
    }
  }

  const handleBatchGenerateStoryboardImages = async (options?: {
    selectedStoryboardIds?: number[]
    manualAgentModelPick?: boolean
    agentCode?: string
    modelCode?: string
    overwrite: boolean
  }) => {
    if (getStore().isGeneratingStoryboardImageBatch || !panelsRef.current.length) return
    const overwrite = options?.overwrite ?? false
    try {
      const result = await storyboardImageBatchGen.runBatchForPanels(panelsRef.current, overwrite, {
        selectedStoryboardIds: options?.selectedStoryboardIds,
        manualAgentModelPick: options?.manualAgentModelPick,
        agentCode: options?.agentCode,
        modelCode: options?.modelCode
      })
      if (pageDisposedRef.current) return
      onChangeRef.current(result.panels)
      if (result.ok) {
        message.success(overwrite ? '已重新批量生成分镜图' : '批量生成分镜图完成')
      } else if (result.message && !shouldSilentStoryboardBatchToast(result.message)) {
        message.error(result.message)
      }
    } catch (error: unknown) {
      if (pageDisposedRef.current) return
      const errorMessage = error instanceof Error ? error.message : '批量生成分镜图失败'
      if (!shouldSilentStoryboardBatchToast(errorMessage)) message.error(errorMessage)
    }
  }

  const stopImageBatchGeneration = async () => {
    await storyboardImageBatchGen.requestStop()
    message.info('已停止生成')
  }

  return {
    isResumingPartialFailed,
    hasOngoingStoryboardScriptGenWork,
    prepareGeneratingProgress,
    clearStoryboardScriptToEmptyState,
    syncStoryboardScriptPanelsFromTaskResult,
    refreshPanelsFromStoryboardListApi,
    startGeneration,
    stopGeneration,
    handleResumePartialFailed,
    handleBatchGenerateStoryboardImages,
    stopImageBatchGeneration
  }
}
