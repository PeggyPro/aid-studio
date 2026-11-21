'use client'
import { useEffect, useRef, useState, type RefObject } from 'react'
import { App, Button, Checkbox, Select } from 'antd'
import { DirectorRecordingDialogs } from './DirectorRecordingDialogs'
import { DownOutlined, UpOutlined } from '@ant-design/icons'
import type { DirectorCamera, DirectorSceneData } from '@/types/studio'
import { beginDirectorPrevizVideo } from '@/utils/studio/directorCanvasBridge'
import { applyDirectorTimelineAt } from '@/utils/studio/directorTimeline'
import { directorModelingCameraResolver } from '@/utils/studio/directorModelingCamera'
import { generatedCameraChoices } from '@/utils/previz/generatedCameras'
import { useStudioUiStore } from '@/stores/studioUi'
import { createDirectorPreviewFrameClock } from '@/utils/studio/directorPreviewFrameClock'
import { DirectorPrevizRenderer } from './directorPrevizRenderer'
import { exportDirectorVideo } from './directorVideoExport'
import type { DirectorViewportHandle } from './DirectorViewport'
import { useDirectorPlaybackStore } from './directorPlaybackStore'
import { isDirectorCameraLocked, listDirectorAuthoredCameras, resolveDirectorActiveCamera, resolveDirectorCamera, resolveDirectorMonitorCameraId } from './directorCameraState'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { directorRecordingDimensions } from '@/utils/studio/directorRecordingResolution'

