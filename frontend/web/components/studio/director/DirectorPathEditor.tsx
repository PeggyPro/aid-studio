'use client'
import { useEffect, useRef, useState, type RefObject } from 'react'
import { App, Button, InputNumber, Modal, Select, Switch } from 'antd'
import { Raycaster, Vector2, Vector3 } from 'three'
import type {
  DirectorPathClip,
  DirectorSceneData,
  DirectorTransformTuple as Vec,
} from '@/types/studio'
import { activeDirectorClip, connectDirectorPathStart, createDirectorPath, insertDirectorDrawnPath, retimeDirectorClip } from '@/utils/studio/directorPathClips'
import {
  bakeBezierCameraPath,
  createCircleCameraPath,
  createLineCameraPath,
  createRectCameraPath,
} from '@/utils/studio/directorPathShapes'
import { applyDirectorTimelineAt, normalizeDirectorTimeline } from '@/utils/studio/directorTimeline'
import { studioId } from '@/utils/studio/studioGraph'
import { bakeDirectorFollow } from '@/utils/studio/directorRecordCamera'
import { directorAnchor } from '@/utils/studio/directorFollowConstraints'
import {
  DIRECTOR_PATH_PLANES,
  directorPathDrawingPlane,
  directorPathDrawingTarget,
  normalizeDirectorPathPlane,
} from '@/utils/studio/directorPathDrawing'
import { useDirectorPlaybackStore } from './directorPlaybackStore'
import type { DirectorViewportHandle } from './DirectorViewport'

export function insertDirectorWaypoint(scene: DirectorSceneData, id: string | null): DirectorSceneData {
  const timeline = normalizeDirectorTimeline(scene.timeline)
  const time = useDirectorPlaybackStore.getState().playhead
  const evaluated = applyDirectorTimelineAt(scene, time)
  const object = evaluated.objects.find((o) => o.id === id && o.kind === 'actor')
  const bookmark = evaluated.cameraBookmarks?.find((b) => b.id === id)
  const camera = id === 'main-camera' ? evaluated.camera : bookmark?.camera
  if ((!object && !camera) || object?.locked || bookmark?.locked || (id === 'main-camera' && scene.mainCameraLocked))
    throw new Error('请先选中一个已解锁的人物或机位')
  const point = {
    id: studioId('waypoint'),
    time,
    position: object?.transform.position ?? camera!.position,
    rotation: object?.transform.rotation,
    lookAt: camera?.target,
    fov: camera?.fov,
  }
  const existing = activeDirectorClip(timeline.pathClips ?? [], id!, time)
  if (existing && existing.kind !== 'path') throw new Error('请先将跟拍转为路径片段')
  const end = Math.max(time + 1, timeline.duration)
  const clip: DirectorPathClip = existing
    ? {
        ...existing,
        waypoints: [...existing.waypoints.filter((p) => Math.abs(p.time - time) > 0.001), point].sort(
          (a, b) => a.time - b.time,
        ),
      }
    : {
        id: studioId('path'),
        kind: 'path',
        targetId: id!,
        targetKind: object ? 'object' : 'camera',
        start: time,
        end: time + 1,
        createdAt: new Date().toISOString(),
        waypoints: [point, { ...point, id: studioId('waypoint'), time: time + 1 }],
      }
  return {
    ...scene,
    timeline: {
      ...timeline,
      duration: end,
      pathClips: [...(timeline.pathClips ?? []).filter((c) => c.id !== clip.id), clip],
    },
  }
}

