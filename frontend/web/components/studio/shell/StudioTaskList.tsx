'use client'

import { useMemo, useState } from 'react'
import { Button, Progress, message } from 'antd'
import { CheckOutlined, CloseOutlined, StopOutlined } from '@ant-design/icons'
import { useStudioCommands } from '@/hooks/studio/useStudioCommands'
import { selectStudioTasks, useStudioUiStore } from '@/stores/studioUi'
import type { StudioTask, StudioTaskStatus } from '@/types/studio'
import { requestCancelUserTaskById } from '@/utils/userTaskCancelFlow'
import { userTaskCancel, userTaskCancelBatch } from '@/utils/businessApi'
import { formatTaskEtaText } from '@/utils/taskSseProgressText'
import { recoverStudioRemoteTask } from '@/utils/studio/studioRemoteTaskRecovery'

const TASK_STATUS_LABEL: Record<StudioTaskStatus, string> = {
  queued: '排队中',
  running: '执行中',
  waiting: '待确认',
  succeeded: '已完成',
  failed: '失败',
  cancelled: '已取消'
}

export function StudioTaskList({ limit = 8 }: { limit?: number }) {
  const allTasks = useStudioUiStore(selectStudioTasks)
  const tasks = useMemo(() => allTasks.slice(0, limit), [allTasks, limit])
  const { confirmPipeline, cancelTask } = useStudioCommands()
  const [cancellingTaskId, setCancellingTaskId] = useState('')
  const [stoppingQueued, setStoppingQueued] = useState(false)
  const queuedCanvasTasks = allTasks.filter((task) => task.canvasOperationId && task.canvasTaskId
    && task.status === 'queued' && task.canCancel)

  const activeVideoTasks = allTasks.filter((task) => task.canvasOperationId && task.title === '视频生成'
    && (task.status === 'queued' || task.status === 'running' || task.status === 'waiting'))
  const estimates = activeVideoTasks.flatMap((task) => task.canvasVideoFinishEta ? [task.canvasVideoFinishEta] : [])
  const batchEta = estimates.length === activeVideoTasks.length && estimates.length > 1
    ? formatTaskEtaText({
      estimatedFinishAtP50: Math.max(...estimates.map(({ p50 }) => p50)),
      estimatedFinishAtP90: Math.max(...estimates.map(({ p90 }) => p90))
    })
    : ''

  const cancelRemoteTask = async (task: StudioTask, remoteTaskId: number | string) => {
    if (cancellingTaskId) return
    setCancellingTaskId(task.id)
    useStudioUiStore.getState().patchTask(task.id, { stage: '正在请求后端取消' })
    try {
      if (task.canvasTaskId) {
        await userTaskCancel({ taskId: String(remoteTaskId), taskSource: 'MEDIA' })
        useStudioUiStore.getState().patchTask(task.id, { canCancel: false, stage: '排队任务已取消，正在同步节点状态' })
        message.success('排队任务已取消')
      } else {
        await requestCancelUserTaskById(remoteTaskId)
        useStudioUiStore.getState().patchTask(task.id, { stage: '已停止后续任务，请查看最终状态' })
        void recoverStudioRemoteTask({
          ...task, status: 'waiting', stage: '已停止后续任务，请查看最终状态'
        }).catch(() => {
          useStudioUiStore.getState().patchTask(task.id, { status: 'waiting', stage: '等待服务端终态' })
        })
        message.info('已停止后续任务；已执行的任务可能继续完成')
      }
    } catch (error: unknown) {
      const errorMessage = (error as Error)?.message || '后端取消失败'
      if (task.canvasTaskId) {
        useStudioUiStore.getState().patchTask(task.id, errorMessage.includes('正在执行')
          ? { canCancel: false, stage: '任务已开始执行，继续等待结果' }
          : { stage: '取消结果待确认，请以服务端任务状态为准' })
      } else {
        useStudioUiStore.getState().patchTask(task.id, { stage: '取消失败', errorMessage })
      }
      message.error(errorMessage)
    } finally {
      setCancellingTaskId('')
    }
  }

  const stopQueuedCanvasTasks = async () => {
    if (stoppingQueued || !queuedCanvasTasks.length) return
    setStoppingQueued(true)
    try {
      const selectedTasks = queuedCanvasTasks.slice(0, 100)
      const result = await userTaskCancelBatch({
        taskSource: 'MEDIA',
        taskIds: selectedTasks.map((task) => task.canvasTaskId!)
      })
      const byId = new Map(selectedTasks.map((task) => [String(task.canvasTaskId!), task]))
      for (const item of result.items ?? []) {
        const task = byId.get(String(item.taskId))
        if (!task) continue
        if (item.status === 'CANCELLED' || item.status === 'ALREADY_CANCELLED') {
          useStudioUiStore.getState().patchTask(task.id, { canCancel: false, stage: '排队任务已取消，正在同步节点状态' })
        } else if (item.status === 'IN_PROGRESS') {
          useStudioUiStore.getState().patchTask(task.id, { canCancel: false, stage: '任务已开始执行，继续等待结果' })
        }
      }
      const remaining = queuedCanvasTasks.length - selectedTasks.length
      message.info(`已取消 ${result.cancelCount + (result.alreadyCancelledCount ?? 0)} 项排队任务；${result.runningCount ?? 0} 项已开始执行，将继续完成${result.rejectedCount ? `；${result.rejectedCount} 项未能取消` : ''}${remaining ? `；另有 ${remaining} 项排队任务未提交停止请求` : ''}`)
    } catch (error: unknown) {
      message.error((error as Error)?.message || '停止后续任务失败')
    } finally {
      setStoppingQueued(false)
    }
  }

  if (!tasks.length) {
    return <div className="studio-panel-empty">暂无任务。真实媒体任务与本地演示任务会在此分别标记。</div>
  }

  return (
    <div className="studio-task-list" aria-live="polite" aria-label="画布任务状态">
      {activeVideoTasks.length > 1 ? (
        <p>剩余 {activeVideoTasks.length} 个视频任务 · {batchEta ? `全部完成 · ${batchEta}` : '暂无法估算整批完成时间'}</p>
      ) : null}
      {queuedCanvasTasks.length > 1 ? (
        <Button size="small" icon={<StopOutlined />} loading={stoppingQueued}
          onClick={() => void stopQueuedCanvasTasks()}>
          停止排队任务（{Math.min(queuedCanvasTasks.length, 100)}）
        </Button>
      ) : null}
      {tasks.map((task) => {
        const remoteTaskId = task.canvasTaskId ?? task.remoteTaskId ?? studioRemoteTaskId(task.id)
        const remote = Boolean(task.canvasOperationId || remoteTaskId)
        const canCancelRemote = task.canvasOperationId
          ? Boolean(task.canvasTaskId && task.status === 'queued' && task.canCancel) : true
        const statusLabel = task.canvasOperationId && task.status === 'waiting'
          ? '状态确认中' : TASK_STATUS_LABEL[task.status]
        return (
        <article key={task.id} className={`studio-task studio-task--${task.status}`} aria-label={`${task.title}，${statusLabel}`}>
          <div className="studio-task__header">
            <strong>{task.title}</strong>
            <span>{remote ? '真实任务' : '本地演示'} · {statusLabel}</span>
          </div>
          <p>{task.stage}</p>
          <Progress
            percent={task.progress}
            aria-label={`${task.title}进度 ${Math.round(task.progress)}%`}
            size="small"
            showInfo={false}
            status={task.status === 'failed' ? 'exception' : task.status === 'succeeded' ? 'success' : 'active'}
          />
          {task.errorMessage ? <div className="studio-task__error">{task.errorMessage}</div> : null}
          <div className="studio-task__actions">
            {task.status === 'waiting' && !remote ? (
              <Button size="small" type="primary" icon={<CheckOutlined />} onClick={() => void confirmPipeline(task.id)}>
                继续本地演示
              </Button>
            ) : null}
            {(task.status === 'running' || task.status === 'queued' || task.status === 'waiting') && (!remote || canCancelRemote) ? (
              <Button size="small" icon={<StopOutlined />} loading={cancellingTaskId === task.id} onClick={() => remoteTaskId ? void cancelRemoteTask(task, remoteTaskId) : void cancelTask(task.id)}>
                {remote ? '取消真实任务' : '停止本地演示'}
              </Button>
            ) : null}
            {remote && !remoteTaskId && (task.status === 'queued' || task.status === 'running' || task.status === 'waiting')
              ? <span>真实任务正在登记</span> : null}
            {task.canvasOperationId && task.status === 'running' ? <span>已开始执行，暂不可取消</span> : null}
            {task.status === 'failed' && !remote ? <span>本地演示不提供“真实重试”入口</span> : null}
            {task.status === 'succeeded' ? <span><CheckOutlined /> {task.canvasOperationId ? '真实任务已完成' : remote ? '真实结果已写入画布' : '本地演示结果已写入画布'}</span> : null}
            {task.status === 'cancelled' ? <span><CloseOutlined /> {remote ? '真实任务已取消' : '本地演示已停止'}</span> : null}
          </div>
        </article>
      )})}
    </div>
  )
}

function studioRemoteTaskId(value: string): number | null {
  const match = /^studio-(?:image|video)-(\d+)$/.exec(value)
  if (!match) return null
  const id = Number(match[1])
  return Number.isFinite(id) && id > 0 ? id : null
}
