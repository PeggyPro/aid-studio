'use client'

import { useRef, useState } from 'react'
import type { PrevizScene, PrevizShot } from '@/types/previz'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import type { PrevizShotEdge } from '@/utils/previz/shotEditing'
import './previz-shot-clip.css'

export function PrevizShotClip({ scene, shot, index, scaleFrames, selected, onSelect, onResize, onInteractionStart }: {
  scene: PrevizScene; shot: PrevizShot; index: number; scaleFrames: number; selected: boolean
  onSelect: () => void
  onResize: (id: string, edge: PrevizShotEdge, frame: number, source: PrevizScene) => void
  onInteractionStart?: () => void
}) {
  const [preview, setPreview] = useState<{ edge: PrevizShotEdge; frame: number } | null>(null)
  const drag = useRef<{ pointer: number; edge: PrevizShotEdge; x: number; initial: number; frame: number; pixels: number; min: number; max: number; commit: (frame: number) => void } | null>(null)
  const cancel = () => { drag.current = null; setPreview(null) }
  const last = index === scene.shots.length - 1
  const bounds = (edge: PrevizShotEdge) => edge === 'start'
    ? { min: index ? scene.shots[index - 1].startFrame + 1 : 0, max: index ? shot.endFrameExclusive - 1 : 0 }
    : { min: Math.max(shot.startFrame + 1, last ? 30 : 1), max: last ? scaleFrames : scene.shots[index + 1].endFrameExclusive - 1 }
  const handle = (edge: PrevizShotEdge) => {
    const value = edge === 'start' ? shot.startFrame : shot.endFrameExclusive
    const { min, max } = bounds(edge)
    return <StudioCanvasTooltip title={edge === 'start' && !index ? '首镜从第 0 帧开始' : '拖动调整镜头边界，方向键微调，Shift 十帧'}>
      <button type="button" className={`pv-shot-handle is-${edge}`} role="slider" aria-label={`调整镜头 ${index + 1} ${edge === 'start' ? '开始' : '结束'}`}
        aria-valuemin={min} aria-valuemax={max} aria-valuenow={preview?.edge === edge ? preview.frame : value} disabled={min === max}
        onPointerDown={event => {
          if (event.button !== 0 || min === max) return
          event.preventDefault(); event.stopPropagation()
          onInteractionStart?.()
          const width = event.currentTarget.closest('.pv-shot-lane')!.getBoundingClientRect().width
          if (!width) return
          drag.current = { pointer: event.pointerId, edge, x: event.clientX, initial: value, frame: value, pixels: width / scaleFrames, min, max,
            commit: frame => onResize(shot.id, edge, frame, scene) }
          event.currentTarget.setPointerCapture(event.pointerId)
        }}
        onPointerMove={event => {
          const current = drag.current
          if (!current || current.pointer !== event.pointerId) return
          current.frame = Math.max(current.min, Math.min(current.max, Math.round(current.initial + (event.clientX - current.x) / current.pixels)))
          setPreview({ edge: current.edge, frame: current.frame })
        }}
        onPointerUp={event => {
          const current = drag.current
          if (!current || current.pointer !== event.pointerId) return
          cancel()
          if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId)
          if (current.frame !== current.initial) current.commit(current.frame)
        }} onPointerCancel={cancel} onLostPointerCapture={cancel}
        onKeyDown={event => {
          if (event.key === 'Escape') { event.preventDefault(); cancel(); return }
          if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return
          event.preventDefault(); event.stopPropagation()
          onResize(shot.id, edge, Math.max(min, Math.min(max, value + (event.key === 'ArrowLeft' ? -1 : 1) * (event.shiftKey ? 10 : 1))), scene)
        }} />
    </StudioCanvasTooltip>
  }
  const start = preview?.edge === 'start' ? preview.frame : shot.startFrame
  const end = preview?.edge === 'end' ? preview.frame : shot.endFrameExclusive
  return <div className={`pv-shot-clip${selected ? ' is-selected' : ''}${preview ? ' is-dragging' : ''}`} style={{ left: start / scaleFrames * 100 + '%', right: (1 - end / scaleFrames) * 100 + '%' }}>
    <button type="button" className="pv-shot-select" aria-label={`镜头 ${index + 1} ${shot.name}`} aria-pressed={selected} onClick={onSelect}>
      <span>{String(index + 1).padStart(2, '0')}</span><strong>{shot.name}</strong>
    </button>{handle('start')}{handle('end')}
  </div>
}
