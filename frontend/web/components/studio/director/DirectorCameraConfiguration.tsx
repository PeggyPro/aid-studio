'use client'

import { Button, InputNumber, Select, Slider } from 'antd'
import { RotateLeftOutlined } from '@ant-design/icons'
import type {
  DirectorCamera,
  DirectorCameraBodyId,
  DirectorLensSeriesId,
  DirectorLensSpec,
  DirectorObject,
  DirectorSensorFormat,
} from '@/types/studio'
import {
  changeDirectorCameraBody,
  changeDirectorLensSeries,
  changeDirectorSensorFormat,
  DIRECTOR_CAMERA_BODIES,
  DIRECTOR_LENS_SERIES,
  DIRECTOR_SENSOR_FORMATS,
  findDirectorCameraBody,
  findDirectorLensSeries,
  normalizeDirectorLensSpec,
} from '@/utils/studio/directorCameraCatalog'
import {
  resolveDirectorAimTarget,
  resolveDirectorCameraOrientation,
  updateDirectorCameraOrientation,
} from '@/utils/studio/directorCameraPose'
import { directorLensFov } from '@/utils/studio/directorLens'
import { useDirectorCameraPreviewStore } from './directorCameraPreviewStore'

export interface DirectorCameraConfigurationValue {
  camera: DirectorCamera
  lens: DirectorLensSpec
  aimObjectId?: string
}

const CAMERA_MOTION_HINT = '监视器拖动只旋转拍摄方向，机位 XYZ 保持固定；滚轮调整视野并同步焦距。'