export function DirectorPathDrawOverlay({
  scene,
  targetId,
  viewport,
  onChange,
  modeling,
}: {
  scene: DirectorSceneData
  targetId: string | null
  viewport: RefObject<DirectorViewportHandle | null>
  onChange: (s: DirectorSceneData) => void
  modeling?: { position: Vec; onCommit: (points: Vec[]) => void; onCancel: () => void }
}) {
  const { message } = App.useApp()
  const mode = modeling ? 'stroke' : scene.viewportPrefs?.pathDrawMode
  // Legacy point-mode sessions use the same continuous pencil gesture.
  const freehand = mode === 'stroke' || mode === 'point'
  const plane = modeling ? 'xz' : normalizeDirectorPathPlane(scene.viewportPrefs?.pathDrawPlane)
  const [session] = useState(() => {
    try { return directorPathDrawingTarget(scene, targetId, useDirectorPlaybackStore.getState().playhead) }
    catch { return null }
  })
  const editClip = normalizeDirectorTimeline(scene.timeline).pathClips?.find((clip) => clip.id === scene.viewportPrefs?.pathEditClipId)
  const penHeight = editClip?.waypoints[0]?.position[1] ?? session?.position[1] ?? 0
  const points = useRef<Vec[]>([])
  const penAnchors = useRef<Array<{ p: Vec; hIn?: Vec; hOut?: Vec }>>(editClip?.penAnchors?.map((anchor) => ({ ...anchor })) ?? [])
  const [penVisual, setPenVisual] = useState(() => editClip?.penAnchors?.map((anchor) => ({ ...anchor })) ?? [])
  const [preview, setPreview] = useState<Array<[number, number]>>([])
  const lastSample = useRef<[number, number] | null>(null)
  const drawing = useRef(false)
  const pointerId = useRef<number | null>(null)
  const draggingHandle = useRef<{ index: number; kind: 'p' | 'hIn' | 'hOut' } | null>(null)
  const refreshPen = () => setPenVisual(penAnchors.current.map((anchor) => ({
    p: [...anchor.p] as Vec,
    ...(anchor.hIn ? { hIn: [...anchor.hIn] as Vec } : {}),
    ...(anchor.hOut ? { hOut: [...anchor.hOut] as Vec } : {}),
  })))
  const exit = () => {
    pointerId.current = null
    drawing.current = false
    if (modeling) { modeling.onCancel(); return }
    onChange({ ...scene, viewportPrefs: { ...scene.viewportPrefs!, pathDrawMode: false, pathEditClipId: undefined } })
  }
  const commit = () => {
    drawing.current = false
    pointerId.current = null
    try {
      if (modeling) { modeling.onCommit(points.current); return }
      if (!session) throw new Error('请先选中一个已解锁的人物、物件或机位')
      directorPathDrawingTarget(scene, targetId, session.time)
      const camera = session.camera
      const object = scene.objects.find((item) => item.id === targetId)
      const height = penHeight
      const lookAt = camera?.target ?? object?.transform.position ?? ([0, 0, 0] as Vec)
      const start = editClip?.start ?? session.time
      const origin = session.position
      let clip: DirectorPathClip
      if (mode === 'circle') {
        const [center, edge] = points.current
        const radius = center && edge ? Math.hypot(edge[0] - center[0], edge[2] - center[2]) : 0
        clip = createCircleCameraPath(center, radius, height, targetId!, start)
      } else if (mode === 'line') {
        clip = createLineCameraPath(points.current[0], points.current[1], height, targetId!, start, lookAt)
      } else if (mode === 'rect') {
        const [a, b] = points.current
        clip = createRectCameraPath(
          Math.min(a[0], b[0]), Math.min(a[2], b[2]), Math.max(a[0], b[0]), Math.max(a[2], b[2]),
          height, targetId!, start,
        )
      } else if (mode === 'pen') {
        clip = bakeBezierCameraPath(penAnchors.current, height, targetId!, start, lookAt)
      } else {
        clip = createDirectorPath(
          targetId!,
          camera ? 'camera' : 'object',
          points.current,
          start,
          { space: 'world', grounded: plane === 'xz' },
        )
        if (camera) clip.waypoints = clip.waypoints.map((p) => ({ ...p, lookAt: [...camera.target], fov: camera.fov }))
        clip.source = 'draw'
      }
      if (!camera && object) {
        clip = { ...clip, targetKind: 'object', waypoints: clip.waypoints.map((point) => ({ ...point, lookAt: undefined, fov: undefined })) }
      }
      if (origin) clip = connectDirectorPathStart(clip, origin)
      if (editClip) clip = { ...clip, id: editClip.id, createdAt: editClip.createdAt }
      const timeline = normalizeDirectorTimeline(scene.timeline)
      onChange({
        ...scene,
        viewportPrefs: { ...scene.viewportPrefs!, pathDrawMode: false, pathEditClipId: undefined },
        timeline: {
          ...timeline,
          duration: Math.max(timeline.duration, clip.end),
          pathClips: insertDirectorDrawnPath(timeline.pathClips ?? [], clip),
        },
      })
    } catch (error) {
      message.warning((error as Error).message)
    }
  }
  const latest = useRef({ commit, exit })
  useEffect(() => { latest.current = { commit, exit } })
  useEffect(() => {
    if (!mode) return
    const key = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' && e.target instanceof Element && e.target.closest('input,textarea,[contenteditable=true]')) return
      if (!['Escape', 'Enter', 'Backspace', 'Delete'].includes(e.key)) return
      e.preventDefault()
      e.stopImmediatePropagation()
      if (e.key === 'Escape') latest.current.exit()
      else if (e.key === 'Enter') latest.current.commit()
      else {
        if (mode === 'pen') {
          if (penAnchors.current.length > 1) penAnchors.current.pop()
          setPenVisual(penAnchors.current.map((anchor) => ({ ...anchor })))
          setPreview((p) => p.slice(0, -1))
        } else {
          points.current.pop()
          lastSample.current = null
          setPreview((p) => p.slice(0, -1))
        }
      }
    }
    window.addEventListener('keydown', key, true)
    return () => window.removeEventListener('keydown', key, true)
  }, [mode])
  if (!mode) return null
  const groundPoint = (event: React.PointerEvent<SVGSVGElement>) => {
    const runtime = viewport.current?.getRuntime()
    const position = modeling?.position ?? session?.position
    if (!runtime || !position) return
    const rect = event.currentTarget.getBoundingClientRect()
    const ray = new Raycaster()
    ray.setFromCamera(
      new Vector2(
        ((event.clientX - rect.left) / rect.width) * 2 - 1,
        1 - ((event.clientY - rect.top) / rect.height) * 2,
      ),
      runtime.activeCamera(),
    )
    const hit = ray.ray.intersectPlane(directorPathDrawingPlane(plane, position), new Vector3())
    if (!hit) return null
    return { world: hit.toArray() as Vec, screen: [event.clientX - rect.left, event.clientY - rect.top] as [number, number] }
  }
  const addPoint = (event: React.PointerEvent<SVGSVGElement>) => {
    const hit = groundPoint(event)
    if (!hit) return
    const previous = lastSample.current
    if (previous && Math.hypot(hit.screen[0] - previous[0], hit.screen[1] - previous[1]) < 2) return
    lastSample.current = hit.screen
    points.current.push(hit.world)
    setPreview((prior) => [...prior, hit.screen])
    if (points.current.length > 1000) {
      points.current = points.current.filter((_, i) => i % 2 === 0)
      setPreview((p) => p.filter((_, i) => i % 2 === 0))
    }
  }
  const project = (point: Vec): [number, number] => {
    const runtime = viewport.current?.getRuntime()
    const canvas = runtime?.renderer.domElement
    if (!runtime || !canvas) return [0, 0]
    const projected = new Vector3(point[0], point[1] + penHeight, point[2]).project(runtime.activeCamera())
    return [(projected.x + 1) * canvas.clientWidth / 2, (1 - projected.y) * canvas.clientHeight / 2]
  }
  const penPath = penVisual.map((anchor, index) => {
    const p = project(anchor.p)
    if (!index) return `M ${p[0]} ${p[1]}`
    const previous = penVisual[index - 1]
    const a = project(previous.hOut ?? previous.p)
    const b = project(anchor.hIn ?? anchor.p)
    return `C ${a[0]} ${a[1]} ${b[0]} ${b[1]} ${p[0]} ${p[1]}`
  }).join(' ')
  const penPoint = (world: Vec): Vec => [world[0], world[1] - penHeight, world[2]]
  return (
    <div className="absolute inset-0 z-20" aria-label="路径绘制区域">
      <svg
        className="h-full w-full touch-none cursor-crosshair"
        onPointerDown={(e) => {
          if (e.button !== 0 || pointerId.current !== null) return
          e.preventDefault()
          e.stopPropagation()
          pointerId.current = e.pointerId
          if (freehand) { points.current = []; lastSample.current = null; setPreview([]) }
          if (mode === 'pen') {
            const handle = (e.target as Element).closest<SVGElement>('[data-anchor-index]')
            if (handle) {
              draggingHandle.current = {
                index: Number(handle.dataset.anchorIndex),
                kind: (handle.dataset.anchorKind as 'p' | 'hIn' | 'hOut') ?? 'p',
              }
              e.currentTarget.setPointerCapture(e.pointerId)
              return
            }
          }
          drawing.current = true
          e.currentTarget.setPointerCapture(e.pointerId)
          if (mode === 'circle' || mode === 'line' || mode === 'rect') {
            const hit = groundPoint(e)
            if (!hit) return
            points.current = [hit.world, hit.world]
            setPreview([hit.screen, hit.screen])
          } else if (mode === 'pen') {
            const hit = groundPoint(e)
            if (!hit) return
            if (!penAnchors.current.length) {
              const evaluated = applyDirectorTimelineAt(scene, useDirectorPlaybackStore.getState().playhead)
              const origin = targetId === 'main-camera'
                ? evaluated.camera.position
                : evaluated.cameraBookmarks?.find((b) => b.id === targetId)?.camera.position
                  ?? evaluated.objects.find((item) => item.id === targetId)?.transform.position
              if (origin) penAnchors.current.push({ p: [origin[0], 0, origin[2]] })
            }
            penAnchors.current.push({ p: penPoint(hit.world) })
            refreshPen()
          } else addPoint(e)
        }}
        onPointerMove={(e) => {
          if (pointerId.current !== e.pointerId) return
          if (mode === 'pen' && draggingHandle.current) {
            const hit = groundPoint(e)
            const drag = draggingHandle.current
            const anchor = penAnchors.current[drag.index]
            if (!hit || !anchor) return
            if (drag.kind === 'p') {
              const next = penPoint(hit.world)
              const delta: Vec = [next[0] - anchor.p[0], next[1] - anchor.p[1], next[2] - anchor.p[2]]
              anchor.p = next
              if (anchor.hIn) anchor.hIn = [anchor.hIn[0] + delta[0], anchor.hIn[1] + delta[1], anchor.hIn[2] + delta[2]]
              if (anchor.hOut) anchor.hOut = [anchor.hOut[0] + delta[0], anchor.hOut[1] + delta[1], anchor.hOut[2] + delta[2]]
            } else {
              const next = penPoint(hit.world)
              anchor[drag.kind] = next
              const opposite = drag.kind === 'hIn' ? 'hOut' : 'hIn'
              anchor[opposite] = [2 * anchor.p[0] - next[0], 2 * anchor.p[1] - next[1], 2 * anchor.p[2] - next[2]]
            }
            refreshPen()
            return
          }
          if (freehand && drawing.current) addPoint(e)
          if ((mode === 'circle' || mode === 'line' || mode === 'rect') && drawing.current) {
            const hit = groundPoint(e)
            if (!hit) return
            points.current[1] = hit.world
            setPreview((prior) => [prior[0], hit.screen])
          }
          if (mode === 'pen' && drawing.current) {
            const hit = groundPoint(e)
            const anchor = penAnchors.current.at(-1)
            if (!hit || !anchor) return
            anchor.hOut = penPoint(hit.world)
            anchor.hIn = [anchor.p[0] * 2 - anchor.hOut[0], anchor.p[1] * 2 - anchor.hOut[1], anchor.p[2] * 2 - anchor.hOut[2]]
            refreshPen()
          }
        }}
        onPointerUp={(e) => {
          if (pointerId.current !== e.pointerId) return
          const shouldCommit = (freehand || ['circle', 'line', 'rect'].includes(String(mode))) && drawing.current
          if (freehand && drawing.current) addPoint(e)
          pointerId.current = null
          draggingHandle.current = null
          drawing.current = false
          if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId)
          if (shouldCommit && (!freehand || points.current.length > 1)) commit()
          else if (freehand) { points.current = []; lastSample.current = null; setPreview([]) }
        }}
        onPointerCancel={() => {
          draggingHandle.current = null
          drawing.current = false
          pointerId.current = null
          if (freehand) { points.current = []; lastSample.current = null; setPreview([]) }
        }}
        onLostPointerCapture={() => {
          if (pointerId.current === null) return
          pointerId.current = null
          draggingHandle.current = null
          drawing.current = false
          if (freehand) { points.current = []; lastSample.current = null; setPreview([]) }
        }}
        onDoubleClick={() => {
          if (mode === 'pen' && penAnchors.current.length > 2) { penAnchors.current.pop(); refreshPen() }
          if (mode === 'pen') commit()
        }}
      >
        {mode === 'pen' ? (
          <>
            <path d={penPath} fill="none" stroke="var(--studio-accent, #5aa9ff)" strokeWidth="3" pointerEvents="none" />
            {penVisual.map((anchor, index) => {
              const p = project(anchor.p)
              return <g key={index}>
                {(['hIn', 'hOut'] as const).map((kind) => {
                  const handle = anchor[kind]
                  if (!handle) return null
                  const h = project(handle)
                  return <g key={kind}>
                    <line x1={p[0]} y1={p[1]} x2={h[0]} y2={h[1]} stroke="#b2bbcb" strokeWidth="1" pointerEvents="none" />
                    <circle data-anchor-index={index} data-anchor-kind={kind} cx={h[0]} cy={h[1]} r="7" fill="#d7e3f5" stroke="#293548" strokeWidth="2" />
                  </g>
                })}
                <circle data-anchor-index={index} data-anchor-kind="p" cx={p[0]} cy={p[1]} r="9" fill="#ca50ea" stroke="#fff" strokeWidth="2" />
              </g>
            })}
          </>
        ) : mode === 'circle' && preview.length > 1 ? (
          <circle cx={preview[0][0]} cy={preview[0][1]} r={Math.hypot(preview[1][0] - preview[0][0], preview[1][1] - preview[0][1])} fill="none" stroke="var(--studio-accent, #5aa9ff)" strokeWidth="3" />
        ) : mode === 'rect' && preview.length > 1 ? (
          <rect x={Math.min(preview[0][0], preview[1][0])} y={Math.min(preview[0][1], preview[1][1])} width={Math.abs(preview[1][0] - preview[0][0])} height={Math.abs(preview[1][1] - preview[0][1])} fill="none" stroke="var(--studio-accent, #5aa9ff)" strokeWidth="3" />
        ) : (
          <polyline points={preview.map((p) => p.join(',')).join(' ')} fill="none" stroke="var(--studio-accent, #5aa9ff)" strokeWidth="3" />
        )}
      </svg>
      <div className="director-path-draw-hint absolute left-4 top-16 flex flex-wrap items-center gap-2 rounded-lg bg-[var(--studio-surface)] p-2">
        {freehand && <span>按住鼠标绘制，松开完成 · Esc 取消</span>}
        <span>{DIRECTOR_PATH_PLANES.find((item) => item.value === plane)?.label} · {plane === 'xz' ? '保持当前高度' : 'Y 轴上下移动'}</span>
        <Button onClick={commit}>完成路径</Button>
        <Button onClick={exit}>取消 Esc</Button>
      </div>
    </div>
  )
}

