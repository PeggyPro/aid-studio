'use client'

import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { DirectorPrevizInspector } from './DirectorPrevizInspector'
import { DirectorActorActionsPanel } from './DirectorActorActionsPanel'
import { useDirectorPlaybackStore } from './directorPlaybackStore'
import { normalizeDirectorTimeline, upsertDirectorPoseKeyframe } from '@/utils/studio/directorTimeline'
import { useEffect, useMemo, useRef, useState } from 'react'
import {
  DeleteOutlined,
  DoubleLeftOutlined,
  DoubleRightOutlined,
  ExportOutlined,
  LockOutlined,
  SkinOutlined,
  UnlockOutlined,
  ZoomInOutlined
} from '@ant-design/icons'
import { Button, Checkbox, Collapse, ColorPicker, Image, Input, InputNumber, Modal, Select, Slider, Switch, Tooltip } from 'antd'
import {
  DIRECTOR_JOINT_GROUPS,
  DIRECTOR_JOINT_LABELS,
  DIRECTOR_POSE_PRESETS,
  DIRECTOR_PROP_SHAPES,
  applyDirectorEnvironment,
  applyDirectorLightPreset,
  applyDirectorWeather,
  createDirectorLight,
  patchTransformAxis,
  resolveDirectorAspectRatio,
} from '@/utils/studio/directorScene'
import { applyDirectorGroupedTransform } from './directorCrowdGroup'
import type { DirectorObject, DirectorSceneData, DirectorViewMode } from '@/types/studio'
import {
  isDirectorCameraLocked,
  resolveDirectorCamera,
  resolveDirectorCameraAimObjectId,
  resolveDirectorCameraLens,
  updateDirectorCameraAimObjectId,
  updateDirectorCameraEntity,
  updateDirectorCameraLens,
} from './directorCameraState'
import {
  resolveDirectorInspectorPanelKind,
  resolveDirectorInspectorTitle
} from './directorInspectorState'
import { DIRECTOR_BATCH_SHOT_LIMIT, listDirectorBatchShotTargets } from './directorBatchShots'
import { DIRECTOR_PANORAMA_DEFAULT_RADIUS, DIRECTOR_PANORAMA_MAX_RADIUS, DIRECTOR_PANORAMA_MIN_RADIUS } from './directorPanorama'
import { DirectorCameraConfiguration, type DirectorCameraConfigurationValue } from './DirectorCameraConfiguration'
import { DirectorVirtualCameraPanel } from './DirectorVirtualCameraPanel'
import { DirectorWeatherControls } from './DirectorWeatherControls'

interface DirectorInspectorProps {
  scene: DirectorSceneData
  selectedObjectId: string | null
  selectedObjectIds?: string[]
  viewMode: DirectorViewMode
  onSelect: (id: string | null) => void
  onChange: (scene: DirectorSceneData) => void
  onPreviewChange?: (scene: DirectorSceneData, cameraId: string) => void
  getLiveScene?: () => DirectorSceneData
  beforeCameraPair?: () => Promise<void>
  viewport?: React.RefObject<import('./DirectorViewport').DirectorViewportHandle | null>
  workspaceId?: string
  onSendCapture: (mediaUrl: string) => void
  onBatchCapture?: (cameraIds: string[], sendToCanvas: boolean) => void
  batchCapturing?: boolean
  onFocusObject?: (id: string) => void
  onAlignCameraToViewport?: (cameraId: string) => void
  onJumpToCamera?: (cameraId: string) => void
  activePoseJoint?: string | null
  onActivePoseJointChange?: (joint: string | null) => void
  onCharacterTabChange?: (tab: CharacterTab | null) => void
  onOpenTimeline?: () => void
  collapsed?: boolean
  authoringDetails?: React.ReactNode
  authoringTitle?: string
  exposureControl?: React.ReactNode
  onCollapsedChange?: (collapsed: boolean) => void
}

type CharacterTab = 'props' | 'pose' | 'actions'
type CameraTab = 'props' | 'motion' | 'captures'

export type { CharacterTab }

