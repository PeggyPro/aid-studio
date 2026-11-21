'use client'

import { useDirectorPlaybackStore } from './directorPlaybackStore'
import { resolveDirectorRecordCamera } from '@/utils/studio/directorRecordCamera'
import { applyDirectorMannequinPose, findDirectorMannequinModelRoot } from './directorMannequinRuntime'
import { applyDirectorCameraRigTransform } from './directorCameraGeometry'
import type { DirectorSceneData } from '@/types/studio'
import { applyDirectorTimelineAt } from '@/utils/studio/directorTimeline'
import * as THREE from 'three'
import { resolveDirectorActiveCameraId } from './directorCameraState'
import type { IClip } from '@webav/av-cliper'

export type DirectorVideoExportView = 'director' | 'camera'

function pickMimeType(): { mimeType: string; ext: string } {
  if (typeof MediaRecorder === 'undefined') return { mimeType: '', ext: 'webm' }
  const candidates = [
    { mimeType: 'video/webm;codecs=vp8', ext: 'webm' },
    { mimeType: 'video/webm;codecs=vp9', ext: 'webm' },
    { mimeType: 'video/webm', ext: 'webm' },
    { mimeType: 'video/mp4;codecs=avc1', ext: 'mp4' }
  ]
  return (
    candidates.find((item) => {
      try {
        return MediaRecorder.isTypeSupported(item.mimeType)
      } catch {
        return false
      }
    }) ?? { mimeType: '', ext: 'webm' }
  )
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.rel = 'noopener'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

function waitNextFrame(signal?: AbortSignal, count = 2): Promise<void> {
  return new Promise((resolve, reject) => {
    let frame = 0
    const abort = () => { cancelAnimationFrame(frame); signal?.removeEventListener('abort', abort); reject(new Error('录制已取消')) }
    const tick = () => {
      if (--count > 0) frame = requestAnimationFrame(tick)
      else { signal?.removeEventListener('abort', abort); resolve() }
    }
    if (signal?.aborted) { abort(); return }
    signal?.addEventListener('abort', abort, { once: true })
    frame = requestAnimationFrame(tick)
  })
}

export async function exportDirectorVideo(options: {
  canvas: HTMLCanvasElement
  view: DirectorVideoExportView
  duration: number
  download?: boolean
  signal?: AbortSignal
  onProgress?: (time: number) => void
  drawRealtimeFrame?: (time: number) => void
  beginCapture: (view: DirectorVideoExportView) => () => void
  renderFrame: (time: number) => Promise<ImageBitmap>
}): Promise<File> {
  const { canvas, view, duration, beginCapture, signal } = options
  const assertActive = () => { if (signal?.aborted) throw new Error('录制已取消') }
  assertActive()
  if (!canvas.width || !canvas.height) throw new Error('三维视口尺寸为空，无法录制')
  const encodedWidth = canvas.width & ~1
  const encodedHeight = canvas.height & ~1
  // Encode frames at timeline positions so slow rendering cannot extend the file.
  if (encodedWidth >= 2 && encodedHeight >= 2 && typeof VideoEncoder !== 'undefined' && typeof OffscreenCanvas !== 'undefined') {
    const { Combinator, OffscreenSprite } = await import('@webav/av-cliper')
    // Combinator.isSupported also requires an audio encoder, even with audio:false.
    // This export is silent, so check only the video codec we actually use.
    const codec = await VideoEncoder.isConfigSupported({
      codec: 'avc1.42E032', width: encodedWidth, height: encodedHeight,
      bitrate: 5_000_000, framerate: 30
    })
    if (codec.supported) {
      assertActive()
      const durationUs = Math.round(duration * 1e6)
      const frameCount = Math.max(1, Math.ceil(duration * 30))
      // Combinator uses rounded 33,333us steps and includes maxTime. Bound the last
      // frame explicitly so a 120-second export cannot contain a 3,601st frame.
      const lastFrameTimeUs = (frameCount - 1) * Math.round(1e6 / 30)
      const finishCapture = beginCapture(view)
      const playback = useDirectorPlaybackStore.getState()
      playback.setPlaying(false)
      if (!options.drawRealtimeFrame) playback.setPlayhead(0)
      let compositor: InstanceType<typeof Combinator> | undefined
      let sprite: InstanceType<typeof OffscreenSprite> | undefined
      let abortEncoding: (() => void) | undefined
      try {
        const clip: IClip = {
          meta: { width: canvas.width, height: canvas.height, duration: durationUs },
          ready: Promise.resolve({ width: canvas.width, height: canvas.height, duration: durationUs }),
          tick: async (time) => {
            assertActive()
            if (time >= durationUs) return { state: 'done' as const }
            const video = await options.renderFrame(time / 1e6)
            if (signal?.aborted) { video.close(); assertActive() }
            options.onProgress?.(time / 1e6)
            return { state: 'success' as const, video }
          },
          clone: async () => clip,
          destroy: () => {}
        }
        sprite = new OffscreenSprite(clip)
        await sprite.ready
        sprite.time = { offset: 0, duration: durationUs }
        compositor = new Combinator({ width: encodedWidth, height: encodedHeight, fps: 30, audio: false })
        await compositor.addSprite(sprite, { main: true })
        const encoding = new Response(compositor.output({ maxTime: lastFrameTimeUs })).blob()
        const blob = await (signal ? Promise.race([encoding, new Promise<never>((_resolve, reject) => {
          abortEncoding = () => reject(new Error('录制已取消'))
          signal.addEventListener('abort', abortEncoding, { once: true })
          if (signal.aborted) abortEncoding()
        })]) : encoding)
        assertActive()
        options.onProgress?.(duration)
        if (!blob.size) throw new Error('时间轴视频编码结果为空')
        const filename = `director-desk-${view === 'director' ? '上帝视角' : '机位视角'}-${Date.now()}.mp4`
        if (options.download) downloadBlob(blob, filename)
        return new File([blob], filename, { type: 'video/mp4' })
      } finally {
        if (abortEncoding) signal?.removeEventListener('abort', abortEncoding)
        compositor?.destroy()
        sprite?.destroy()
        playback.setPlaying(false)
        if (!options.drawRealtimeFrame) playback.setPlayhead(playback.playhead)
        finishCapture()
      }
    }
  }
  if (typeof MediaRecorder === 'undefined') {
    throw new Error('当前浏览器不支持视频录制（MediaRecorder），请用 Chrome/Edge/Firefox')
  }
  if (!canvas.width || !canvas.height) throw new Error('三维视口尺寸为空，无法录制')

  const { mimeType, ext } = pickMimeType()
  const fps = 30
  const finishCapture = beginCapture(view)
  let stream: MediaStream | undefined
  try {
    stream = canvas.captureStream(fps)
  } catch (error) {
    finishCapture()
    throw error
  }
  let recorder: MediaRecorder
  try {
    recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined)
  } catch (error) {
    stream.getTracks().forEach((track) => track.stop())
    finishCapture()
    throw error
  }
  const chunks: BlobPart[] = []
  recorder.ondataavailable = (event) => {
    if (event.data && event.data.size > 0) chunks.push(event.data)
  }

  const playback = useDirectorPlaybackStore.getState()
  playback.setPlaying(false)
  if (!options.drawRealtimeFrame) playback.setPlayhead(0)

  const stopped = new Promise<void>((resolve, reject) => {
    recorder.onstop = () => resolve()
    recorder.onerror = () => reject(new Error('浏览器视频录制失败'))
  })

  try {
    // Recording owns its clock. UI playback can be reset by scene/collaboration
    // updates while an export is running, which used to stop the recorder early.
    options.drawRealtimeFrame?.(0)
    await waitNextFrame(signal)
    assertActive()
    recorder.start()
    await waitNextFrame(signal)
    const startedAt = performance.now()
    while (true) {
      await waitNextFrame(signal, 1)
      assertActive()
      const elapsed = Math.min(duration, (performance.now() - startedAt) / 1000)
      if (options.drawRealtimeFrame) options.drawRealtimeFrame(elapsed)
      else playback.setPlayhead(elapsed)
      options.onProgress?.(elapsed)
      if (elapsed >= duration) break
    }
    await waitNextFrame(signal)
    assertActive()
    if (recorder.state === 'recording') recorder.requestData()
    recorder.stop()
    await stopped
  }
  finally {
    if (recorder.state !== 'inactive') recorder.stop()
    stream.getTracks().forEach((track) => track.stop())
    playback.setPlaying(false)
    if (!options.drawRealtimeFrame) playback.setPlayhead(playback.playhead)
    finishCapture()
  }

  const viewLabel = view === 'director' ? '上帝视角' : '机位视角'
  const blob = new Blob(chunks, { type: mimeType || 'video/webm' })
  if (!blob.size) throw new Error('录制结果为空，请检查浏览器画布录制权限')
  const filename = `director-desk-${viewLabel}-${Date.now()}.${ext}`
  if (options.download) downloadBlob(blob, filename)
  return new File([blob], filename, { type: blob.type })
}

