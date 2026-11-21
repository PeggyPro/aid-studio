'use client'

/**
 * 分镜脚本批量生成（原 composables/useStoryboardScriptBatchGenerate.ts 平移）。
 * SSE 跟踪与终态判定拆分见 utils/storyboardScriptBatchTrack.ts。
 * 原实现为模块级共享单例（多组件复用同一任务跟随状态），React 版保持一致。
 */

import { message } from 'antd'
import {
captureCreationLiveGenScope,
matchesCreationLiveGenScope
} from '~/composables/useCreationLiveGenScopeGuard'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { useCreationStore } from '~/stores/creation'
import type { StoryboardPanel } from '~/types'
import type { UserTaskDetailData,UserTaskRow } from '~/types/business-api'
import { userStoryboardGenerateScript, userTaskDetailCached } from '~/utils/businessApi'
import { buildStoryboardScriptSubmitBody } from '~/utils/storyboardScriptSubmitBody'
import { stripStoryboardScriptSkeletonPanels } from '~/utils/storyboardPanelMap'
import {
applyStoryboardScriptFailedOutcome,
applyStoryboardScriptPartialFailedOutcome,
applyStoryboardScriptSuccessOutcome,
storyboardScriptBizErr as bizErr,
isStoryboardScriptTaskBusyMessage,
parseStoryboardScriptTaskId as parseTaskId,
pickOngoingStoryboardScriptTask,
refreshStoryboardScriptPanelsFromApi as refreshPanelsFromApi,
resolveScenePlotCountHint,
seedStoryboardScriptProgressFromDetailRecord as seedProgressFromDetailRecord,
trackStoryboardScriptTaskUntilDone
} from '~/utils/storyboardScriptBatchTrack'
import {
resumeUserTask
} from '~/utils/taskPartialFailed'
import {
fetchFlowUserTaskList,
filterUserTaskRowsForEpisode
} from '~/utils/userTaskListFlowOnce'
import { restoreOngoingGenerationIfNeeded as restoreStoryboardScriptGeneration } from './storyboardScriptGenerationRestore'
import { createStoryboardScriptTaskControls } from './storyboardScriptTaskControls'

let sharedStoryboardScriptBatchGen: ReturnType<
  typeof createStoryboardScriptBatchGenerate
> | null = null