export function DirectorInspector({
  scene,
  selectedObjectId,
  selectedObjectIds,
  viewMode,
  onSelect,
  onChange,
  onPreviewChange,
  getLiveScene,
  beforeCameraPair,
  workspaceId = '',
  viewport,
  onSendCapture,
  onBatchCapture,
  batchCapturing = false,
  onFocusObject,
  onAlignCameraToViewport,
  onJumpToCamera,
  activePoseJoint = null,
  onActivePoseJointChange,
  onCharacterTabChange,
  onOpenTimeline,
  collapsed = false,
  authoringDetails,
  authoringTitle,
  exposureControl,
  onCollapsedChange,
}: DirectorInspectorProps) {
  const resolvedSelectedIds = selectedObjectIds ?? (selectedObjectId ? [selectedObjectId] : [])
  const panelKind = resolveDirectorInspectorPanelKind(scene, selectedObjectId, viewMode)
  const selectedObject = scene.objects.find((object) => object.id === selectedObjectId)
  const multiSelected = resolvedSelectedIds.length > 1
  const [characterTab, setCharacterTab] = useState<CharacterTab>('props')
  const [cameraTab, setCameraTab] = useState<CameraTab>('props')

  useEffect(() => {
    setCharacterTab('props')
    onCharacterTabChange?.(null)
    onActivePoseJointChange?.(null)
  }, [selectedObjectId, onActivePoseJointChange, onCharacterTabChange])

  const updateObject = (objectId: string, patch: Partial<DirectorObject>) => {
    if (patch.transform && resolvedSelectedIds.length > 1) {
      onChange(touchScene({
        ...scene,
        objects: applyDirectorGroupedTransform(scene.objects, resolvedSelectedIds, objectId, patch.transform)
      }))
      return
    }
    onChange(touchScene({
      ...scene,
      objects: scene.objects.map((object) => object.id === objectId ? { ...object, ...patch } : object)
    }))
  }
  const handleCharacterTabChange = (tab: CharacterTab) => {
    setCharacterTab(tab)
    onCharacterTabChange?.(tab)
    if (tab === 'pose' && selectedObject?.kind === 'actor') {
      onFocusObject?.(selectedObject.id)
    } else if (tab === 'props') {
      onActivePoseJointChange?.(null)
    }
  }

  const inspectorBody = (
    <>
      {panelKind === 'scene' ? (
        <SceneInspectorPanel scene={scene} onChange={onChange} onSelect={onSelect} exposureControl={exposureControl} />
      ) : null}
      {panelKind === 'light' && selectedObjectId ? <LightPropertiesPanel scene={scene} lightId={selectedObjectId} onChange={onChange} onRemoved={() => onSelect(null)} /> : null}

      {panelKind === 'character' && selectedObject?.kind === 'actor' ? (
        <>
          {multiSelected ? <p className="director-inspector-crowd-hint">已选中 {resolvedSelectedIds.length} 个对象，拖动或改位置将整体移动</p> : null}
          <nav className="director-inspector-tabs" aria-label="角色面板">
            <button type="button" className={characterTab === 'props' ? 'is-active' : ''} onClick={() => handleCharacterTabChange('props')}>属性</button>
            <button type="button" className={characterTab === 'pose' ? 'is-active' : ''} onClick={() => handleCharacterTabChange('pose')}>姿势</button>
            <button type="button" className={characterTab === 'actions' ? 'is-active' : ''} onClick={() => handleCharacterTabChange('actions')}>动作</button>
          </nav>
          {characterTab === 'props' ? (
            <div className="director-inspector__scroll">
              <ObjectPropertiesPanel
                object={selectedObject}
                onUpdate={(patch) => updateObject(selectedObject.id, patch)}
                embedded
              />
              <DirectorPrevizInspector
                scene={scene}
                selectedId={selectedObjectId}
                onChange={onChange}
                variant="actor-motion"
              />
            </div>
          ) : characterTab === 'actions' ? (
            <div className="director-inspector__scroll">
              <DirectorActorActionsPanel key={selectedObject.id} scene={scene} actorId={selectedObject.id}
                onChange={onChange} onOpenTimeline={onOpenTimeline} />
            </div>
          ) : (
            <PoseInspector
              object={selectedObject}
              activeJoint={activePoseJoint}
              onActiveJointChange={onActivePoseJointChange}
              onUpdate={(patch) => updateObject(selectedObject.id, patch)}
              onRecord={() => {
                if (selectedObject.locked) return
                const playback = useDirectorPlaybackStore.getState()
                playback.setPlaying(false)
                const pose = { ...Object.fromEntries(Object.keys(DIRECTOR_JOINT_LABELS).map(joint => [joint, 0])), rootHeight: 0, ...selectedObject.pose }
                onChange({ ...scene, timeline: upsertDirectorPoseKeyframe(normalizeDirectorTimeline(scene.timeline), selectedObject.id, playback.playhead, pose) })
                onOpenTimeline?.()
              }}
            />
          )}
        </>
      ) : null}

      {panelKind === 'prop' && selectedObject?.kind === 'prop' ? (
        <ObjectPropertiesPanel
          object={selectedObject}
          onUpdate={(patch) => updateObject(selectedObject.id, patch)}
        />
      ) : null}

      {panelKind === 'camera' ? (
        <>
          <nav className="director-inspector-tabs" aria-label="摄像机面板">
            <button type="button" className={cameraTab === 'props' ? 'is-active' : ''} onClick={() => setCameraTab('props')}>属性</button>
            <button type="button" className={cameraTab === 'motion' ? 'is-active' : ''} onClick={() => setCameraTab('motion')}>运动轨迹</button>
            <button type="button" className={cameraTab === 'captures' ? 'is-active' : ''} onClick={() => setCameraTab('captures')}>截图</button>
          </nav>
          {cameraTab === 'props' ? (
            <div className="director-inspector__scroll">
              <CameraPropertiesPanel
                scene={scene}
                selectedObjectId={selectedObjectId}
                batchCapturing={batchCapturing}
                onSelect={onSelect}
                onChange={onChange}
                onBatchCapture={onBatchCapture}
                onAlignCameraToViewport={onAlignCameraToViewport}
                onJumpToCamera={onJumpToCamera}
                embedded
              />
              <DirectorPrevizInspector
                scene={scene}
                selectedId={selectedObjectId}
                onChange={onChange}
                variant="camera-follow"
              />
            </div>
          ) : cameraTab === 'motion' && selectedObjectId ? (
            <DirectorVirtualCameraPanel
              scene={scene}
              cameraId={selectedObjectId}
              workspaceId={workspaceId}
              viewport={viewport}
              onChange={onChange}
              beforePair={beforeCameraPair}
              onPreviewChange={onPreviewChange ?? onChange}
              getLiveScene={getLiveScene}
              onSelect={(id) => onSelect(id)}
            />
          ) : (
            <CameraCapturesPanel
              scene={scene}
              batchCapturing={batchCapturing}
              onChange={onChange}
              onSendCapture={onSendCapture}
              onBatchCapture={onBatchCapture}
            />
          )}
        </>
      ) : null}
    </>
  )

  return (
    <aside className={`director-inspector${collapsed ? ' is-collapsed' : ''}`} aria-label="属性面板">
      <section className="director-inspector__panel" aria-hidden={collapsed} inert={collapsed || undefined}>
        <header className="director-inspector__header">
          <strong>{authoringTitle ?? (multiSelected && selectedObject?.crowdGroupId ? '群众列表' : resolveDirectorInspectorTitle(panelKind))}</strong>
        </header>
        <div className="director-inspector__body">
          {authoringDetails ?? inspectorBody}
        </div>
      </section>
      <nav className="director-inspector__rail" aria-label="属性面板折叠">
        <Tooltip title={collapsed ? '展开右侧面板' : '收起右侧面板'} placement="left">
          <button
            type="button"
            className="director-inspector__collapse"
            aria-label={collapsed ? '展开右侧面板' : '收起右侧面板'}
            aria-expanded={!collapsed}
            onClick={() => onCollapsedChange?.(!collapsed)}
          >
            {collapsed ? <DoubleLeftOutlined /> : <DoubleRightOutlined />}
          </button>
        </Tooltip>
      </nav>
    </aside>
  )
}

