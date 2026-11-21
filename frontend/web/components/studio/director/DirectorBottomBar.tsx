'use client'

import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { DirectorActorActionDialog } from './DirectorActorActionsPanel'
import { DirectorTimelineToolbar } from './DirectorTimelineToolbar'
import { DirectorActionTimelineClip } from './DirectorActionTimelineClip'
import { DirectorTimelineClip } from './DirectorTimelineClip'
import { appendDirectorActorAction, DIRECTOR_ACTOR_ACTIONS } from '@/utils/studio/directorActorActions'
import { DirectorPathClipEditor, insertDirectorWaypoint } from './DirectorPathEditor'
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type PointerEvent as ReactPointerEvent, type ReactNode } from 'react'
import { App, Dropdown, Modal } from 'antd'
import {
  CameraOutlined,
  DeleteOutlined,
  DragOutlined,
  ExpandOutlined,
  EditOutlined,
  PlusOutlined,
  RotateRightOutlined
} from '@ant-design/icons'
import { availableDirectorTimelineLanes, buildDirectorTimelineLanes, removeDirectorTimelineLane, timeAtTimelinePixel, type DirectorTimelineLane } from './directorTimelineLanes'
import { startDirectorCameraPathDrawing } from './directorMotionPanelState'
import type {
  DirectorCamera,
  DirectorCameraKeyframeValue,
  DirectorKeyframeTransformValue,
  DirectorPathClip,
  DirectorPathDrawPlane,
  DirectorPoseKeyframeValue,
  DirectorSceneData
} from '@/types/studio'
import { applyDirectorTimelineAt } from '@/utils/studio/directorScene'
import {
  clearDirectorTimelineKeyframes,
  cloneDirectorPoseValue,
  countDirectorTimelineKeyframes,
  normalizeDirectorTimeline,
  removeDirectorCameraKeyframe,
  removeDirectorObjectKeyframe,
  removeDirectorPoseKeyframe,
  setDirectorTimelineDuration,
  transformToKeyframeValue,
  updateDirectorCameraKeyframeValue,
  updateDirectorObjectKeyframeValue,
  updateDirectorPoseKeyframeValue,
  upsertDirectorCameraKeyframe,
  upsertDirectorObjectKeyframe,
  upsertDirectorPoseKeyframe
} from '@/utils/studio/directorTimeline'
import { useDirectorPlaybackStore } from './directorPlaybackStore'
import { applyThreePrevizProject, projectThreePreviz, threePrevizDuration, threePrevizPlaybackEnd } from '@/utils/studio/threePrevizDocument'
import { applyCommands, resizeDuration } from '@/utils/previz/core'
import { exportDirectorVideo, type DirectorVideoExportView } from './directorVideoExport'
import { retimeDirectorClip } from '@/utils/studio/directorPathClips'
import {
  DIRECTOR_PATH_PLANES,
  directorPathDrawingTarget,
  directorPathDrawingView,
  normalizeDirectorPathPlane,
} from '@/utils/studio/directorPathDrawing'

export type DirectorTransformMode = 'translate' | 'rotate' | 'scale'

interface DirectorBottomBarProps {
  scene: DirectorSceneData
  selectedObjectId: string | null
  onSelectObject: (id: string | null) => void
  transformMode: DirectorTransformMode
  timelineOpen: boolean
  extraTimelineRows?: (frameCount: number) => ReactNode
  extraTimelineRowCount?: number
  onPreviewShots?: () => void
  onEditShots: () => void
  onAnnotate: () => void
  onFocus: () => void
  renderDock: (toolbar: ReactNode) => ReactNode
  modelingPathAction?: () => void
  batchCapturing?: boolean
  onTransformModeChange: (mode: DirectorTransformMode) => void
  onTimelineOpenChange: (open: boolean) => void
  onCapture: () => void
  onBatchCapture: () => void
  onSceneChange: (scene: DirectorSceneData) => void
  onCommitPlayhead: (time: number) => void
  captureBusy: boolean
  onCaptureBusyChange: (busy: boolean) => void
  getViewportCanvas?: () => HTMLCanvasElement | null
  beginViewportRecording?: (view: DirectorVideoExportView) => () => void
  renderViewportRecordingFrame?: (time: number) => Promise<ImageBitmap>
}

const VIEWPORT_TOOL_PATHS = {
  annotation: 'M14 5H5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-4M7 15l3-3 3 2M14 10l4.5-4.5a1.4 1.4 0 0 1 2 2L16 12l-3 1z',
  focus: 'M8 4H5a1 1 0 0 0-1 1v3m12-4h3a1 1 0 0 1 1 1v3m0 8v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3m4-6 4-2 4 2v4l-4 2-4-2z',
  batch: 'M7 4h12a2 2 0 0 1 2 2v9M5 8h11a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2Zm1 8 3-3 2 2 2-1 2 2',
  timeline: 'M4 5v14m8-14v4m8-4v14M4 9h16M7 13h4v4H7zm7 0h3v4h-3z',
} as const

function ViewportToolIcon({ kind }: { kind: keyof typeof VIEWPORT_TOOL_PATHS }) {
  return <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round">
    <path d={VIEWPORT_TOOL_PATHS[kind]} />
  </svg>
}

