import { createRandomUuid } from '@/utils/clientId'
import type { DirectorCameraRoom, DirectorScope } from './directorCameraApi'
import { directorCameraPost } from './directorCameraApi'
import type { DirectorPathClip, DirectorSceneData, DirectorTransformTuple } from '@/types/studio'
import { bakeRecordedCameraClip } from './directorPathClipOps'

export interface DirectorRecordingSample { tMs: number; position: DirectorTransformTuple; target: DirectorTransformTuple; fov: number; roll: number }
export interface DirectorRecordingView {
  takeId: string; state: string; contextVersion: number; durableFrameSeq: number; expectedChunkNo: number;
  frameCount: number; durationMs: number; recovered: boolean; contentHash?: string; sceneRevision?: number; clipId?: string;
  durability?: string
}
interface RecordingChunk { chunkNo: number; firstFrameSeq: number; lastFrameSeq: number; samples: DirectorRecordingSample[] }
export interface RecordingRead { recording: DirectorRecordingView; chunks: Array<{ chunkNo: number; samples: DirectorRecordingSample[] }>; nextChunkCursor?: number | null }
const post = <T>(operation: string, data: object) => directorCameraPost<T>(`virtual-camera/recording/${operation}`, data)
export const directorRecordingApi = {
  control: (data: { sessionId: string; commandId: string; action: 'START' | 'STOP'; peerEpoch: number; bindingEpoch: number; takeId?: string }) => post<DirectorRecordingView>('control', data),
  confirm: (data: { takeId: string; contextVersion: number; commandId: string; result: 'STARTED' | 'STOPPED' | 'REJECTED' }) => post<DirectorRecordingView>('confirm', data),
  chunk: (data: RecordingChunk & { takeId: string; contextVersion: number }) => post<DirectorRecordingView>('chunk', data),
  state: (takeId: string) => post<DirectorRecordingView>('state', { takeId }),
  finalize: (data: { takeId: string; contextVersion: number; expectedLastChunkNo: number; expectedFrameCount: number; mode: 'NORMAL' | 'RECOVER_PREFIX' }) => post<DirectorRecordingView>('finalize', data),
  apply: (data: { takeId: string; contextVersion: number; expectedSceneRevision: number; timelineStartMs: number }) => post<DirectorRecordingView>('apply', data),
  recover: (data: { takeId: string; expectedContextVersion: number; sessionId: string }) => post<DirectorRecordingView>('recover', data),
  read: (takeId: string, chunkCursor = 0) => post<RecordingRead>('read', { takeId, chunkCursor }),
  list: (scope: DirectorScope, sceneId: string, cursor?: string) => post<{ items: DirectorRecordingView[]; nextCursor: string | null }>('list', { ...scope, sceneId, ...(cursor ? { cursor } : {}), pageSize: 20 }),
  discard: (takeId: string) => post<DirectorRecordingView>('discard', { takeId })
}
export const DIRECTOR_RECORDING_FRAMES_PER_CHUNK = 150
export const DIRECTOR_RECORDING_MAX_FRAMES = 18000
export const DIRECTOR_RECORDING_MAX_MS = 600000
const MAX_PENDING_CHUNKS = 2
type Api = typeof directorRecordingApi

/** 仅供机位注册投影保存：未知应用结果未回填画布时也不能删除服务端已经确认的take引用。 */
export function sceneWithPersistedDirectorTakes(local: DirectorSceneData, authoritative: DirectorSceneData) {
  const clips = [...(local.timeline?.pathClips ?? [])], ids = new Set(clips.map(clip => clip.id))
  for (const clip of authoritative.timeline?.pathClips ?? []) {
    if (clip.source === 'virtual-camera' && clip.takeId && clip.id === `take_${clip.takeId}` && !ids.has(clip.id)) {
      clips.push(clip); ids.add(clip.id)
    }
  }
  return { ...local, timeline: { ...local.timeline!, duration: Math.max(local.timeline?.duration ?? 10, authoritative.timeline?.duration ?? 10), pathClips: clips } }
}