const SCENE_ENVIRONMENTS = [
  { value: 'day', label: '日光' },
  { value: 'night', label: '夜景' },
  { value: 'studio', label: '摄影棚' },
] as const

const LIGHT_PRESETS = [
  { value: 'cinematic', label: '电影三点光' },
  { value: 'soft', label: '柔光' },
  { value: 'night', label: '夜景' },
] as const

function SceneMeter({ label, display, min, max, step, value, onChange }: {
  label: string
  display: string
  min: number
  max: number
  step?: number
  value: number
  onChange: (value: number) => void
}) {
  return (
    <div className="director-scene-slider">
      <span>{label}</span>
      <em>{display}</em>
      <Slider aria-label={label} min={min} max={max} step={step} value={value} onChange={onChange} />
    </div>
  )
}

function SceneInspectorPanel({ scene, onChange, onSelect, exposureControl }: { scene: DirectorSceneData; onChange: (scene: DirectorSceneData) => void; onSelect: (id: string | null) => void; exposureControl?: React.ReactNode }) {
  const environment = scene.environmentPreset ?? 'studio'
  const addLight = (kind: 'point' | 'spot') => {
    const light = createDirectorLight(scene.lights.length + 1, kind)
    onChange(touchScene({ ...scene, lights: [...scene.lights, light] }))
    onSelect(light.id)
  }

  return (
    <div className="director-inspector__scroll">
      <section className="director-control-section director-control-section--flat" aria-label="场景环境">
        <div className="director-section-title"><strong>场景环境</strong></div>
        <div className="director-preset-grid director-preset-grid--cards" role="group" aria-label="环境预设">
          {SCENE_ENVIRONMENTS.map((preset) => (
            <button key={preset.value} type="button" className={environment === preset.value ? 'is-active' : ''} aria-pressed={environment === preset.value}
              onClick={() => onChange(applyDirectorEnvironment(scene, preset.value))}>{preset.label}</button>
          ))}
        </div>
        <DirectorWeatherControls environment={environment} weather={scene.weather}
          onChange={weather => onChange(applyDirectorWeather(scene, weather))} />
        <label className="director-field"><span>天空颜色</span><ColorPicker size="small" value={scene.environmentColor} onChange={(_, css) => onChange(touchScene({ ...scene, environmentColor: css }))} /></label>
        {exposureControl}
        <label className="director-field"><span>显示地面网格</span><Switch size="small" checked={scene.gridVisible ?? true} onChange={(gridVisible) => onChange(touchScene({ ...scene, gridVisible }))} /></label>
        <label className="director-field"><span>网格吸附</span><Switch size="small" checked={scene.gridSnap ?? false} onChange={(gridSnap) => onChange(touchScene({ ...scene, gridSnap }))} /></label>
        <label className="director-field"><span>显示地面</span><Switch size="small" checked={scene.groundVisible ?? true} onChange={(groundVisible) => onChange(touchScene({ ...scene, groundVisible }))} /></label>
        <SceneMeter label="地面透明度" display={(scene.groundOpacity ?? 0.4).toFixed(2)} min={0.1} max={1} step={0.05} value={scene.groundOpacity ?? 0.4} onChange={(groundOpacity) => onChange(touchScene({ ...scene, groundOpacity }))} />
      </section>
      <section className="director-control-section director-control-section--flat" aria-label="全景球">
        <div className="director-section-title"><strong>全景球</strong></div>
        {scene.panoramaUrl ? (
          <>
            <figure className="director-reference-preview">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={scene.panoramaUrl} alt="已连接全景图" />
              <figcaption>已连接全景图，视口内按等距柱状投影包在球体内侧</figcaption>
            </figure>
            <SceneMeter label="水平旋转" display={`${Math.round(scene.panoramaYaw ?? 0)}°`} min={-180} max={180} value={scene.panoramaYaw ?? 0} onChange={(panoramaYaw) => onChange(touchScene({ ...scene, panoramaYaw }))} />
            <SceneMeter label="球体半径" display={String(scene.panoramaRadius ?? DIRECTOR_PANORAMA_DEFAULT_RADIUS)} min={DIRECTOR_PANORAMA_MIN_RADIUS} max={DIRECTOR_PANORAMA_MAX_RADIUS} value={scene.panoramaRadius ?? DIRECTOR_PANORAMA_DEFAULT_RADIUS} onChange={(panoramaRadius) => onChange(touchScene({ ...scene, panoramaRadius }))} />
            <Button size="small" onClick={() => onChange(touchScene({ ...scene, panoramaUrl: undefined }))}>移除全景背景</Button>
          </>
        ) : <p className="director-field__hint">左侧上传等距柱状全景图后，会作为可环绕的 360° 场景背景。</p>}
      </section>
      <section className="director-control-section director-control-section--flat" aria-label="灯光设置">
        <div className="director-section-title"><strong>灯光设置</strong></div>
        <div className="director-preset-grid director-preset-grid--cards" role="group" aria-label="灯光预设">
          {LIGHT_PRESETS.map((preset) => (
            <button key={preset.value} type="button" onClick={() => onChange(applyDirectorLightPreset(scene, preset.value))}>{preset.label}</button>
          ))}
        </div>
        <div className="director-preset-grid director-preset-grid--cards director-preset-grid--pair">
          <button type="button" onClick={() => addLight('point')}>点光</button>
          <button type="button" onClick={() => addLight('spot')}>聚光</button>
        </div>
        <p className="director-field__hint">在左侧场景树选择灯光，调整颜色与强度。</p>
      </section>
    </div>
  )
}

