/**
 * 分镜脚本批量生成：SSE 跟踪与终态判定（原 composables/useStoryboardScriptBatchGenerate.ts
 * 模块级纯函数 + trackTaskUntilDone 拆分，闭包状态经 ctx 显式传入，主体见
 * hooks/useStoryboardScriptBatchGenerate.ts）。
 */

import { applyStoryboardScriptPanelsFromApi } from '~/composables/useCreateFlowStoryboardSync'
import { captureCreationLiveGenScope, matchesCreationLiveGenScope } from '~/composables/useCreationLiveGenScopeGuard'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import { isOngoingUserTaskStatus, normalizeTaskStatus } from '~/composables/useTaskSseFollow'
import {
createTaskStream,
type TaskProgressEventData,
type TaskStreamHandle,
type TaskStreamResult
} from '~/composables/useTaskStream'
import { useCreationStore } from '~/stores/creation'
import type { StoryboardPanel } from '~/types'
import type { UserTaskDetailData,UserTaskRow } from '~/types/business-api'
import { userStoryboardList, userTaskDetailCached } from '~/utils/businessApi'
import {
getPersistedStoryboardScriptPanels,
mapStoryboardListRowToPanel
} from '~/utils/storyboardPanelMap'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import {
formatPartialFailedMessage,
parseTaskPartialFailedData,
type TaskPartialFailedData
} from '~/utils/taskPartialFailed'
import { shouldPreferSseBusinessTerminalOverOngoingDetail } from '~/utils/taskSseSilentDisconnect'
import { scheduleUserBalanceRefresh } from '~/utils/userBalanceRefresh'

export function storyboardScriptBizErr(e: unknown): string {
  const x = e as { msg?: string; message?: string }
  return x?.msg || x?.message || '操作失败'
}

export function parseStoryboardScriptTaskId(raw: unknown): number | null {
  const n = Number(raw)
  return Number.isFinite(n) && n > 0 ? n : null
}

function normStoryboardScriptTaskType(ty: unknown): string {
  return String(ty ?? '').trim().toLowerCase().replace(/-/g, '_')
}

export function isStoryboardScriptBatchTask(ty: unknown): boolean {
  return normStoryboardScriptTaskType(ty) === 'storyboard_script_batch'
}

export function isOngoingStoryboardScriptTaskStatus(status: unknown): boolean {
  return isOngoingUserTaskStatus(status)
}

/** 后端防重：同 project+episode 已有进行中的分镜脚本任务 */
export function isStoryboardScriptTaskBusyMessage(msg: string): boolean {
  const m = String(msg ?? '').trim()
  if (!m) return false
  return (
    m === '任务处理中' ||
    m.includes('任务处理中') ||
    m.includes('正在执行的分镜任务') ||
    m.includes('项目下有正在执行')
  )
}

export function hasPersistedStoryboards(panels: StoryboardPanel[]): boolean {
  return panels.some((p) => parseServerStoryboardId(p.id) != null)
}

export function safeParseResultData(raw: unknown): unknown {
  if (raw == null) return null
  if (typeof raw === 'object') return raw
  const s = String(raw).trim()
  if (!s) return null
  try {
    return JSON.parse(s)
  } catch {
    return null
  }
}

/* ---------- 状态无关的 store 结果落地助手（原创建器内函数，仅依赖全局 store） ---------- */

const getStore = () => useCreationStore.getState()

export function resolveScenePlotCountHint(): number {
  const scenes = getStore().formData.sceneCharacter?.scenes || []
  const count = scenes.filter((s: unknown) => String(s ?? '').trim().length > 0).length
  return Math.max(count, 1)
}

/** 拉取分镜列表并作为唯一数据源写回 store（终态后调用，不在生成中轮询） */
export async function refreshStoryboardScriptPanelsFromApi(shouldApply?: () => boolean): Promise<StoryboardPanel[]> {
  const scope = captureCreationLiveGenScope()
  const ctx = await resolveStoryScriptSaveContext(getStore(), getRouteLikeSnapshot())
  if (!ctx || !matchesCreationLiveGenScope(scope) || shouldApply?.() === false) return []
  const list = await userStoryboardList({
    projectId: ctx.projectId,
    episodeId: ctx.episodeId
  }, { refresh: true })
  const sorted = [...list].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
  const panels = sorted.map((row, index) => mapStoryboardListRowToPanel(row, index))
  if (matchesCreationLiveGenScope(scope) && shouldApply?.() !== false) {
    applyStoryboardScriptPanelsFromApi(panels)
  }
  return panels
}

