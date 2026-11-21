'use client'

import { message } from 'antd'
import { request } from '@/utils/api'
import { unwrap } from '@/utils/business/shared'
import type { ApiEnvelope } from '@/types/business-api'
import type { StudioAudioEdit, StudioAudioOperationRequest } from '@/types/studio-audio'
import { useCreationStore } from '@/stores/creation'
import { createStudioTask, useStudioUiStore } from '@/stores/studioUi'
import { audioDerivedTitle, audioEditProblem, AUDIO_OPERATION_LABELS, parseAudioOutputs } from '@/utils/studio/studioAudioEdit'
import { runStudioRequestSingleFlight } from '@/utils/studio/studioRequestSingleFlight'
import { studioId } from '@/utils/studio/studioGraph'
import { studioSseTaskProgressPatch } from '@/utils/studio/studioSseTaskProgress'
import { fetchUserTaskDetailOnce, isOngoingUserTaskStatus, normalizeTaskStatus, TASK_SSE_TIMEOUT_MS, waitUserTaskSseTerminal } from '@/hooks/useTaskSseFollow'
import { requireSafeStudioMediaUrl, studioRemoteMediaUrlProblem } from '@/utils/mediaUrlPolicy'
import {
  createStudioOperationInputHash,
  isStudioOperationInputCurrent,
  settleStudioOperationRun
} from '@/utils/studio/studioOperationRun'

export function buildStudioAudioRequest(edit: StudioAudioEdit, targetNodeId: string): StudioAudioOperationRequest {
  const creation = useCreationStore.getState()
  const projectId = Number(creation.currentProjectId), episodeId = Number(creation.currentEpisodeId)
  return { nodeId: edit.sourceNodeId, targetNodeId, operation: edit.operation, sourceAudioUrl: edit.sourceAudioUrl,
    ...(edit.sourceRecordId ? { sourceRecordId: edit.sourceRecordId } : {}),
    ...(projectId > 0 ? { projectId } : {}),
    ...(creation.currentEpisodeId != null && Number.isFinite(episodeId) && episodeId >= 0 ? { episodeId } : {}),
    parameters: edit.operation === 'TRIM' ? { clipRange: { startSeconds: edit.startSeconds, endSeconds: edit.endSeconds } }
      : edit.operation === 'SPEED' ? { speed: edit.speed, preservePitch: true } : { instruction: edit.instruction.trim() }
  }
}