function LightPropertiesPanel({ scene, lightId, onChange, onRemoved }: {
  scene: DirectorSceneData; lightId: string; onChange: (scene: DirectorSceneData) => void; onRemoved: () => void
}) {
  const light = scene.lights.find(item => item.id === lightId)
  if (!light) return null
  const patch = (value: Partial<typeof light>) => onChange(touchScene({ ...scene, lights: scene.lights.map(item => item.id === lightId ? { ...item, ...value } : item) }))
  return <div className="director-inspector__scroll">
    <section className="director-control-section director-control-section--flat" aria-label={`${light.name}属性`}>
      <div className="director-section-title"><strong>{light.name}</strong><span>{light.enabled ? '已启用' : '已关闭'}</span></div>
      <label className="director-field"><span>颜色</span><ColorPicker size="small" aria-label={`${light.name}颜色`} value={light.color} onChange={(_, color) => patch({ color })} /></label>
      <SceneMeter label="强度" display={light.intensity.toFixed(1)} min={0} max={20} step={0.1} value={light.intensity} onChange={intensity => patch({ intensity })} />
      {!['ambient', 'directional'].includes(light.kind) ? <Button size="small" danger icon={<DeleteOutlined />} aria-label={`移除${light.name}`} onClick={() => {
        onChange(touchScene({ ...scene, lights: scene.lights.filter(item => item.id !== lightId) }))
        onRemoved()
      }}>移除灯光</Button> : null}
    </section>
  </div>
}

