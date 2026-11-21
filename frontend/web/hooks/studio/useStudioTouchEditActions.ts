'use client'

import { useCallback, useState } from 'react'
import { message } from 'antd'
import { useStudioUiStore, createStudioTask } from '@/stores/studioUi'
import { openRechargeModalFromInsufficientBalance } from '@/utils/api'
import { runStudioRequestSingleFlight } from '@/utils/studio/studioRequestSingleFlight'
import {
  buildTouchEditGeneratePayload,
  canSubmitTouchEdit
} from '@/utils/studio/touchEditSessionModel'
import { userTouchEditGenerate } from '~/utils/businessApi'
import {
  fetchUserTaskDetailOnce,
  isOngoingUserTaskStatus,
  normalizeTaskStatus,
  TASK_SSE_TIMEOUT_MS,
  waitUserTaskSseTerminal
} from '~/hooks/useTaskSseFollow'
import { requireSafeStudioMediaUrl, studioRemoteMediaUrlProblem } from '@/utils/mediaUrlPolicy'
import {
  beginStudioOperationRun,
  createStudioOperationInputHash,
  isStudioOperationInputCurrent,
  isStudioOperationRunCurrent,
  settleStudioOperationRun
} from '@/utils/studio/studioOperationRun'

function pickImageUrl(raw: unknown): string {
  if (!raw || typeof raw !== 'object') return ''
  const data = raw as Record<string, unknown>
  const direct = String(data.imageUrl || '').trim()
  if (direct) return direct
  const nested = data.data
  if (nested && typeof nested === 'object') {
    const nestedUrl = String((nested as Record<string, unknown>).imageUrl || '').trim()
    if (nestedUrl) return nestedUrl
  }
  const items = data.items
  if (Array.isArray(items)) {
    for (let i = items.length - 1; i >= 0; i -= 1) {
      const item = items[i]
      if (item && typeof item === 'object') {
        const url = String((item as Record<string, unknown>).imageUrl || '').trim()
        if (url) return url
      }
    }
  }
  return ''
}

