'use client'

import { useCallback, useState } from 'react'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { runCanvasVideoTool } from './runCanvasVideoTool'
import { message } from 'antd'
import { useCreationStore } from '@/stores/creation'
import { createStudioTask, useStudioUiStore } from '@/stores/studioUi'
import type {
  StudioCanvasNode,
  StudioVideoInterpolationMode,
  StudioVideoClipRange,
  StudioLayerSplitRect,
  StudioVideoResolution,
  StudioVideoDepthResolution,
  StudioVideoSlowMotionFactor,
  StudioVideoSubtitleRegion
} from '@/types/studio'
import type {
  StudioVideoOperation,
  StudioVideoOperationRequest
} from '@/types/business-api'
import { openRechargeModalFromInsufficientBalance } from '@/utils/api'
import { userStudioVideoOperation } from '@/utils/businessApi'
import { runStudioRequestSingleFlight } from '@/utils/studio/studioRequestSingleFlight'
import { studioSseTaskProgressPatch } from '@/utils/studio/studioSseTaskProgress'
import { studioVideoSubtitleRequestRegions } from '@/utils/studio/studioVideoSubtitleSelection'
import type { VideoGenerationRequestSettings } from '@/utils/videoGenerationRequest'
import { requireSafeStudioMediaUrl, studioRemoteMediaUrlProblem } from '@/utils/mediaUrlPolicy'
import {
  beginStudioOperationRun,
  createStudioOperationInputHash,
  isStudioOperationInputCurrent,
  isStudioOperationRunCurrent,
  settleStudioOperationRun
} from '@/utils/studio/studioOperationRun'
import {
  fetchUserTaskDetailOnce,
  isOngoingUserTaskStatus,
  normalizeTaskStatus,
  TASK_SSE_TIMEOUT_MS,
  waitUserTaskSseTerminal
} from '@/hooks/useTaskSseFollow'

export interface RunStudioVideoOperationInput {
  source: StudioCanvasNode
  targetNodeId: string
  operation: StudioVideoOperation
  label: string
  modelCode?: string
  generationSettings?: VideoGenerationRequestSettings
  resolution?: StudioVideoResolution | StudioVideoDepthResolution
  interpolationMode?: StudioVideoInterpolationMode
  frameRate?: number
  slowMotionFactor?: StudioVideoSlowMotionFactor
  regions?: StudioVideoSubtitleRegion[]
  clipRange?: StudioVideoClipRange
  cropRect?: StudioLayerSplitRect
  prompt?: string
  onProgress?: (percent: number) => void
  subjectEdits?: import('@/types/studio-video-subject').StudioVideoSubjectMark[]
}

function parseUnknownData(raw: unknown): unknown {
  if (typeof raw !== 'string') return raw
  const value = raw.trim()
  if (!value) return null
  try {
    return JSON.parse(value)
  } catch {
    return value
  }
}

/** 兼容同步出参、SSE data 与 task/detail resultData 的常用视频地址字段。 */
export function pickStudioVideoResult(raw: unknown): { videoUrl: string; recordId?: number } {
  const parsed = parseUnknownData(raw)
  if (typeof parsed === 'string') {
    return /^(?:https?:|blob:|data:video\/|\/)/i.test(parsed)
      ? { videoUrl: parsed }
      : { videoUrl: '' }
  }
  if (!parsed || typeof parsed !== 'object') return { videoUrl: '' }
  const value = parsed as Record<string, unknown>
  const direct = ['videoUrl', 'fileUrl', 'outputUrl', 'resultUrl', 'url']
    .map((key) => String(value[key] || '').trim())
    .find(Boolean)
  const recordId = Number(value.recordId ?? value.videoRecordId ?? value.id)
  if (direct) {
    return {
      videoUrl: direct,
      ...(Number.isFinite(recordId) && recordId > 0 ? { recordId } : {})
    }
  }
  const nestedKeys = ['data', 'result', 'output'] as const
  for (const key of nestedKeys) {
    const nested = pickStudioVideoResult(value[key])
    if (nested.videoUrl) return nested
  }
  if (Array.isArray(value.items)) {
    for (let index = value.items.length - 1; index >= 0; index -= 1) {
      const nested = pickStudioVideoResult(value.items[index])
      if (nested.videoUrl) return nested
    }
  }
  return { videoUrl: '' }
}

