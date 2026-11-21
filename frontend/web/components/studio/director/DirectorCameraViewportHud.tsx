'use client'

import { useState } from 'react'
import { DownOutlined, UpOutlined } from '@ant-design/icons'
import type { DirectorSceneData } from '@/types/studio'
import {
  findDirectorCameraBody,
  findDirectorLensSeries,
  findDirectorSensorFormat,
} from '@/utils/studio/directorCameraCatalog'
import { resolveDirectorCameraLens } from './directorCameraState'
import { useDirectorCameraPreviewStore } from './directorCameraPreviewStore'

export function DirectorCameraViewportHud({
  scene,
  cameraId,
}: {
  scene: DirectorSceneData
  cameraId: string
}) {
  const [collapsed, setCollapsed] = useState(true)
  const previewCamera = useDirectorCameraPreviewStore((state) => (
    state.sceneId === scene.id && state.cameraId === cameraId ? state.camera : null
  ))
  const previewLens = useDirectorCameraPreviewStore((state) => (
    state.sceneId === scene.id && state.cameraId === cameraId ? state.lens : null
  ))
  const lens = previewLens ?? resolveDirectorCameraLens(scene, cameraId)
  const camera = previewCamera ?? (cameraId === 'main-camera'
    ? scene.camera
    : scene.cameraBookmarks?.find((item) => item.id === cameraId)?.camera)
  if (!camera) return null

  const body = findDirectorCameraBody(lens.cameraBody)
  const series = findDirectorLensSeries(lens.lensSeries)
  const sensor = findDirectorSensorFormat(lens.bodyName)
  const name = cameraId === 'main-camera'
    ? '主机位'
    : scene.cameraBookmarks?.find((item) => item.id === cameraId)?.name ?? '机位'
  const aperture = lens.apertureF ?? series.defaultAperture

  return (
    <aside
      className={`director-camera-hud${collapsed ? ' is-collapsed' : ''}`}
      aria-label={`${name}机位参数`}
    >
      <header className="director-camera-hud__head">
        <div className="director-camera-hud__title">
          <strong>已选机位</strong>
          {!collapsed ? <span>{name}</span> : null}
        </div>
        {!collapsed ? (
          <em className="director-camera-hud__fov">FOV {camera.fov.toFixed(1)}°</em>
        ) : null}
        <button
          type="button"
          className="director-monitor__toggle"
          aria-label={collapsed ? '展开机位参数' : '折叠机位参数'}
          aria-expanded={!collapsed}
          onClick={() => setCollapsed((value) => !value)}
        >
          {collapsed ? <DownOutlined /> : <UpOutlined />}
        </button>
      </header>

      <div className="director-camera-hud__body" hidden={collapsed}>
        <dl className="director-camera-hud__specs">
          <div>
            <dt>机身</dt>
            <dd>{body.label}</dd>
          </div>
          <div>
            <dt>镜头</dt>
            <dd>{series.label}</dd>
          </div>
          <div>
            <dt>焦距</dt>
            <dd>{lens.focalLengthMm.toFixed(0)}mm</dd>
          </div>
          <div>
            <dt>光圈</dt>
            <dd>f/{aperture}</dd>
          </div>
        </dl>

        <p className="director-camera-hud__hint">
          {sensor.label} · 拖动监视器时机身保持原位并实时旋转
        </p>
      </div>
    </aside>
  )
}
