'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import {
  clampFocal,
  clampVertical,
  cubeRotationFromAngles,
  normalizeHorizontal
} from './multiAngleSceneMath'

export interface MultiAngleCubeSceneProps {
  imageUrl: string
  horizontal: number
  vertical: number
  focal: number
  onCameraMove: (horizontal: number, vertical: number) => void
  onReady?: () => void
}

const DRAG_HORIZONTAL_SENS = 0.42
const DRAG_VERTICAL_SENS = 0.34

const FACE_LABELS = {
  right: 'R',
  left: 'L',
  top: 'T',
  bottom: 'B'
} as const

export function StudioMultiAngleCubeScene({
  imageUrl,
  horizontal,
  vertical,
  focal,
  onCameraMove,
  onReady
}: MultiAngleCubeSceneProps) {
  const stageRef = useRef<HTMLDivElement>(null)
  const dragRef = useRef<{
    pointerId: number
    startX: number
    startY: number
    startH: number
    startV: number
  } | null>(null)
  const [dragging, setDragging] = useState(false)
  const readyNotifiedRef = useRef(false)

  const zoom = 0.92 + (clampFocal(focal) - 5) * 0.03
  const { rotateX, rotateY } = cubeRotationFromAngles(horizontal, vertical)

  useEffect(() => {
    readyNotifiedRef.current = false
  }, [imageUrl])

  useEffect(() => {
    if (readyNotifiedRef.current) return
    readyNotifiedRef.current = true
    onReady?.()
  }, [imageUrl, onReady])

  const finishDrag = useCallback((pointerId: number) => {
    const stage = stageRef.current
    if (!stage || dragRef.current?.pointerId !== pointerId) return
    dragRef.current = null
    setDragging(false)
    try { stage.releasePointerCapture(pointerId) } catch { /* ignore */ }
  }, [])

  const handlePointerDown = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return
    event.preventDefault()
    event.currentTarget.setPointerCapture(event.pointerId)
    dragRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      startH: normalizeHorizontal(horizontal),
      startV: clampVertical(vertical)
    }
    setDragging(true)
  }, [horizontal, vertical])

  const handlePointerMove = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== event.pointerId) return
    event.preventDefault()
    const dx = event.clientX - drag.startX
    const dy = event.clientY - drag.startY
    onCameraMove(
      normalizeHorizontal(drag.startH + dx * DRAG_HORIZONTAL_SENS),
      clampVertical(drag.startV - dy * DRAG_VERTICAL_SENS)
    )
  }, [onCameraMove])

  const handlePointerUp = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    finishDrag(event.pointerId)
  }, [finishDrag])

  const handlePointerCancel = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    finishDrag(event.pointerId)
  }, [finishDrag])

  return (
    <div
      ref={stageRef}
      className={`multi-angle-cube-stage${dragging ? ' is-dragging' : ''}`}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onPointerCancel={handlePointerCancel}
      role="presentation"
    >
      <div className="multi-angle-cube-pivot">
        <div
          className="multi-angle-cube"
          style={{
            transform: `rotateX(${rotateX}deg) rotateY(${rotateY}deg) scale(${zoom})`,
            transition: dragging ? 'none' : 'transform 0.18s ease-out'
          }}
        >
          <div className="multi-angle-cube-face multi-angle-cube-face--front multi-angle-cube-face--image">
            {imageUrl ? (
              <img src={imageUrl} alt="" draggable={false} decoding="async" />
            ) : (
              <span className="multi-angle-cube-face__placeholder" aria-hidden />
            )}
          </div>
          {/* 背面贴同一张图（水平镜像），背面视角仍能看到正面内容 */}
          <div className="multi-angle-cube-face multi-angle-cube-face--back multi-angle-cube-face--image multi-angle-cube-face--mirror">
            {imageUrl ? (
              <img src={imageUrl} alt="" draggable={false} decoding="async" />
            ) : (
              <span className="multi-angle-cube-face__placeholder" aria-hidden />
            )}
          </div>
          <div className="multi-angle-cube-face multi-angle-cube-face--right multi-angle-cube-face--glass">
            <span className="multi-angle-cube-face__label">{FACE_LABELS.right}</span>
          </div>
          <div className="multi-angle-cube-face multi-angle-cube-face--left multi-angle-cube-face--glass">
            <span className="multi-angle-cube-face__label">{FACE_LABELS.left}</span>
          </div>
          <div className="multi-angle-cube-face multi-angle-cube-face--top multi-angle-cube-face--glass">
            <span className="multi-angle-cube-face__label">{FACE_LABELS.top}</span>
          </div>
          <div className="multi-angle-cube-face multi-angle-cube-face--bottom multi-angle-cube-face--glass">
            <span className="multi-angle-cube-face__label">{FACE_LABELS.bottom}</span>
          </div>
        </div>
      </div>
    </div>
  )
}