export function DirectorPathClipEditor({
  scene,
  onChange: onSceneChange,
  selectedClipId,
  onCloseEditor,
  onEditPen,
}: {
  scene: DirectorSceneData
  onChange: (s: DirectorSceneData) => void
  selectedClipId?: string
  onCloseEditor: () => void
  onEditPen?: (clip: DirectorPathClip) => void
}) {
  const { message } = App.useApp()
  const [waypointId, setWaypointId] = useState<string>()
  const [modal, modalHolder] = Modal.useModal()
  const timeline = normalizeDirectorTimeline(scene.timeline)
  const clips = timeline.pathClips ?? []
  const clip = clips.find((c) => c.id === selectedClipId)
  const activeWaypointId = clip?.waypoints.some((point) => point.id === waypointId) ? waypointId : clip?.waypoints[0]?.id
  const point = clip?.waypoints.find((item) => item.id === activeWaypointId)
  const onChange = (next: DirectorSceneData) => {
    const locked =
      scene.objects.find((o) => o.id === clip?.targetId)?.locked ||
      scene.cameraBookmarks?.find((b) => b.id === clip?.targetId)?.locked ||
      (clip?.targetId === 'main-camera' && scene.mainCameraLocked)
    if (locked) message.warning('请先解锁后再编辑轨道')
    else onSceneChange(next)
  }
  const update = (next: DirectorPathClip) =>
    onChange({
      ...scene,
      timeline: {
        ...timeline,
        duration: Math.max(timeline.duration, next.end),
        pathClips: clips.map((c) => (c.id === next.id ? next : c)),
      },
    })
  return (<>
      {modalHolder}
      <Modal
        open={!!clip}
        title="路径片段与航点"
        onCancel={onCloseEditor}
        footer={null}
        destroyOnHidden
        centered
        width="min(440px, calc(100vw - 32px))"
        className="director-path-editor-modal"
      >
        {clip && (
          <div className="director-path-editor">
            {clip.penAnchors && <Button onClick={() => onEditPen?.(clip)}>在视图中编辑钢笔曲线</Button>}
            {clip.follow && (
              <section className="director-control-section director-control-section--flat" aria-label="跟拍约束">
                <div className="director-section-title"><strong>跟拍约束</strong></div>
                <label className="director-field">
                  <span>跟拍目标</span>
                  <Select
                    aria-label="跟拍目标"
                    size="small"
                    value={clip.follow.targetObjectId}
                    options={scene.objects.filter((o) => o.kind === 'actor').map((o) => ({ value: o.id, label: o.name }))}
                    onChange={(targetObjectId) => update({ ...clip, follow: { ...clip.follow!, targetObjectId } })}
                  />
                </label>
                <label className="director-field">
                  <span>跟拍锚点</span>
                  <Select
                    aria-label="跟拍锚点"
                    size="small"
                    value={clip.follow.anchor}
                    options={['origin', 'chest', 'head'].map((value) => ({
                      value,
                      label: value === 'origin' ? '原点' : value === 'chest' ? '胸口' : '头部',
                    }))}
                    onChange={(anchor) => update({ ...clip, follow: { ...clip.follow!, anchor } })}
                  />
                </label>
                <label className="director-field">
                  <span>持续看向</span>
                  <Switch
                    size="small"
                    checked={clip.follow.continuousLookAt}
                    disabled={clip.kind === 'follow_static'}
                    onChange={(continuousLookAt) => update({ ...clip, follow: { ...clip.follow!, continuousLookAt } })}
                  />
                </label>
                {clip.kind === 'follow_relative' ? (
                  <div className="director-vector-control" aria-label="跟拍偏移">
                    <span>偏移</span>
                    {(['X', 'Y', 'Z'] as const).map((axis, i) => (
                      <label key={axis}>
                        {axis}
                        <InputNumber
                          size="small"
                          step={0.1}
                          value={clip.follow?.offset?.[i] ?? 0}
                          onChange={(v) => {
                            if (v === null) return
                            const offset = [...(clip.follow!.offset ?? [0, 0, 0])] as Vec
                            offset[i] = v
                            update({ ...clip, follow: { ...clip.follow!, offset } })
                          }}
                        />
                      </label>
                    ))}
                  </div>
                ) : null}
              </section>
            )}

            <section className="director-control-section director-control-section--flat" aria-label="片段时间">
              <div className="director-section-title"><strong>片段时间</strong></div>
              <label className="director-field">
                <span>开始秒</span>
                <InputNumber
                  size="small"
                  value={clip.start}
                  min={0}
                  step={0.1}
                  onChange={(v) => v !== null && update(retimeDirectorClip(clip, v, v + clip.end - clip.start))}
                />
              </label>
              <label className="director-field">
                <span>结束秒</span>
                <InputNumber
                  size="small"
                  value={Number(clip.end.toFixed(3))}
                  min={clip.start + 0.2}
                  step={0.1}
                  onChange={(v) => v !== null && update(retimeDirectorClip(clip, clip.start, v))}
                />
              </label>
            </section>

            <section className="director-control-section director-control-section--flat" aria-label="航点">
              <div className="director-section-title"><strong>航点</strong></div>
              <label className="director-field">
                <span>当前航点</span>
                <Select
                  aria-label="选择航点"
                  size="small"
                  value={activeWaypointId}
                  options={clip.waypoints.map((p) => ({ value: p.id, label: `◆ ${p.time.toFixed(2)} 秒` }))}
                  onChange={setWaypointId}
                />
              </label>
              {point ? (
                <>
                  <label className="director-field">
                    <span>航点时间</span>
                    <InputNumber
                      size="small"
                      min={clip.start}
                      max={clip.end}
                      step={0.1}
                      value={point.time}
                      onChange={(v) =>
                        v !== null &&
                        update({
                          ...clip,
                          waypoints: clip.waypoints
                            .map((p) => (p.id === point.id ? { ...p, time: v } : p))
                            .sort((a, b) => a.time - b.time),
                        })
                      }
                    />
                  </label>
                  <div className="director-vector-control" aria-label="航点坐标">
                    <span>坐标</span>
                    {(['X', 'Y', 'Z'] as const).map((axis, i) => (
                      <label key={axis}>
                        {axis}
                        <InputNumber
                          size="small"
                          value={Number(point.position[i].toFixed(3))}
                          step={0.1}
                          onChange={(v) => {
                            if (v === null) return
                            const position = [...point.position] as Vec
                            position[i] = v
                            update({
                              ...clip,
                              grounded: i === 1 ? false : clip.grounded,
                              waypoints: clip.waypoints.map((p) => (p.id === point.id ? { ...p, position } : p)),
                            })
                            if (i === 1 && clip.grounded) message.info('已关闭贴地，可做升降运镜')
                          }}
                        />
                      </label>
                    ))}
                  </div>
                  {clip.targetKind === 'camera' ? (
                    <>
                      <label className="director-field">
                        <span>瞬间对准</span>
                        <Select
                          size="small"
                          placeholder="仅该航点瞬间对准"
                          value={point.lookAtObjectId}
                          options={scene.objects.map((o) => ({ value: o.id, label: o.name }))}
                          onChange={(id) =>
                            update({
                              ...clip,
                              waypoints: clip.waypoints.map((p) =>
                                p.id === point.id
                                  ? {
                                      ...p,
                                      lookAt: directorAnchor(
                                        applyDirectorTimelineAt(scene, p.time).objects.find((o) => o.id === id)!,
                                      ),
                                      lookAtObjectId: id,
                                    }
                                  : p,
                              ),
                            })
                          }
                        />
                      </label>
                      <label className="director-field">
                        <span>FOV</span>
                        <InputNumber
                          size="small"
                          min={1}
                          max={170}
                          value={point.fov}
                          onChange={(v) =>
                            v !== null &&
                            update({
                              ...clip,
                              waypoints: clip.waypoints.map((p) => (p.id === point.id ? { ...p, fov: v } : p)),
                            })
                          }
                        />
                      </label>
                    </>
                  ) : null}
                </>
              ) : null}
            </section>

            <div className="director-path-editor__actions">
              {point ? (
                <Button
                  danger
                  block
                  onClick={() => {
                    const points = clip.waypoints.filter((p) => p.id !== point.id)
                    onChange({
                      ...scene,
                      timeline: {
                        ...timeline,
                        pathClips:
                          points.length < 2
                            ? clips.filter((c) => c.id !== clip.id)
                            : clips.map((c) => (c.id === clip.id ? { ...c, waypoints: points } : c)),
                      },
                    })
                    setWaypointId(points[0]?.id)
                  }}
                >
                  删除航点
                </Button>
              ) : null}
              {clip.kind !== 'path' ? (
                <Button
                  block
                  onClick={() =>
                    modal.confirm({
                      title: '转换为可编辑路径后，将失去自动跟随，确定？',
                      onOk: () => update(bakeDirectorFollow(scene, clip)),
                    })
                  }
                >
                  转为路径片段
                </Button>
              ) : null}
              <Button
                danger
                block
                onClick={() =>
                  onChange({ ...scene, timeline: { ...timeline, pathClips: clips.filter((c) => c.id !== clip.id) } })
                }
              >
                删除片段
              </Button>
            </div>
          </div>
        )}
      </Modal>
    </>
  )
}