function createStoryboardScriptBatchGenerate() {
  /** 事件回调 / 异步流程一律取最新 store 状态（原 Pinia 实例为响应式，Zustand 需调用时取） */
  const getStore = () => useCreationStore.getState()

  // 原 Vue ref：仅内部/回调读取，不驱动渲染；React 版用普通可变对象保留 `.value` 访问形状
  const activeTaskId = { value: null as number | null }
  const taskProgressMessage = { value: '' }
  let streamCloser: (() => void) | null = null
  let stopRequested = false
  let resumeFollowGeneration = 0
  let restoreSessionInFlight: Promise<void> | null = null
  let restoreScopeKey = ''
  let followInFlight: Promise<{
    ok: boolean
    panels: StoryboardPanel[]
    message?: string
  }> | null = null
  let followOwner: { taskId: number; scopeKey: string; generation: number } | null = null

  function closeStream() {
    const close = streamCloser
    streamCloser = null
    if (close) {
      try {
        close()
      } catch {
        /* ignore */
      }
    }
  }

  function syncActiveTaskIdToStore(taskId: number | null) {
    activeTaskId.value = taskId
    getStore().setStoryboardScriptActiveTaskId(taskId)
  }

  function applySseProgress(p: {
    progress?: number
    stepIndex?: number
    stepTotal?: number
    message?: string
    stepTitle?: string
  }) {
    getStore().applyStoryboardScriptSseProgress(p)
  }

  function isFollowingTask(taskId: number) {
    return !!followInFlight && followOwner?.taskId === taskId &&
      followOwner.scopeKey === captureCreationLiveGenScope().scopeKey &&
      followOwner.generation === resumeFollowGeneration
  }

  async function followExistingTask(
    taskId: number,
    currentPanels: StoryboardPanel[],
    options?: { progressTotalHint?: number; startDetail?: UserTaskDetailData | null }
  ): Promise<{
    ok: boolean
    panels: StoryboardPanel[]
    message?: string
  }> {
    if (followInFlight && isFollowingTask(taskId)) return followInFlight

    closeStream()
    const routeCtx = captureCreationLiveGenScope()
    const owner = { taskId, scopeKey: routeCtx.scopeKey, generation: resumeFollowGeneration }
    followOwner = owner
    const isCurrent = () => followOwner === owner && owner.generation === resumeFollowGeneration &&
      matchesCreationLiveGenScope(routeCtx)

    const run = async (): Promise<{
      ok: boolean
      panels: StoryboardPanel[]
      message?: string
    }> => {
      stopRequested = false
      syncActiveTaskIdToStore(taskId)
      getStore().setStoryboardGenerating(true)
      getStore().setStoryboardError(null)

      const progressTotal = Math.max(
        options?.progressTotalHint ?? 0,
        getStore().storyboardGenerationProgress.total || 0,
        resolveScenePlotCountHint(),
        1
      )
      if (!getStore().storyboardGenerationProgress.total) {
        getStore().setStoryboardProgress(0, progressTotal)
      }
      const startDetail = options?.startDetail ?? await userTaskDetailCached(taskId, { force: true }).catch(() => null)
      if (isCurrent() && !stopRequested) seedProgressFromDetailRecord(startDetail)
      const workingPanels = stripStoryboardScriptSkeletonPanels(currentPanels)

      const outcome = await trackStoryboardScriptTaskUntilDone(taskId, applySseProgress, {
        isCurrent,
        isStopRequested: () => stopRequested,
        registerStop: (stop) => {
          streamCloser = stop
          return () => { if (streamCloser === stop) streamCloser = null }
        }
      }, startDetail)
      if (!isCurrent()) {
        return {
          ok: false,
          panels: workingPanels,
          message: '任务仍在后台执行，请稍候或刷新页面自动恢复进度'
        }
      }
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
      }

      if (outcome.ongoing) {
        getStore().setStoryboardGenerating(true)
        getStore().setStoryboardError(null)
        syncActiveTaskIdToStore(taskId)
        return {
          ok: false,
          panels: workingPanels,
          message: outcome.message
        }
      }

      // 仅在任务终态（成功 / 部分失败 / 失败）后拉一次分镜列表
      let panels = workingPanels
      try {
        panels = await refreshPanelsFromApi(isCurrent)
      } catch (e: unknown) {
        if (isCurrent() && outcome.ok) {
          const text = `分镜已生成，但同步列表失败：${bizErr(e)}`
          syncActiveTaskIdToStore(null)
          applyStoryboardScriptFailedOutcome(text, workingPanels)
          return { ok: false, panels: workingPanels, message: text }
        }
      }
      if (!isCurrent()) {
        return { ok: false, panels: workingPanels, message: '任务仍在后台执行，请稍候或刷新页面自动恢复进度' }
      }
      if (!outcome.partial) syncActiveTaskIdToStore(null)

      if (outcome.ok) {
        const total = Math.max(
          getStore().storyboardGenerationProgress.total || progressTotal,
          progressTotal
        )
        getStore().setStoryboardProgress(total, total)
        applyStoryboardScriptSuccessOutcome()
        return { ok: true, panels }
      }

      if (outcome.partial && taskId) {
        applyStoryboardScriptPartialFailedOutcome(
          taskId,
          outcome.message || '部分场次生成失败，可点击续生重试失败项',
          outcome.partialData ?? null,
          panels
        )
        return {
          ok: false,
          panels,
          message: outcome.message
        }
      }

      applyStoryboardScriptFailedOutcome(
        outcome.message || '分镜生成失败，请稍后重试。',
        panels
      )
      return {
        ok: false,
        panels,
        message: outcome.message
      }
    }

    const pending = run()
    followInFlight = pending
    try {
      return await pending
    } finally {
      if (followInFlight === pending) {
        followInFlight = null
        followOwner = null
      }
    }
  }

  function isTaskFollowPaused(taskId: number): boolean {
    return getStore().taskIdsWithLocalFollowPaused.includes(taskId)
  }

  /**
   * 刷新或切换作品后：根据任务列表与持久化状态恢复分镜脚本生成 UI 与 SSE。
   */
  function restoreOngoingGenerationIfNeeded(
    currentPanels: StoryboardPanel[],
    onPanelsUpdate: (panels: StoryboardPanel[]) => void,
    onShowGeneratingSkeleton: () => void
  ): Promise<void> {
    if (isFollowingTask(Number(getStore().storyboardScriptActiveTaskId))) return Promise.resolve()
    const { scopeKey } = captureCreationLiveGenScope()
    return restoreStoryboardScriptGeneration({
      getStore,
      getRestoreSessionInFlight: () => restoreScopeKey === scopeKey ? restoreSessionInFlight : null,
      setRestoreSessionInFlight: (pending) => {
        restoreScopeKey = pending ? scopeKey : ''
        restoreSessionInFlight = pending
      },
      nextResumeFollowGeneration: () => ++resumeFollowGeneration,
      getResumeFollowGeneration: () => resumeFollowGeneration,
      hasCurrentFollow: () => isFollowingTask(Number(getStore().storyboardScriptActiveTaskId)),
      activeTaskId,
      isTaskFollowPaused,
      followExistingTask,
      syncActiveTaskIdToStore,
      getStopRequested: () => stopRequested
    }, currentPanels, onPanelsUpdate, onShowGeneratingSkeleton)
  }

  /**
   * 提交批量生成分镜脚本并 SSE 追踪；成功后刷新分镜列表。
   */
  async function runBatchGenerate(
    currentPanels: StoryboardPanel[],
    options?: { manualAgentModelPick?: boolean; sceneIds?: number[]; overwrite?: boolean }
  ): Promise<{
    ok: boolean
    panels: StoryboardPanel[]
    message?: string
  }> {
    stopRequested = false
    taskProgressMessage.value = ''
    const scope = captureCreationLiveGenScope()
    const switched = { ok: false, panels: currentPanels, message: '已切换作品，任务仍在后台执行' }
    const submitBody = await buildStoryboardScriptSubmitBody({
      store: getStore(),
      route: getRouteLikeSnapshot(),
      currentPanels,
      sceneIds: options?.sceneIds,
      manualAgentModelPick: options?.manualAgentModelPick,
      overwrite: options?.overwrite
    })
    if (!matchesCreationLiveGenScope(scope)) return switched
    if (!submitBody) {
      return { ok: false, panels: currentPanels, message: '缺少项目信息，请从「我的作品」打开作品后再操作' }
    }
    const ctx = { projectId: submitBody.projectId, episodeId: submitBody.episodeId }
    const selectiveSceneIds = submitBody.sceneIds ?? []
    const isSelective = selectiveSceneIds.length > 0

    let submitted: Awaited<ReturnType<typeof userStoryboardGenerateScript>>
    try {
      submitted = await userStoryboardGenerateScript(submitBody)
    } catch (e: unknown) {
      if (!matchesCreationLiveGenScope(scope)) return switched
      const msg = bizErr(e)
      if (isStoryboardScriptTaskBusyMessage(msg)) {
        let tasks: UserTaskRow[] = []
        try {
          /** 剧集隔离：busy 续跟也只认本集任务 */
          tasks = filterUserTaskRowsForEpisode(
            await fetchFlowUserTaskList(ctx.projectId, { intent: 'read' }),
            ctx.episodeId
          )
        } catch {
          tasks = []
        }
        if (!matchesCreationLiveGenScope(scope)) return switched
        const ongoingTask = pickOngoingStoryboardScriptTask(
          tasks,
          getStore().storyboardScriptActiveTaskId
        )
        const ongoingId = parseTaskId(ongoingTask?.id)
        if (ongoingId) {
          syncActiveTaskIdToStore(ongoingId)
          getStore().setStoryboardGenerating(true)
          getStore().setStoryboardError(null)
          const progressTotalHint =
            Number((ongoingTask as { totalBatches?: number })?.totalBatches) > 0
              ? Number((ongoingTask as { totalBatches?: number }).totalBatches)
              : isSelective
                ? selectiveSceneIds.length
                : resolveScenePlotCountHint()
          return followExistingTask(ongoingId, currentPanels, { progressTotalHint })
        }
      }
      return { ok: false, panels: currentPanels, message: msg }
    }

    if (!matchesCreationLiveGenScope(scope)) return switched
    const taskId = parseTaskId(submitted.taskId)
    if (!taskId) {
      return { ok: false, panels: currentPanels, message: '提交失败：未返回任务ID' }
    }

    syncActiveTaskIdToStore(taskId)
    if (typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
    }

    const warningText = String(submitted.warning || '').trim()
    if (warningText) {
      message.warning(warningText)
    }

    const progressTotal =
      Number(submitted.totalBatches) > 0
        ? Number(submitted.totalBatches)
        : isSelective
          ? selectiveSceneIds.length
          : resolveScenePlotCountHint()

    getStore().setStoryboardProgress(0, Math.max(progressTotal, 1))

    const result = await followExistingTask(taskId, currentPanels, { progressTotalHint: progressTotal })
    return result
  }

  async function resumePartialFailedGenerate(
    taskId: number,
    currentPanels: StoryboardPanel[]
  ): Promise<{ ok: boolean; panels: StoryboardPanel[]; message?: string }> {
    const id = parseTaskId(taskId)
    if (!id) {
      return { ok: false, panels: currentPanels, message: '任务ID无效' }
    }
    stopRequested = false
    taskProgressMessage.value = ''
    const scope = captureCreationLiveGenScope()
    let resumeTotalBatches = 0
    try {
      const resumed = await resumeUserTask(id, 'storyboard_script_batch')
      resumeTotalBatches = Number(resumed.totalBatches)
    } catch (e: unknown) {
      return { ok: false, panels: currentPanels, message: bizErr(e) }
    }
    if (!matchesCreationLiveGenScope(scope)) {
      return { ok: false, panels: currentPanels, message: '已切换作品，任务仍在后台进行' }
    }
    if (typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
    }
    syncActiveTaskIdToStore(id)
    getStore().setStoryboardGenerating(true)
    getStore().setStoryboardError(null)
    getStore().setStoryboardScriptPartialFailedData(null)
    if (Number.isFinite(resumeTotalBatches) && resumeTotalBatches > 0) {
      getStore().setStoryboardProgress(0, resumeTotalBatches)
    }
    return followExistingTask(id, currentPanels, {
      progressTotalHint: resumeTotalBatches > 0 ? resumeTotalBatches : undefined
    })
  }

  async function resumeTrackFromGlobal(
    taskId: number,
    currentPanels: StoryboardPanel[]
  ): Promise<{ ok: boolean; panels: StoryboardPanel[]; message?: string }> {
    const id = parseTaskId(taskId)
    if (!id) {
      return { ok: false, panels: currentPanels, message: '任务ID无效' }
    }
    getStore().removePausedTaskFollow(id)
    getStore().setStoryboardGenerating(true)
    getStore().setStoryboardError(null)
    return followExistingTask(id, currentPanels)
  }

  const {
    requestStop,
    onGlobalStopTask,
    onGlobalTrackTask,
    cancelResumeFollow
  } = createStoryboardScriptTaskControls({
    activeTaskId,
    getStore,
    getFollowInFlight: () => followInFlight,
    setStopRequested: (value) => {
      stopRequested = value
    },
    closeStream,
    refreshPanelsFromApi,
    syncActiveTaskIdToStore,
    resumeTrackFromGlobal,
    nextResumeFollowGeneration: () => {
      restoreSessionInFlight = null
      return ++resumeFollowGeneration
    }
  })

  return {
    activeTaskId,
    isFollowingTask,
    taskProgressMessage,
    runBatchGenerate,
    requestStop,
    refreshPanelsFromApi,
    restoreOngoingGenerationIfNeeded,
    resumePartialFailedGenerate,
    resumeTrackFromGlobal,
    onGlobalStopTask,
    onGlobalTrackTask,
    cancelResumeFollow
  }
}

export function useStoryboardScriptBatchGenerate() {
  if (!sharedStoryboardScriptBatchGen) {
    sharedStoryboardScriptBatchGen = createStoryboardScriptBatchGenerate()
  }
  return sharedStoryboardScriptBatchGen
}