/** 每块只在DURABLE回执后移除；失败保留同一块及命令，显式重试不会创建第二段录制。 */
export class DirectorRecordingWriter {
  view: DirectorRecordingView | null = null
  readonly startCommandId = createRandomUuid()
  readonly stopCommandId = createRandomUuid()
  private current: DirectorRecordingSample[] = []
  private queue: RecordingChunk[] = []
  private count = 0
  private lastTime = -1
  private nextChunkNo = 0
  private flight: Promise<void> | null = null
  private stopRequested = false
  constructor(readonly room: DirectorCameraRoom, private update: (view: DirectorRecordingView) => void,
    private failed: (error: unknown) => void, private api: Api = directorRecordingApi) {}
  private accept(view: DirectorRecordingView) {
    if (this.view && (view.takeId !== this.view.takeId || view.contextVersion !== this.view.contextVersion)) throw new Error('录制上下文已变化，请恢复已保存片段')
    this.view = view; this.update(view); return view
  }
  async begin() {
    const started = this.accept(await this.api.control({ sessionId: this.room.sessionId, commandId: this.startCommandId,
      action: 'START', peerEpoch: this.room.peerEpoch, bindingEpoch: this.room.bindingEpoch }))
    if (!['PREPARING', 'RECORDING'].includes(started.state)) throw new Error('录制状态已变化，请恢复草稿')
    const confirmed = this.accept(await this.api.confirm({ takeId: started.takeId, contextVersion: started.contextVersion,
      commandId: this.startCommandId, result: 'STARTED' }))
    if (confirmed.state !== 'RECORDING') throw new Error('服务端尚未确认开始录制')
    return confirmed
  }
  add(sample: DirectorRecordingSample) {
    if (!this.view || this.view.state !== 'RECORDING' || this.stopRequested) throw new Error('录制尚未确认或已经停止')
    if (this.count >= DIRECTOR_RECORDING_MAX_FRAMES || sample.tMs > DIRECTOR_RECORDING_MAX_MS) throw new Error('已达到十分钟录制上限，请保存片段')
    if (this.queue.length >= MAX_PENDING_CHUNKS) throw new Error('保存速度不足，已停止采样，请重试保存')
    if (sample.tMs <= this.lastTime || (this.count === 0 && sample.tMs !== 0)) throw new Error('录制采样时间无效')
    if (![sample.fov, sample.roll, ...sample.position, ...sample.target].every(Number.isFinite)) throw new Error('机位参数无效，已停止采样')
    this.current.push(structuredClone(sample)); this.lastTime = sample.tMs; this.count++
    if (this.current.length >= DIRECTOR_RECORDING_FRAMES_PER_CHUNK) { this.seal(); void this.flush().catch(error => this.failed(error)) }
  }
  private seal() {
    if (!this.current.length) return
    const samples = this.current; this.current = []
    const chunk: RecordingChunk = { chunkNo: this.nextChunkNo++, firstFrameSeq: this.count - samples.length,
      lastFrameSeq: this.count - 1, samples }
    if (new TextEncoder().encode(JSON.stringify(samples)).byteLength > 128 * 1024) throw new Error('录制分块超过大小限制')
    this.queue.push(chunk)
  }
  private flush(): Promise<void> {
    if (this.flight) return this.flight
    const run = async () => {
      while (this.queue.length) {
        const chunk = this.queue[0], view = this.view!
        const receipt = await this.api.chunk({ ...chunk, takeId: view.takeId, contextVersion: view.contextVersion })
        if (receipt.durability !== 'DURABLE' || receipt.expectedChunkNo !== chunk.chunkNo + 1
          || receipt.durableFrameSeq !== chunk.lastFrameSeq) throw new Error('录制分块尚未确认保存')
        this.accept(receipt); this.queue.shift()
      }
    }
    this.flight = run().finally(() => { this.flight = null }); return this.flight
  }
  async finish() {
    if (!this.view) throw new Error('录制尚未开始，请重试开始或恢复草稿')
    this.stopRequested = true
    if (this.count < 2) throw new Error('录制过短，至少需要两帧，请丢弃后重录')
    const view = this.view
    if (view.state === 'RECORDING' || view.state === 'PREPARING') {
      this.accept(await this.api.control({ sessionId: this.room.sessionId, commandId: this.stopCommandId,
        action: 'STOP', takeId: view.takeId, peerEpoch: this.room.peerEpoch, bindingEpoch: this.room.bindingEpoch }))
    }
    this.seal(); await this.flush()
    if (!this.view || ['READY', 'APPLIED'].includes(this.view.state)) return this.view!
    this.accept(await this.api.confirm({ takeId: view.takeId, contextVersion: view.contextVersion,
      commandId: this.stopCommandId, result: 'STOPPED' }))
    return this.accept(await this.api.finalize({ takeId: view.takeId, contextVersion: view.contextVersion,
      expectedLastChunkNo: this.nextChunkNo - 1, expectedFrameCount: this.count, mode: 'NORMAL' }))
  }
}

