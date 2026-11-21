import type { CanvasSmartEditComposeClip, CanvasSmartEditComposeJob, CanvasSmartEditComposeSubmit } from '@/types/canvas-api'
import type { SmartEditAudioClip, SmartEditComposeTicket, SmartEditOutputMode, SmartEditVideoClip, StudioCanvasNode, StudioSmartEditState } from '@/types/studio'
import type { CanvasProjectWorkspaceRuntime } from '@/hooks/studio/useCanvasProjectWorkspace'
import { canvasSmartEditComposeCancel, canvasSmartEditComposeList, canvasSmartEditComposeSubmit } from '@/utils/business/canvas'
import { useStudioUiStore } from '@/stores/studioUi'
import { notifySmartEdit, useStudioSmartEdit } from '@/stores/studioSmartEdit'
import { downloadMediaFileWithToast } from '@/utils/mediaFileDownload'
import { studioId } from './studioGraph'
import { smartEditExportFingerprint, smartEditMediaClips, timelineDurationSeconds } from './studioSmartEdit'
import { smartEditDecodedClips } from './smartEditComposeRoute'
import { smartEditOutputSize } from './smartEditExport'

type ComposeRuntime = Pick<CanvasProjectWorkspaceRuntime, 'project' | 'canMutate' | 'saveNow'>
type OutputResource = { resourceId: string; resourceUrl: string }

/** 浏览器与服务端成片共用的写回：节点换成新成片，导出到画布时另建视频节点。 */
export function placeSmartEditOutput(nodeId: string, mode: SmartEditOutputMode, resource: OutputResource, edit: StudioSmartEditState) {
  const store = useStudioUiStore.getState()
  const node = store.nodes.find((item) => item.id === nodeId)
  if (!node) return
  const patch = { mediaKind: 'video' as const, mediaUrl: resource.resourceUrl, canvasResourceIds: [...new Set([resource.resourceId, ...smartEditMediaClips(edit.timeline).flatMap((clip) => clip.sourceResourceId ? [clip.sourceResourceId] : [])])], canvasPrimaryResourceId: resource.resourceId, status: 'success' as const, errorMessage: undefined }
  store.updateNodeData(nodeId, patch)
  if (mode !== 'canvas') return
  const resultId = store.addNode('video', { x: node.position.x + 380, y: node.position.y }, { ...patch, canvasResourceIds: [resource.resourceId], title: `${node.data.title} · 成片`, generationOptions: { aspectRatio: node.data.generationOptions?.aspectRatio || '16:9' } })
  store.addConnection({ source: nodeId, target: resultId, sourceHandle: null, targetHandle: null }, 'reference')
}

export function buildSmartEditComposeRequest(node: StudioCanvasNode, projectId: string): CanvasSmartEditComposeSubmit {
  const edit = node.data.smartEdit!
  const aspectRatio = node.data.generationOptions?.aspectRatio || '16:9'
  const hidden = edit.hiddenTracks || []
  const decoded = new Set(smartEditDecodedClips(edit.timeline, hidden))
  const base = (clip: SmartEditAudioClip) => ({ clipId: clip.id, sourceResourceId: clip.sourceResourceId!, timelineStartSeconds: clip.timelineStartSeconds, rangeStartSeconds: clip.range.startSeconds, rangeEndSeconds: clip.range.endSeconds, volume: clip.volume })
  const visual = (clip: SmartEditVideoClip): CanvasSmartEditComposeClip => ({ ...base(clip), track: clip.track, ...(clip.overlayRect ? { overlayRect: clip.overlayRect } : {}), ...(clip.transitionOut ? { transitionOut: clip.transitionOut } : {}) })
  const clips: CanvasSmartEditComposeClip[] = [
    ...[...edit.timeline.videoClips, ...edit.timeline.overlayClips].filter((clip) => decoded.has(clip)).map(visual),
    ...edit.timeline.audioClips.filter((clip) => decoded.has(clip)).map((clip) => ({ ...base(clip), track: 'audio' as const }))
  ]
  return {
    projectId, nodeId: node.id, operationId: studioId('compose'), fingerprint: smartEditExportFingerprint(edit, node.data.generationOptions?.aspectRatio),
    aspectRatio, ...smartEditOutputSize(aspectRatio), durationSeconds: timelineDurationSeconds(edit.timeline), hiddenTracks: hidden, clips,
    subtitles: hidden.includes('subtitle') ? [] : edit.timeline.subtitles, texts: hidden.includes('text') ? [] : edit.timeline.texts
  }
}

export type SmartEditComposeDecision = 'pending' | 'write' | 'download' | 'failed' | 'cancelled'
export function smartEditComposeDecision(job: Pick<CanvasSmartEditComposeJob, 'status' | 'resourceId' | 'resourceUrl'>, ticket: SmartEditComposeTicket, currentFingerprint: string): SmartEditComposeDecision {
  if (job.status === 'queued' || job.status === 'running') return 'pending'
  if (job.status === 'cancelled') return 'cancelled'
  if (job.status !== 'succeeded' || !job.resourceId || !job.resourceUrl) return 'failed'
  return ticket.fingerprint === currentFingerprint ? 'write' : 'download'
}