export function DirectorMonitorPip({
  viewport,
  scene,
  getLiveScene,
  nodeId,
  ready,
  selectedCameraId,
  motionDuration,
  recordingOpen,
  captureBusy,
  onRecordingOpenChange,
  modelingView,
  onRecording,
  onCameraSelect,
  onModelingCameraSelect,
  onCameraPreview,
  onCameraCommit,
}: {
  viewport: RefObject<DirectorViewportHandle | null>
  scene: DirectorSceneData
  getLiveScene: () => DirectorSceneData
  nodeId: string
  ready: boolean
  selectedCameraId: string | null
  motionDuration: number
  recordingOpen: boolean
  captureBusy: boolean
  onRecordingOpenChange: (open: boolean) => void
  modelingView?: string | null
  onRecording: (value: boolean) => void
  onCameraSelect: (cameraId: string | null) => void
  onModelingCameraSelect: (cameraId: string | null) => void
  onCameraPreview: (cameraId: string, camera: DirectorCamera) => void
  onCameraCommit: (cameraId: string, camera: DirectorCamera) => void
}) {
  const { message } = App.useApp()
  const canvasRuntime = useCanvasProjectRuntime()
  const host = useRef<HTMLDivElement>(null)
  const renderer = useRef<DirectorPrevizRenderer | null>(null)
  const [rendererAvailable, setRendererAvailable] = useState(false)
  const onCameraPreviewRef = useRef(onCameraPreview)
  const onCameraCommitRef = useRef(onCameraCommit)
  const abort = useRef<AbortController | null>(null)
  const uploadLock = useRef(false)
  const [collapsed, setCollapsed] = useState(false),
    [labels, setLabels] = useState(false),
    [frame, setFrame] = useState(true)
  const [mode, setMode] = useState<'active_camera' | 'director'>('active_camera')
  const [resolution, setResolution] = useState(''),
    [burn, setBurn] = useState(false),
    [recording, setRecording] = useState(false),
    [uploading, setUploading] = useState(false),
    [progress, setProgress] = useState(0)
  const [pending, setPending] = useState<{
    blob: Blob
    duration: number
    width: number
    height: number
    mode: 'active_camera' | 'director'
    burn: boolean
    scope: string
    targetNodeId: string
  }>()
  useEffect(() => {
    onCameraPreviewRef.current = onCameraPreview
    onCameraCommitRef.current = onCameraCommit
  }, [onCameraCommit, onCameraPreview])
  useEffect(() => {
    setRendererAvailable(false)
    if (!ready) return
    let r: DirectorPrevizRenderer
    try {
      r = new DirectorPrevizRenderer({
        onStart: (cameraId) => {
          useDirectorPlaybackStore.getState().setPlaying(false)
          useDirectorPlaybackStore.getState().setEditingObjectId(cameraId)
        },
        onEnd: (cameraId, camera) => {
          useDirectorPlaybackStore.getState().setEditingObjectId(null)
          onCameraCommitRef.current(cameraId, camera)
        },
        onCameraChange: (cameraId, camera) => onCameraPreviewRef.current(cameraId, camera),
      })
      r.setInteractionEnabled(false)
      r.resize(640, 360)
      renderer.current = r
      r.nameplateCanvas.className = 'director-monitor__overlay'
      r.canvas.className = 'director-monitor__overlay'
      r.nameplateCanvas.hidden = true
      r.canvas.hidden = true
      host.current?.append(r.previewCanvas, r.nameplateCanvas, r.canvas)
      setRendererAvailable(true)
    } catch {
      message.warning('监视器初始化失败')
      return
    }
    return () => {
      abort.current?.abort()
      r.previewCanvas.remove()
      r.nameplateCanvas.remove()
      r.canvas.remove()
      r.dispose()
      renderer.current = null
    }
  }, [ready, message])
  useEffect(() => {
    if (recording || collapsed) renderer.current?.setInteractionEnabled(false)
  }, [collapsed, recording])
  useEffect(() => {
    const current = renderer.current
    if (!current) return
    current.nameplateCanvas.hidden = !labels || recording
    current.canvas.hidden = !recording
  }, [labels, ready, recording])
  useEffect(() => {
    if (!ready || recording || collapsed) return
    const frameDue = createDirectorPreviewFrameClock()
    let animation = 0
    let evaluated: { scene: DirectorSceneData; time: number; value: DirectorSceneData } | undefined
    let cameraScene: DirectorSceneData | undefined
    let modelingCameraAt: (time: number) => string | undefined = () => undefined
    const tick = (now: number) => {
      const runtime = viewport.current?.getRuntime()
      const currentRenderer = renderer.current
      if (runtime && currentRenderer && !document.hidden) {
        const currentScene = getLiveScene()
        const playback = useDirectorPlaybackStore.getState()
        const playhead = playback.playhead
        if (cameraScene !== currentScene) { cameraScene = currentScene; modelingCameraAt = directorModelingCameraResolver(currentScene, modelingView) }
        const nativeCameraId = modelingCameraAt(playhead)
        const cameraId = resolveDirectorMonitorCameraId(currentScene, selectedCameraId)
        const canEdit = Boolean(selectedCameraId) && !isDirectorCameraLocked(currentScene, cameraId)
        const draw = frameDue(now)
        if (nativeCameraId) {
          currentRenderer.setInteractionEnabled(false)
          if (draw) currentRenderer.draw(runtime, currentScene, playhead, 'active_camera', labels, nativeCameraId)
        } else {
          if (draw) {
            let camera = resolveDirectorCamera(currentScene, cameraId) ?? resolveDirectorActiveCamera(currentScene)
            if (playback.editingObjectId !== cameraId) {
              if (evaluated?.scene !== currentScene || evaluated.time !== playhead)
                evaluated = { scene: currentScene, time: playhead, value: applyDirectorTimelineAt(currentScene, playhead) }
              camera = resolveDirectorCamera(evaluated.value, cameraId) ?? camera
            }
            currentRenderer.syncInteractiveCamera(cameraId, camera, playback.isPlaying)
            currentRenderer.setInteractionEnabled(canEdit)
          }
          const interactionChanged = currentRenderer.updateInteraction()
          if (interactionChanged || draw) currentRenderer.drawInteractive(runtime, currentScene, playhead, labels)
        }
      }
      animation = requestAnimationFrame(tick)
    }
    animation = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(animation)
  }, [ready, recording, collapsed, viewport, getLiveScene, labels, selectedCameraId, modelingView])
  const selectedCameraLocked = selectedCameraId ? isDirectorCameraLocked(scene, selectedCameraId) : false
  const authoredCameraOptions = listDirectorAuthoredCameras(scene).map(({ id, name }) => ({ value: id, label: name }))
  const modelCameras = scene.modeling ? generatedCameraChoices(scene.modeling.project.scene) : []
  const cameraOptions = [...authoredCameraOptions, ...modelCameras.map(camera => ({ value: camera.id, label: camera.name })), ...(modelCameras.length ? [{value:'@sequence',label:'按拍摄镜头预览'}] : [])]
  const selectedModelView = modelingView ?? (!authoredCameraOptions.length && modelCameras.length
    ? directorModelingCameraResolver(scene)(useDirectorPlaybackStore.getState().playhead) : undefined)
  const upload = async (result: NonNullable<typeof pending>) => {
    if (uploadLock.current) return
    uploadLock.current = true
    setUploading(true)
    try {
      const file = new File([result.blob], result.blob.type.includes('mp4') ? 'previz.mp4' : 'previz.webm', { type: result.blob.type })
      if (!canvasRuntime) throw new Error('画布资源服务尚未就绪，请重新打开3D预演')
      if (useStudioUiStore.getState().scopeKey !== result.scope) throw new Error('画布已切换，未上传本次录制')
      if (!useStudioUiStore.getState().nodes.some(node => node.id === result.targetNodeId)) throw new Error('录制结果节点已删除')
      const resource = await canvasRuntime.uploadResource(file, 'VIDEO')
      const asset = { mediaId: String(resource.resourceId), url: resource.resourceUrl }
      if (useStudioUiStore.getState().scopeKey !== result.scope) return
      if (!asset.mediaId || !asset.url) throw new Error('录制资源登记失败，请重试上传')
      const store = useStudioUiStore.getState()
      if (!store.nodes.some(node => node.id === result.targetNodeId)) return
      store.updateNodeData(result.targetNodeId, {
        title: `白模预演 · ${result.duration}s`, status: 'success', canvasTransient: false,
        mediaUrl: asset.url, mediaId: asset.mediaId, canvasResourceIds: [asset.mediaId], canvasPrimaryResourceId: asset.mediaId,
        previzRecordMode: result.mode, previzFps: 30, previzWidth: result.width, previzHeight: result.height,
        previzBurnedNameplates: result.burn, errorMessage: undefined
      })
      setPending(undefined)
      message.success('白模预演已添加到画布')
    } catch (error) {
      if (useStudioUiStore.getState().scopeKey === result.scope) useStudioUiStore.getState().updateNodeData(result.targetNodeId, {
        status: 'failed', errorMessage: (error as Error).message
      })
      message.error((error as Error).message)
    } finally {
      uploadLock.current = false
      setUploading(false)
    }
  }
  const start = async () => {
    if (captureBusy || abort.current || uploadLock.current || pending || !ready || !canvasRuntime?.canEdit) return
    const runtime = viewport.current?.getRuntime(),
      r = renderer.current
    if (!runtime || !r) return
    const duration = Math.min(120, motionDuration)
    if (duration <= 0) {
      message.warning('请先设置时间轴时长')
      return
    }
    const snapshot = structuredClone(getLiveScene())
    const scope = useStudioUiStore.getState().scopeKey
    let targetNodeId: string
    let modelingCameraAt: (time: number) => string | undefined
    try {
      modelingCameraAt = directorModelingCameraResolver(snapshot, modelingView)
      targetNodeId = beginDirectorPrevizVideo(nodeId, scene.id)
    } catch (error) {
      message.error(error instanceof Error ? error.message : '无法开始录制')
      return
    }
    const controller = new AbortController()
    abort.current = controller
    r.setInteractionEnabled(false)
    onRecordingOpenChange(false)
    setProgress(0)
    setRecording(true)
    onRecording(true)
    useDirectorPlaybackStore.getState().setPlaying(false)
    const hidden = () => {
      if (document.hidden) controller.abort()
    }
    document.addEventListener('visibilitychange', hidden)
    try {
      let { width, height } = directorRecordingDimensions(resolution)
      try {
        r.resize(width, height)
        r.draw(runtime, snapshot, 0, mode, burn, modelingCameraAt?.(0))
      } catch (error) {
        if (height !== 1080) throw error
        height = 720
        width = 1280
        r.resize(width, height)
        message.warning('当前设备无法录制1080p，已降为720p')
      }
      const drawFrame = (time: number) => r.draw(runtime, snapshot, time, mode, burn, modelingCameraAt?.(time))
      const blob = await exportDirectorVideo({
        canvas: r.canvas, view: mode === 'director' ? 'director' : 'camera', duration,
        signal: controller.signal, onProgress: setProgress,
        beginCapture: () => () => {},
        renderFrame: async time => { drawFrame(time); return createImageBitmap(r.canvas) },
        drawRealtimeFrame: drawFrame,
      })
      if (!blob.size || blob.size > 500 * 1024 * 1024) throw new Error('录制文件为空或超过 500MB')
      const result = { blob, width, height, duration, mode, burn, scope, targetNodeId }
      setPending(result)
      await upload(result)
    } catch (error) {
      if (useStudioUiStore.getState().scopeKey === scope) useStudioUiStore.getState().updateNodeData(targetNodeId, {
        status: 'failed', errorMessage: controller.signal.aborted ? '录制已取消' : (error as Error).message
      })
      if (!controller.signal.aborted) message.error((error as Error).message)
    } finally {
      document.removeEventListener('visibilitychange', hidden)
      r.resize(640, 360)
      setRecording(false)
      onRecording(false)
      abort.current = null
    }
  }
  return (
    <div className={`director-monitor${collapsed ? ' is-collapsed' : ''}`}>
      <div className="director-monitor__head">
        <strong className={recording ? 'is-recording' : undefined}>
          {recording ? 'REC 正在录制白模预演…' : '机位监视器'}
        </strong>
        <div className="director-monitor__head-actions">
          <button
            type="button"
            className="director-monitor__toggle"
            aria-label={collapsed ? '展开机位监视器' : '折叠机位监视器'}
            aria-expanded={!collapsed}
            onClick={() => setCollapsed((v) => !v)}
          >
            {collapsed ? <DownOutlined /> : <UpOutlined />}
          </button>
        </div>
      </div>
      <div className="director-monitor__body" hidden={collapsed}>
        {cameraOptions.length ? (
          <Select
            className="director-monitor__select"
            aria-label="监视器机位"
            size="small"
            value={selectedModelView ?? selectedCameraId ?? undefined}
            placeholder="选择机位后开启环视"
            allowClear
            options={cameraOptions}
            onChange={(cameraId) => { if (cameraId === '@sequence' || modelCameras.some(camera => camera.id === cameraId)) onModelingCameraSelect(cameraId); else { onModelingCameraSelect(null); onCameraSelect(cameraId ?? null) } }}
          />
        ) : null}
        <div className={`director-monitor__preview${frame ? ' is-framed' : ''}${!selectedModelView && (!selectedCameraId || selectedCameraLocked) ? ' is-disabled' : ''}`}>
          <div ref={host} className="director-monitor__surface" />
          {!selectedModelView && (!selectedCameraId || selectedCameraLocked) ? (
            <div className="director-monitor__gate">
              {selectedCameraLocked ? '当前机位已锁定，解锁后可环视' : '请先选择机位，再在监视器内环视'}
            </div>
          ) : null}
        </div>
        <p className="director-monitor__hint">
          {selectedModelView ? '生成机位随模型动画与分镜播放，保留原有运镜。' : selectedCameraId && !selectedCameraLocked
            ? '拖动旋转拍摄角度（机位位置固定）· 滚轮调整视野'
            : '监视器仅在明确选择机位后开放操作'}
        </p>
        <div className="director-monitor__options">
          <Checkbox checked={labels} onChange={(e) => setLabels(e.target.checked)}>
            预览名牌
          </Checkbox>
          <Checkbox checked={frame} onChange={(e) => setFrame(e.target.checked)}>
            画幅框
          </Checkbox>
        </div>
      </div>
      {pending ? (
        <div className="director-monitor__actions">
          <Button size="small" loading={uploading} onClick={() => void upload(pending)}>{uploading ? '正在上传' : '重试上传'}</Button>
          <Button size="small" disabled={uploading} onClick={() => setPending(undefined)}>放弃重试</Button>
        </div>
      ) : null}
      <DirectorRecordingDialogs open={recordingOpen} recording={recording} duration={motionDuration}
        progress={progress} mode={mode} resolution={resolution} burn={burn}
        disabledReason={!canvasRuntime?.canEdit ? '当前画布没有上传视频的权限' : !ready || !rendererAvailable ? '请等待三维视口和模型加载完成' : uploading ? '正在上传上一次录制，请稍候' : pending ? '请在机位监视器重试上传或放弃上一次录制' : ''}
        onClose={() => onRecordingOpenChange(false)} onModeChange={setMode} onResolutionChange={setResolution} onBurnChange={setBurn}
        onStart={() => void start()} onAbort={() => abort.current?.abort()} />
    </div>
  )
}