export function DirectorBottomBar({
  scene,
  selectedObjectId,
  onSelectObject,
  transformMode,
  timelineOpen,
  extraTimelineRows,
  extraTimelineRowCount,
  onPreviewShots,
  onEditShots,
  onAnnotate,
  onFocus,
  renderDock,
  modelingPathAction,
  batchCapturing = false,
  onTransformModeChange,
  onTimelineOpenChange,
  onCapture,
  onBatchCapture,
  onSceneChange,
  onCommitPlayhead,
  captureBusy,
  onCaptureBusyChange,
  getViewportCanvas,
  beginViewportRecording,
  renderViewportRecordingFrame
}: DirectorBottomBarProps) {
  const [operationOpen, setOperationOpen] = useState(false)
  const cameraSelected = selectedObjectId === 'main-camera' || scene.cameraBookmarks?.some((item) => item.id === selectedObjectId)

  return (
    <>
      {renderDock(<div className="director-bottom-bar" role="toolbar" aria-label="3D预演操作">
        {modelingPathAction ? <StudioCanvasTooltip title="在视口绘制选中对象的运动路径"><button type="button" aria-label="绘制路径" onClick={modelingPathAction}><EditOutlined /></button></StudioCanvasTooltip> : null}
        <StudioCanvasDropdownPopover open={operationOpen} onOpenChange={setOperationOpen}
          placement="top" autoAdjustOverflow ariaLabel="对象操作"
          activeKey={transformMode}
          items={[
            { key: 'translate', label: '移动 · V', icon: <DragOutlined /> },
            { key: 'rotate', label: '旋转 · R', icon: <RotateRightOutlined /> },
            { key: 'scale', label: '缩放 · F', icon: <ExpandOutlined />, disabled: Boolean(cameraSelected), disabledHint: '机位不支持缩放，请调整焦距或位置' }
          ]}
          onSelect={mode => onTransformModeChange(mode as DirectorTransformMode)}
          trigger={<StudioCanvasTooltip title="对象操作"><button type="button" className={operationOpen ? 'is-active' : ''}
            aria-label="对象操作" aria-haspopup="menu" aria-expanded={operationOpen}><DragOutlined /></button></StudioCanvasTooltip>} />
        <StudioCanvasTooltip title="截取当前机位">
          <button type="button" aria-label="截取当前机位" onClick={onCapture}><CameraOutlined /></button>
        </StudioCanvasTooltip>
        <StudioCanvasTooltip title={batchCapturing ? '正在批量出图，请等待完成' : '按全部可见机位出图并发送到画布'}>
          <button type="button" className={batchCapturing ? 'is-active' : ''} aria-label="多机位批量出图" disabled={batchCapturing} onClick={onBatchCapture}><ViewportToolIcon kind="batch" /></button>
        </StudioCanvasTooltip>
        <StudioCanvasTooltip title="动画时间轴">
          <button type="button" className={timelineOpen ? 'is-active' : ''} aria-label="动画时间轴" data-previz-step="timeline" aria-pressed={timelineOpen} onClick={() => onTimelineOpenChange(!timelineOpen)}><ViewportToolIcon kind="timeline" /></button>
        </StudioCanvasTooltip>
        <StudioCanvasTooltip title="画面标注"><button type="button" aria-label="画面标注" onClick={onAnnotate}><ViewportToolIcon kind="annotation" /></button></StudioCanvasTooltip>
        <StudioCanvasTooltip title="聚焦所选对象"><button type="button" aria-label="聚焦所选对象" onClick={onFocus}><ViewportToolIcon kind="focus" /></button></StudioCanvasTooltip>
      </div>)}
      {timelineOpen ? (
        <DirectorTimelinePanel
          extraRows={extraTimelineRows}
          extraRowCount={extraTimelineRowCount}
          onPreviewShots={onPreviewShots}
          onEditShots={onEditShots}
          scene={scene}
          selectedObjectId={selectedObjectId}
          onSelectObject={onSelectObject}
          onChange={onSceneChange}
          onClose={() => onTimelineOpenChange(false)}
          onCommitPlayhead={onCommitPlayhead}
          captureBusy={captureBusy}
          onCaptureBusyChange={onCaptureBusyChange}
          getViewportCanvas={getViewportCanvas}
          beginViewportRecording={beginViewportRecording}
          renderViewportRecordingFrame={renderViewportRecordingFrame}
        />
      ) : null}
    </>
  )
}

interface SelectedKeyframe {
  kind: 'object' | 'camera' | 'pose'
  targetId: string
  keyframeId: string
}