export function useStudioTouchEditActions(nodeId: string) {
  const [busy, setBusy] = useState(false)

  const generate = useCallback(async () => {
    const store = useStudioUiStore.getState()
    const session = store.touchEditSession
    const node = store.nodes.find((item) => item.id === nodeId)
    if (!session || session.nodeId !== nodeId || !node) {
      message.warning('元素编辑会话已失效')
      return false
    }
    if (!canSubmitTouchEdit(session.edits)) {
      message.warning('请先添加编辑内容')
      return false
    }
    const image = String(node.data.mediaUrl || '').trim()
    if (!image) {
      message.warning('请先上传或生成源图片')
      return false
    }
    const mediaProblem = studioRemoteMediaUrlProblem(image)
    if (mediaProblem) {
      message.warning(mediaProblem)
      return false
    }

    const scopeKey = store.scopeKey
    setBusy(true)
    try {
      return await runStudioRequestSingleFlight(`${scopeKey}:${nodeId}:touch-edit`, async () => {
        const operationRunId = beginStudioOperationRun(scopeKey, [nodeId])
        if (!operationRunId) return false
        const inputNodeIds = [nodeId]
        const inputSnapshotHash = createStudioOperationInputHash(scopeKey, inputNodeIds)
        const live = () => isStudioOperationRunCurrent(scopeKey, operationRunId, [nodeId]) &&
          isStudioOperationInputCurrent(scopeKey, inputSnapshotHash, inputNodeIds)
        let keepRunForRecovery = false
        useStudioUiStore.getState().updateNodeData(nodeId, {
          status: 'generating',
          resultSummary: '元素编辑中',
          errorMessage: undefined
        })

        const body = buildTouchEditGeneratePayload(image, session.edits)
        let studioTaskId = ''
        try {
          const submitted = await userTouchEditGenerate(body)
          if (!live()) return false
          const syncUrl = pickImageUrl(submitted)
          if (syncUrl) {
            const safeSyncUrl = requireSafeStudioMediaUrl(syncUrl)
            useStudioUiStore.getState().updateNodeData(nodeId, {
              status: 'success',
              mediaUrl: safeSyncUrl,
              operationRunId: undefined,
              mediaKind: 'image',
              resultSummary: '元素编辑完成',
              errorMessage: undefined
            })
            useStudioUiStore.getState().closeTouchEditSession(session)
            message.success('元素编辑完成')
            return true
          }

          const taskId = Number(submitted.taskId ?? submitted.data?.taskId)
          if (!Number.isFinite(taskId) || taskId <= 0) {
            throw new Error('未返回有效任务或图片地址')
          }

          studioTaskId = `studio-touch-${taskId}`
          const task = createStudioTask(`${node.data.title} · 元素编辑`, {
            nodeId,
            targetNodeIds: [nodeId],
            runId: operationRunId,
            remoteTaskId: taskId,
            resultKind: 'touch_edit',
            inputSnapshotHash,
            inputNodeIds,
            stage: '任务已提交',
            progress: 0,
            status: 'running'
          })
          useStudioUiStore.getState().upsertTask({
            ...task,
            id: studioTaskId,
            remoteTaskId: taskId
          })

          const terminal = await waitUserTaskSseTerminal({
            taskId,
            timeoutMs: TASK_SSE_TIMEOUT_MS
          })

          if (terminal.kind === 'superseded') {
            useStudioUiStore.getState().updateNodeData(nodeId, {
              status: 'generating',
              resultSummary: '任务仍在后台执行，请在任务中心查看'
            })
            useStudioUiStore.getState().patchTask(studioTaskId, { status: 'waiting', stage: '后台执行中' })
            keepRunForRecovery = true
            return false
          }
          if (!live()) return false

          let imageUrl = ''
          if (terminal.kind === 'sse' && (terminal.event.type === 'complete' || terminal.event.type === 'partial_failed')) {
            imageUrl = pickImageUrl(terminal.event.data)
          }
          if (!imageUrl) {
            const detail = await fetchUserTaskDetailOnce(taskId)
            const st = normalizeTaskStatus(detail?.status)
            if (isOngoingUserTaskStatus(st)) {
              useStudioUiStore.getState().patchTask(studioTaskId, {
                status: 'waiting', stage: '后台执行中，可稍后返回恢复'
              })
              keepRunForRecovery = true
              return false
            }
            if (st === 'FAILED') {
              throw new Error(String(detail?.errorMessage || '元素编辑失败'))
            }
            imageUrl = pickImageUrl(detail?.resultData)
          }
          if (!imageUrl) {
            throw new Error('任务已完成，但未恢复到图片地址')
          }
          imageUrl = requireSafeStudioMediaUrl(imageUrl)

          useStudioUiStore.getState().updateNodeData(nodeId, {
            status: 'success',
            mediaUrl: imageUrl,
            operationRunId: undefined,
            mediaKind: 'image',
            resultSummary: '元素编辑完成',
            errorMessage: undefined
          })
          useStudioUiStore.getState().patchTask(studioTaskId, {
            status: 'succeeded',
            stage: '已完成',
            progress: 100
          })
          useStudioUiStore.getState().closeTouchEditSession(session)
          message.success('元素编辑完成')
          return true
        } catch (error: unknown) {
          const errMsg = String((error as { msg?: string; message?: string })?.msg
            || (error as Error)?.message
            || '元素编辑失败')
          openRechargeModalFromInsufficientBalance(errMsg)
          if (live()) useStudioUiStore.getState().updateNodeData(nodeId, {
            status: 'failed',
            errorMessage: errMsg,
            resultSummary: undefined
          })
          if (studioTaskId) {
            useStudioUiStore.getState().patchTask(studioTaskId, {
              status: 'failed',
              stage: '执行失败',
              errorMessage: errMsg
            })
          }
          message.error(errMsg)
          return false
        } finally {
          const task = studioTaskId ? useStudioUiStore.getState().tasks.find((item) => item.id === studioTaskId) : null
          if (task?.status === 'running' && useStudioUiStore.getState().scopeKey === scopeKey && !live()) {
            useStudioUiStore.getState().patchTask(studioTaskId, {
              status: 'succeeded', stage: '结果基于旧输入，未覆盖当前节点', progress: 100
            })
          }
          if (!keepRunForRecovery) settleStudioOperationRun(scopeKey, operationRunId, [nodeId])
        }
      })
    } finally {
      setBusy(false)
    }
  }, [nodeId])

  return { generate, busy }
}
