import { formatTaskEtaText, normalizeTaskEta, formatTaskWaitReason } from '@/utils/taskSseProgressText'
import type { CanvasTaskProjection } from '@/types/canvas-api'
import type { StudioNodeStatus, StudioTaskStatus } from '@/types/studio'

export function isCanvasTaskTerminal(status: string): boolean {
  return ['SUCCEEDED', 'FAILED', 'CANCELLED', 'CANCELED'].includes(status.toUpperCase())
}

export function canvasTaskAwaitingVerification(task: Pick<CanvasTaskProjection, 'status' | 'eta'>): boolean {
  return !isCanvasTaskTerminal(task.status) && task.eta?.waitReasonCode === 'SUBMISSION_RESULT_UNKNOWN'
}

/** ETA is an estimate, never proof that a task has finished. Missing estimates stay indeterminate. */
export function canvasTaskProgress(task: Pick<CanvasTaskProjection, 'status' | 'eta' | 'progress'>): number | null {
  if (task.status.toUpperCase() === 'SUCCEEDED') return 100
  if (!isCanvasTaskTerminal(task.status) && formatTaskWaitReason(task.eta)) return null
  const value = task.eta?.displayProgress ?? task.progress
  return typeof value === 'number' && Number.isFinite(value) ? Math.max(0, Math.min(95, Math.round(value))) : null
}

const CAPACITY_LABELS = {
  MODEL_CAPACITY: '等模型名额',
  PROVIDER_CAPACITY: '等渠道名额',
  ACCOUNT_CAPACITY: '等账号名额',
  PLATFORM_CAPACITY: '等平台名额'
}

export function canvasVideoFinishEta(task: CanvasTaskProjection) {
  if (task.mediaType !== 'VIDEO' || isCanvasTaskTerminal(task.status)) return undefined
  const eta = normalizeTaskEta(task.eta)
  const p50 = eta?.estimatedFinishAtP50
  const p90 = eta?.estimatedFinishAtP90
  return typeof p50 === 'number' && typeof p90 === 'number' ? { p50, p90 } : undefined
}

export function canvasTaskState(task: CanvasTaskProjection): { status: StudioTaskStatus; nodeStatus: StudioNodeStatus; stage: string } {
  const status = task.status.toUpperCase()
  if (status === 'SUCCEEDED') return { status: 'succeeded', nodeStatus: 'success', stage: '已完成' }
  if (status === 'FAILED') return { status: 'failed', nodeStatus: 'failed', stage: '生成失败' }
  if (status.startsWith('CANCEL')) return { status: 'cancelled', nodeStatus: 'empty', stage: '已取消' }
  const waitReason = formatTaskWaitReason(task.eta)
  if (waitReason) return { status: status === 'QUEUED' ? 'queued' : 'waiting', nodeStatus: 'queued', stage: waitReason }
  const timing = formatTaskEtaText(normalizeTaskEta(task.eta))
  const suffix = timing ? ` · ${timing}` : ''
  if (task.executionPhase === 'SAVING_MEDIA') return { status: 'running', nodeStatus: 'generating', stage: '保存素材 · 正在登记到画布' }
  if (status === 'QUEUED') {
    const reason = task.queueWaitReason ? CAPACITY_LABELS[task.queueWaitReason] : '等待调度'
    const ahead = task.queuePosition && task.queuePosition > 1 ? ` · 同模型前方 ${task.queuePosition - 1} 个` : ''
    return { status: 'queued', nodeStatus: 'queued', stage: reason + ahead + suffix }
  }
  if (task.executionPhase === 'UPSTREAM_PROCESSING') return { status: 'running', nodeStatus: 'generating', stage: '上游处理中' + suffix }
  if (task.executionPhase === 'SUBMITTING') return { status: 'running', nodeStatus: 'generating', stage: '正在提交上游' + suffix }
  return { status: status === 'SUBMITTING' ? 'waiting' : 'running', nodeStatus: 'generating', stage: (status === 'SUBMITTING' ? '提交中' : '生成中') + suffix }
}