export async function runStudioAudioOperation(edit: StudioAudioEdit, targetNodeId: string): Promise<boolean> {
  const problem = audioEditProblem(edit)
  if (problem) { message.warning(problem); return false }
  const mediaProblem = studioRemoteMediaUrlProblem(edit.sourceAudioUrl)
  if (mediaProblem) { message.warning(mediaProblem); return false }
  const scope = useStudioUiStore.getState().scopeKey
  return runStudioRequestSingleFlight(`audio:${scope}:${targetNodeId}`, async () => {
    const target = useStudioUiStore.getState().nodes.find((node) => node.id === targetNodeId)
    if (!target || target.data.audioRunId) return false
    const runId = studioId('audio-run'), label = AUDIO_OPERATION_LABELS[edit.operation]
    const inputNodeIds = [edit.sourceNodeId, targetNodeId]
    const live = () => {
      const state = useStudioUiStore.getState()
      return state.scopeKey === scope && state.nodes.some((node) =>
        node.id === targetNodeId && node.data.audioRunId === runId && node.data.operationRunId === runId
      ) && isStudioOperationInputCurrent(scope, inputSnapshotHash, inputNodeIds)
    }
    let studioTaskId = ''
    useStudioUiStore.getState().updateNodeData(targetNodeId, {
      status: 'generating', audioRunId: runId, operationRunId: runId,
      errorMessage: undefined, resultSummary: `${label}处理中`
    }, false)
    const inputSnapshotHash = createStudioOperationInputHash(scope, inputNodeIds)
    try {
      const submitted = unwrap(await request.post<ApiEnvelope<{ taskId?: number; audioUrl?: string; outputs?: unknown[] }>>('/api/user/studio/audio/operation', buildStudioAudioRequest(edit, targetNodeId)))
      if (!live()) return false
      let outputs = parseAudioOutputs(submitted)
      if (!outputs.length) {
        const taskId = Number(submitted.taskId)
        if (!Number.isFinite(taskId) || taskId <= 0) throw new Error('missing task')
        studioTaskId = `studio-audio-${taskId}`
        useStudioUiStore.getState().upsertTask({ ...createStudioTask(`${edit.sourceTitle} · ${label}`, {
          nodeId: targetNodeId, targetNodeIds: [targetNodeId], runId, remoteTaskId: taskId,
          stage: '任务已提交', status: 'running', progress: 0,
          resultKind: 'audio', inputSnapshotHash, inputNodeIds
        }), id: studioTaskId })
        const terminal = await waitUserTaskSseTerminal({ taskId, timeoutMs: TASK_SSE_TIMEOUT_MS,
          onProgress: (progress) => { if (live()) useStudioUiStore.getState().patchTask(studioTaskId, studioSseTaskProgressPatch(progress)) }
        })
        if (!live()) return false
        if (terminal.kind === 'sse' && terminal.event.type === 'complete') outputs = parseAudioOutputs(terminal.event.data)
        if (!outputs.length) {
          const detail = await fetchUserTaskDetailOnce(taskId)
          if (!live()) return false
          const status = normalizeTaskStatus(detail?.status)
          if (isOngoingUserTaskStatus(status) || (terminal.kind === 'superseded' && !detail)) {
            useStudioUiStore.getState().patchTask(studioTaskId, { status: 'waiting', stage: '后台执行中，请在任务中心查看' })
            return true
          }
          if (status !== 'SUCCEEDED' && status !== 'SUCCESS') throw new Error('task failed')
          outputs = parseAudioOutputs(detail?.resultData)
        }
      }
      if (!outputs.length || (edit.operation !== 'CUSTOM_SPLIT' && outputs.length !== 1)) throw new Error('invalid outputs')
      outputs = outputs.map((output) => ({ ...output, audioUrl: requireSafeStudioMediaUrl(output.audioUrl) }))
      if (!live()) return false
      const state = useStudioUiStore.getState(), title = audioDerivedTitle(edit)
      const patches = outputs.map((output, index) => ({ title: outputs.length > 1 ? `${title}-${index + 1}` : title,
        status: 'success' as const, mediaKind: 'audio' as const, mediaUrl: output.audioUrl, recordId: output.recordId,
        audioDurationSeconds: output.durationSeconds, audioRunId: undefined, operationRunId: undefined, errorMessage: undefined,
        composerHidden: true, resultSummary: `${label}完成` }))
      state.updateNodeData(targetNodeId, patches[0], false)
      const extraIds = patches.length > 1 ? useStudioUiStore.getState().deriveAudioNodes(targetNodeId, patches.slice(1), false) : []
      if (studioTaskId) useStudioUiStore.getState().patchTask(studioTaskId, { status: 'succeeded', progress: 100, stage: '已完成', completedNodeIds: [targetNodeId, ...extraIds] })
      message.success(`${label}完成`)
      return true
    } catch {
      if (!live()) return false
      const errorMessage = `${label}未完成，请重试；音频处理接口需已开通`
      const state = useStudioUiStore.getState()
      state.updateNodeData(targetNodeId, {
        status: 'failed', audioRunId: undefined, operationRunId: undefined,
        errorMessage, resultSummary: errorMessage
      }, false)
      if (studioTaskId) state.patchTask(studioTaskId, { status: 'failed', stage: '处理失败', errorMessage })
      message.error(errorMessage)
      return false
    } finally {
      const task = studioTaskId ? useStudioUiStore.getState().tasks.find((item) => item.id === studioTaskId) : null
      if (task?.status === 'running' && useStudioUiStore.getState().scopeKey === scope && !live()) {
        useStudioUiStore.getState().patchTask(studioTaskId, {
          status: 'succeeded', stage: '结果基于旧输入，未覆盖当前节点', progress: 100
        })
      }
      if (!task || task.status !== 'waiting') settleStudioOperationRun(scope, runId, [targetNodeId])
    }
  })
}