export function buildStudioVideoOperationRequest(
  input: RunStudioVideoOperationInput
): StudioVideoOperationRequest {
  const creation = useCreationStore.getState()
  const projectId = Number(creation.currentProjectId)
  const episodeId = Number(creation.currentEpisodeId)
  const storyboardId = Number(input.source.data.storyboardId)
  const sourceRecordId = Number(input.source.data.recordId ?? input.source.data.genRecordId)
  return {
    nodeId: input.source.id,
    targetNodeId: input.targetNodeId,
    sourceVideoUrl: String(input.source.data.mediaUrl || '').trim(),
    operation: input.operation,
    ...(input.modelCode ? { modelCode: input.modelCode } : {}),
    ...(Number.isFinite(projectId) && projectId > 0 ? { projectId } : {}),
    ...(Number.isFinite(episodeId) && episodeId >= 0 ? { episodeId } : {}),
    ...(Number.isFinite(storyboardId) && storyboardId > 0 ? { storyboardId } : {}),
    ...(Number.isFinite(sourceRecordId) && sourceRecordId > 0 ? { sourceRecordId } : {}),
    parameters: {
      ...(input.subjectEdits ? { subjectEdits: input.subjectEdits } : {}),
      ...(input.resolution ? { resolution: input.resolution } : {}),
      ...(input.interpolationMode ? { interpolationMode: input.interpolationMode } : {}),
      ...(input.frameRate ? { frameRate: input.frameRate } : {}),
      ...(input.slowMotionFactor ? { slowMotionFactor: input.slowMotionFactor } : {}),
      ...(input.regions?.length
        ? { regions: studioVideoSubtitleRequestRegions(input.regions) }
        : {}),
      ...(input.clipRange
        ? { clipRange: {
            startSeconds: input.clipRange.startSeconds,
            endSeconds: input.clipRange.endSeconds
          } }
        : {}),
      ...(input.prompt?.trim() ? { prompt: input.prompt.trim() } : {}),
      ...(input.cropRect ? { cropRect: { x: input.cropRect.x, y: input.cropRect.y, width: input.cropRect.w, height: input.cropRect.h } } : {})
    }
  }
}

function safeError(error: unknown, fallback: string): string {
  const detail = error as { msg?: string; message?: string }
  const raw = String(detail?.msg || detail?.message || '').replace(/\s+/g, ' ').trim()
  if (!raw || /authorization|bearer|api[-_ ]?key|secret|token|signature/i.test(raw)) return fallback
  return raw.slice(0, 160)
}