/** Shared evaluation for preview, monitor and recording. */
export function applyDirectorPlaybackTransforms(options: {
  sceneData: DirectorSceneData; playhead: number; editingObjectId: string | null;
  poseEditingObjectId?: string | null;
  objectRoot: THREE.Group; cameraRoot: THREE.Group; shotCamera: THREE.PerspectiveCamera
}) {
  const { sceneData, playhead, editingObjectId, objectRoot, cameraRoot, shotCamera } = options
  const evaluated = applyDirectorTimelineAt(sceneData, playhead)
  for (const child of objectRoot.children) {
    const object = evaluated.objects.find(o => o.id === child.userData.objectId)
    if (!object || object.id === editingObjectId) continue
    child.position.fromArray(object.transform.position)
    child.rotation.set(...object.transform.rotation)
    child.scale.fromArray(object.transform.scale)
    const model = findDirectorMannequinModelRoot(child)
    if (model && object.kind === 'actor') applyDirectorMannequinPose(model,
      object.id === options.poseEditingObjectId ? sceneData.objects.find(item => item.id === object.id)?.pose ?? object.pose : object.pose,
      model.userData.directorRestPose)
  }
  for (const child of cameraRoot.children) {
    const id = child.userData.cameraId
    if (id === editingObjectId) continue
    const camera = id === 'main-camera' ? evaluated.camera : evaluated.cameraBookmarks?.find(b => b.id === id)?.camera
    if (!camera) continue
    applyDirectorCameraRigTransform(child, camera)
  }
  if (resolveDirectorActiveCameraId(evaluated) !== editingObjectId) {
    const camera = resolveDirectorRecordCamera(sceneData, playhead)
    shotCamera.position.fromArray(camera.position)
    shotCamera.lookAt(...camera.target)
    shotCamera.rotateZ((camera.roll ?? 0) * Math.PI / 180)
    shotCamera.fov = camera.fov
    shotCamera.updateProjectionMatrix()
  }
  const modelingFrame = objectRoot.userData.threePrevizFrame as ((time: number) => void) | undefined
  modelingFrame?.(playhead)
}