function ObjectPropertiesPanel({
  object,
  onUpdate,
  embedded = false
}: {
  object: DirectorObject
  onUpdate: (patch: Partial<DirectorObject>) => void
  embedded?: boolean
}) {
  const body = (
    <section className="director-control-section director-control-section--flat">
      <label className="director-field"><span>名称</span><Input size="small" value={object.name} onChange={(event) => onUpdate({ name: event.target.value })} /></label>
      <label className="director-field"><span>显示对象</span><Switch size="small" checked={object.visible !== false} onChange={(visible) => onUpdate({ visible })} /></label>
      <label className="director-field"><span>锁定变换</span><Switch size="small" checked={object.locked === true} onChange={(locked) => onUpdate({ locked })} /></label>
      {object.referenceImageUrl ? (
        <figure className="director-reference-preview">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={object.referenceImageUrl} alt={`${object.name} 上游参考`} />
          <figcaption>来自画布节点的参考图</figcaption>
        </figure>
      ) : null}
      <label className="director-field"><span>颜色</span><ColorPicker size="small" value={object.color} onChange={(_, css) => onUpdate({ color: css })} /></label>
      {object.kind === 'prop' ? (
        <label className="director-field"><span>形状</span><Select size="small" value={object.shape} options={DIRECTOR_PROP_SHAPES} onChange={(shape) => onUpdate({ shape })} /></label>
      ) : null}
      {(['position', 'rotation', 'scale'] as const).map((field) => (
        <div key={field} className="director-vector-control">
          <span>{field === 'position' ? '位置' : field === 'rotation' ? '旋转' : '缩放'}</span>
          {['X', 'Y', 'Z'].map((axis, index) => (
            <label key={axis}>{axis}<InputNumber
              size="small"
              step={field === 'rotation' ? 0.1 : 0.05}
              disabled={object.locked === true}
              value={Number(object.transform[field][index].toFixed(2))}
              onChange={(value) => onUpdate({
                transform: patchTransformAxis(object.transform, field, index, Number(value ?? 0))
              })}
            /></label>
          ))}
        </div>
      ))}
    </section>
  )
  return embedded ? body : <div className="director-inspector__scroll">{body}</div>
}

