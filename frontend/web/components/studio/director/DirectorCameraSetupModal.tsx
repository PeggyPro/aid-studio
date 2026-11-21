'use client'

import { useEffect, useRef, useState, type RefObject } from 'react'
import { Button, Input, Modal } from 'antd'
import { CameraOutlined, CheckOutlined } from '@ant-design/icons'
import type { DirectorCamera, DirectorSceneData } from '@/types/studio'
import { resolveDirectorAspectRatio } from '@/utils/studio/directorScene'
import { createDirectorLensSpecForCamera, directorLensFov, syncDirectorLensToCameraFov } from '@/utils/studio/directorLens'
import { useDirectorPlaybackStore } from './directorPlaybackStore'
import { DirectorCameraConfiguration, type DirectorCameraConfigurationValue } from './DirectorCameraConfiguration'
import { createDirectorPreviewFrameClock } from '@/utils/studio/directorPreviewFrameClock'
import { DirectorPrevizRenderer } from './directorPrevizRenderer'
import type { DirectorViewportHandle } from './DirectorViewport'

export interface DirectorCameraSetupRequest {
  key: number
  preset: string
  camera: DirectorCamera
}

export interface DirectorCameraSetupResult extends DirectorCameraConfigurationValue {
  name: string
  preset: string
}

export function DirectorCameraSetupModal({
  request,
  scene,
  viewport,
  onCancel,
  onCreate,
}: {
  request: DirectorCameraSetupRequest
  scene: DirectorSceneData
  viewport: RefObject<DirectorViewportHandle | null>
  onCancel: () => void
  onCreate: (result: DirectorCameraSetupResult) => void
}) {
  const [host, setHost] = useState<HTMLDivElement | null>(null)
  const sceneRef = useRef(scene)
  const draftRef = useRef<DirectorCameraConfigurationValue | null>(null)
  const aspect = resolveDirectorAspectRatio(scene.aspectRatio) ?? 16 / 9
  const initialLens = createDirectorLensSpecForCamera(request.camera, aspect)
  const [name, setName] = useState(() => request.preset === '当前视角'
    ? `机位 ${(scene.cameraBookmarks?.length ?? 0) + 1}`
    : `${request.preset} ${(scene.cameraBookmarks?.length ?? 0) + 1}`)
  const [draft, setDraft] = useState<DirectorCameraConfigurationValue>(() => ({
    camera: { ...structuredClone(request.camera), fov: directorLensFov(initialLens, aspect), roll: request.camera.roll ?? 0 },
    lens: initialLens,
  }))
  const [previewReady, setPreviewReady] = useState(false)
  const [previewError, setPreviewError] = useState('')

  useEffect(() => {
    sceneRef.current = scene
  }, [scene])
  useEffect(() => {
    draftRef.current = draft
  }, [draft])

  useEffect(() => {
    if (!host) return
    let runtimeRenderer: DirectorPrevizRenderer
    let lastUiUpdate = 0
    let readyReported = false
    const applyPreviewCamera = (camera: DirectorCamera, force = false) => {
      const current = draftRef.current
      if (!current) return
      const next = { ...current, camera, lens: syncDirectorLensToCameraFov(current.lens, camera, aspect), aimObjectId: undefined }
      draftRef.current = next
      const now = performance.now()
      if (force || now - lastUiUpdate >= 1000 / 30) {
        lastUiUpdate = now
        setDraft(next)
      }
    }
    try {
      runtimeRenderer = new DirectorPrevizRenderer({
        onStart: () => useDirectorPlaybackStore.getState().setPlaying(false),
        onCameraChange: (_cameraId, camera) => applyPreviewCamera(camera),
        onEnd: (_cameraId, camera) => applyPreviewCamera(camera, true),
      })
      runtimeRenderer.resize(960, Math.round(960 / aspect))
      runtimeRenderer.setInteractionEnabled(true)
      host.append(runtimeRenderer.previewCanvas)
    } catch (error) {
      setPreviewError(error instanceof Error ? error.message : '当前设备无法初始化机位预览')
      return
    }
    let animation = 0
    const frameDue = createDirectorPreviewFrameClock()
    let stopped = false
    const tick = (now: number) => {
      const currentDraft = draftRef.current
      const runtime = viewport.current?.getRuntime()
      if (currentDraft && runtime) {
        try {
          runtimeRenderer.syncInteractiveCamera('camera-draft', currentDraft.camera)
          const draw = frameDue(now)
          if (runtimeRenderer.updateInteraction() || draw) {
            runtimeRenderer.drawInteractive(runtime, sceneRef.current, useDirectorPlaybackStore.getState().playhead, false, true)
            if (!readyReported) {
              readyReported = true
              setPreviewReady(true)
            }
          }
        } catch (error) {
          stopped = true
          setPreviewError(error instanceof Error ? error.message : '机位预览渲染失败')
        }
      }
      if (!stopped) animation = requestAnimationFrame(tick)
    }
    animation = requestAnimationFrame(tick)
    return () => {
      cancelAnimationFrame(animation)
      runtimeRenderer.previewCanvas.remove()
      runtimeRenderer.dispose()
    }
  }, [aspect, host, viewport])

  const create = () => {
    const cameraName = name.trim()
    if (!cameraName) return
    onCreate({ ...(draftRef.current ?? draft), name: cameraName, preset: request.preset })
  }

  return (
    <Modal
      open
      centered
      width="min(1080px, calc(100vw - 32px))"
      title="摄像机创建"
      footer={(
        <div className="director-camera-setup__footer">
          <span>创建后可在机位属性中继续调整</span>
          <div className="director-camera-setup__actions">
            <Button aria-label="取消" onClick={onCancel}>取消</Button>
            <Button type="primary" icon={<CheckOutlined aria-hidden="true" />} disabled={!name.trim()} onClick={create}>
              创建机位
            </Button>
          </div>
        </div>
      )}
      className="director-camera-setup-modal"
      styles={{ body: { padding: 0 } }}
      destroyOnHidden
      mask={{ closable: false }}
      onCancel={onCancel}
    >
      <div className="director-camera-setup">
        <section className="director-camera-setup__stage" aria-label="机位预览">
          <div className="director-camera-setup__preview">
            <div ref={setHost} className="director-camera-setup__preview-host" />
            <div className="director-camera-setup__crosshair" aria-hidden="true">
              <i />
              <i />
            </div>
            <div className="director-camera-setup__badge">
              <CameraOutlined />
              <span>{previewError ? '预览不可用' : previewReady ? '实时取景' : '正在连接场景'}</span>
            </div>
            {previewError ? (
              <div className="director-camera-setup__preview-error">{previewError}</div>
            ) : null}
          </div>

          <div className="director-camera-setup__hint">
            <div>
              <strong>固定机位试拍</strong>
              <p>拖动旋转拍摄方向，滚轮调整视野；机位 XYZ 始终保持当前配置。</p>
            </div>
            <em>{draft.camera.fov.toFixed(1)}° FOV</em>
          </div>

          <label className="director-camera-setup__name">
            <span>机位名称</span>
            <Input
              value={name}
              maxLength={60}
              placeholder="输入机位名称"
              onChange={(event) => setName(event.target.value)}
            />
          </label>
        </section>

        <aside className="director-camera-setup__sidebar" aria-label="机位参数">
          <DirectorCameraConfiguration
            value={draft}
            objects={scene.objects}
            aspect={aspect}
            onChange={setDraft}
          />
        </aside>

      </div>
    </Modal>
  )
}