/** 只读取权威已有块，恢复不会把浏览器未确认尾部伪装成已保存样本。 */
export async function readDirectorRecording(takeId: string, api: Api = directorRecordingApi) {
  const samples: DirectorRecordingSample[] = []
  let cursor: number | null = 0, view: DirectorRecordingView | null = null, expectedChunk = 0, lastTime = -1
  while (cursor !== null) {
    if (expectedChunk >= 200 || cursor !== expectedChunk) throw new Error('录制分块游标无效')
    const page = await api.read(takeId, cursor)
    if (page.recording.takeId !== takeId || page.chunks.length > 1
      || view && page.recording.contextVersion !== view.contextVersion) throw new Error('录制状态变化，请重新读取')
    view = page.recording
    for (const chunk of page.chunks) {
      if (chunk.chunkNo !== expectedChunk++ || !chunk.samples.length || chunk.samples.length > 300) throw new Error('录制分块不连续')
      for (const sample of chunk.samples) {
        if (sample.tMs <= lastTime || (!samples.length && sample.tMs !== 0) || sample.tMs > DIRECTOR_RECORDING_MAX_MS
          || ![sample.tMs, sample.fov, sample.roll, ...sample.position, ...sample.target].every(Number.isFinite)) throw new Error('录制样本不完整')
        samples.push(sample); lastTime = sample.tMs
        if (samples.length > DIRECTOR_RECORDING_MAX_FRAMES) throw new Error('录制样本超过上限')
      }
    }
    cursor = page.nextChunkCursor ?? null
  }
  if (!view || samples.length !== view.frameCount || samples.length - 1 !== view.durableFrameSeq
    || expectedChunk !== view.expectedChunkNo) throw new Error('录制高水位与分块不一致')
  return { view, samples }
}

export function hydrateDirectorRecordedClip(scene: DirectorSceneData, cameraId: string,
  view: DirectorRecordingView, samples: DirectorRecordingSample[], metadata: { start: number; end: number; sessionId?: string; targetId: string }) {
  if (view.state !== 'APPLIED' || !view.clipId || !view.sceneRevision || metadata.targetId !== cameraId || samples.length < 2) throw new Error('录制尚未应用到当前机位')
  const old = scene.timeline?.pathClips?.find(clip => clip.id === view.clipId)
  const baked = bakeRecordedCameraClip({ cameraId, sessionId: metadata.sessionId ?? '', start: metadata.start,
    samples: samples.map(sample => ({ time: sample.tMs / 1000, position: sample.position, lookAt: sample.target, fov: sample.fov, roll: sample.roll })) })
  const clip: DirectorPathClip = { ...baked, id: view.clipId, takeId: view.takeId, end: metadata.end,
    createdAt: old?.createdAt ?? baked.createdAt, waypoints: baked.waypoints.map((point, index) => ({ ...point, id: `${view.clipId}_frame_${index}` })) }
  return { ...scene, revision: view.sceneRevision, timeline: { ...scene.timeline!, duration: Math.max(scene.timeline?.duration ?? 10, clip.end),
    pathClips: [...(scene.timeline?.pathClips ?? []).filter(item => item.id !== clip.id && item.takeId !== view.takeId), clip] } }
}
