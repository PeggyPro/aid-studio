'use client'
import { createRandomUuid } from '@/utils/clientId'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { DirectorCamera, DirectorSceneData } from '@/types/studio'
import {
  directorCameraApi,
  directorCameraErrorText,
  DirectorCameraError,
  type DirectorCameraRoom
} from '@/utils/studio/directorCameraApi'
import { DirectorRtcSession, type DirectorCameraVideoSource } from '@/utils/studio/directorRtcSession'
import { directorHostRooms } from '@/utils/studio/directorCameraHostRoom'
import { useUserStore } from '@/stores/user'
import { useStudioFreeTheme } from '@/components/studio/theme/StudioFreeThemeProvider'
import { directorCameraPairingUrl } from '@/utils/studio/directorCameraPairing'
import { useStudioUiStore } from '@/stores/studioUi'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { DirectorRecordingWriter, directorRecordingApi, readDirectorRecording,
  sceneWithPersistedDirectorTakes, type DirectorRecordingView } from '@/utils/studio/directorRecordingPersistence'
import { directorRecordingSaveScene, restoreAppliedDirectorRecording } from '@/utils/studio/directorRecordingScene'
import { normalizeDirectorTimeline } from '@/utils/studio/directorTimeline'
import {
  applyVirtualCameraControl,
  focalMmToCamera,
  shouldApplySeq,
  type VirtualCameraControl
} from '@/utils/studio/virtualCameraPose'
import {
  parseVirtualCameraMessage,
  type VirtualCameraMessage,
  type VirtualCameraSnapshot
} from '@/utils/studio/virtualCameraMessages'
import {
  isDirectorCameraLocked,
  resolveDirectorCamera,
  resolveDirectorCameraLens,
  updateDirectorCameraEntity,
  updateDirectorCameraLens
} from '@/components/studio/director/directorCameraState'
import { useDirectorPlaybackStore } from '@/components/studio/director/directorPlaybackStore'

export interface VirtualCameraHostControlState {
  camera: DirectorCamera
  lastSeq: number
}

export function applyIncomingControl(
  state: VirtualCameraHostControlState,
  control: VirtualCameraControl,
  dt: number
): VirtualCameraHostControlState & { applied: boolean } {
  if (!shouldApplySeq(state.lastSeq, control.seq)) return { ...state, applied: false }
  return { camera: applyVirtualCameraControl(state.camera, control, dt), lastSeq: control.seq, applied: true }
}

type ConnectionState = 'creating' | 'waiting' | 'connected' | 'error'
type RecordingOrigin = { scopeKey: string; sceneId: string; cameraId: string; token: string | null }
const RECORDING_AUTHORITY_POLL_MS = 200

function safeSnapshot(scene: DirectorSceneData, cameraId: string): VirtualCameraSnapshot | null {
  const camera = resolveDirectorCamera(scene, cameraId)
  if (!camera) return null
  const snapshot: VirtualCameraSnapshot = {
    cameraId,
    duration: normalizeDirectorTimeline(scene.timeline).duration,
    camera,
    lens: resolveDirectorCameraLens(scene, cameraId),
    objects: [],
    modelAssets: [],
    lights: []
  }
  return JSON.parse(
    JSON.stringify(snapshot, (_key, value) =>
      typeof value === 'string' && /^(blob:|data:)/i.test(value) ? undefined : value
    )
  ) as VirtualCameraSnapshot
}