export function seedStoryboardScriptProgressFromDetailRecord(
  detail: UserTaskDetailData | null | undefined
) {
  if (!detail) return
  const totalBatches = Number((detail as { totalBatches?: number }).totalBatches)
  const total = Number.isFinite(totalBatches) && totalBatches > 0 ? totalBatches : 0
  if (total > 0) {
    const cur = getStore().storyboardGenerationProgress
    if (!cur.total || cur.total < total) {
      getStore().setStoryboardProgress(Math.min(cur.completed, total), total)
    }
  }
}

export function applyStoryboardScriptSuccessOutcome() {
  getStore().clearStoryboardScriptGenerationOutcome()
  getStore().setStoryboardGenerating(false)
}

function resetStoryboardScriptListToEmpty() {
  applyStoryboardScriptPanelsFromApi([])
  getStore().setStoryboardProgress(0, 0)
}

function hasPersistedStoryboardScriptPanels(panels: StoryboardPanel[]): boolean {
  return getPersistedStoryboardScriptPanels(panels).length > 0
}

export function applyStoryboardScriptPartialFailedOutcome(
  taskId: number,
  msg: string,
  partialData: TaskPartialFailedData | null,
  panels: StoryboardPanel[]
) {
  if (!hasPersistedStoryboardScriptPanels(panels)) {
    resetStoryboardScriptListToEmpty()
  }
  getStore().setStoryboardPartialFailedOutcome(msg, taskId, partialData)
}

export function applyStoryboardScriptFailedOutcome(msg: string, panels: StoryboardPanel[]) {
  getStore().clearStoryboardScriptGenerationOutcome()
  if (!hasPersistedStoryboardScriptPanels(panels)) {
    resetStoryboardScriptListToEmpty()
  }
  getStore().setStoryboardError(msg)
  getStore().setStoryboardGenerating(false)
}

export function pickOngoingStoryboardScriptTask(
  tasks: UserTaskRow[],
  preferredTaskId?: number | null
): UserTaskRow | null {
  const ongoing = tasks
    .filter(
      (t) =>
        t && isStoryboardScriptBatchTask(t.taskType) && isOngoingStoryboardScriptTaskStatus(t.status)
    )
    .sort((a, b) => Number(b.id || 0) - Number(a.id || 0))

  if (!ongoing.length) return null

  const pref = parseStoryboardScriptTaskId(preferredTaskId)
  if (pref != null) {
    const hit = ongoing.find((t) => Number(t.id) === pref)
    if (hit) return hit
  }
  return ongoing[0] ?? null
}

export type StoryboardScriptTrackOutcome = {
  ok: boolean
  partial?: boolean
  message?: string
  partialData?: TaskPartialFailedData | null
  /** SSE 断连但服务端任务仍在跑：勿清 taskId / 勿当终态失败 */
  ongoing?: boolean
}

export function resolveOutcomeFromTaskDetail(
  detail: UserTaskDetailData | null
): StoryboardScriptTrackOutcome | null {
  if (!detail) return null
  const status = normalizeTaskStatus(detail.status)
  if (status === 'SUCCEEDED') {
    return { ok: true }
  }
  if (status === 'PARTIAL_FAILED') {
    const parsed = parseTaskPartialFailedData(
      detail.resultData ? safeParseResultData(detail.resultData) : null
    )
    return {
      ok: false,
      partial: true,
      message: formatPartialFailedMessage(
        parsed,
        detail.errorMessage || '部分场次生成失败，可点击续生重试失败项'
      ),
      partialData: parsed
    }
  }
  if (status === 'FAILED' || status === 'CANCELLED') {
    return { ok: false, message: detail.errorMessage || '分镜生成失败' }
  }
  return null
}

export function mapStreamResultToOutcome(res: TaskStreamResult): StoryboardScriptTrackOutcome {
  if (res.type === 'cancelled') {
    return { ok: false, message: res.message || '任务已取消' }
  }
  if (res.type === 'error') {
    return { ok: false, message: res.errorMessage || '分镜生成失败' }
  }
  if (res.type === 'partial_failed') {
    return {
      ok: false,
      partial: true,
      message: formatPartialFailedMessage(
        res.data,
        '部分场次生成失败，可点击续生重试失败项'
      ),
      partialData: res.data
    }
  }
  return { ok: true }
}

const STORYBOARD_STATUS_CHECK_MS = 12_000