const submittedHere = new Set<string>()
const progressKey = (scopeKey: string, nodeId: string) => `${scopeKey}:${nodeId}`
function setComposeProgress(key: string, value?: number) {
  useStudioSmartEdit.setState((state) => {
    const next = { ...state.composeProgress }
    if (value === undefined) delete next[key]; else next[key] = value
    return { composeProgress: next }
  })
}
function writeEdit(nodeId: string, change: (edit: StudioSmartEditState) => StudioSmartEditState) {
  const store = useStudioUiStore.getState()
  const edit = store.nodes.find((item) => item.id === nodeId)?.data.smartEdit
  if (edit) store.updateNodeData(nodeId, { smartEdit: change(edit) })
}

export async function submitSmartEditCompose(runtime: ComposeRuntime, scopeKey: string, nodeId: string, mode: SmartEditOutputMode) {
  const node = useStudioUiStore.getState().nodes.find((item) => item.id === nodeId)
  const projectId = runtime.project?.projectId
  if (!node?.data.smartEdit || !projectId) throw new Error('当前画布尚未保存，暂不能提交服务端成片')
  if (node.data.smartEdit.compose) throw new Error('该节点已有未完成的成片任务')
  const request = buildSmartEditComposeRequest(node, String(projectId))
  const { jobId } = await canvasSmartEditComposeSubmit(request)
  if (useStudioUiStore.getState().scopeKey !== scopeKey) return
  submittedHere.add(String(jobId))
  writeEdit(nodeId, (edit) => ({ ...edit, compose: { jobId: String(jobId), mode, fingerprint: request.fingerprint } }))
  setComposeProgress(progressKey(scopeKey, nodeId), 0)
  await runtime.saveNow()
}

export async function cancelSmartEditCompose(runtime: ComposeRuntime, scopeKey: string, nodeId: string) {
  const ticket = useStudioUiStore.getState().nodes.find((item) => item.id === nodeId)?.data.smartEdit?.compose
  const projectId = runtime.project?.projectId
  if (!ticket || !projectId) return
  await canvasSmartEditComposeCancel({ projectId, nodeId, jobId: ticket.jobId })
  if (useStudioUiStore.getState().scopeKey !== scopeKey) return
  writeEdit(nodeId, ({ compose: _done, ...edit }) => edit)
  setComposeProgress(progressKey(scopeKey, nodeId))
  notifySmartEdit(nodeId, 'info', '已取消导出', scopeKey)
  await runtime.saveNow()
}

/** 按项目查询一次服务端任务，并处理画布上所有带任务票据的剪辑节点。 */
export async function syncSmartEditComposeJobs(runtime: ComposeRuntime, scopeKey: string) {
  const projectId = runtime.project?.projectId
  const pending = () => useStudioUiStore.getState().nodes.filter((node) => node.data.smartEdit?.compose)
  if (!projectId || !pending().length) return
  const { jobs } = await canvasSmartEditComposeList(projectId)
  const workspace = useStudioUiStore.getState()
  if (workspace.scopeKey !== scopeKey) return
  const byId = new Map(jobs.map((job) => [String(job.jobId), job]))
  let changed = false
  for (const node of pending()) {
    const edit = node.data.smartEdit!, ticket = edit.compose!, key = progressKey(scopeKey, node.id)
    const job = byId.get(ticket.jobId)
    const decision = job ? smartEditComposeDecision(job, ticket, smartEditExportFingerprint(edit, node.data.generationOptions?.aspectRatio)) : 'failed'
    if (decision === 'pending') { setComposeProgress(key, Math.max(0, Math.min(99, Math.round(job?.progress ?? 0)))); continue }
    // 只读成片的协作者仍看到进度，结果由可编辑的一方写回。
    if (!runtime.canMutate) continue
    setComposeProgress(key)
    changed = true
    const result = decision === 'write' || decision === 'download' ? { jobId: ticket.jobId, resourceId: String(job!.resourceId), resourceUrl: job!.resourceUrl! } : undefined
    writeEdit(node.id, ({ compose: _done, ...rest }) => result ? { ...rest, composeDownload: result } : rest)
    if (decision === 'write') {
      placeSmartEditOutput(node.id, ticket.mode, result!, edit)
      notifySmartEdit(node.id, 'success', ticket.mode === 'canvas' ? '服务端成片已添加到画布' : '服务端成片已保存到剪辑节点', scopeKey)
      if (ticket.mode === 'local' && submittedHere.has(ticket.jobId)) void downloadMediaFileWithToast(result!.resourceUrl, `${node.data.title}.mp4`, '成片')
    } else if (decision === 'download') notifySmartEdit(node.id, 'warning', '服务端成片已完成，但时间轴已修改，未覆盖当前成片；可在导出菜单下载这次成片', scopeKey)
    else if (decision === 'failed') notifySmartEdit(node.id, 'error', job?.errorMessage || '服务端成片失败，原成片未被覆盖', scopeKey)
    submittedHere.delete(ticket.jobId)
  }
  if (changed) await runtime.saveNow()
}