function DirectorTimelinePanel({
  extraRows,
  extraRowCount = 0,
  onPreviewShots,
  onEditShots,
  scene,
  selectedObjectId,
  onSelectObject,
  onChange,
  onClose,
  onCommitPlayhead,
  captureBusy,
  onCaptureBusyChange,
  getViewportCanvas,
  beginViewportRecording,
  renderViewportRecordingFrame
}: {
  extraRows?: (frameCount: number) => ReactNode
  extraRowCount?: number
  onPreviewShots?: () => void
  onEditShots: () => void
  scene: DirectorSceneData
  selectedObjectId: string | null
  onSelectObject: (id: string | null) => void
  onChange: (scene: DirectorSceneData) => void
  onClose: () => void
  onCommitPlayhead: (time: number) => void
  captureBusy: boolean
  onCaptureBusyChange: (busy: boolean) => void
  getViewportCanvas?: () => HTMLCanvasElement | null
  beginViewportRecording?: (view: DirectorVideoExportView) => () => void
  renderViewportRecordingFrame?: (time: number) => Promise<ImageBitmap>
}) {
  const { message } = App.useApp()
  const timeline = useMemo(() => normalizeDirectorTimeline(scene.timeline), [scene.timeline])
  const motionEnd = threePrevizPlaybackEnd(scene)
  const isPlaying = useDirectorPlaybackStore((state) => state.isPlaying)
  const playhead = useDirectorPlaybackStore((state) => state.playhead)
  const duration = useDirectorPlaybackStore((state) => state.duration)
  const playbackEnd = useDirectorPlaybackStore((state) => state.motionEnd)
  const togglePlaying = useDirectorPlaybackStore((state) => state.togglePlaying)
  const setPlaying = useDirectorPlaybackStore((state) => state.setPlaying)
  const setPlayhead = useDirectorPlaybackStore((state) => state.setPlayhead)
  const [selectedKeyframe, setSelectedKeyframe] = useState<SelectedKeyframe | null>(null)
  const [focusedLane, setFocusedLane] = useState<{ id: string; targetId: string } | null>(null)
  const [selectedClipId, setSelectedClipId] = useState<string>()
  const [selectedActionId, setSelectedActionId] = useState<string>()
  const [modal, modalHolder] = Modal.useModal()
  const [pixelsPerSecond, setPixelsPerSecond] = useState(90)
  const [panelBounds, setPanelBounds] = useState({ min: 190, max: 300 })
  const handleRef = useRef<HTMLDivElement>(null)
  const panelDrag = useRef<{ pointerId: number; startY: number; startHeight: number } | null>(null)
  const preferredPanelHeight = useRef(300)
  const [isRecording, setIsRecording] = useState(false)
  const exportLock = useRef(false)
  const rulerRef = useRef<HTMLDivElement>(null)
  const panelRef = useRef<HTMLElement>(null)
  const panelHeightRef = useRef(300)
  const keyScrubTimerRef = useRef<number | null>(null)
  const scrub = useRef<{ pointerId: number; element: HTMLDivElement; startTime: number; x: number; frame: number } | null>(null)

  useEffect(() => () => {
    if (keyScrubTimerRef.current !== null) window.clearTimeout(keyScrubTimerRef.current)
    if (scrub.current?.frame) window.cancelAnimationFrame(scrub.current.frame)
    scrub.current = null
  }, [])

  const resizeTimeline = useCallback((height: number, bounds: { min: number; max: number }) => {
    const next = Math.round(Math.max(bounds.min, Math.min(bounds.max, height)))
    panelHeightRef.current = next
    panelRef.current?.parentElement?.style.setProperty('--director-timeline-height', `${next}px`)
    handleRef.current?.setAttribute('aria-valuenow', String(next))
    return next
  }, [])

  useLayoutEffect(() => {
    const viewport = panelRef.current?.parentElement
    if (!viewport) return
    const measure = () => {
      const height = viewport.clientHeight
      // Reserve space above the timeline for the viewport and dock, even in short windows.
      const max = Math.max(1, Math.floor(Math.min(height * .7, height - 144)))
      const bounds = { min: Math.min(190, max), max }
      setPanelBounds(current => current.min === bounds.min && current.max === bounds.max ? current : bounds)
      resizeTimeline(preferredPanelHeight.current, bounds)
    }
    const observer = new ResizeObserver(measure)
    observer.observe(viewport)
    measure()
    return () => {
      observer.disconnect()
      viewport.style.removeProperty('--director-timeline-height')
    }
  }, [resizeTimeline])

  const selectedObject = scene.objects.find((item) => item.id === selectedObjectId)
  const cameraSelection = resolveSelectedCamera(scene, selectedObjectId)

  const lanes = useMemo(() => buildDirectorTimelineLanes(scene), [scene])
  const availableLanes = useMemo(() => availableDirectorTimelineLanes(scene).filter((lane) => !lanes.some((item) => item.id === lane.id)), [scene, lanes])
  const activeLaneId = focusedLane?.targetId === selectedObjectId && lanes.some((lane) => lane.id === focusedLane.id)
    ? focusedLane.id
    : lanes.find((lane) => lane.targetId === selectedObjectId)?.id ?? null
  const timelineWidth = Math.ceil(threePrevizDuration(scene) * pixelsPerSecond) + 32

  const selectLane = (lane: DirectorTimelineLane) => {
    setFocusedLane({ id: lane.id, targetId: lane.targetId })
    setSelectedKeyframe(null)
    onSelectObject(lane.targetId)
  }

  const commitTimeline = (nextTimeline: ReturnType<typeof normalizeDirectorTimeline>, applyAtPlayhead = false) => {
    const base = touchScene({ ...scene, timeline: nextTimeline })
    onChange(applyAtPlayhead ? applyDirectorTimelineAt(base, useDirectorPlaybackStore.getState().playhead) : base)
  }

  const snapshotObjectTransform = (): DirectorKeyframeTransformValue => {
    if (!selectedObject) throw new Error('no object')
    return transformToKeyframeValue(selectedObject.transform)
  }

  const snapshotPose = (): DirectorPoseKeyframeValue => {
    if (!selectedObject || selectedObject.kind !== 'actor') throw new Error('no actor pose')
    return cloneDirectorPoseValue(selectedObject.pose ?? {})
  }

  const snapshotCamera = (): DirectorCameraKeyframeValue => {
    if (!cameraSelection) throw new Error('no camera')
    return {
      position: [...cameraSelection.camera.position] as [number, number, number],
      target: [...cameraSelection.camera.target] as [number, number, number],
      fov: cameraSelection.camera.fov,
      roll: cameraSelection.camera.roll ?? 0,
    }
  }

  const addKeyframe = () => {
    const time = useDirectorPlaybackStore.getState().playhead
    if (cameraSelection) {
      commitTimeline(upsertDirectorCameraKeyframe(timeline, cameraSelection.id, time, snapshotCamera()), true)
      message.success(`已记录机位「${cameraSelection.name}」关键帧`)
      return
    }
    if (!selectedObject) {
      message.warning('请先选择一个角色、物件或机位')
      return
    }
    commitTimeline(upsertDirectorObjectKeyframe(timeline, selectedObject.id, time, snapshotObjectTransform()), true)
    message.success(`已记录「${selectedObject.name}」变换关键帧`)
  }

  const addPoseKeyframe = () => {
    if (!selectedObject || selectedObject.kind !== 'actor') {
      message.warning('请先选择一个角色后再打姿态关键帧')
      return
    }
    const time = useDirectorPlaybackStore.getState().playhead
    commitTimeline(upsertDirectorPoseKeyframe(timeline, selectedObject.id, time, snapshotPose()), true)
    message.success(`已记录「${selectedObject.name}」姿态关键帧`)
  }

  const seek = (time: number) => {
    const fps = timeline.fps ?? 30
    useDirectorPlaybackStore.getState().seek(Math.round(time * fps) / fps)
  }
  const persistPlayhead = () => onCommitPlayhead(useDirectorPlaybackStore.getState().playhead)
  const seekPointer = (element: HTMLDivElement, clientX: number) => {
    seek(timeAtTimelinePixel(clientX - element.getBoundingClientRect().left, pixelsPerSecond, duration))
  }
  const beginScrub = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (event.button !== 0 || !event.isPrimary || scrub.current) return
    const interactive = (event.target as Element).closest('button,input,[role="slider"]')
    if (interactive && interactive !== event.currentTarget) return
    event.preventDefault()
    if (keyScrubTimerRef.current !== null) window.clearTimeout(keyScrubTimerRef.current)
    const element = event.currentTarget
    const startTime = useDirectorPlaybackStore.getState().playhead
    scrub.current = { pointerId: event.pointerId, element, startTime, x: event.clientX, frame: 0 }
    element.setPointerCapture(event.pointerId)
    seekPointer(element, event.clientX)
  }
  const moveScrub = (event: ReactPointerEvent<HTMLDivElement>) => {
    const active = scrub.current
    if (active?.pointerId !== event.pointerId || active.element !== event.currentTarget) return
    active.x = event.clientX
    if (active.frame) return
    active.frame = window.requestAnimationFrame(() => {
      active.frame = 0
      if (scrub.current === active) seekPointer(active.element, active.x)
    })
  }
  const endScrub = (event: ReactPointerEvent<HTMLDivElement>) => {
    const active = scrub.current
    if (active?.pointerId !== event.pointerId || active.element !== event.currentTarget) return
    scrub.current = null
    if (active.frame) window.cancelAnimationFrame(active.frame)
    if (event.type === 'pointerup') {
      seekPointer(active.element, event.clientX)
      persistPlayhead()
    } else {
      useDirectorPlaybackStore.getState().seek(active.startTime)
    }
  }

  const addLane = (laneId: string) => {
    const lane = availableLanes.find((item) => item.id === laneId)
    if (!lane) return
    commitTimeline({ ...timeline, laneIds: [...(timeline.laneIds ?? []), lane.id] })
    selectLane(lane)
  }

  const removeLane = (lane: DirectorTimelineLane) => {
    modal.confirm({ className: 'create-flow-modal', title: `移除「${lane.label}」轨道？`,
      content: lane.kind === 'action' ? '只移除此人物的动作片段，保留走位与姿态关键帧。' : '移除此轨道及其中的关键帧和路径片段。',
      okText: '移除', cancelText: '保留', onOk: () => {
        if (lane.kind === 'action' && scene.objects.find(object => object.id === lane.targetId)?.locked) {
          message.warning('请先解锁人物再移除动作轨道'); return Promise.reject(new Error('人物已锁定'))
        }
        commitTimeline(removeDirectorTimelineLane(timeline, lane.id))
        setSelectedKeyframe(null)
        if (activeLaneId === lane.id) { setFocusedLane(null); onSelectObject(null) }
      }
    })
  }

  const beginPanelResize = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (event.button !== 0 || panelDrag.current) return
    event.preventDefault()
    event.stopPropagation()
    event.currentTarget.focus({ preventScroll: true })
    event.currentTarget.setPointerCapture(event.pointerId)
    panelDrag.current = { pointerId: event.pointerId, startY: event.clientY, startHeight: panelHeightRef.current }
  }
  const movePanelResize = (event: ReactPointerEvent<HTMLDivElement>) => {
    const drag = panelDrag.current
    if (!drag || drag.pointerId !== event.pointerId) return
    preferredPanelHeight.current = resizeTimeline(drag.startHeight + drag.startY - event.clientY, panelBounds)
  }
  const endPanelResize = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (panelDrag.current?.pointerId !== event.pointerId) return
    panelDrag.current = null
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId)
  }

  const beginDrawing = (lane: DirectorTimelineLane, mode: 'circle' | 'line' | 'rect' | 'stroke' | 'pen' | 'point' | 'top' | 'waypoint') => {
    setFocusedLane({ id: lane.id, targetId: lane.targetId })
    if (mode === 'waypoint') {
      try { onChange(insertDirectorWaypoint(scene, lane.targetId)); onSelectObject(lane.targetId) }
      catch (error) { message.warning((error as Error).message) }
      return
    }
    const drawMode = mode === 'top' || mode === 'point' ? 'stroke' : mode
    const selectedPlane = normalizeDirectorPathPlane(scene.viewportPrefs?.pathDrawPlane)
    const drawPlane: DirectorPathDrawPlane = mode === 'top' || ['circle', 'line', 'rect'].includes(drawMode) ? 'xz' : selectedPlane
    let target: ReturnType<typeof directorPathDrawingTarget>
    try { target = directorPathDrawingTarget(scene, lane.targetId, useDirectorPlaybackStore.getState().playhead) }
    catch (error) { message.warning((error as Error).message); return }
    setPlaying(false)
    const drawingView = mode === 'top' || drawPlane !== 'xz'
      ? directorPathDrawingView(drawPlane, target.position)
      : undefined
    if (lane.kind === 'camera') {
      try {
        const next = startDirectorCameraPathDrawing(scene, lane.targetId, drawMode)
        onChange({
          ...next,
          ...(drawingView ? { directorCamera: drawingView } : {}),
          viewportPrefs: {
            ...next.viewportPrefs!,
            pathDrawPlane: drawPlane,
            ...(mode === 'top' ? { shading: 'xray' as const, cameraPreset: 'top' as const } : {}),
          },
        })
        onSelectObject(lane.targetId)
      }
      catch (error) { message.warning((error as Error).message) }
      return
    }
    const object = scene.objects.find((item) => item.id === lane.targetId)
    if (!object || object.locked) { message.warning('请先解锁对象后再绘制轨迹'); return }
    onChange({
      ...scene,
      viewMode: 'director',
      ...(drawingView ? { directorCamera: drawingView } : {}),
      viewportPrefs: {
        ...scene.viewportPrefs!,
        pathDrawMode: drawMode,
        pathDrawPlane: drawPlane,
        ...(mode === 'top' ? { shading: 'xray' as const, cameraPreset: 'top' as const } : {}),
      },
    })
    onSelectObject(lane.targetId)
  }

  const retimePathClip = (clip: DirectorPathClip, start: number, end: number) => {
    const nextClip = retimeDirectorClip(clip, start, end)
    commitTimeline({ ...timeline, duration: Math.max(timeline.duration, nextClip.end),
      pathClips: (timeline.pathClips ?? []).map(item => item.id === clip.id ? nextClip : item) })
  }

  const handleDurationChange = (value: number | null) => {
    if (value == null || !Number.isFinite(value)) return
    const next = setDirectorTimelineDuration(timeline, value)
    if (!scene.modeling) { commitTimeline(next); return }
    try {
      const project = projectThreePreviz(scene)
      const graph = applyCommands(project.scene, [resizeDuration(project.scene, Math.round(next.duration * 30))])
      onChange(applyThreePrevizProject({ ...scene, timeline: next }, { ...project, scene: graph }))
    } catch (error) {
      message.warning(error instanceof Error ? error.message : '无法调整预演时长')
    }
  }

  const handleSelectObjectKeyframe = (objectId: string, keyframeId: string, time: number) => {
    setPlaying(false)
    setPlayhead(time)
    setSelectedKeyframe({ kind: 'object', targetId: objectId, keyframeId })
  }

  const handleSelectPoseKeyframe = (objectId: string, keyframeId: string, time: number) => {
    setPlaying(false)
    setPlayhead(time)
    setSelectedKeyframe({ kind: 'pose', targetId: objectId, keyframeId })
  }

  const handleSelectCameraKeyframe = (cameraId: string, keyframeId: string, time: number) => {
    setPlaying(false)
    setPlayhead(time)
    setSelectedKeyframe({ kind: 'camera', targetId: cameraId, keyframeId })
  }

  const updateSelected = () => {
    if (!selectedKeyframe) return
    if (selectedKeyframe.kind === 'object' && selectedObject && selectedObject.id === selectedKeyframe.targetId) {
      commitTimeline(updateDirectorObjectKeyframeValue(timeline, selectedKeyframe.targetId, selectedKeyframe.keyframeId, snapshotObjectTransform()))
      message.success('已更新物体关键帧')
      return
    }
    if (selectedKeyframe.kind === 'pose' && selectedObject && selectedObject.id === selectedKeyframe.targetId && selectedObject.kind === 'actor') {
      commitTimeline(updateDirectorPoseKeyframeValue(timeline, selectedKeyframe.targetId, selectedKeyframe.keyframeId, snapshotPose()))
      message.success('已更新姿态关键帧')
      return
    }
    if (selectedKeyframe.kind === 'camera' && cameraSelection && cameraSelection.id === selectedKeyframe.targetId) {
      commitTimeline(updateDirectorCameraKeyframeValue(timeline, selectedKeyframe.targetId, selectedKeyframe.keyframeId, snapshotCamera()))
      message.success('已更新机位关键帧')
    }
  }

  const deleteSelected = () => {
    if (!selectedKeyframe) return
    const next = selectedKeyframe.kind === 'object'
      ? removeDirectorObjectKeyframe(timeline, selectedKeyframe.targetId, selectedKeyframe.keyframeId)
      : selectedKeyframe.kind === 'pose'
        ? removeDirectorPoseKeyframe(timeline, selectedKeyframe.targetId, selectedKeyframe.keyframeId)
        : removeDirectorCameraKeyframe(timeline, selectedKeyframe.targetId, selectedKeyframe.keyframeId)
    commitTimeline(next)
    setSelectedKeyframe(null)
  }

  const clearAll = () => {
    if (!countDirectorTimelineKeyframes(timeline)) return
    modal.confirm({ title: '清空所有关键帧？', content: '可用工作台的撤销操作恢复。', okText: '清空',
      okButtonProps: { danger: true }, onOk: () => {
        commitTimeline(clearDirectorTimelineKeyframes(timeline))
        setSelectedKeyframe(null)
        setPlayhead(0)
      } })
  }

  const handleExportVideo = async (view: DirectorVideoExportView) => {
    const canvas = getViewportCanvas?.()
    if (!canvas || !beginViewportRecording || !renderViewportRecordingFrame) {
      message.error('三维视口尚未准备好，请等待场景加载')
      return
    }
    if (captureBusy || exportLock.current) return
    exportLock.current = true
    setIsRecording(true)
    onCaptureBusyChange(true)
    try {
      await exportDirectorVideo({
        canvas, view, duration: Math.min(120, motionEnd), download: true,
        beginCapture: beginViewportRecording, renderFrame: renderViewportRecordingFrame
      })
      message.success('视频已下载')
    } catch (error: unknown) {
      message.error(error instanceof Error ? error.message : '视频导出失败')
    } finally {
      exportLock.current = false
      setIsRecording(false)
      onCaptureBusyChange(false)
    }
  }

  const selectedLane = lanes.find((lane) => lane.id === activeLaneId && lane.kind !== 'pose' && lane.kind !== 'action')
  const selectedPoseLane = lanes.find((lane) => lane.id === activeLaneId && lane.kind === 'pose')
  const pixels = (time: number) => Math.max(0, time * pixelsPerSecond)
  const drawItems = (lane: DirectorTimelineLane) => [
    ...DIRECTOR_PATH_PLANES.map((item) => ({
      key: `plane-${item.value}`,
      label: `${normalizeDirectorPathPlane(scene.viewportPrefs?.pathDrawPlane) === item.value ? '✓ ' : ''}${item.label}`,
      onClick: () => onChange({ ...scene, viewportPrefs: { ...scene.viewportPrefs!, pathDrawMode: false, pathDrawPlane: item.value } }),
    })),
    { type: 'divider' as const },
    ...[
      { key: 'point', label: '逐点绘制' },
      { key: 'stroke', label: '铅笔绘制' },
      { key: 'pen', label: '钢笔路径' },
      { key: 'top', label: '俯视画轨' },
      { key: 'waypoint', label: '插入航点' },
      { key: 'circle', label: '圆环路径（XZ）' },
      { key: 'line', label: '直线路径（XZ）' },
      { key: 'rect', label: '矩形路径（XZ）' },
    ].map((item) => ({ ...item, onClick: () => beginDrawing(lane, item.key as 'stroke' | 'point' | 'pen' | 'top' | 'waypoint' | 'circle' | 'line' | 'rect') })),
  ]

  return (
    <section ref={panelRef} className="director-timeline" aria-label="动画时间轴">
      <div ref={handleRef} className="director-timeline__resize" role="separator" aria-label="调整时间轴高度"
        aria-orientation="horizontal" aria-valuemin={panelBounds.min} aria-valuemax={panelBounds.max}
        tabIndex={0} onPointerDown={beginPanelResize} onPointerMove={movePanelResize} onPointerUp={endPanelResize}
        onPointerCancel={endPanelResize} onLostPointerCapture={endPanelResize} onKeyDown={(event) => {
          const heights = { ArrowUp: panelHeightRef.current + 24, ArrowDown: panelHeightRef.current - 24, Home: panelBounds.min, End: panelBounds.max }
          if (!Object.hasOwn(heights, event.key)) return
          const next = heights[event.key as keyof typeof heights]
          event.preventDefault()
          event.stopPropagation()
          preferredPanelHeight.current = resizeTimeline(next, panelBounds)
        }}><span /></div>
      {modalHolder}
      <DirectorTimelineToolbar playing={isPlaying} playhead={playhead} playbackEnd={playbackEnd} duration={timeline.duration} zoom={pixelsPerSecond}
        onPlay={togglePlaying} onDuration={handleDurationChange} onZoom={setPixelsPerSecond} onClose={onClose}
        shots={extraRows ? [
          {key:'edit',label:'编辑分镜',description:'调整镜头顺序、切点与机位',run:onEditShots},
          ...(onPreviewShots ? [{key:'preview',label:'按分镜预览',description:'按拍摄镜头自动切换视角',run:onPreviewShots}] : []),
        ] : []}
        lanes={availableLanes.map(lane=>({key:lane.id,label:lane.label,description:lane.kind === 'camera' ? '机位轨道' : lane.kind === 'pose' ? '姿态轨道' : lane.kind === 'action' ? '动作轨道' : '角色 / 物体轨道',run:()=>addLane(lane.id)}))}
        keyframes={[
          {key:'keyframe',label:selectedLane?.kind === 'camera' ? '记录机位关键帧' : '记录变换关键帧',description:'记录当前帧的位置与角度',disabled:!selectedLane,disabledHint:'请先选择机位或物体轨道',run:addKeyframe},
          {key:'pose',label:'记录姿态关键帧',disabled:!selectedPoseLane || selectedObject?.kind !== 'actor',disabledHint:'请先选择角色姿态轨道',run:addPoseKeyframe},
          {key:'update',label:'更新选中关键帧',disabled:!selectedKeyframe,disabledHint:'请先选择时间轴上的关键帧',run:updateSelected},
          {key:'delete',label:'删除选中关键帧',disabled:!selectedKeyframe,disabledHint:'请先选择时间轴上的关键帧',run:deleteSelected},
          {key:'clear',label:'清空全部关键帧…',icon:<DeleteOutlined />,description:'清空前会再次确认',disabled:!countDirectorTimelineKeyframes(timeline),disabledHint:'当前没有关键帧',run:clearAll},
        ]}
        actorActions={selectedObject?.kind === 'actor' ? DIRECTOR_ACTOR_ACTIONS.map(item=>({...item,disabled:selectedObject.locked,disabledHint:'请先解锁人物再追加动作',run:()=>{
          try {
            const result=appendDirectorActorAction(scene,selectedObject.id,item.key,playhead)
            setPlaying(false);setPlayhead(result.clip.start);onChange(result.scene)
          } catch(reason) {message.warning(reason instanceof Error ? reason.message : '追加动作失败')}
        }})) : undefined}
        output={[
          {key:'director',label:'下载上帝视角视频',description:'使用当前场景观察角度',run:()=>void handleExportVideo('director')},
          {key:'camera',label:'下载机位视角视频',description:'使用选定机位或拍摄镜头',run:()=>void handleExportVideo('camera')},
        ]} exporting={isRecording} captureBusy={captureBusy} />
      <div className="director-timeline__editor">
        <div className="director-timeline__grid" style={{ minWidth: timelineWidth + 224, '--timeline-second': `${pixelsPerSecond}px` } as CSSProperties}>
          <div className="director-timeline__corner">轨道 · {timeline.fps ?? 30} fps</div>
          <div ref={rulerRef} className="director-timeline__ruler" role="slider" tabIndex={0} aria-label="预演时间游标" aria-valuemin={0} aria-valuemax={duration} aria-valuenow={playhead} onKeyDown={(event) => {
            const step = event.shiftKey ? 1 : 1 / (timeline.fps ?? 30)
            const next = event.key === 'Home' ? 0 : event.key === 'End' ? duration : event.key === 'ArrowRight' ? playhead + step : event.key === 'ArrowLeft' ? playhead - step : null
            if (next === null) return
            event.preventDefault()
            seek(timeAtTimelinePixel(next * pixelsPerSecond, pixelsPerSecond, duration))
            if (keyScrubTimerRef.current !== null) window.clearTimeout(keyScrubTimerRef.current)
            keyScrubTimerRef.current = window.setTimeout(persistPlayhead, 180)
          }} onPointerDown={beginScrub} onPointerMove={moveScrub} onPointerUp={endScrub} onPointerCancel={endScrub} onLostPointerCapture={endScrub}>
            {Array.from({ length: Math.ceil(duration) + 1 }, (_, second) => <span key={second} style={{ left: pixels(second) }}>{second}s</span>)}
          </div>
          {lanes.map((lane) => {
            const keyframes = lane.kind === 'action' ? [] : lane.kind === 'camera' ? timeline.cameraTracks.find((track) => track.cameraId === lane.targetId)?.keyframes ?? [] : lane.kind === 'pose' ? timeline.poseTracks.find((track) => track.objectId === lane.targetId)?.keyframes ?? [] : timeline.tracks.find((track) => track.objectId === lane.targetId)?.keyframes ?? []
            const clips = lane.kind === 'pose' || lane.kind === 'action' ? [] : (timeline.pathClips ?? []).filter((clip) => clip.targetId === lane.targetId)
            return <div key={lane.id} className="director-timeline__row">
              <div className={`director-timeline__label${activeLaneId === lane.id ? ' is-selected' : ''}`}>
                <button type="button" className="director-timeline__lane-name" aria-pressed={activeLaneId === lane.id} onClick={() => selectLane(lane)} title={lane.label}>{lane.label}</button>
                {lane.kind !== 'pose' && lane.kind !== 'action' && <Dropdown trigger={['click']} menu={{ items: drawItems(lane) }}><button type="button" className="director-timeline__draw" aria-label={`${lane.label}添加轨迹`}><PlusOutlined /> 添加轨迹</button></Dropdown>}
                {lane.id !== 'camera:main-camera' && <button type="button" className="director-timeline__remove" aria-label={`移除${lane.label}轨道`} title="移除轨道" onClick={() => removeLane(lane)}><DeleteOutlined /></button>}
              </div>
              <div className={`director-timeline__lane director-timeline__lane--${lane.kind}${activeLaneId === lane.id ? ' is-selected' : ''}`} onPointerDown={beginScrub} onPointerMove={moveScrub} onPointerUp={endScrub} onPointerCancel={endScrub} onLostPointerCapture={endScrub}>
                {clips.map(clip => <DirectorTimelineClip key={clip.id} start={clip.start} end={clip.end} scale={pixelsPerSecond}
                  minimumDuration={0.2} selected={selectedClipId === clip.id}
                  label={lane.label + '轨迹片段 ' + clip.start.toFixed(1) + ' 至 ' + clip.end.toFixed(1) + ' 秒，拖动移动，拖动右端调整时长，点击编辑'}
                  onRetime={(start, end) => retimePathClip(clip, start, end)} onSelect={() => { selectLane(lane); setSelectedClipId(clip.id) }}>
                  {clip.kind === 'path' ? '运动轨迹' : '跟拍'}
                </DirectorTimelineClip>)}
                {lane.kind === 'action' ? (timeline.actionClips ?? []).filter(clip => clip.actorId === lane.targetId).map(clip => (
                  <DirectorActionTimelineClip key={clip.id} scene={scene} clip={clip} pixelsPerSecond={pixelsPerSecond} onChange={onChange}
                    onSelect={() => { selectLane(lane); setSelectedActionId(clip.id) }} />
                )) : null}
                {keyframes.map((keyframe) => <button key={keyframe.id} type="button" className={`director-timeline__keyframe${selectedKeyframe?.keyframeId === keyframe.id ? ' is-selected' : ''}`} style={{ left: pixels(keyframe.time) }} aria-label={`${lane.label} ${keyframe.time.toFixed(2)} 秒关键帧`} onClick={() => { selectLane(lane); if (lane.kind === 'camera') handleSelectCameraKeyframe(lane.targetId, keyframe.id, keyframe.time); else if (lane.kind === 'pose') handleSelectPoseKeyframe(lane.targetId, keyframe.id, keyframe.time); else handleSelectObjectKeyframe(lane.targetId, keyframe.id, keyframe.time) }} />)}
              </div>
            </div>
          })}
          {extraRows?.(Math.round(timelineWidth / pixelsPerSecond * 30) + 1)}
          <div className="director-timeline__playhead" style={{ left: 224 + pixels(playhead), height: 34 + (lanes.length + extraRowCount) * 48 }} aria-hidden="true"><i /></div>
        </div>
      </div>
      <DirectorActorActionDialog scene={scene} clipId={selectedActionId} onChange={onChange} onClose={() => setSelectedActionId(undefined)} />
      <DirectorPathClipEditor scene={scene} onChange={onChange} selectedClipId={selectedClipId} onCloseEditor={() => setSelectedClipId(undefined)} onEditPen={(clip) => {
        setSelectedClipId(undefined)
        onSelectObject(clip.targetId)
        onChange({ ...scene, viewMode: 'director', viewportPrefs: { ...scene.viewportPrefs!, pathDrawMode: 'pen', pathEditClipId: clip.id } })
      }} />
    </section>
  )
}

function resolveSelectedCamera(
  scene: DirectorSceneData,
  selectedObjectId: string | null
): { id: string; name: string; camera: DirectorCamera } | null {
  if (!selectedObjectId) return null
  if (selectedObjectId === 'main-camera') {
    return { id: 'main-camera', name: '主相机', camera: scene.camera }
  }
  const bookmark = scene.cameraBookmarks?.find((item) => item.id === selectedObjectId)
  if (!bookmark) return null
  return { id: bookmark.id, name: bookmark.name, camera: bookmark.camera }
}

function touchScene(scene: DirectorSceneData): DirectorSceneData {
  return { ...scene, updatedAt: new Date().toISOString() }
}