export interface StoryboardScriptTrackCtx {
  isCurrent: () => boolean
  isStopRequested: () => boolean
  registerStop: (stop: () => void) => () => void
}

export async function trackStoryboardScriptTaskUntilDone(
  taskId: number,
  onSseProgress: (p: TaskProgressEventData) => void,
  ctx: StoryboardScriptTrackCtx,
  startDetail?: UserTaskDetailData | null
): Promise<StoryboardScriptTrackOutcome> {
  const stopped = (): StoryboardScriptTrackOutcome => ctx.isStopRequested()
    ? { ok: false, message: '已停止生成' }
    : { ok: false, ongoing: true, message: '任务仍在后台执行，请稍候或刷新页面自动恢复进度' }

  if (!ctx.isCurrent() || ctx.isStopRequested()) return stopped()
  const terminalAtStart = resolveOutcomeFromTaskDetail(startDetail ?? null)
  if (terminalAtStart) {
    scheduleUserBalanceRefresh()
    return terminalAtStart
  }

  let stream: TaskStreamHandle | undefined
  let timer: ReturnType<typeof setTimeout> | undefined
  let settled = false
  let checking = false
  let lastProgress = ''
  let reconnected = false
  let terminalStreamOutcome: StoryboardScriptTrackOutcome | undefined
  let stopProgress = () => {}
  let unregister = () => {}

  try {
    return await new Promise<StoryboardScriptTrackOutcome>((resolve) => {
      const finish = (result: StoryboardScriptTrackOutcome) => {
        if (settled) return
        settled = true
        resolve(result)
      }

      const scheduleStatusCheck = (delay = STORYBOARD_STATUS_CHECK_MS) => {
        clearTimeout(timer)
        if (settled || checking) return
        timer = setTimeout(async () => {
          if (!ctx.isCurrent() || ctx.isStopRequested()) {
            finish(stopped())
            return
          }
          checking = true
          // SSE 停顿或断开时只核对当前任务。分镜列表仅在确认终态后读取。
          const detail = await userTaskDetailCached(taskId, { force: true }).catch(() => null)
          checking = false
          if (settled) return
          if (!ctx.isCurrent() || ctx.isStopRequested()) {
            finish(stopped())
            return
          }
          const outcome = resolveOutcomeFromTaskDetail(detail) ?? terminalStreamOutcome
          if (outcome) {
            scheduleUserBalanceRefresh()
            finish(outcome)
          } else {
            if (!reconnected && stream?.isClosed() && isOngoingUserTaskStatus(detail?.status)) {
              reconnected = true
              connect()
            }
            scheduleStatusCheck()
          }
        }, delay)
      }

      const reconcileAfterDisconnect = () => {
        if (settled) return
        if (!ctx.isCurrent() || ctx.isStopRequested()) {
          finish(stopped())
          return
        }
        onSseProgress({ message: '连接中断，正在核对分镜任务状态…', stepTitle: '正在核对分镜任务状态…' })
        scheduleStatusCheck(0)
      }

      const connect = () => {
        stopProgress()
        stream = createTaskStream(taskId)
        stopProgress = stream.subscribeProgress((progress) => {
          if (settled) return
          if (!ctx.isCurrent() || ctx.isStopRequested()) {
            finish(stopped())
            return
          }
          onSseProgress(progress)
          // ETA 倒计时由本地生成，不能让它持续推迟终态核对。
          const signature = JSON.stringify([
            progress.status, progress.stage, progress.progress, progress.stepId,
            progress.stepIndex, progress.stepTotal, progress.processedCount, progress.currentCount,
            progress.updateMillis, progress.updateTime
          ])
          if (signature !== lastProgress) {
            lastProgress = signature
            scheduleStatusCheck()
          }
        })
        void stream.done.then((result) => {
          if (settled) return
          if (!ctx.isCurrent() || ctx.isStopRequested()) {
            finish(stopped())
          } else if (result.type === 'complete' || result.type === 'partial_failed') {
            finish(mapStreamResultToOutcome(result))
          } else if (shouldPreferSseBusinessTerminalOverOngoingDetail(result)) {
            terminalStreamOutcome = mapStreamResultToOutcome(result)
            scheduleStatusCheck(0)
          } else {
            reconcileAfterDisconnect()
          }
        }, reconcileAfterDisconnect)
      }

      unregister = ctx.registerStop(() => finish(stopped()))
      connect()
      scheduleStatusCheck()
    })
  } finally {
    clearTimeout(timer)
    stopProgress()
    unregister()
    stream?.close()
  }
}