export function useVirtualCameraHost({
  workspaceId,
  video,
  scene,
  cameraId,
  beforePair,
  getLiveScene,
  onPreviewChange,
  onCommitChange,
  onCameraRejected,
  onError
}: {
  workspaceId: string
  video: DirectorCameraVideoSource | null
  scene: DirectorSceneData
  cameraId: string
  beforePair?: () => Promise<void>
  getLiveScene?: () => DirectorSceneData
  onPreviewChange: (scene: DirectorSceneData, cameraId: string) => void
  onCommitChange: (scene: DirectorSceneData) => void
  onCameraRejected?: (cameraId: string) => void
  onError?: (message: string) => void
}) {
  const runtime = useCanvasProjectRuntime()
  const projectId = runtime?.project?.projectId
  const actorId = useUserStore(state => state.user?.id)
  const { theme } = useStudioFreeTheme()
  const themeRef = useRef(theme)
  themeRef.current = theme
  const [connection, setConnection] = useState<ConnectionState>('creating')
  const [session, setSession] = useState<DirectorCameraRoom | null>(null)
  const refreshingRef = useRef(false)
  const [refreshing, setRefreshing] = useState(false)
  const [recording, setRecording] = useState(false)
  const [recordingAuthorized, setRecordingAuthorized] = useState(false)
  const [generation, setGeneration] = useState(0)
  const sceneRef = useRef(scene)
  const occupiedCameraIdRef = useRef(cameraId)
  const startFlight = useRef<Promise<unknown>>(Promise.resolve())
  const closeFlight = useRef<Promise<unknown>>(Promise.resolve())
  const sessionRef = useRef<DirectorCameraRoom | null>(null)
  const socketRef = useRef<DirectorRtcSession | null>(null)
  const lastSeqRef = useRef(-1)
  const lastControlAtRef = useRef<number | null>(null)
  const lastCameraFeedback = useRef<{ camera: DirectorCamera; recording: boolean; at: number } | null>(null)
  const writerRef = useRef<DirectorRecordingWriter | null>(null)
  const writerOrigin = useRef<RecordingOrigin | null>(null)
  const recordingFlight = useRef<Promise<void> | null>(null)
  const mountedRef = useRef(true)
  const captureStartedAt = useRef(0)
  const captureLastMs = useRef(-1)
  const stopAfterStart = useRef(false)
  const endRecordingRef = useRef<(commit?: boolean) => void>(() => {})
  const [connectionError, setConnectionError] = useState('')
  const [recordingStatus, setRecordingStatus] = useState<'idle' | 'starting' | 'recording' | 'saving' | 'error'>('idle')
  const [recordingError, setRecordingError] = useState('')
  const [draftsError, setDraftsError] = useState('')
  const [recordingTake, setRecordingTake] = useState<DirectorRecordingView | null>(null)
  const [recordingDrafts, setRecordingDrafts] = useState<DirectorRecordingView[]>([])
  const [recordingCursor, setRecordingCursor] = useState<string | null>(null)
  const [recordingsLoading, setRecordingsLoading] = useState(false)
  const draftsFlight = useRef<{ key: string; epoch: number; promise: Promise<void> } | null>(null)
  const draftsWriteEpoch = useRef(0)
  const registeredScene = useRef<string | null>(null)
  const applyPlan = useRef<{ takeId: string; contextVersion: number; scene: DirectorSceneData; saveId: string;
    timelineStartMs: number; expectedSceneRevision?: number } | null>(null)
  const recordingStartRef = useRef(0)
  const sampleTimerRef = useRef<number | undefined>(undefined)
  const recordingRef = useRef(false)
  const connectedRef = useRef(false)
  const getLiveSceneRef = useRef(getLiveScene)
  getLiveSceneRef.current = getLiveScene
  const readLiveScene = useCallback(() => getLiveSceneRef.current?.() ?? sceneRef.current, [])
  const previewRef = useRef(onPreviewChange)
  const commitRef = useRef(onCommitChange)
  const beforePairRef = useRef(beforePair)
  beforePairRef.current = beforePair
  const errorRef = useRef(onError)
  const rejectRef = useRef(onCameraRejected)
  useEffect(() => { mountedRef.current = true; return () => { mountedRef.current = false } }, [])
  useEffect(() => {
    sceneRef.current = scene
  }, [scene])
  useEffect(() => {
    previewRef.current = onPreviewChange
    commitRef.current = onCommitChange
    errorRef.current = onError
    rejectRef.current = onCameraRejected
  }, [onPreviewChange, onCommitChange, onError, onCameraRejected])

  const send = useCallback((message: VirtualCameraMessage) => socketRef.current?.send(message) === true, [])

  const stopCapture = useCallback(() => {
    recordingRef.current = false
    if (sampleTimerRef.current !== undefined) window.clearInterval(sampleTimerRef.current)
    sampleTimerRef.current = undefined
    if (mountedRef.current) setRecording(false)
  }, [])
  const recordingFailed = useCallback((error: unknown) => {
    stopCapture()
    socketRef.current?.send({ type: 'record', recording: 'stop' })
    if (!mountedRef.current) return
    const text = directorCameraErrorText(error)
    setRecordingStatus('error'); setRecordingError(text); errorRef.current?.(text)
  }, [stopCapture])
  const sameRecordingOrigin = useCallback((origin: RecordingOrigin) => mountedRef.current
    && useStudioUiStore.getState().scopeKey === origin.scopeKey && sceneRef.current.id === origin.sceneId
    && occupiedCameraIdRef.current === origin.cameraId && localStorage.getItem('token') === origin.token, [])
  const interruptRecording = useCallback(() => {
    const origin = writerOrigin.current
    if (!recordingRef.current || !origin || !sameRecordingOrigin(origin)) return false
    stopCapture()
    // The lost lease cannot authorize STOP or finalize. Recovery reads only durable chunks.
    const text = '录制连接或授权已中断，已保存前缀可稍后恢复'
    setRecordingStatus('error'); setRecordingError(text); errorRef.current?.(text)
    return true
  }, [sameRecordingOrigin, stopCapture])
  useEffect(() => {
    // READY may still carry the negotiation's preview-only lease until renewal.
    // This bounded local check never requests or extends authority.
    const timer = window.setInterval(() => {
      if (!mountedRef.current) return
      const authorized = connectedRef.current && socketRef.current?.can('RECORD') === true
      setRecordingAuthorized(current => current === authorized ? current : authorized)
      if (!authorized) interruptRecording()
    }, RECORDING_AUTHORITY_POLL_MS)
    return () => window.clearInterval(timer)
  }, [interruptRecording])
  const sampleNow = useCallback(() => {
    if (!recordingRef.current || !writerRef.current) return
    if (!connectedRef.current || !socketRef.current?.can('RECORD')) {
      interruptRecording()
      return
    }
    const camera = resolveDirectorCamera(readLiveScene(), occupiedCameraIdRef.current)
    if (!camera) return
    const tMs = captureLastMs.current < 0 ? 0 : Math.max(captureLastMs.current + 1, Math.round(performance.now() - captureStartedAt.current))
    try {
      writerRef.current.add({ tMs, position: [...camera.position], target: [...camera.target], fov: camera.fov, roll: camera.roll ?? 0 })
      captureLastMs.current = tMs
    } catch (error) { recordingFailed(error) }
  }, [interruptRecording, readLiveScene, recordingFailed])

  const refreshRecordings = useCallback(function requestRecordings(cursor?: string, afterWrite = false): Promise<void> {
    if (!runtime?.project || !projectId) return Promise.resolve()
    const scope = { projectId: String(projectId), workspaceId: String(runtime.project.workspace.workspaceId) }, sceneId = sceneRef.current.id
    const scopeKey = useStudioUiStore.getState().scopeKey
    const token = localStorage.getItem('token')
    const registryKey = JSON.stringify([scopeKey, scope.projectId, scope.workspaceId, sceneId, token])
    if (registeredScene.current !== registryKey) return Promise.resolve()
    const key = JSON.stringify([registryKey, cursor ?? null])
    if (afterWrite) draftsWriteEpoch.current++
    const epoch = draftsWriteEpoch.current
    const current = () => mountedRef.current && sceneRef.current.id === sceneId && registeredScene.current === registryKey
      && useStudioUiStore.getState().scopeKey === scopeKey && localStorage.getItem('token') === token
    const active = draftsFlight.current
    if (active?.key === key) {
      if (active.epoch >= epoch) return active.promise
      // A write completed after this physical read started. Join it, then perform one fresh read.
      return active.promise.then(() => { if (current()) return requestRecordings(cursor) })
    }
    setRecordingsLoading(true)
    setDraftsError('')
    const task = directorRecordingApi.list(scope, sceneId, cursor).then(page => {
      if (!current() || epoch !== draftsWriteEpoch.current) return
      setRecordingDrafts(current => cursor ? [...new Map([...current, ...page.items].map(item => [item.takeId, item])).values()] : page.items)
      setRecordingCursor(page.nextCursor)
    }).catch(error => { if (current() && epoch === draftsWriteEpoch.current) setDraftsError(directorCameraErrorText(error)) })
      .finally(() => {
        if (draftsFlight.current?.promise !== task) return
        draftsFlight.current = null; if (current() && epoch === draftsWriteEpoch.current) setRecordingsLoading(false)
      })
    draftsFlight.current = { key, epoch, promise: task }; return task
  }, [projectId, runtime])

  const applyRecording = useCallback(async (view: DirectorRecordingView, timelineStartMs: number, selectedCameraId: string, origin: RecordingOrigin) => {
    if (!runtime?.project || !projectId) throw new Error('画布项目已变化，请重新打开录制')
    const scope = { projectId: String(projectId), workspaceId: String(runtime.project.workspace.workspaceId) }
    const sceneId = origin.sceneId, scopeKey = origin.scopeKey
    const current = () => {
      if (!mountedRef.current || useStudioUiStore.getState().scopeKey !== scopeKey || sceneRef.current.id !== sceneId
        || localStorage.getItem('token') !== origin.token || occupiedCameraIdRef.current !== selectedCameraId
        || !resolveDirectorCamera(sceneRef.current, selectedCameraId)) throw new Error('画布或机位已变化，请从草稿恢复')
    }
    current()
    let applied = view
    if (view.state !== 'APPLIED') {
      if (view.state !== 'READY') throw new Error('录制尚未完整保存')
      if (!applyPlan.current || applyPlan.current.takeId !== view.takeId || applyPlan.current.contextVersion !== view.contextVersion) {
        const authoritative = await directorCameraApi.detail(scope, sceneId)
        current()
        if (authoritative.revision !== sceneRef.current.revision) throw new Error('场景版本已变化，请同步场景后恢复录制')
        applyPlan.current = { takeId: view.takeId, contextVersion: view.contextVersion, scene: directorRecordingSaveScene(sceneRef.current, authoritative.scene, selectedCameraId),
          saveId: createRandomUuid(), timelineStartMs }
      }
      const plan = applyPlan.current
      if (plan.expectedSceneRevision == null) {
        const saved = await directorCameraApi.save(scope, plan.scene, plan.saveId)
        current(); plan.expectedSceneRevision = saved.revision
      }
      try {
        applied = await directorRecordingApi.apply({ takeId: view.takeId, contextVersion: view.contextVersion,
          expectedSceneRevision: plan.expectedSceneRevision, timelineStartMs: plan.timelineStartMs })
      } catch (error) {
        // 应用响应丢失先查权威状态，不能重开录制或把未知结果当成取消。
        current()
        const authoritative = await directorRecordingApi.state(view.takeId)
        current()
        if (authoritative.state !== 'APPLIED') throw error
        applied = authoritative
      }
    }
    current()
    if (applied.state !== 'APPLIED' || !applied.clipId) throw new Error('服务端尚未确认应用录制')
    setRecordingTake(applied)
    setRecordingDrafts(items => items.map(item => item.takeId === applied.takeId ? applied : item))
    const [detail, durable] = await Promise.all([directorCameraApi.detail(scope, sceneId), readDirectorRecording(view.takeId)])
    current()
    const next = restoreAppliedDirectorRecording(sceneRef.current, selectedCameraId, applied, detail, durable)
    sceneRef.current = next; commitRef.current(next)
    const state = useStudioUiStore.getState()
    if (state.scopeKey === scopeKey && state.nodes.some(node => node.data.kind === 'director' && node.data.directorSceneId === sceneId)) state.setDirectorScene(next)
    setRecordingTake(applied); writerRef.current = null; applyPlan.current = null; setRecordingStatus('idle'); setRecordingError('')
    void refreshRecordings(undefined, true)
  }, [projectId, refreshRecordings, runtime])

  const beginRecording = useCallback(() => {
    if (recordingRef.current || recordingFlight.current) return
    if (!sessionRef.current || !connectedRef.current || !socketRef.current?.can('RECORD')) {
      const text = '机位录制授权尚未就绪，请稍候或重新连接'
      setRecordingStatus('error'); setRecordingError(text); errorRef.current?.(text)
      return
    }
    if (writerRef.current?.view && writerRef.current.view.state !== 'PREPARING') { recordingFailed(new Error('请先保存或丢弃当前录制草稿')); return }
    const sceneId = sceneRef.current.id, selectedCameraId = occupiedCameraIdRef.current, scopeKey = useStudioUiStore.getState().scopeKey
    const origin: RecordingOrigin = { sceneId, cameraId: selectedCameraId, scopeKey, token: localStorage.getItem('token') }
    setRecordingStatus('starting'); setRecordingError('')
    const task = (async () => {
      const room = await directorCameraApi.state(sessionRef.current!.sessionId)
      if (!mountedRef.current || sceneRef.current.id !== sceneId || occupiedCameraIdRef.current !== selectedCameraId
        || useStudioUiStore.getState().scopeKey !== scopeKey || !connectedRef.current || !socketRef.current?.can('RECORD')) throw new Error('连接或机位已变化，请重新开始')
      sessionRef.current = room
      if (!writerRef.current) {
        writerOrigin.current = origin
        const sameOrigin = () => mountedRef.current && sceneRef.current.id === sceneId && occupiedCameraIdRef.current === selectedCameraId && useStudioUiStore.getState().scopeKey === scopeKey && localStorage.getItem('token') === origin.token
        writerRef.current = new DirectorRecordingWriter(room, view => { if (sameOrigin()) setRecordingTake(view) }, error => { if (sameOrigin()) recordingFailed(error) })
      }
      await writerRef.current.begin()
      if (!mountedRef.current || !connectedRef.current || sceneRef.current.id !== sceneId || occupiedCameraIdRef.current !== selectedCameraId
        || useStudioUiStore.getState().scopeKey !== scopeKey || !socketRef.current?.can('RECORD')) throw new Error('连接或授权已变化，录制草稿可稍后恢复')
      recordingRef.current = true; setRecording(true); setRecordingStatus('recording')
      recordingStartRef.current = useDirectorPlaybackStore.getState().playhead
      captureStartedAt.current = performance.now(); captureLastMs.current = -1; sampleNow()
      if (recordingRef.current) sampleTimerRef.current = window.setInterval(sampleNow, 1000 / 30)
      send({ type: 'record', recording: 'start' })
    })().catch(error => { if (sameRecordingOrigin(origin)) recordingFailed(error) }).finally(() => {
      if (recordingFlight.current !== task) return
      recordingFlight.current = null
      if (stopAfterStart.current) { stopAfterStart.current = false; endRecordingRef.current(true) }
    })
    recordingFlight.current = task
  }, [recordingFailed, sameRecordingOrigin, sampleNow, send])

  const endRecording = useCallback((commit = true) => {
    if (recordingFlight.current) { if (recordingStatus === 'starting') stopAfterStart.current = true; return }
    const writer = writerRef.current, origin = writerOrigin.current
    if (!writer || !origin) return
    if (recordingRef.current) sampleNow()
    stopCapture()
    send({ type: 'record', recording: 'stop' })
    if (!commit || !connectedRef.current || !socketRef.current?.can('RECORD')) {
      recordingFailed(new Error('录制连接已中断，已保存前缀可稍后恢复')); return
    }
    setRecordingStatus('saving'); setRecordingError('')
    const selectedCameraId = occupiedCameraIdRef.current
    const task = writer.finish().then(view => applyRecording(view, Math.round(recordingStartRef.current * 1000), selectedCameraId, origin))
      .catch(error => { if (sameRecordingOrigin(origin)) recordingFailed(error) })
      .finally(() => { if (recordingFlight.current === task) recordingFlight.current = null })
    recordingFlight.current = task
  }, [applyRecording, recordingFailed, recordingStatus, sameRecordingOrigin, sampleNow, send, stopCapture])
  useEffect(() => { endRecordingRef.current = endRecording }, [endRecording])

  const recoverRecording = useCallback((take: DirectorRecordingView) => {
    if (recordingFlight.current || recordingRef.current) return
    const selectedCameraId = occupiedCameraIdRef.current, room = sessionRef.current
    const origin: RecordingOrigin = { sceneId: sceneRef.current.id, cameraId: selectedCameraId,
      scopeKey: useStudioUiStore.getState().scopeKey, token: localStorage.getItem('token') }
    if (!room && take.state !== 'APPLIED') { recordingFailed(new Error('请先重新建立机位会话后恢复')); return }
    setRecordingStatus('saving'); setRecordingError('')
    const task = (async () => {
      let view = await directorRecordingApi.state(take.takeId)
      if (view.state !== 'APPLIED') {
        if (!room) throw new Error('请先重新建立机位会话后恢复')
        view = await directorRecordingApi.recover({ takeId: view.takeId, expectedContextVersion: view.contextVersion, sessionId: room.sessionId })
        if (writerRef.current?.view?.takeId === view.takeId) writerRef.current = null
        const durable = await readDirectorRecording(view.takeId)
        if (durable.samples.length < 2) throw new Error('已保存片段不足两帧，请丢弃草稿')
        if (view.state !== 'READY') view = await directorRecordingApi.finalize({ takeId: view.takeId, contextVersion: view.contextVersion,
          expectedLastChunkNo: durable.view.expectedChunkNo - 1, expectedFrameCount: durable.view.frameCount, mode: 'RECOVER_PREFIX' })
      }
      await applyRecording(view, Math.round(useDirectorPlaybackStore.getState().playhead * 1000), selectedCameraId, origin)
    })().catch(error => { if (sameRecordingOrigin(origin)) recordingFailed(error) })
      .finally(() => { if (recordingFlight.current === task) recordingFlight.current = null })
    recordingFlight.current = task
  }, [applyRecording, recordingFailed, sameRecordingOrigin])
  const discardRecording = useCallback((take: DirectorRecordingView) => {
    if (recordingFlight.current || recordingRef.current || take.state === 'APPLIED') return
    const origin: RecordingOrigin = { scopeKey: useStudioUiStore.getState().scopeKey, sceneId: sceneRef.current.id,
      cameraId: occupiedCameraIdRef.current, token: localStorage.getItem('token') }
    setRecordingStatus('saving')
    const task = directorRecordingApi.discard(take.takeId).then(view => {
      if (view.state !== 'DISCARDED') throw new Error('服务端尚未确认丢弃草稿')
      if (!sameRecordingOrigin(origin)) return
      if (writerRef.current?.view?.takeId === take.takeId) writerRef.current = null
      applyPlan.current = null
      if (mountedRef.current) { setRecordingTake(null); setRecordingStatus('idle'); setRecordingError(''); void refreshRecordings(undefined, true) }
    }).catch(error => { if (sameRecordingOrigin(origin)) recordingFailed(error) })
      .finally(() => { if (recordingFlight.current === task) recordingFlight.current = null })
    recordingFlight.current = task
  }, [recordingFailed, refreshRecordings, sameRecordingOrigin])

  const applyControl = useCallback(
    (control: VirtualCameraControl) => {
      const current = readLiveScene()
      const id = occupiedCameraIdRef.current
      const camera = resolveDirectorCamera(current, id)
      if (!camera) return
      if (isDirectorCameraLocked(current, id)) {
        send({ type: 'error', code: 'CAMERA_LOCKED', message: '请先在电脑解锁机位' })
        return
      }
      const previous = lastControlAtRef.current
      // Device-relative elapsed time is stable even when packets arrive in bursts.
      const dt = previous !== null && control.t >= previous ? Math.min(.1, (control.t - previous) / 1000) : 1 / 30
      const applied = applyIncomingControl({ camera, lastSeq: lastSeqRef.current }, control, dt)
      if (!applied.applied) return
      lastControlAtRef.current = control.t
      lastSeqRef.current = applied.lastSeq
      const lens = resolveDirectorCameraLens(current, id)
      const focused = focalMmToCamera(applied.camera, lens, control.focalMm)
      const changed = Math.abs(camera.fov - focused.camera.fov) > .00001 || lens.focalLengthMm !== focused.lens.focalLengthMm
        || camera.position.some((value, axis) => Math.abs(value - focused.camera.position[axis]) > .00001)
        || camera.target.some((value, axis) => Math.abs(value - focused.camera.target[axis]) > .00001)
      if (changed) {
        const next = updateDirectorCameraLens(updateDirectorCameraEntity(current, id, focused.camera), id, focused.lens)
        sceneRef.current = next
        previewRef.current(next, id)
      }
      if (control.recording === 'start') beginRecording()
      if (control.recording === 'stop') endRecording(true)
      // Video and pose frames are independent of ordered UI feedback. Coalesce
      // the latest pose before it enters the reliable state channel.
      const now = performance.now()
      const effectiveCamera = changed ? focused.camera : camera
      const recording = recordingRef.current
      const last = lastCameraFeedback.current
      if (!last || (now - last.at >= 100 && (last.camera !== effectiveCamera || last.recording !== recording))) {
        const sent = send({
          type: 'camera', cameraId: id, camera: effectiveCamera, lens: focused.lens, recording,
          playhead: recording ? useDirectorPlaybackStore.getState().playhead : undefined
        })
        if (sent) lastCameraFeedback.current = { camera: effectiveCamera, recording, at: now }
      }
    },
    [beginRecording, endRecording, readLiveScene, send]
  )

  const handleMessageRef = useRef<(raw: unknown) => void>(() => {})
  useEffect(() => {
    handleMessageRef.current = (raw) => {
      const message = parseVirtualCameraMessage(raw)
      if (!message) return
      if (message.type === 'hello' && message.role === 'guest') {
        send({ type: 'theme', theme: themeRef.current })
        lastSeqRef.current = -1
        lastControlAtRef.current = null
        lastCameraFeedback.current = null
        const snapshot = safeSnapshot(sceneRef.current, occupiedCameraIdRef.current)
        if (snapshot) send({ type: 'snapshot', snapshot })
      } else if (message.type === 'pose') applyControl(message.control)
      else if (message.type === 'record') {
        if (message.recording === 'start') beginRecording()
        else endRecording(true)
      }
    }
  }, [applyControl, beginRecording, endRecording, send])

  useEffect(() => {
    if (!projectId) {
      setConnection('error')
      setConnectionError('画布项目尚未就绪，暂时无法建立信令会话')
      return
    }
    if (!video) return
    if (!actorId) {
      setConnection('error')
      setConnectionError('请先登录后连接手机')
      return
    }
    let disposed = false,
      created: DirectorCameraRoom | null = null,
      peer: DirectorRtcSession | null = null
    connectedRef.current = false
    lastSeqRef.current = -1
    lastControlAtRef.current = null
    lastCameraFeedback.current = null
    setConnection('creating')
    setConnectionError('')
    setSession(null)
    setRefreshing(false)
    registeredScene.current = null
    const scope = { projectId, workspaceId: String(runtime!.project!.workspace.workspaceId) }
    const openingScope = useStudioUiStore.getState().scopeKey, openingToken = localStorage.getItem('token')
    const currentOpening = () => !disposed && useStudioUiStore.getState().scopeKey === openingScope
      && sceneRef.current.id === scene.id && localStorage.getItem('token') === openingToken
    const pending = startFlight.current
    const rooms = directorHostRooms(String(actorId), openingToken)
    const release = () => {
      if (created) closeFlight.current = rooms.close(created.sessionId).catch(() => {})
    }
    const pageHide = (event: PageTransitionEvent) => {
      if (event.persisted) return
      disposed = true
      peer?.close()
      release()
    }
    window.addEventListener('pagehide', pageHide)
    const start = async () => {
      await pending.catch(() => {})
      await closeFlight.current
      if (!currentOpening()) return
      await beforePairRef.current?.()
      if (!currentOpening()) return
      const room = await rooms.open(scope, scene.id, occupiedCameraIdRef.current, currentOpening, async () => {
        let revision = 0
        let authoritativeScene: DirectorSceneData | null = null
        try {
          const detail = await directorCameraApi.detail(scope, scene.id)
          revision = detail.revision; authoritativeScene = detail.scene
        } catch (e) {
          if ((e as { status?: number }).status !== 404) throw e
        }
        if (!currentOpening()) return
        const saved = await directorCameraApi.save(
          scope,
          { ...(authoritativeScene ? sceneWithPersistedDirectorTakes(sceneRef.current, authoritativeScene) : sceneRef.current), revision },
          createRandomUuid()
        )
        if (!currentOpening()) return
        sceneRef.current = { ...sceneRef.current, revision: saved.revision }
        commitRef.current(sceneRef.current)
        registeredScene.current = JSON.stringify([openingScope, String(projectId), scope.workspaceId, scene.id, openingToken])
        void refreshRecordings(undefined, true)
      })
      if (!room) return
      created = room
      if (!currentOpening()) {
        release()
        return
      }
      sessionRef.current = room
      setSession(room)
      peer = new DirectorRtcSession({
        role: 'host',
        room,
        video,
        onMessage: (m) => handleMessageRef.current(m),
        onRoomState: (next) => {
          if (!currentOpening() || sessionRef.current?.sessionId !== next.sessionId || next.stateVersion < sessionRef.current.stateVersion) return
          const { pairingCode, pairingUrl, pairingExpiresAtMs } = sessionRef.current
          const roomState = { ...next, pairingCode, pairingUrl, pairingExpiresAtMs }
          if (next.guestOnline || next.state === 'SUSPENDED') {
            roomState.pairingCode = undefined
            roomState.pairingUrl = undefined
            roomState.pairingExpiresAtMs = undefined
          }
          sessionRef.current = roomState
          setSession(roomState)
        },
        onState: (state, text) => {
          if (!currentOpening()) return
          connectedRef.current = state === 'connected'
          setConnection(state)
          if (state === 'connected') {
            send({ type: 'theme', theme: themeRef.current })
            useDirectorPlaybackStore.getState().setPlaying(false)
            useDirectorPlaybackStore.getState().setEditingObjectId(occupiedCameraIdRef.current)
            const snapshot = safeSnapshot(sceneRef.current, occupiedCameraIdRef.current)
            if (snapshot) send({ type: 'snapshot', snapshot })
          } else {
            if (useDirectorPlaybackStore.getState().editingObjectId === occupiedCameraIdRef.current)
              useDirectorPlaybackStore.getState().setEditingObjectId(null)
            interruptRecording()
            if (state === 'error' && text) setConnectionError(text)
          }
        }
      })
      socketRef.current = peer
      await peer.open()
    }
    startFlight.current = start().catch((e) => {
      if (currentOpening()) {
        setConnection('error')
        setConnectionError(directorCameraErrorText(e))
      }
    })
    return () => {
      disposed = true
      registeredScene.current = null
      connectedRef.current = false
      if (useDirectorPlaybackStore.getState().editingObjectId === occupiedCameraIdRef.current)
        useDirectorPlaybackStore.getState().setEditingObjectId(null)
      endRecording(true)
      peer?.close()
      socketRef.current = null
      window.removeEventListener('pagehide', pageHide)
      release()
      sessionRef.current = null
    }
    // Live scene edits do not recreate the pairing room.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [actorId, generation, scene.id, workspaceId, projectId, video])

  useEffect(() => {
    const previous = occupiedCameraIdRef.current
    if (previous === cameraId) return
    if (recordingRef.current || recordingFlight.current) {
      errorRef.current?.('请先停止录制')
      rejectRef.current?.(previous)
      return
    }
    lastSeqRef.current = -1
    const snapshot = safeSnapshot(sceneRef.current, cameraId)
    void socketRef.current
      ?.switchCamera(cameraId)
      .then(() => {
        occupiedCameraIdRef.current = cameraId
        lastSeqRef.current = -1
        lastControlAtRef.current = null
        lastCameraFeedback.current = null
        const playback = useDirectorPlaybackStore.getState()
        if (connectedRef.current && (playback.editingObjectId === previous || playback.editingObjectId === null)) {
          playback.setEditingObjectId(cameraId)
        }
        if (snapshot) send({ type: 'camera:switch', cameraId, snapshot })
      })
      .catch((e) => {
        occupiedCameraIdRef.current = previous
        rejectRef.current?.(previous)
        errorRef.current?.(directorCameraErrorText(e))
      })
  }, [cameraId, send])

  useEffect(
    () => () => {
      if (sampleTimerRef.current !== undefined) window.clearInterval(sampleTimerRef.current)
    },
    []
  )

  const toggleRecording = useCallback(() => {
    if (connection !== 'connected') return
    if (writerRef.current && !recordingRef.current) {
      if (!writerRef.current.view || writerRef.current.view.state === 'PREPARING') beginRecording()
      else endRecording(true)
      return
    }
    const next = recordingRef.current ? 'stop' : 'start'
    if (next === 'start') beginRecording()
    else endRecording(true)
  }, [beginRecording, connection, endRecording])

  const retry = useCallback(() => {
    if (recordingFlight.current) return
    endRecording(true)
    const saving = recordingFlight.current as Promise<void> | null
    if (saving) void saving.finally(() => { if (mountedRef.current) setGeneration((value) => value + 1) })
    else setGeneration((value) => value + 1)
  }, [endRecording])

  useEffect(() => {
    if (connection === 'connected') send({ type: 'theme', theme })
  }, [connection, send, theme])

  const qrUrl = useMemo(
    () =>
      session && typeof window !== 'undefined'
        ? directorCameraPairingUrl(session, window.location.origin, theme)
        : '',
    [session, theme]
  )

  const refreshQr = useCallback(() => {
    if (refreshingRef.current || recordingRef.current || recordingFlight.current) return
    if (!session || connection === 'error') {
      retry()
      return
    }
    refreshingRef.current = true
    setRefreshing(true)
    const rePair = async () => {
      let room = await directorCameraApi.state(session.sessionId)
      if (sessionRef.current?.sessionId !== room.sessionId) return
      if (room.guestOnline) room = await directorCameraApi.action(room, 'DISCONNECT_GUEST')
      const next = await directorCameraApi.action(room, 'PAIRING_REFRESH')
      if (sessionRef.current?.sessionId !== next.sessionId) return
      sessionRef.current = next
      setSession(next)
    }
    void rePair()
      .catch((error) => {
        if (!(error instanceof DirectorCameraError) || !['STALE_STATE', 'GUEST_FULL'].includes(error.code ?? '')) throw error
        return rePair()
      })
      .catch((e) => {
        if (sessionRef.current?.sessionId === session.sessionId)
          errorRef.current?.(directorCameraErrorText(e))
      })
      .finally(() => {
        refreshingRef.current = false
        if (sessionRef.current) setRefreshing(false)
      })
  }, [connection, retry, session])

  const recordingStartBlockedReason = connection !== 'connected' ? '请先连接手机机位'
    : !recordingAuthorized ? '正在等待机位录制授权，请稍候' : ''
  return { connection, connectionError, session, qrUrl, recording, toggleRecording, retry, refreshQr, refreshing, recordingStartBlockedReason,
    recordingStatus, recordingError: recordingError || draftsError, recordingTake, recordingDrafts, recordingCursor, recordingsLoading,
    recordingBusy: recordingStatus === 'starting' || recordingStatus === 'saving',
    refreshRecordings, recoverRecording, discardRecording }
}
