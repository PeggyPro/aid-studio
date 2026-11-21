'use client'

import { useCallback, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import type { StudioVideoClipRange } from '@/types/studio'
import { StudioVideoFilmstrip } from './StudioVideoFilmstrip'
import { editVideoRange } from '@/utils/studio/studioVideoEditing'
import {
  createStudioVideoClipRange,
  moveStudioVideoClipRange,
  normalizeStudioVideoClipRange
} from '@/utils/studio/studioVideoClip'
import './studio-video-clip.css'

type DragState = {
  mode: 'start' | 'end' | 'move'
  pointerId: number
  offsetSeconds: number
  originX: number
  range: StudioVideoClipRange
}

export function StudioVideoClipTimeline({
  videoUrl,
  value,
  onChange,
  selected = true,
  maxClips = 5,
  showCount = true,
  snapToSeconds = false,
  playheadSeconds,
  className = ''
}: {
  videoUrl: string
  value: StudioVideoClipRange
  onChange: (range: StudioVideoClipRange) => void
  selected?: boolean
  maxClips?: number
  showCount?: boolean
  snapToSeconds?: boolean
  playheadSeconds?: number
  className?: string
}) {
  const trackRef = useRef<HTMLDivElement>(null)
  const dragRef = useRef<DragState | null>(null)
  const detectedDurationRef = useRef(value.durationSeconds)
  const [dragging, setDragging] = useState(false)
  const [detectedDuration, setDetectedDuration] = useState<number | null>(null)
  const duration = Math.max(0.1, (detectedDuration ?? value.durationSeconds) || 10)
  const normalized = normalizeStudioVideoClipRange(value, duration)
  const left = (normalized.startSeconds / duration) * 100
  const width = ((normalized.endSeconds - normalized.startSeconds) / duration) * 100
  const receiveDuration = useCallback((nextDuration: number) => {
    if (!Number.isFinite(nextDuration) || nextDuration <= 0 || Math.abs(nextDuration - detectedDurationRef.current) < 0.05) return
    detectedDurationRef.current = nextDuration
    setDetectedDuration(nextDuration)
    if (!selected) return
    const isInitialRange = value.startSeconds === 0 && value.endSeconds === value.durationSeconds
    onChange(isInitialRange ? createStudioVideoClipRange(nextDuration) : normalizeStudioVideoClipRange(value, nextDuration))
  }, [onChange, selected, value])

  const secondsAt = (clientX: number) => {
    const rect = trackRef.current?.getBoundingClientRect()
    if (!rect) return 0
    return Math.min(duration, Math.max(0, ((clientX - rect.left) / Math.max(1, rect.width)) * duration))
  }

  const updateDrag = (clientX: number, precise = false) => {
    const drag = dragRef.current
    if (!drag) return
    const seconds = secondsAt(drag.originX + (clientX - drag.originX) * (precise ? 0.1 : 1))
    onChange(editVideoRange(drag.range, drag.mode,
      drag.mode === 'move' ? seconds - drag.offsetSeconds : seconds,
      snapToSeconds && !precise))
  }

  const startDrag = (
    event: ReactPointerEvent<HTMLElement>,
    mode: DragState['mode']
  ) => {
    if (event.button !== 0) return
    event.preventDefault()
    event.stopPropagation()
    event.currentTarget.setPointerCapture(event.pointerId)
    const seconds = secondsAt(event.clientX)
    dragRef.current = {
      mode,
      pointerId: event.pointerId,
      originX: event.clientX,
      range: normalized,
      offsetSeconds: mode === 'move' ? seconds - normalized.startSeconds : 0
    }
    setDragging(true)
  }

  const finishDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (dragRef.current?.pointerId !== event.pointerId) return
    updateDrag(event.clientX, event.shiftKey)
    dragRef.current = null
    setDragging(false)
  }

  return (
    <div className={`studio-video-clip${dragging ? ' is-dragging' : ''}${showCount ? '' : ' without-count'}${className ? ` ${className}` : ''}`}>
      <div
        ref={trackRef}
        className="studio-video-clip__track"
        onPointerDown={(event) => {
          if ((event.target as HTMLElement).closest('.studio-video-clip__selection')) return
          const span = Math.min(duration, Math.max(0.5, normalized.endSeconds - normalized.startSeconds))
          const centered = moveStudioVideoClipRange(
            { ...normalized, endSeconds: normalized.startSeconds + span },
            secondsAt(event.clientX) - span / 2
          )
          onChange(editVideoRange(centered, 'move', centered.startSeconds, snapToSeconds && !event.shiftKey))
        }}
      >
        <div className="studio-video-clip__frames">
          <StudioVideoFilmstrip key={videoUrl} url={videoUrl} onDuration={receiveDuration} />
        </div>
        <span className="studio-video-clip__hover-hint">点击可截取片段</span>
        {playheadSeconds != null ? <i className="studio-video-clip__playhead" style={{ left: `${Math.min(100, Math.max(0, playheadSeconds / duration * 100))}%` }} /> : null}
        {selected ? (
          <div
            className="studio-video-clip__selection"
            style={{ left: `${left}%`, width: `${width}%` }}
            onPointerDown={(event) => startDrag(event, 'move')}
            onPointerMove={(event) => {
              if (dragRef.current?.pointerId === event.pointerId) updateDrag(event.clientX, event.shiftKey)
            }}
            onPointerUp={finishDrag}
            onPointerCancel={() => {
              dragRef.current = null
              setDragging(false)
            }}
          >
            <button
              type="button"
              className="studio-video-clip__handle is-start"
              aria-label="拖动片段开始时间"
              onPointerDown={(event) => startDrag(event, 'start')}
            />
            <span className="studio-video-clip__duration">
              {(normalized.endSeconds - normalized.startSeconds).toFixed(1)} 秒
            </span>
            <button
              type="button"
              className="studio-video-clip__handle is-end"
              aria-label="拖动片段结束时间"
              onPointerDown={(event) => startDrag(event, 'end')}
            />
          </div>
        ) : null}
      </div>
      {showCount ? <span className="studio-video-clip__count">{selected ? 1 : 0}/{maxClips} 个片段</span> : null}
    </div>
  )
}
