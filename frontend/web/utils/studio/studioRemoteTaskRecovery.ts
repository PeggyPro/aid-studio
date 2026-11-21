import { applyStudioImageResults } from './studioImageResults'
import {
  fetchUserTaskDetailOnce,
  resolveUserTaskTerminalOutcome,
  TASK_SSE_TIMEOUT_MS,
  waitUserTaskSseTerminal
} from '@/hooks/useTaskSseFollow'
import { useStudioUiStore } from '@/stores/studioUi'
import type { LayerSplitData } from '@/types/business-api'
import type { StudioCanvasNode, StudioTask } from '@/types/studio'
import { applyLayerSplitSuccess } from './studioLayerSplitSubmit'
import { studioSseTaskProgressPatch } from './studioSseTaskProgress'
import { isStudioOperationInputCurrent } from './studioOperationRun'
import { studioRemoteMediaUrlProblem } from '@/utils/mediaUrlPolicy'

type UnknownRecord = Record<string, unknown>

function isRecord(value: unknown): value is UnknownRecord {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function parseResultPayload(value: unknown): unknown {
  if (typeof value !== 'string') return value
  const trimmed = value.trim()
  if (!trimmed) return null
  try {
    return JSON.parse(trimmed) as unknown
  } catch {
    return value
  }
}

function walkRecords(value: unknown, depth = 0): UnknownRecord[] {
  if (depth > 6) return []
  const parsed = parseResultPayload(value)
  if (Array.isArray(parsed)) return parsed.flatMap((item) => walkRecords(item, depth + 1))
  if (!isRecord(parsed)) return []
  return [parsed, ...Object.values(parsed).flatMap((item) => walkRecords(item, depth + 1))]
}

function firstUrl(records: UnknownRecord[], keys: string[]): string {
  for (const record of records) {
    for (const key of keys) {
      const value = record[key]
      if (typeof value === 'string' && value.trim()) return value.trim()
    }
  }
  return ''
}

function mediaResultUrl(records: UnknownRecord[], kind: 'image' | 'video' | 'audio'): string {
  const specificKeys = kind === 'video'
    ? ['videoUrl', 'video_url', 'silentVideoUrl', 'outputVideoUrl']
    : kind === 'audio'
      ? ['audioUrl', 'audio_url', 'vocalUrl', 'backgroundAudioUrl', 'outputAudioUrl']
      : ['imageUrl', 'image_url', 'outputImageUrl']
  const specific = firstUrl(records, specificKeys)
  if (specific) return specific
  const typed = records.filter((record) => {
    const role = String(record.role ?? record.mediaType ?? record.type ?? '').toLowerCase()
    return role.includes(kind) || (kind === 'audio' && (role.includes('vocal') || role.includes('background')))
  })
  const typedUrl = firstUrl(typed, ['mediaUrl', 'fileUrl', 'outputUrl', 'url'])
  if (typedUrl) return typedUrl
  const genericUrls = [...new Set(records.flatMap((record) =>
    ['mediaUrl', 'fileUrl', 'outputUrl', 'url']
      .map((key) => record[key])
      .filter((value): value is string => typeof value === 'string' && Boolean(value.trim()))
      .map((value) => value.trim())
  ))]
  return genericUrls.length === 1 ? genericUrls[0] : ''
}

function mediaResultRecordId(records: UnknownRecord[], kind: 'image' | 'video' | 'audio'): number | null {
  const specificKey = kind === 'video' ? 'videoRecordId' : kind === 'audio' ? 'audioRecordId' : 'imageRecordId'
  const typed = records.filter((record) => {
    const role = String(record.role ?? record.mediaType ?? record.type ?? '').toLowerCase()
    return role.includes(kind) || (kind === 'audio' && (role.includes('vocal') || role.includes('background')))
  })
  const candidates = [...records.map((record) => record[specificKey]), ...typed.map((record) => record.recordId ?? record.id)]
  if (kind === 'image') candidates.push(...records.map((record) => record.imageId))
  const value = candidates.find((candidate) => Number.isSafeInteger(Number(candidate)) && Number(candidate) > 0)
  return value == null ? null : Number(value)
}

function matchesPendingRun(node: StudioCanvasNode, task: StudioTask): boolean {
  if (!task.runId) return node.data.status === 'generating' || node.data.status === 'queued'
  return node.data.operationRunId === task.runId || node.data.audioRunId === task.runId
}

function matchesInputSnapshot(task: StudioTask): boolean {
  if (!task.inputSnapshotHash || !task.inputNodeIds?.length) return true
  return isStudioOperationInputCurrent(useStudioUiStore.getState().scopeKey, task.inputSnapshotHash, task.inputNodeIds)
}

function clearUnresolvedTarget(node: StudioCanvasNode, summary: string) {
  useStudioUiStore.getState().updateNodeData(node.id, {
    status: node.data.mediaUrl ? 'success' : 'empty',
    operationRunId: undefined,
    audioRunId: undefined,
    resultSummary: summary
  }, false)
}

function applyLayerSplitResult(task: StudioTask, payload: unknown): boolean {
  const store = useStudioUiStore.getState()
  if (!matchesInputSnapshot(task)) return false
  const resultNode = task.nodeId ? store.nodes.find((node) => node.id === task.nodeId) : null
  if (!resultNode || !matchesPendingRun(resultNode, task)) return false
  const records = walkRecords(payload)
  const candidate = records.find((item) => typeof item.backgroundUrl === 'string' && Array.isArray(item.layers))
  if (!candidate) return false
  const sourceNodeId = resultNode.data.imageToolOptions?.sourceNodeId
  if (!sourceNodeId || !store.nodes.some((node) =>
    node.id === sourceNodeId && (!task.runId || node.data.operationRunId === task.runId)
  )) return false
  const data = candidate as unknown as LayerSplitData
  if (!data.backgroundUrl || !data.layers?.length) return false
  if (studioRemoteMediaUrlProblem(data.backgroundUrl) || data.layers.some((layer) => studioRemoteMediaUrlProblem(layer.mediaUrl))) {
    return false
  }
  const { sourcePatch, resultPatch } = applyLayerSplitSuccess({ sourceNodeId, resultNodeId: resultNode.id, data })
  store.updateNodeData(sourceNodeId, { ...sourcePatch, operationRunId: undefined }, false)
  store.updateNodeData(resultNode.id, { ...resultPatch, operationRunId: undefined }, false)
  return true
}

function applyMediaResult(task: StudioTask, payload: unknown): boolean {
  const store = useStudioUiStore.getState()
  if (!matchesInputSnapshot(task)) return false
  const targetIds = [...new Set([...(task.targetNodeIds ?? []), ...(task.nodeId ? [task.nodeId] : [])])]
  const targets = targetIds.map((id) => store.nodes.find((node) => node.id === id)).filter((node): node is StudioCanvasNode => Boolean(node))
  const records = walkRecords(payload)
  let applied = false
  for (const node of targets) {
    if (!matchesPendingRun(node, task)) continue
    const role = node.data.mediaDerivation?.outputRole
    const mediaKind = role ?? node.data.mediaKind ?? (task.resultKind === 'audio' ? 'audio' : task.resultKind === 'video' ? 'video' : 'image')
    const resultMediaKind = mediaKind === 'video' ? 'video' : mediaKind === 'audio' ? 'audio' : 'image'
    if (resultMediaKind === 'image') {
      const items = records.flatMap((record) => typeof record.imageUrl === 'string' ? [{ imageUrl: record.imageUrl,
        recordId: Number(record.recordId ?? record.imageId) || undefined }] : [])
      if (items.length && applyStudioImageResults(node.id, items, '真实任务已恢复')) { applied = true; continue }
    }
    const url = mediaResultUrl(records, resultMediaKind)
    if (!url || studioRemoteMediaUrlProblem(url)) continue
    const recordIdValue = mediaResultRecordId(records, resultMediaKind)
    store.updateNodeData(node.id, {
      status: 'success',
      mediaKind,
      mediaUrl: url,
      operationRunId: undefined,
      audioRunId: undefined,
      errorMessage: undefined,
      resultSummary: '真实任务已恢复',
      ...(recordIdValue ? resultMediaKind === 'image'
        ? { genRecordId: recordIdValue }
        : { recordId: recordIdValue } : {})
    }, false)
    applied = true
  }
  return applied
}

export function applyRecoveredStudioTaskResult(task: StudioTask, payload: unknown): boolean {
  if (task.resultKind === 'director_space') return false
  return task.resultKind === 'layer_split'
    ? applyLayerSplitResult(task, payload)
    : applyMediaResult(task, payload)
}

/** 统一恢复一项自由画布真实任务；以服务端终态为准，不把 SSE 断连伪装成失败。 */
export async function recoverStudioRemoteTask(task: StudioTask): Promise<void> {
  if (task.resultKind === 'director_space') return
  const remoteTaskId = Number(task.remoteTaskId)
  if (!Number.isSafeInteger(remoteTaskId) || remoteTaskId <= 0) return
  const scopeKey = useStudioUiStore.getState().scopeKey
  const liveTask = () => {
    const state = useStudioUiStore.getState()
    return state.scopeKey === scopeKey ? state.tasks.find((item) => item.id === task.id) : undefined
  }

  let outcome = await resolveUserTaskTerminalOutcome(remoteTaskId, { force: true })
  if (!liveTask()) return
  let eventPayload: unknown = null
  if (outcome.kind === 'ongoing') {
    useStudioUiStore.getState().patchTask(task.id, { status: 'running', stage: '已恢复真实任务连接' })
    const terminal = await waitUserTaskSseTerminal({
      taskId: remoteTaskId,
      timeoutMs: TASK_SSE_TIMEOUT_MS,
      onProgress: (progress) => {
        if (liveTask()) {
          const patch = studioSseTaskProgressPatch(progress)
          useStudioUiStore.getState().patchTask(task.id, patch)
        }
      }
    })
    if (!liveTask() || terminal.kind === 'superseded') return
    if (terminal.kind === 'sse' && (terminal.event.type === 'complete' || terminal.event.type === 'partial_failed')) {
      eventPayload = terminal.event.data
    }
    outcome = await resolveUserTaskTerminalOutcome(remoteTaskId, { force: true })
  }
  if (!liveTask()) return
  const store = useStudioUiStore.getState()
  if (outcome.kind === 'ongoing') {
    store.patchTask(task.id, { status: 'waiting', stage: '后台执行中，可稍后返回恢复' })
    return
  }
  if (outcome.kind === 'failed' || outcome.kind === 'partial_failed') {
    const errorMessage = outcome.kind === 'failed' ? outcome.errorMessage : '任务部分失败，请在任务中心查看'
    store.patchTask(task.id, { status: 'failed', stage: '真实任务失败', errorMessage })
    return
  }
  if (outcome.kind === 'cancelled') {
    store.patchTask(task.id, { status: 'cancelled', stage: '服务端已取消', progress: 0, errorMessage: undefined })
    for (const nodeId of [...(task.targetNodeIds ?? []), ...(task.nodeId ? [task.nodeId] : [])]) {
      const node = store.nodes.find((item) => item.id === nodeId)
      if (node && matchesPendingRun(node, task)) clearUnresolvedTarget(node, '已取消')
    }
    return
  }
  const detail = outcome.detail ?? await fetchUserTaskDetailOnce(remoteTaskId)
  const applied = applyRecoveredStudioTaskResult(task, eventPayload ?? detail?.resultData)
  store.patchTask(task.id, {
    status: 'succeeded',
    progress: 100,
    stage: applied ? '已恢复并写入画布' : '已完成，结果未覆盖当前编辑'
  })
  if (!applied) {
    for (const nodeId of [...(task.targetNodeIds ?? []), ...(task.nodeId ? [task.nodeId] : [])]) {
      const node = store.nodes.find((item) => item.id === nodeId)
      if (node && matchesPendingRun(node, task)) clearUnresolvedTarget(node, '任务已完成，请从任务中心查看结果')
    }
  }
}