export async function runStudioVideoOperation(input: RunStudioVideoOperationInput): Promise<boolean> {
  const sourceUrl = String(input.source.data.mediaUrl || '').trim()
  if (!sourceUrl) {
    message.warning('请先上传或生成源视频')
    return false
  }
  const mediaProblem = studioRemoteMediaUrlProblem(sourceUrl)
  if (mediaProblem) {
    message.warning(mediaProblem)
    return false
  }
  const initialStore = useStudioUiStore.getState()
  const scopeKey = initialStore.scopeKey
  const singleFlightKey = `${scopeKey}:${input.source.id}:${input.targetNodeId}:${input.operation}`
  return runStudioRequestSingleFlight(singleFlightKey, async () => {
    const operationRunId = beginStudioOperationRun(scopeKey, [input.targetNodeId])
    if (!operationRunId) return false
    const inputNodeIds = [input.source.id, input.targetNodeId]
    const inputSnapshotHash = createStudioOperationInputHash(scopeKey, inputNodeIds)
    const live = () => isStudioOperationRunCurrent(scopeKey, operationRunId, [input.targetNodeId]) &&
      isStudioOperationInputCurrent(scopeKey, inputSnapshotHash, inputNodeIds)
    let studioTaskId = ''
    try {
      initialStore.updateNodeData(input.targetNodeId, {
        status: 'generating',
        errorMessage: undefined,
        resultSummary: `${input.label}处理中`
      })
      const submitted = await userStudioVideoOperation(buildStudioVideoOperationRequest(input))
      if (!live()) return false
      let result = pickStudioVideoResult(submitted)
      const taskId = Number(submitted.taskId)

      if (!result.videoUrl) {
        if (!Number.isFinite(taskId) || taskId <= 0) throw new Error('视频处理接口未返回有效任务')
        studioTaskId = `studio-video-${taskId}`
        const task = createStudioTask(`${input.source.data.title} · ${input.label}`, {
          nodeId: input.targetNodeId,
          targetNodeIds: [input.targetNodeId],
          runId: operationRunId,
          remoteTaskId: taskId,
          resultKind: 'video',
          inputSnapshotHash,
          inputNodeIds,
          stage: '任务已提交',
          progress: 0,
          status: 'running'
        })
        useStudioUiStore.getState().upsertTask({ ...task, id: studioTaskId, remoteTaskId: taskId })
        const terminal = await waitUserTaskSseTerminal({
          taskId,
          timeoutMs: TASK_SSE_TIMEOUT_MS,
          onProgress: (progress) => {
            const patch = studioSseTaskProgressPatch(progress)
            if (!live()) return
            useStudioUiStore.getState().patchTask(studioTaskId, patch)
            if (patch.progress != null) input.onProgress?.(patch.progress)
          }
        })
        if (!live()) return false
        if (terminal.kind === 'superseded') {
          useStudioUiStore.getState().patchTask(studioTaskId, { status: 'waiting', stage: '后台执行中' })
          return true
        }
        if (terminal.kind === 'sse' && (terminal.event.type === 'complete' || terminal.event.type === 'partial_failed')) {
          result = pickStudioVideoResult(terminal.event.data)
        }
        const detail = result.videoUrl ? null : await fetchUserTaskDetailOnce(taskId)
        if (!live()) return false
        const status = normalizeTaskStatus(detail?.status)
        if (!result.videoUrl && isOngoingUserTaskStatus(status)) {
          useStudioUiStore.getState().updateNodeData(input.targetNodeId, {
            status: 'generating',
            resultSummary: '任务仍在后台执行，请在任务中心查看'
          })
          useStudioUiStore.getState().patchTask(studioTaskId, { status: 'waiting', stage: '后台执行中' })
          return true
        }
        if (status === 'FAILED' || status === 'CANCELLED') {
          throw new Error(String(detail?.errorMessage || `${input.label}失败`))
        }
        if (!result.videoUrl) result = pickStudioVideoResult(detail?.resultData)
      }

      if (!result.videoUrl) throw new Error('任务已完成，但未恢复到视频地址')
      result.videoUrl = requireSafeStudioMediaUrl(result.videoUrl)
      const store = useStudioUiStore.getState()
      if (!live()) return false
      store.updateNodeData(input.targetNodeId, {
        status: 'success',
        mediaKind: 'video',
        mediaUrl: result.videoUrl,
        operationRunId: undefined,
        errorMessage: undefined,
        resultSummary: `${input.label}完成`,
        ...(result.recordId ? { recordId: result.recordId } : {})
      })
      if (studioTaskId) store.patchTask(studioTaskId, { status: 'succeeded', stage: '已完成', progress: 100 })
      message.success(`${input.label}完成`)
      return true
    } catch (error: unknown) {
      const store = useStudioUiStore.getState()
      const errorMessage = safeError(error, `${input.label}失败，请稍后重试`)
      if (live()) {
        store.updateNodeData(input.targetNodeId, {
          status: 'failed',
          errorMessage,
          resultSummary: `${input.label}失败`
        })
      }
      if (store.scopeKey !== scopeKey) return false
      if (studioTaskId) store.patchTask(studioTaskId, { status: 'failed', stage: '执行失败', errorMessage })
      openRechargeModalFromInsufficientBalance(errorMessage)
      message.error(errorMessage)
      return false
    } finally {
      const task = studioTaskId ? useStudioUiStore.getState().tasks.find((item) => item.id === studioTaskId) : null
      if (task?.status === 'running' && useStudioUiStore.getState().scopeKey === scopeKey && !live()) {
        useStudioUiStore.getState().patchTask(studioTaskId, {
          status: 'succeeded', stage: '结果基于旧输入，未覆盖当前节点', progress: 100
        })
      }
      if (!task || task.status !== 'waiting') settleStudioOperationRun(scopeKey, operationRunId, [input.targetNodeId])
    }
  })
}

export function useStudioVideoOperation() {
  const canvasRuntime = useCanvasProjectRuntime()
  const [busy, setBusy] = useState(false)
  const run = useCallback(async (input: RunStudioVideoOperationInput) => {
    if (busy) return false
    setBusy(true)
    try {
      return canvasRuntime
        ? await runStudioRequestSingleFlight(`${useStudioUiStore.getState().scopeKey}:${input.targetNodeId}:${input.operation}`, () => runCanvasVideoTool(input, canvasRuntime))
        : await runStudioVideoOperation(input)
    } finally {
      setBusy(false)
    }
  }, [busy, canvasRuntime])
  return { run, busy }
}