function PoseInspector({
  object,
  activeJoint,
  onActiveJointChange,
  onUpdate,
  onRecord
}: {
  object: DirectorObject
  activeJoint?: string | null
  onActiveJointChange?: (joint: string | null) => void
  onUpdate: (patch: Partial<DirectorObject>) => void
  onRecord: () => void
}) {
  const pose = object.pose
  const jointRefs = useRef<Record<string, HTMLLabelElement | null>>({})

  useEffect(() => {
    if (!activeJoint) return
    jointRefs.current[activeJoint]?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }, [activeJoint])

  return (
    <div className="director-inspector__scroll">
      <p className="director-actor-actions__hint">这里是静态摆姿。记录姿态关键帧后才随时间变化；跑步、哭泣等连续表演请使用“动作”页。</p>
      <StudioCanvasTooltip title={object.locked ? '请先解锁人物' : '在当前游标记录全身姿态，会覆盖动作轨道的对应关节'}>
        <Button size="small" disabled={object.locked} onClick={onRecord}>记录当前姿态关键帧</Button>
      </StudioCanvasTooltip>
      <label className="director-action-field"><span>身体高低（米）</span>
        <InputNumber aria-label="身体高低（米）" disabled={object.locked} step={.02}
          min={-1.5 * object.transform.scale[1]} max={.5 * object.transform.scale[1]}
          value={Number(((pose.rootHeight ?? 0) * object.transform.scale[1]).toFixed(2))}
          onChange={value => { if (value !== null && object.transform.scale[1] > 0) onUpdate({ pose: { ...pose, rootHeight: value / object.transform.scale[1] } }) }} />
      </label>
      <section className="director-control-section director-control-section--flat">
        <div className="director-section-title"><strong>姿势预设</strong></div>
        <div className="director-preset-grid director-preset-grid--cards">
          {Object.entries(DIRECTOR_POSE_PRESETS).map(([name, preset]) => (
            <button key={name} type="button" disabled={object.locked === true} onClick={() => onUpdate({ pose: { ...preset } })}>{name}</button>
          ))}
        </div>
      </section>
      <Collapse
        className="director-inspector-collapse director-joint-collapse"
        size="small"
        defaultActiveKey={['躯干与头部']}
        items={DIRECTOR_JOINT_GROUPS.map((group) => ({
          key: group.label,
          label: group.label,
          children: (
            <div className="director-joint-list">
              {group.joints.map((jointName) => (
                <label
                  key={jointName}
                  ref={(node) => { jointRefs.current[jointName] = node }}
                  className={activeJoint === jointName ? 'is-active' : ''}
                  onMouseEnter={() => onActiveJointChange?.(jointName)}
                >
                  <span>{DIRECTOR_JOINT_LABELS[jointName]}<em>{Math.round(pose[jointName] ?? 0)}°</em></span>
                  <Slider
                    min={-110}
                    max={110}
                    disabled={object.locked === true}
                    value={pose[jointName] ?? 0}
                    onChange={(value) => onUpdate({ pose: { ...pose, [jointName]: value } })}
                  />
                </label>
              ))}
            </div>
          )
        }))}
      />
    </div>
  )
}

