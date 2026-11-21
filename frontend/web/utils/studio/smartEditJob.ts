import type { SmartEditAudioClip, SmartEditCommand, SmartEditPlaybook, SmartEditSubtitleCue, SmartEditVideoClip, StudioCanvasNode, StudioSmartEditState } from '@/types/studio'
import { useStudioUiStore } from '@/stores/studioUi'
import { dispatchSmartEdit, useStudioSmartEdit } from '@/stores/studioSmartEdit'
import { studioId } from './studioGraph'
import { defaultStudioSmartEditState, SMART_EDIT_SOURCE_LIMIT, smartEditMediaClips, smartEditSources } from './studioSmartEdit'
import { resolveMediaPlaybackUrl } from '@/utils/mediaFetch'

type Source = { id: string; url: string; durationSeconds: number; resourceId?: string }
export type SmartEditJobStep =
  | { type: 'addVideoClip'; clip: SmartEditVideoClip }
  | { type: 'addAudioClip'; clip: SmartEditAudioClip }
  | { type: 'addSubtitle'; cue: SmartEditSubtitleCue }
  | { type: 'requestGenerate'; kind: 'video' | 'voice'; prompt: string; sourceNodeId?: string }
export function canStartSmartEditJob(playbook: SmartEditPlaybook, sources: Pick<StudioCanvasNode, 'id' | 'data'>[]): { ok: true } | { ok: false; message: string } {
  const valid = sources.filter((source) => source.data.mediaUrl)
  if (playbook === 'mix' && !valid.some((source) => source.data.kind === 'video')) return { ok: false, message: '请先连接至少一个视频节点' }
  if (playbook !== 'mix' && !valid.some((source) => ['video', 'image'].includes(source.data.kind))) return { ok: false, message: '请先连接视频或图片节点' }
  if (valid.length > SMART_EDIT_SOURCE_LIMIT) return { ok: false, message: '剪辑成片最多引用 24 个素材' }
  return { ok: true }
}
function mediaClip(source: Source, timelineStartSeconds: number): SmartEditAudioClip {
  return { id: studioId('clip'), sourceNodeId: source.id, sourceUrl: source.url, sourceResourceId: source.resourceId, sourceDurationSeconds: source.durationSeconds, timelineStartSeconds, range: { startSeconds: 0, endSeconds: source.durationSeconds }, volume: 1 }
}
export function planSmartEditJob(input: { playbook: SmartEditPlaybook; prompt: string; videos: Source[]; audios: Source[]; images: Array<{ id: string; url: string }> }): SmartEditJobStep[] {
  const steps: SmartEditJobStep[] = []
  let videoTime = 0
  let audioTime = 0
  for (const source of input.videos) {
    steps.push({ type: 'addVideoClip', clip: { ...mediaClip(source, videoTime), track: 'video', transitionOut: { type: 'cut', durationSeconds: 0 } } })
    videoTime += source.durationSeconds
  }
  for (const source of input.audios) { steps.push({ type: 'addAudioClip', clip: mediaClip(source, audioTime) }); audioTime += source.durationSeconds }
  if (input.playbook !== 'mix') {
    if (!input.videos.length && input.images.length) steps.push({ type: 'requestGenerate', kind: 'video', prompt: input.prompt, sourceNodeId: input.images[0].id })
    if (!input.audios.length) steps.push({ type: 'requestGenerate', kind: 'voice', prompt: input.prompt })
    const sentences = input.prompt.split('。').map((sentence) => sentence.trim()).filter(Boolean)
    const texts = sentences.length ? sentences : ['']
    const duration = Math.max(0.1, videoTime, audioTime)
    texts.forEach((text, index) => steps.push({ type: 'addSubtitle', cue: { id: studioId('subtitle'), text, timelineStartSeconds: duration * index / texts.length, durationSeconds: duration / texts.length } }))
  }
  return steps
}
export function isSmartEditJobRunning(state: StudioSmartEditState): boolean { return Boolean(state.jobId) }

