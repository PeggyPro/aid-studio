import {
captureCreationLiveGenScope,
matchesCreationLiveGenScope
} from '~/composables/useCreationLiveGenScopeGuard'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import {
fetchUserTaskDetailOnce,
isTerminalTaskStatus,
normalizeTaskStatus
} from '~/composables/useTaskSseFollow'
import { useCreationStore } from '~/stores/creation'
import type { StoryboardPanel } from '~/types'
import type { UserTaskRow } from '~/types/business-api'
import { stripStoryboardScriptSkeletonPanels } from '~/utils/storyboardPanelMap'
import {
applyStoryboardScriptFailedOutcome,
applyStoryboardScriptPartialFailedOutcome,
applyStoryboardScriptSuccessOutcome,
isOngoingStoryboardScriptTaskStatus,
parseStoryboardScriptTaskId as parseTaskId,
pickOngoingStoryboardScriptTask,
refreshStoryboardScriptPanelsFromApi as refreshPanelsFromApi,
safeParseResultData
} from '~/utils/storyboardScriptBatchTrack'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import {
formatPartialFailedMessage,
isUserTaskStatusPartialFailed,
parseTaskPartialFailedData
} from '~/utils/taskPartialFailed'
import {
beginFlowTaskListQuietWindow,
endFlowTaskListQuietWindow,
fetchFlowUserTaskList,
filterUserTaskRowsForEpisode
} from '~/utils/userTaskListFlowOnce'
export interface StoryboardScriptRestoreRuntime {
  getStore: () => ReturnType<typeof useCreationStore.getState>
  getRestoreSessionInFlight: () => Promise<void> | null
  setRestoreSessionInFlight: (pending: Promise<void> | null) => void
  nextResumeFollowGeneration: () => number
  getResumeFollowGeneration: () => number
  hasCurrentFollow: () => boolean
  activeTaskId: { value: number | null }
  isTaskFollowPaused: (taskId: number) => boolean
  followExistingTask: (
    taskId: number,
    currentPanels: StoryboardPanel[],
    options?: { progressTotalHint?: number; startDetail?: import('~/types/business-api').UserTaskDetailData | null }
  ) => Promise<{ ok: boolean; panels: StoryboardPanel[]; message?: string }>
  syncActiveTaskIdToStore: (taskId: number | null) => void
  getStopRequested: () => boolean
}