function CameraPropertiesPanel({
  scene,
  selectedObjectId,
  batchCapturing,
  onSelect,
  onChange,
  onBatchCapture,
  onAlignCameraToViewport,
  onJumpToCamera,
  embedded = false
}: {
  scene: DirectorSceneData
  selectedObjectId: string | null
  batchCapturing: boolean
  onSelect: (id: string | null) => void
  onChange: (scene: DirectorSceneData) => void
  onBatchCapture?: (cameraIds: string[], sendToCanvas: boolean) => void
  onAlignCameraToViewport?: (cameraId: string) => void
  onJumpToCamera?: (cameraId: string) => void
  embedded?: boolean
}) {
  const bookmarks = scene.cameraBookmarks ?? []
  const activeCameraId = selectedObjectId === 'main-camera' || bookmarks.some((item) => item.id === selectedObjectId)
    ? selectedObjectId
    : null
  const activeCamera = activeCameraId ? resolveDirectorCamera(scene, activeCameraId) : null
  const activeCameraLocked = activeCameraId ? isDirectorCameraLocked(scene, activeCameraId) : false
  const activeBookmark = bookmarks.find((item) => item.id === activeCameraId)

  if (!activeCameraId || !activeCamera) {
    return (
      <section className="director-control-section director-control-section--flat">
        <p className="director-field__hint">请先在场景列表或机位监视器中选择一个机位。</p>
      </section>
    )
  }

  const cameraConfiguration: DirectorCameraConfigurationValue = {
    camera: activeCamera,
    lens: resolveDirectorCameraLens(scene, activeCameraId),
    aimObjectId: resolveDirectorCameraAimObjectId(scene, activeCameraId),
  }
  const updateConfiguration = (value: DirectorCameraConfigurationValue) => {
    let next = updateDirectorCameraEntity(scene, activeCameraId, value.camera)
    next = updateDirectorCameraLens(next, activeCameraId, value.lens)
    next = updateDirectorCameraAimObjectId(next, activeCameraId, value.aimObjectId)
    onChange(touchScene(next))
  }

  const body = (
    <div className="flex flex-col gap-3">
      {activeBookmark ? (
        <label className="director-field">
          <span>名称</span>
          <Input
            size="small"
            value={activeBookmark.name}
            onChange={(event) => onChange(touchScene({
              ...scene,
              cameraBookmarks: bookmarks.map((item) => item.id === activeBookmark.id ? { ...item, name: event.target.value } : item)
            }))}
          />
        </label>
      ) : null}
      <DirectorCameraConfiguration
        showTitle={false}
        value={cameraConfiguration}
        objects={scene.objects}
        aspect={resolveDirectorAspectRatio(scene.aspectRatio) ?? 16 / 9}
        disabled={activeCameraLocked}
        motionPreview={{ sceneId: scene.id, cameraId: activeCameraId }}
        onChange={updateConfiguration}
      />
      <div className="director-camera-lock-row">
        <span>{activeCameraLocked ? '机位已锁定' : '机位可编辑'}</span>
        <Button
          size="small"
          type="text"
          icon={activeCameraLocked ? <LockOutlined /> : <UnlockOutlined />}
          onClick={() => {
            if (activeCameraId === 'main-camera') {
              onChange(touchScene({ ...scene, mainCameraLocked: !scene.mainCameraLocked }))
              return
            }
            onChange(touchScene({
              ...scene,
              cameraBookmarks: bookmarks.map((item) => item.id === activeCameraId ? { ...item, locked: !item.locked } : item)
            }))
          }}
        />
      </div>
      <div className="grid grid-cols-2 gap-2">
        <Button size="small" disabled={activeCameraLocked} onClick={() => onAlignCameraToViewport?.(activeCameraId)}>对准当前视口</Button>
        <Button size="small" type="primary" onClick={() => onJumpToCamera?.(activeCameraId)}>聚焦显示此机位</Button>
      </div>
      {onBatchCapture ? (
        <Button
          size="small"
          block
          type="primary"
          loading={batchCapturing}
          onClick={() => onBatchCapture([], true)}
        >
          按全部可见机位出图到画布
        </Button>
      ) : null}
    </div>
  )
  return embedded ? body : <div className="director-inspector__scroll">{body}</div>
}

