'use client'

import { message } from 'antd'
import { useCreationStore } from '@/stores/creation'
import { createStudioTask, useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode, StudioNodeData, StudioVideoAudioTool } from '@/types/studio'
import type { StudioVideoAudioOperationRequest } from '@/types/business-api'
import { openRechargeModalFromInsufficientBalance } from '@/utils/api'
import { userStudioVideoAudioOperation } from '@/utils/businessApi'
import { runStudioRequestSingleFlight } from '@/utils/studio/studioRequestSingleFlight'
import { studioSseTaskProgressPatch } from '@/utils/studio/studioSseTaskProgress'
import { studioVideoAudioToolDefinition } from '@/utils/studio/studioVideoAudioTool'
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

export interface StudioVideoAudioTargets {
  videoNodeId: string
  audioNodeId: string
}

export interface RunStudioVideoAudioOperationInput {
  source: StudioCanvasNode
  targets: StudioVideoAudioTargets
  operation: StudioVideoAudioTool
  label: string
}

export interface StudioVideoAudioResult {
  videoUrl: string
  audioUrl: string
  videoRecordId?: number
  audioRecordId?: number
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

function mediaUrl(value: unknown): string {
  const url = typeof value === 'string' ? value.trim() : ''
  return /^(?:https?:|blob:|data:(?:video|audio)\/|\/)/i.test(url) ? url : ''
}

function positiveId(value: unknown): number | undefined {
  const id = Number(value)
  return Number.isFinite(id) && id > 0 ? id : undefined
}

/** 兼容同步结果、SSE、task/detail 以及带 role/mediaType 的 outputs 数组。 */
export function pickStudioVideoAudioResult(raw: unknown): StudioVideoAudioResult {
  const parsed = parseUnknownData(raw)
  if (!parsed || typeof parsed !== 'object') return { videoUrl: '', audioUrl: '' }
  const value = parsed as Record<string, unknown>
  const result: StudioVideoAudioResult = {
    videoUrl: ['videoUrl', 'silentVideoUrl', 'mutedVideoUrl', 'outputVideoUrl']
      .map((key) => mediaUrl(value[key]))
      .find(Boolean) || '',
    audioUrl: ['audioUrl', 'vocalUrl', 'vocalsUrl', 'backgroundAudioUrl', 'bgmUrl', 'outputAudioUrl']
      .map((key) => mediaUrl(value[key]))
      .find(Boolean) || '',
    videoRecordId: positiveId(value.videoRecordId),
    audioRecordId: positiveId(value.audioRecordId)
  }

  const merge = (nested: StudioVideoAudioResult) => {
    result.videoUrl ||= nested.videoUrl
    result.audioUrl ||= nested.audioUrl
    result.videoRecordId ||= nested.videoRecordId
    result.audioRecordId ||= nested.audioRecordId
  }
  for (const key of ['data', 'result', 'output'] as const) merge(pickStudioVideoAudioResult(value[key]))
  if (Array.isArray(value.outputs)) {
    value.outputs.forEach((entry) => {
      if (!entry || typeof entry !== 'object') return
      const output = entry as Record<string, unknown>
      const role = String(output.role ?? output.mediaType ?? output.type ?? '').toLowerCase()
      const url = mediaUrl(output.url ?? output.fileUrl ?? output.outputUrl)
      if (role.includes('video')) {
        result.videoUrl ||= url
        result.videoRecordId ||= positiveId(output.recordId ?? output.id)
      }
      if (role.includes('audio') || role.includes('vocal') || role.includes('background')) {
        result.audioUrl ||= url
        result.audioRecordId ||= positiveId(output.recordId ?? output.id)
      }
    })
  }
  return result
}

export function buildStudioVideoAudioOperationRequest(
  input: RunStudioVideoAudioOperationInput
): StudioVideoAudioOperationRequest {
  const creation = useCreationStore.getState()
  const projectId = Number(creation.currentProjectId)
  const episodeId = Number(creation.currentEpisodeId)
  const storyboardId = Number(input.source.data.storyboardId)
  const sourceRecordId = Number(input.source.data.recordId ?? input.source.data.genRecordId)
  return {
    nodeId: input.source.id,
    targetNodeIds: input.targets,
    sourceVideoUrl: String(input.source.data.mediaUrl || '').trim(),
    operation: studioVideoAudioToolDefinition(input.operation).businessOperation,
    ...(Number.isFinite(projectId) && projectId > 0 ? { projectId } : {}),
    ...(Number.isFinite(episodeId) && episodeId >= 0 ? { episodeId } : {}),
    ...(Number.isFinite(storyboardId) && storyboardId > 0 ? { storyboardId } : {}),
    ...(Number.isFinite(sourceRecordId) && sourceRecordId > 0 ? { sourceRecordId } : {})
  }
}

function safeError(error: unknown, fallback: string): string {
  const detail = error as { msg?: string; message?: string }
  const raw = String(detail?.msg || detail?.message || '').replace(/\s+/g, ' ').trim()
  if (!raw || /authorization|bearer|api[-_ ]?key|secret|token|signature/i.test(raw)) return fallback
  return raw.slice(0, 160)
}

function targetNodesStillExist(targets: StudioVideoAudioTargets): boolean {
  const ids = new Set(useStudioUiStore.getState().nodes.map((node) => node.id))
  return ids.has(targets.videoNodeId) && ids.has(targets.audioNodeId)
}

function updateTargetPair(
  targets: StudioVideoAudioTargets,
  patch: Partial<StudioNodeData>
): void {
  const store = useStudioUiStore.getState()
  if (store.nodes.some((node) => node.id === targets.videoNodeId)) store.updateNodeData(targets.videoNodeId, patch, false)
  if (store.nodes.some((node) => node.id === targets.audioNodeId)) store.updateNodeData(targets.audioNodeId, patch, false)
}

/** 单次提交、双节点共享进度与终态，避免音频/视频分别发起重复模型任务。 */
export async function runStudioVideoAudioOperation(
  input: RunStudioVideoAudioOperationInput
): Promise<boolean> {
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
  const scopeKey = useStudioUiStore.getState().scopeKey
  const singleFlightKey = `${scopeKey}:${input.source.id}:${input.targets.videoNodeId}:${input.targets.audioNodeId}:${input.operation}`
  return runStudioRequestSingleFlight(singleFlightKey, async () => {
    const targetIds = [input.targets.videoNodeId, input.targets.audioNodeId]
    const operationRunId = beginStudioOperationRun(scopeKey, targetIds)
    if (!operationRunId) return false
    const inputNodeIds = [input.source.id, ...targetIds]
    const inputSnapshotHash = createStudioOperationInputHash(scopeKey, inputNodeIds)
    const live = () => isStudioOperationRunCurrent(scopeKey, operationRunId, targetIds) &&
      isStudioOperationInputCurrent(scopeKey, inputSnapshotHash, inputNodeIds)
    let studioTaskId = ''
    try {
      updateTargetPair(input.targets, {
        status: 'generating',
        errorMessage: undefined,
        resultSummary: `${input.label}处理中`
      })
      const submitted = await userStudioVideoAudioOperation(buildStudioVideoAudioOperationRequest(input))
      if (!live()) return false
      let result = pickStudioVideoAudioResult(submitted)
      const taskId = Number(submitted.taskId)

      if (!result.videoUrl || !result.audioUrl) {
        if (!Number.isFinite(taskId) || taskId <= 0) throw new Error('媒体处理接口未返回有效任务')
        studioTaskId = `studio-video-audio-${taskId}`
        const task = createStudioTask(`${input.source.data.title} · ${input.label}`, {
          nodeId: input.targets.videoNodeId,
          targetNodeIds: [input.targets.videoNodeId, input.targets.audioNodeId],
          runId: operationRunId,
          remoteTaskId: taskId,
          resultKind: 'video_audio',
          inputSnapshotHash,
          inputNodeIds,
          stage: '任务已提交',
          progress: 0,
          status: 'running'
        })
        useStudioUiStore.getState().upsertTask({ ...task, id: studioTaskId })
        const terminal = await waitUserTaskSseTerminal({
          taskId,
          timeoutMs: TASK_SSE_TIMEOUT_MS,
          onProgress: (progress) => {
            if (!live()) return
            useStudioUiStore.getState().patchTask(studioTaskId, studioSseTaskProgressPatch(progress))
          }
        })
        if (!live()) return false
        if (terminal.kind === 'superseded') {
          useStudioUiStore.getState().patchTask(studioTaskId, { status: 'waiting', stage: '后台执行中' })
          return true
        }
        if (terminal.kind === 'sse' && (terminal.event.type === 'complete' || terminal.event.type === 'partial_failed')) {
          result = pickStudioVideoAudioResult(terminal.event.data)
        }
        const detail = result.videoUrl && result.audioUrl ? null : await fetchUserTaskDetailOnce(taskId)
        if (!live()) return false
        const status = normalizeTaskStatus(detail?.status)
        if ((!result.videoUrl || !result.audioUrl) && isOngoingUserTaskStatus(status)) {
          updateTargetPair(input.targets, { status: 'generating', resultSummary: '任务仍在后台执行，请在任务中心查看' })
          useStudioUiStore.getState().patchTask(studioTaskId, { status: 'waiting', stage: '后台执行中' })
          return true
        }
        if (status === 'FAILED' || status === 'CANCELLED') {
          throw new Error(String(detail?.errorMessage || `${input.label}失败`))
        }
        if (!result.videoUrl || !result.audioUrl) result = pickStudioVideoAudioResult(detail?.resultData)
      }

      if (!result.videoUrl || !result.audioUrl) throw new Error('任务已完成，但视频或音频结果不完整')
      result.videoUrl = requireSafeStudioMediaUrl(result.videoUrl)
      result.audioUrl = requireSafeStudioMediaUrl(result.audioUrl)
      const store = useStudioUiStore.getState()
      if (!live() || !targetNodesStillExist(input.targets)) return false
      store.updateNodeData(input.targets.videoNodeId, {
        status: 'success',
        mediaKind: 'video',
        mediaUrl: result.videoUrl,
        operationRunId: undefined,
        errorMessage: undefined,
        resultSummary: `${input.label}完成`,
        ...(result.videoRecordId ? { recordId: result.videoRecordId } : {})
      }, false)
      store.updateNodeData(input.targets.audioNodeId, {
        status: 'success',
        mediaKind: 'audio',
        mediaUrl: result.audioUrl,
        operationRunId: undefined,
        errorMessage: undefined,
        resultSummary: `${input.label}完成`,
        ...(result.audioRecordId ? { recordId: result.audioRecordId } : {})
      }, false)
      if (studioTaskId) store.patchTask(studioTaskId, {
        status: 'succeeded',
        stage: '已完成',
        progress: 100,
        completedNodeIds: [input.targets.videoNodeId, input.targets.audioNodeId]
      })
      message.success(`${input.label}完成`)
      return true
    } catch (error: unknown) {
      const store = useStudioUiStore.getState()
      const errorMessage = safeError(error, `${input.label}失败，请稍后重试`)
      if (live()) {
        updateTargetPair(input.targets, {
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
      if (!task || task.status !== 'waiting') settleStudioOperationRun(scopeKey, operationRunId, targetIds)
    }
  })
}