export async function restoreOngoingGenerationIfNeeded(
  runtime: StoryboardScriptRestoreRuntime,
  currentPanels: StoryboardPanel[],
  onPanelsUpdate: (panels: StoryboardPanel[]) => void,
  onShowGeneratingSkeleton: () => void
): Promise<void> {
  if (typeof window === 'undefined') return
  if (runtime.getRestoreSessionInFlight()) {
    await runtime.getRestoreSessionInFlight()
    return
  }

  const pending = (async () => {
    const routeSnap = getRouteLikeSnapshot()
    const routeCtx = captureCreationLiveGenScope()
    const ctx = await resolveStoryScriptSaveContext(runtime.getStore(), routeSnap)
    if (!ctx || !matchesCreationLiveGenScope(routeCtx) || runtime.hasCurrentFollow()) return

    const gen = runtime.nextResumeFollowGeneration()
    const isCurrent = () => gen === runtime.getResumeFollowGeneration() && matchesCreationLiveGenScope(routeCtx)
    beginFlowTaskListQuietWindow(ctx.projectId)
    try {
      let tasks: UserTaskRow[] = []
      let taskListOk = true
      try {
        /** 剧集隔离：禁止把其它集的分镜脚本任务恢复到本集 */
        tasks = filterUserTaskRowsForEpisode(
          await fetchFlowUserTaskList(ctx.projectId, { intent: 'read' }),
          ctx.episodeId
        )
      } catch {
        tasks = []
        taskListOk = false
      }
      if (!isCurrent() || runtime.hasCurrentFollow()) return

      const preferredId = runtime.getStore().storyboardScriptActiveTaskId
      const ongoingTask = pickOngoingStoryboardScriptTask(tasks, preferredId)
      const ongoingId = parseTaskId(ongoingTask?.id)

      const prefId = parseTaskId(preferredId)
      if (prefId && !runtime.isTaskFollowPaused(prefId) && runtime.activeTaskId.value !== prefId) {
        const prefTask = tasks.find((t) => Number(t.id) === prefId)
        if (prefTask && isUserTaskStatusPartialFailed(prefTask.status)) {
          let panels = currentPanels
          try {
            panels = await refreshPanelsFromApi(isCurrent)
          } catch {
            /* ignore */
          }
          if (gen !== runtime.getResumeFollowGeneration()) return
          if (!matchesCreationLiveGenScope(routeCtx)) return
          onPanelsUpdate(panels)
          runtime.syncActiveTaskIdToStore(prefId)
          const parsed = parseTaskPartialFailedData(
            prefTask.resultData ? safeParseResultData(prefTask.resultData) : null
          )
          applyStoryboardScriptPartialFailedOutcome(
            prefId,
            formatPartialFailedMessage(
              parsed,
              prefTask.errorMessage || '部分场次生成失败，可点击续生重试失败项'
            ),
            parsed,
            panels
          )
          return
        }
      }

      if (ongoingId && !runtime.isTaskFollowPaused(ongoingId)) {
        if (!runtime.getStore().isGeneratingStoryboard) {
          runtime.getStore().setStoryboardGenerating(true)
          runtime.getStore().setStoryboardError(null)
        }
        if (!stripStoryboardScriptSkeletonPanels(currentPanels).length) {
          onShowGeneratingSkeleton()
        }

        const result = await runtime.followExistingTask(ongoingId, currentPanels, {
          progressTotalHint:
            Number((ongoingTask as { totalBatches?: number })?.totalBatches) || undefined
        })
        if (gen !== runtime.getResumeFollowGeneration()) return
        if (!matchesCreationLiveGenScope(routeCtx)) return

        onPanelsUpdate(result.panels)
        if (result.ok) {
          applyStoryboardScriptSuccessOutcome()
        } else if (!runtime.getStopRequested()) {
          if (result.message?.includes('部分') || result.message?.includes('续生')) {
            /* followExistingTask 已写入 partial failed 状态 */
          } else if (result.message?.includes('仍在后台执行')) {
            /* SSE 断连但任务未终态：保留 generating 与 taskId，供刷新后继续跟进 */
          } else {
            applyStoryboardScriptFailedOutcome(
              result.message || '分镜生成失败，请稍后重试。',
              result.panels
            )
          }
        }

        return
      }

      // 列表里没有「进行中」的脚本任务，但本地仍持久化了 taskId：
      // 用 task/detail 兜底确认真实终态（SSE 建连即终态补发，detail 比 list 更可靠），
      // 避免因列表瞬时缺失 / 主从延迟而误判任务已结束、错误清空 loading 与 taskId。
      if (prefId && !runtime.isTaskFollowPaused(prefId)) {
        const detail = await fetchUserTaskDetailOnce(prefId)
        if (gen !== runtime.getResumeFollowGeneration()) return
        if (!matchesCreationLiveGenScope(routeCtx)) return
        const detailStatus = normalizeTaskStatus(detail?.status)

        if (detail && (isOngoingStoryboardScriptTaskStatus(detailStatus) || isTerminalTaskStatus(detailStatus))) {
          if (!runtime.getStore().isGeneratingStoryboard) {
            runtime.getStore().setStoryboardGenerating(true)
            runtime.getStore().setStoryboardError(null)
          }
          if (!stripStoryboardScriptSkeletonPanels(currentPanels).length) {
            onShowGeneratingSkeleton()
          }
          const result = await runtime.followExistingTask(prefId, currentPanels, { startDetail: detail })
          if (gen !== runtime.getResumeFollowGeneration()) return
          if (!matchesCreationLiveGenScope(routeCtx)) return
          onPanelsUpdate(result.panels)
          if (result.ok) {
            applyStoryboardScriptSuccessOutcome()
          } else if (!runtime.getStopRequested()) {
            if (result.message?.includes('部分') || result.message?.includes('续生')) {
              /* followExistingTask 已写入 partial failed 状态 */
            } else if (result.message?.includes('仍在后台执行')) {
              /* SSE 断连但任务未终态：保留 generating 与 taskId，供下次刷新继续跟进 */
            } else {
              applyStoryboardScriptFailedOutcome(
                result.message || '分镜生成失败，请稍后重试。',
                result.panels
              )
            }
          }
          return
        }

        // detail 拿不到（任务已被清理）则视为终态缺失：继续走下方停止清理逻辑。
      }

      // 列表读取失败时仍可通过已知的 taskId 恢复。两处均无法确认时保留原状态。
      if (!taskListOk) return

      if (runtime.getStore().isGeneratingStoryboard || preferredId) {
        if (matchesCreationLiveGenScope(routeCtx)) {
          // 仍在 generating 但 taskId 已被误清且列表/详情均未命中进行中：保留状态供下次 watch 重试，勿误停
          if (runtime.getStore().isGeneratingStoryboard && !prefId && !ongoingId) {
            return
          }
          runtime.getStore().stopStoryboardGeneration()
        }
      }
    } finally {
      endFlowTaskListQuietWindow(ctx.projectId)
    }
  })()

  runtime.setRestoreSessionInFlight(pending)
  try {
    await pending
  } finally {
    if (runtime.getRestoreSessionInFlight() === pending) runtime.setRestoreSessionInFlight(null)
  }
}
