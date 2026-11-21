'use client'

import { useMemo, useRef, type PointerEvent } from 'react'
import * as THREE from 'three'
import { NINE_GRID_SPHERE_CAMERA_POSITIONS } from '~/utils/nineGridCameraAngles'

export interface CameraPose { yaw: number; pitch: number }
const RADIUS = 2.35
const clampPitch = (pitch: number) => Math.max(-30, Math.min(60, pitch))
export const normalizeCameraYaw = (yaw: number) => ((yaw % 360) + 360) % 360

const camera = new THREE.PerspectiveCamera(42, 600 / 480, .1, 100)
camera.position.set(4.4, 2.7, 6.8)
camera.lookAt(0, 0, 0)
camera.updateMatrixWorld()

function project(x: number, y: number, z: number) {
  const point = new THREE.Vector3(x, y, z).project(camera)
  return { x: (point.x + 1) * 300, y: (1 - point.y) * 240, depth: point.z }
}
function markerPosition(pose: CameraPose) {
  const yaw = pose.yaw * Math.PI / 180, pitch = pose.pitch * Math.PI / 180
  return project(RADIUS * Math.cos(pitch) * Math.sin(yaw), RADIUS * Math.sin(pitch), RADIUS * Math.cos(pitch) * Math.cos(yaw))
}
function pathFor(points: Array<{x: number; y: number}>) {
  return points.map((point, index) => `${index ? 'L' : 'M'}${point.x.toFixed(2)} ${point.y.toFixed(2)}`).join(' ')
}

export function MultiAngleCameraPreview({ imageUrl, pose, fixed, onChange, onImageError }: {
  imageUrl: string
  pose: CameraPose
  fixed: boolean
  onChange: (pose: CameraPose) => void
  onImageError: () => void
}) {
  const drag = useRef<{ id: number; x: number; y: number; pose: CameraPose; direction: number } | null>(null)
  const grid = useMemo(() => {
    const lines: string[] = []
    for (let latitude = -60; latitude <= 60; latitude += 30) {
      const phi = latitude * Math.PI / 180
      lines.push(pathFor(Array.from({ length: 97 }, (_, index) => {
        const theta = index / 96 * Math.PI * 2
        return project(RADIUS * Math.cos(phi) * Math.sin(theta), RADIUS * Math.sin(phi), RADIUS * Math.cos(phi) * Math.cos(theta))
      })))
    }
    for (let longitude = 0; longitude < 180; longitude += 30) {
      const theta = longitude * Math.PI / 180
      lines.push(pathFor(Array.from({length: 97}, (_, index) => {
        const phi = index / 96 * Math.PI * 2
        return project(RADIUS * Math.sin(theta) * Math.cos(phi), RADIUS * Math.sin(phi), RADIUS * Math.cos(theta) * Math.cos(phi))
      })))
    }
    return lines
  }, [])
  const center = project(0, 0, 0)
  const position = markerPosition(pose)
  const markers = fixed
    ? NINE_GRID_SPHERE_CAMERA_POSITIONS.map(value => markerPosition({yaw: value.yaw, pitch: value.pitch}))
    : [position]

  function finish(event: PointerEvent<SVGSVGElement>) {
    if (drag.current?.id !== event.pointerId) return
    if (event.type === 'pointercancel' || event.type === 'lostpointercapture') onChange(drag.current.pose)
    drag.current = null
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId)
  }

  return <svg className={`ma-orbit${fixed ? ' ma-orbit--fixed' : ''}`} viewBox="0 0 600 480"
    aria-label="多机位示意，拖动相机或背景调整水平和垂直角度"
    onPointerDown={event => {
      if (fixed || drag.current || event.button !== 0 || !event.isPrimary) return
      const next = markerPosition({yaw: pose.yaw + 1, pitch: pose.pitch})
      drag.current = {id: event.pointerId, x: event.clientX, y: event.clientY, pose, direction: next.x >= position.x ? 1 : -1}
      event.currentTarget.setPointerCapture(event.pointerId)
      event.preventDefault()
    }}
    onPointerMove={event => {
      const active = drag.current
      if (!active || active.id !== event.pointerId) return
      const scale = 600 / Math.max(1, event.currentTarget.getBoundingClientRect().width)
      onChange({
        yaw: normalizeCameraYaw(Math.round(active.pose.yaw + (event.clientX - active.x) * scale * .55 * active.direction)),
        pitch: Math.round(clampPitch(active.pose.pitch - (event.clientY - active.y) * scale * .35))
      })
    }}
    onPointerUp={finish} onPointerCancel={finish} onLostPointerCapture={finish}>
    <g className="ma-orbit-grid" aria-hidden="true">{grid.map((path, index) => <path key={index} d={path} />)}</g>
    <g className="ma-orbit-sight" aria-hidden="true">{markers.map((point, index) =>
      <path key={index} d={`M${point.x} ${point.y} L${center.x - 45} ${center.y + 38} M${point.x} ${point.y} L${center.x + 45} ${center.y + 38}`} />)}</g>
    <foreignObject x={center.x - 82} y={center.y - 88} width="164" height="176">
      <div className="ma-reference">
        {imageUrl ? <img key={imageUrl} src={imageUrl} alt="当前多机位参考图" draggable={false} onError={onImageError} /> : <span>请选择参考图</span>}
        <small>参考图</small>
      </div>
    </foreignObject>
    {markers.map((point, index) => <g key={index} className="ma-camera-handle"
      transform={`translate(${point.x} ${point.y})`}
      role={fixed ? undefined : 'slider'} tabIndex={fixed ? undefined : 0}
      aria-label={fixed ? undefined : '拍摄机位，方向键调整，Home 重置'}
      aria-valuemin={fixed ? undefined : 0} aria-valuemax={fixed ? undefined : 359}
      aria-valuenow={fixed ? undefined : Math.round(normalizeCameraYaw(pose.yaw))}
      aria-valuetext={fixed ? undefined : `水平 ${Math.round(pose.yaw)} 度，垂直 ${pose.pitch} 度`}
      onKeyDown={event => {
        if (fixed) return
        const deltas: Record<string, CameraPose> = {ArrowLeft:{yaw:-5,pitch:0},ArrowRight:{yaw:5,pitch:0},ArrowUp:{yaw:0,pitch:5},ArrowDown:{yaw:0,pitch:-5}}
        const delta = deltas[event.key]
        if (!delta && event.key !== 'Home') return
        event.preventDefault()
        onChange(event.key === 'Home' ? {yaw:0,pitch:0} : {yaw:normalizeCameraYaw(pose.yaw + delta.yaw),pitch:clampPitch(pose.pitch + delta.pitch)})
      }}>
      <rect className="ma-camera-hit" x="-23" y="-23" width="46" height="46" rx="14" />
      <rect className="ma-camera-body" x="-15" y="-12" width="30" height="24" rx="7" />
      <path d="M-4-12-1-16H6L9-12 M15-5 21-8V8L15 5" />
      <circle r="6" />
      {fixed ? <text x="0" y="34">{index + 1}</text> : null}
    </g>)}
  </svg>
}