export function readSmartEditMediaDuration(node: StudioCanvasNode, signal?: AbortSignal): Promise<number> {
  if (node.data.kind === 'voice' && Number.isFinite(node.data.audioDurationSeconds) && node.data.audioDurationSeconds! > 0) return Promise.resolve(node.data.audioDurationSeconds!)
  return new Promise((resolve, reject) => {
    if (typeof document === 'undefined') { reject(new Error('无法读取素材时长')); return }
    const media = document.createElement(node.data.kind === 'voice' ? 'audio' : 'video')
    const cleanup = () => { clearTimeout(timer); signal?.removeEventListener('abort', abort); media.onloadedmetadata = null; media.onerror = null; media.removeAttribute('src'); media.load() }
    const fail = (error: Error) => { cleanup(); reject(error) }
    const abort = () => fail(new DOMException('剪辑作业已停止', 'AbortError'))
    const timer = setTimeout(() => fail(new Error(`无法读取“${node.data.title}”的时长，请检查素材`)), 20000)
    media.onloadedmetadata = () => {
      const duration = media.duration
      if (!Number.isFinite(duration) || duration <= 0) { fail(new Error('素材时长无效')); return }
      cleanup(); resolve(duration)
    }
    media.onerror = () => fail(new Error(`无法读取“${node.data.title}”`))
    signal?.addEventListener('abort', abort, { once: true })
    if (signal?.aborted) { abort(); return }
    media.preload = 'metadata'
    media.src = resolveMediaPlaybackUrl(node.data.mediaUrl || '')
  })
}
export interface SmartEditJobOptions {
  generateNode?: (nodeId: string) => void | Promise<boolean>
  readDuration?: (node: StudioCanvasNode, signal?: AbortSignal) => Promise<number>
  canEdit?: boolean
}
const running = new Set<string>()
export async function startSmartEditJob(nodeId: string, prompt: string, options: SmartEditJobOptions = {}): Promise<{ ok: boolean; message?: string }> {
  const store = useStudioUiStore.getState()
  const scope = store.scopeKey
  const target = store.nodes.find((node) => node.id === nodeId)
  if (options.canEdit === false || target?.data.kind !== 'smart_edit') return { ok: false, message: '当前画布不可编辑' }
  const key = `${scope}:${nodeId}`
  if (useStudioSmartEdit.getState().exportProgress[key] !== undefined) return { ok: false, message: '正在导出，请稍后剪辑' }
  if (running.has(key) || target.data.status === 'generating') return { ok: false, message: '正在剪辑' }
  const state = target.data.smartEdit ?? defaultStudioSmartEditState()
  const sources = smartEditSources(nodeId, store.nodes, store.edges)
  const valid = canStartSmartEditJob(state.playbook, sources)
  if (!valid.ok) return valid
  running.add(key)
  const jobId = studioId('editjob')
  const controller = new AbortController()
  const readDuration = options.readDuration ?? readSmartEditMediaDuration
  const liveNode = () => {
    const current = useStudioUiStore.getState()
    const node = current.nodes.find((item) => item.id === nodeId)
    if (current.scopeKey !== scope || node?.data.smartEdit?.jobId !== jobId) throw new Error('画布或剪辑节点已切换，作业已停止')
    return node
  }
  const commit = (command: SmartEditCommand) => {
    liveNode()
    if ((command.type === 'addVideoClip' || command.type === 'addAudioClip') && !useStudioUiStore.getState().nodes.some((node) => node.id === command.clip.sourceNodeId)) throw new Error('来源节点已删除，请重新选择素材')
    if (!dispatchSmartEdit(nodeId, command)) throw new Error('时间轴未能写入：请检查素材参数、数量与节点保存上限')
  }
  store.updateNodeData(nodeId, { prompt, status: 'generating', errorMessage: undefined, smartEdit: { ...state, jobId } })
  const unsubscribe = useStudioUiStore.subscribe((current) => { if (current.scopeKey !== scope || !current.nodes.some((node) => node.id === nodeId)) controller.abort() })
  const messageId = store.addMessage({ role: 'assistant', status: 'generating', content: '正在整理素材与时间轴…', nodeIds: [nodeId] })
  try {
    const videos: Source[] = [], audios: Source[] = []
    for (const source of sources) {
      if (source.data.kind === 'image') continue
      const durationSeconds = await readDuration(source, controller.signal)
      liveNode()
      ;(source.data.kind === 'video' ? videos : audios).push({ id: source.id, url: source.data.mediaUrl!, durationSeconds, resourceId: source.data.canvasPrimaryResourceId || source.data.canvasResourceIds?.[0] })
    }
    const steps = planSmartEditJob({ playbook: state.playbook, prompt, videos, audios, images: sources.filter((node) => node.data.kind === 'image').map((node) => ({ id: node.id, url: node.data.mediaUrl! })) })
    for (const step of steps) {
      const current = liveNode().data.smartEdit!
      if (step.type === 'addVideoClip' || step.type === 'addAudioClip') {
        // 重试只补缺段，不覆盖用户已裁切的片段，也不重复铺成功来源。
        if (smartEditMediaClips(current.timeline).some((clip) => clip.sourceNodeId === step.clip.sourceNodeId && !clip.invalid)) continue
        const lane = step.type === 'addVideoClip' ? current.timeline.videoClips : current.timeline.audioClips
        const start = Math.max(0, ...lane.map((clip) => clip.timelineStartSeconds + clip.range.endSeconds - clip.range.startSeconds))
        commit(step.type === 'addVideoClip' ? { type: 'addVideoClip', clip: { ...step.clip, timelineStartSeconds: start } } : { type: 'addAudioClip', clip: { ...step.clip, timelineStartSeconds: start } })
      } else if (step.type === 'requestGenerate') {
        const currentStore = useStudioUiStore.getState()
        const title = step.kind === 'voice' ? '剪辑旁白' : '剪辑画面'
        const source = sources.find((node) => node.id === step.sourceNodeId)
        const existing = currentStore.nodes.find((candidate) => candidate.data.smartEditOrigin?.nodeId === nodeId && candidate.data.smartEditOrigin.kind === step.kind && candidate.data.smartEditOrigin.sourceNodeId === step.sourceNodeId)
        const generatedId = existing?.id ?? currentStore.addNode(step.kind, { x: target.position.x - 380, y: target.position.y + (step.kind === 'voice' ? 250 : 0) }, { title, prompt: step.prompt, smartEditOrigin: { nodeId, kind: step.kind, sourceNodeId: step.sourceNodeId }, model: step.kind === 'video' ? target.data.model : undefined, generationOptions: { ...target.data.generationOptions, ...(source ? { startFrameUrl: source.data.mediaUrl } : {}) } })
        // 创建素材节点不抢走当前精修台的选择；失败重试复用同一个普通素材节点。
        if (!existing) currentStore.selectNodes(currentStore.selectedNodeIds)
        else if (!existing.data.mediaUrl) currentStore.updateNodeData(generatedId, { prompt: step.prompt })
        if (source) currentStore.addConnection({ source: source.id, target: generatedId, sourceHandle: null, targetHandle: null }, 'reference')
        if (!options.generateNode) throw new Error('当前环境未接入素材生成，请连接已有的视频或音频')
        const succeeded = existing?.data.mediaUrl ? true : await options.generateNode(generatedId)
        liveNode()
        const generated = useStudioUiStore.getState().nodes.find((node) => node.id === generatedId)
        if (!succeeded || !generated?.data.mediaUrl) throw new Error(generated?.data.errorMessage || `${title}生成未完成，请在素材节点重试后重新发送`)
        const durationSeconds = await readDuration(generated, controller.signal)
        liveNode()
        currentStore.addConnection({ source: generatedId, target: nodeId, sourceHandle: null, targetHandle: null }, 'reference')
        const live = liveNode().data.smartEdit!.timeline
        const lane = step.kind === 'voice' ? live.audioClips : live.videoClips
        const start = Math.max(0, ...lane.map((clip) => clip.timelineStartSeconds + clip.range.endSeconds - clip.range.startSeconds))
        const clip = mediaClip({ id: generatedId, url: generated.data.mediaUrl, durationSeconds, resourceId: generated.data.canvasPrimaryResourceId || generated.data.canvasResourceIds?.[0] }, start)
        commit(step.kind === 'voice' ? { type: 'addAudioClip', clip } : { type: 'addVideoClip', clip: { ...clip, track: 'video', transitionOut: { type: 'cut', durationSeconds: 0 } } })
      }
    }
    const completed = liveNode().data.smartEdit!
    if (state.playbook !== 'mix' && !completed.timeline.subtitles.length) {
      const cues = steps.filter((step) => step.type === 'addSubtitle')
      cues.forEach((step, index) => commit({ type: 'addSubtitle', cue: { ...step.cue, timelineStartSeconds: completed.timeline.durationSeconds * index / cues.length, durationSeconds: completed.timeline.durationSeconds / cues.length } }))
    }
    const latest = liveNode().data.smartEdit!
    store.updateNodeData(nodeId, { status: 'success', smartEdit: { ...latest, jobId: undefined } })
    store.updateMessage(messageId, { status: 'complete', content: '时间轴已排好，可以打开剪辑成片精修和导出。' })
    return { ok: true }
  } catch (error) {
    const detail = error instanceof Error ? error.message : '剪辑失败，请重试'
    if (useStudioUiStore.getState().scopeKey === scope) {
      const latest = useStudioUiStore.getState().nodes.find((node) => node.id === nodeId)
      if (latest?.data.smartEdit?.jobId === jobId) store.updateNodeData(nodeId, { status: 'failed', errorMessage: detail, smartEdit: { ...latest.data.smartEdit, jobId: undefined } })
      store.updateMessage(messageId, { status: 'failed', content: `${detail}；已完成片段保留，可重新发送补齐。`, actionable: true })
    }
    return { ok: false, message: detail }
  } finally { unsubscribe(); controller.abort(); running.delete(key) }
}