function CameraCapturesPanel({
  scene,
  batchCapturing,
  onChange,
  onSendCapture,
  onBatchCapture
}: {
  scene: DirectorSceneData
  batchCapturing: boolean
  onChange: (scene: DirectorSceneData) => void
  onSendCapture: (mediaUrl: string) => void
  onBatchCapture?: (cameraIds: string[], sendToCanvas: boolean) => void
}) {
  const captures = scene.captures ?? []
  const [preview, setPreview] = useState<{ name: string; mediaUrl: string } | null>(null)

  return (
    <div className="director-inspector__scroll">
      {onBatchCapture ? (
        <DirectorBatchShotActions
          scene={scene}
          batchCapturing={batchCapturing}
          onBatchCapture={onBatchCapture}
        />
      ) : null}
      <section className="director-control-section director-control-section--flat">
        <div className="director-section-title">
          <strong>相机截图（{captures.length}）</strong>
          {captures.length > 0 ? <Button size="small" type="text" danger onClick={() => onChange(touchScene({ ...scene, captures: [] }))}>全部清空</Button> : null}
        </div>
        {captures.length > 0 ? (
          <div className="director-capture-grid">
            {captures.map((capture) => (
              <article key={capture.id} className="director-capture-card">
                <button type="button" className="director-capture-card__preview" onClick={() => setPreview({ name: capture.name, mediaUrl: capture.mediaUrl })}>
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={capture.mediaUrl} alt={capture.name} />
                  <span><ZoomInOutlined /> 点击放大</span>
                </button>
                <div className="director-capture-card__meta">
                  <strong>{capture.name}</strong>
                  <div className="director-capture-card__actions">
                    <Button size="small" type="text" icon={<ExportOutlined />} aria-label={`发送${capture.name}到画布`} onClick={() => onSendCapture(capture.mediaUrl)} />
                    <Button size="small" type="text" danger icon={<DeleteOutlined />} aria-label={`删除${capture.name}`} onClick={() => onChange(touchScene({ ...scene, captures: captures.filter((item) => item.id !== capture.id) }))} />
                  </div>
                </div>
              </article>
            ))}
          </div>
        ) : <InspectorEmpty text="点击底部相机按钮截取当前机位，或使用上方多机位出图" />}
      </section>
      <Modal
        className="director-capture-preview-modal"
        open={Boolean(preview)}
        title={preview?.name}
        footer={preview ? (
          <div className="director-capture-preview-modal__footer">
            <Button onClick={() => setPreview(null)}>关闭</Button>
            <Button type="primary" icon={<ExportOutlined />} onClick={() => { onSendCapture(preview.mediaUrl); setPreview(null) }}>发送到画布</Button>
          </div>
        ) : null}
        onCancel={() => setPreview(null)}
        width={760}
        centered
      >
        {preview ? <Image src={preview.mediaUrl} alt={preview.name} preview={false} /> : null}
      </Modal>
    </div>
  )
}

function DirectorBatchShotActions({
  scene,
  batchCapturing,
  onBatchCapture
}: {
  scene: DirectorSceneData
  batchCapturing: boolean
  onBatchCapture: (cameraIds: string[], sendToCanvas: boolean) => void
}) {
  const targets = useMemo(() => listDirectorBatchShotTargets(scene), [scene])
  const targetKey = targets.map((item) => item.id).join('|')
  const [selectedIds, setSelectedIds] = useState<string[]>(() => targets.map((item) => item.id))

  useEffect(() => {
    const available = targetKey.split('|').filter(Boolean)
    setSelectedIds((current) => {
      const kept = current.filter((id) => available.includes(id))
      return kept.length ? kept : available
    })
  }, [targetKey])

  const toggle = (id: string, checked: boolean) => {
    setSelectedIds((current) => checked
      ? [...current, id]
      : current.filter((item) => item !== id))
  }

  return (
    <section className="director-control-section director-control-section--flat director-batch-shots">
      <div className="director-section-title">
        <strong>多机位出图</strong>
        <Button size="small" type="text" onClick={() => setSelectedIds(targets.map((item) => item.id))}>全选</Button>
      </div>
      <p className="director-batch-shots__hint">
        同一场景按勾选机位截图，保持空间与光影一致，并回灌到画布。
        {targets.length > DIRECTOR_BATCH_SHOT_LIMIT ? ` 一次最多 ${DIRECTOR_BATCH_SHOT_LIMIT} 个机位，当前可见 ${targets.length} 个，超出部分需取消勾选或先隐藏。` : ''}
      </p>
      <div className="director-batch-shots__list" role="group" aria-label="出图机位">
        {targets.map((target) => (
          <Checkbox
            key={target.id}
            checked={selectedIds.includes(target.id)}
            onChange={(event) => toggle(target.id, event.target.checked)}
          >
            {target.name}
          </Checkbox>
        ))}
      </div>
      <div className="director-batch-shots__actions">
        <Button
          size="small"
          type="primary"
          loading={batchCapturing}
          disabled={!selectedIds.length}
          onClick={() => onBatchCapture(selectedIds, true)}
        >
          出图并发送到画布
        </Button>
        <Button
          size="small"
          loading={batchCapturing}
          disabled={!selectedIds.length}
          onClick={() => onBatchCapture(selectedIds, false)}
        >
          仅出图
        </Button>
      </div>
    </section>
  )
}

function InspectorEmpty({ text }: { text: string }) {
  return <div className="director-inspector-empty"><SkinOutlined /><p>{text}</p></div>
}

function touchScene(scene: DirectorSceneData): DirectorSceneData {
  return { ...scene, updatedAt: new Date().toISOString() }
}