export function DirectorCameraConfiguration({
  value,
  objects,
  aspect,
  disabled = false,
  showTitle = true,
  motionPreview,
  onChange,
}: {
  value: DirectorCameraConfigurationValue
  objects: DirectorObject[]
  aspect: number
  disabled?: boolean
  /** 外层属性面板已有主标题时不再重复显示。 */
  showTitle?: boolean
  motionPreview?: { sceneId: string; cameraId: string }
  onChange: (value: DirectorCameraConfigurationValue) => void
}) {
  const lens = normalizeDirectorLensSpec(value.lens)
  const body = findDirectorCameraBody(lens.cameraBody)
  const series = findDirectorLensSeries(lens.lensSeries)
  const orientation = resolveDirectorCameraOrientation(value.camera)
  const sensorOptions = DIRECTOR_SENSOR_FORMATS
    .filter((item) => body.sensorFormats.includes(item.id))
    .map((item) => ({ value: item.id, label: `${item.label} · ${item.sensorWidthMm}mm` }))
  const focalLengths = series.focalLengths.includes(lens.focalLengthMm)
    ? series.focalLengths
    : [lens.focalLengthMm, ...series.focalLengths]

  const commitLens = (nextLens: DirectorLensSpec) => {
    const normalized = normalizeDirectorLensSpec(nextLens)
    onChange({
      ...value,
      lens: normalized,
      camera: { ...value.camera, fov: directorLensFov(normalized, aspect) },
    })
  }
  const patchPosition = (index: number, next: number | null) => {
    if (next === null) return
    const position = [...value.camera.position] as DirectorCamera['position']
    position[index] = next
    onChange({ ...value, aimObjectId: undefined, camera: { ...value.camera, position } })
  }
  const patchOrientation = (key: 'yaw' | 'pitch' | 'roll', next: number | null) => {
    if (next === null) return
    onChange({
      ...value,
      aimObjectId: undefined,
      camera: updateDirectorCameraOrientation(value.camera, { [key]: next }),
    })
  }
  const selectAim = (id?: string) => {
    const object = objects.find((item) => item.id === id)
    onChange({
      ...value,
      aimObjectId: object?.id,
      camera: object ? { ...value.camera, target: resolveDirectorAimTarget(object) } : value.camera,
    })
  }

  return (
    <div className={`director-camera-config${disabled ? ' is-locked' : ''}`}>
      <section className="director-control-section director-control-section--flat" aria-label="机位属性">
        <div className="director-section-title">
          <div className="director-camera-config__heading">
            {showTitle ? <strong>机位属性</strong> : null}
            <span>拍摄系统与光学参数</span>
          </div>
          <em className="director-camera-config__fov">FOV {value.camera.fov.toFixed(1)}°</em>
        </div>

        <div className="director-camera-config__grid" role="group" aria-label="光学参数">
          <label className="director-camera-config__cell">
            <span>摄像机</span>
            <Select
              aria-label="摄像机"
              size="small"
              disabled={disabled}
              value={lens.cameraBody ?? 'digital-cinema'}
              options={DIRECTOR_CAMERA_BODIES.map((item) => ({ value: item.id, label: item.label }))}
              onChange={(id) => commitLens(changeDirectorCameraBody(lens, id as DirectorCameraBodyId))}
            />
          </label>
          <label className="director-camera-config__cell">
            <span>镜头系列</span>
            <Select
              aria-label="镜头系列"
              size="small"
              disabled={disabled}
              value={lens.lensSeries ?? 'cine-prime'}
              options={DIRECTOR_LENS_SERIES.map((item) => ({ value: item.id, label: item.label }))}
              onChange={(id) => commitLens(changeDirectorLensSeries(lens, id as DirectorLensSeriesId))}
            />
          </label>
          <label className="director-camera-config__cell">
            <span>焦距</span>
            <Select
              aria-label="焦距"
              size="small"
              disabled={disabled}
              value={lens.focalLengthMm}
              options={focalLengths.map((item, index) => ({
                value: item,
                label: `${item}mm${index === 0 && !series.focalLengths.includes(item) ? ' · 当前视角' : ''}`,
              }))}
              onChange={(focalLengthMm) => commitLens({ ...lens, focalLengthMm: Number(focalLengthMm) })}
            />
          </label>
          <label className="director-camera-config__cell">
            <span>光圈</span>
            <Select
              aria-label="光圈"
              size="small"
              disabled={disabled}
              value={lens.apertureF ?? series.defaultAperture}
              options={series.apertures.map((item) => ({ value: item, label: `f/${item}` }))}
              onChange={(apertureF) => commitLens({ ...lens, apertureF: Number(apertureF) })}
            />
          </label>
        </div>

        <label className="director-field">
          <span>画幅设置</span>
          <Select
            aria-label="画幅设置"
            size="small"
            disabled={disabled}
            value={lens.bodyName}
            options={sensorOptions}
            onChange={(id) => commitLens(changeDirectorSensorFormat(lens, id as DirectorSensorFormat))}
          />
        </label>

        <dl className="director-camera-config__specs">
          <div>
            <dt>最高画质</dt>
            <dd>{body.maxResolution}</dd>
          </div>
          <div>
            <dt>帧率</dt>
            <dd>{body.frameRate}</dd>
          </div>
          <div>
            <dt>原生感光度</dt>
            <dd>{body.nativeIso}</dd>
          </div>
          <div>
            <dt>镜头卡口</dt>
            <dd>{body.mount}</dd>
          </div>
          <p>{body.summary} · {series.summary}</p>
        </dl>
      </section>

      <section className="director-control-section director-control-section--flat" aria-label="位置与朝向">
        <div className="director-section-title">
          <div className="director-camera-config__heading">
            <strong>位置与朝向</strong>
            <span>机身坐标与拍摄方向独立控制</span>
          </div>
          <span className="director-camera-config__unit">单位：m / °</span>
        </div>

        <div className="director-vector-control" aria-label="机位坐标">
          <span>机位坐标</span>
          {(['X', 'Y', 'Z'] as const).map((axis, index) => (
            <label key={axis}>
              {axis}
              <InputNumber
                size="small"
                step={0.1}
                precision={2}
                aria-label={`位置 ${axis}`}
                value={value.camera.position[index]}
                disabled={disabled}
                onChange={(next) => patchPosition(index, next)}
              />
            </label>
          ))}
        </div>

        <OrientationControl label="水平" value={orientation.yaw} min={-180} max={180} disabled={disabled} onChange={(next) => patchOrientation('yaw', next)} />
        <OrientationControl label="俯仰" value={orientation.pitch} min={-89} max={89} disabled={disabled} onChange={(next) => patchOrientation('pitch', next)} />
        <OrientationControl label="横滚" value={orientation.roll} min={-180} max={180} disabled={disabled} onChange={(next) => patchOrientation('roll', next)} />

        <label className="director-field">
          <span>看向场景对象</span>
          <Select
            aria-label="看向对象"
            size="small"
            allowClear
            placeholder="选择对象"
            disabled={disabled}
            value={value.aimObjectId}
            options={objects.map((item) => ({ value: item.id, label: item.name }))}
            onChange={selectAim}
          />
        </label>

        <p className="director-field__hint">
          {motionPreview ? <CameraMotionHint {...motionPreview} /> : CAMERA_MOTION_HINT}
        </p>
      </section>
    </div>
  )
}

function CameraMotionHint({ sceneId, cameraId }: { sceneId: string; cameraId: string }) {
  const hint = useDirectorCameraPreviewStore((state) => {
    if (state.sceneId !== sceneId || state.cameraId !== cameraId || !state.camera) return CAMERA_MOTION_HINT
    const { yaw, pitch } = resolveDirectorCameraOrientation(state.camera)
    return `实时取景 · 水平 ${yaw.toFixed(0)}° · 俯仰 ${pitch.toFixed(0)}° · FOV ${state.camera.fov.toFixed(1)}°`
  })
  return hint
}

function OrientationControl({
  label,
  value,
  min,
  max,
  disabled,
  onChange,
}: {
  label: string
  value: number
  min: number
  max: number
  disabled: boolean
  onChange: (value: number | null) => void
}) {
  return (
    <div className="director-camera-config__orient">
      <span>{label}</span>
      <Button
        type="text"
        size="small"
        aria-label={`重置${label}`}
        icon={<RotateLeftOutlined />}
        disabled={disabled || value === 0}
        onClick={() => onChange(0)}
      />
      <Slider
        className="director-camera-config__slider"
        min={min}
        max={max}
        step={0.1}
        value={value}
        disabled={disabled}
        onChange={onChange}
      />
      <InputNumber
        size="small"
        min={min}
        max={max}
        step={0.1}
        precision={1}
        value={value}
        disabled={disabled}
        onChange={onChange}
      />
    </div>
  )
}
