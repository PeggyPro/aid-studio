'use client'

import { useRef, type CSSProperties, type ReactNode } from 'react'
import { useDirectorPlaybackStore } from './directorPlaybackStore'

interface ClipDrag {
  pointerId: number
  clientX: number
  start: number
  end: number
  scale: number
  resize: boolean
  moved: boolean
}

function clipRange(drag: Pick<ClipDrag, 'start' | 'end' | 'resize'>, delta: number, minimumDuration: number) {
  if (drag.resize) return { start: drag.start, end: Math.max(drag.start + minimumDuration, drag.end + delta) }
  const start = Math.max(0, drag.start + delta)
  return { start, end: start + (drag.end - drag.start) }
}

export function DirectorTimelineClip({
  start, end, scale, minimumDuration, label, children, locked, action, selected, onSelect, onRetime
}: {
  start: number
  end: number
  scale: number
  minimumDuration: number
  label: string
  children: ReactNode
  locked?: boolean
  action?: boolean
  selected?: boolean
  onSelect: () => void
  onRetime: (start: number, end: number) => void
}) {
  const drag = useRef<ClipDrag | null>(null)
  const suppressClick = useRef(false)
  const clearPreview = (element: HTMLButtonElement) => {
    element.style.removeProperty('--director-clip-offset')
    element.style.removeProperty('--director-clip-preview-width')
  }
  const commit = (range: { start: number; end: number }) => {
    if (Math.abs(range.start - start) > 0.00001 || Math.abs(range.end - end) > 0.00001) {
      onRetime(range.start, range.end)
    }
  }

  return <button type="button"
    className={`director-timeline__clip${action ? ' director-timeline__clip--action' : ''}${selected ? ' is-selected' : ''}`}
    style={{ left: start * scale, '--director-clip-width': Math.max(18, (end - start) * scale) + 'px' } as CSSProperties}
    aria-label={label}
    onClick={event => {
      event.stopPropagation()
      if (event.detail > 0 && suppressClick.current) return
      onSelect()
    }}
    onKeyDown={event => {
      if (locked || (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight')) return
      event.preventDefault()
      event.stopPropagation()
      const delta = (event.key === 'ArrowRight' ? 1 : -1) * (event.shiftKey ? 1 : 1 / 30)
      commit(clipRange({ start, end, resize: event.altKey }, delta, minimumDuration))
    }}
    onPointerDown={event => {
      event.stopPropagation()
      if (event.button !== 0 || !event.isPrimary || drag.current) return
      suppressClick.current = false
      if (locked) return
      const element = event.currentTarget
      const resize = event.clientX >= element.getBoundingClientRect().right - 13
      drag.current = { pointerId: event.pointerId, clientX: event.clientX, start, end, scale, resize, moved: false }
      element.setPointerCapture(event.pointerId)
    }}
    onPointerMove={event => {
      const active = drag.current
      if (active?.pointerId !== event.pointerId) return
      if (!active.moved) {
        if (Math.abs(event.clientX - active.clientX) < 4) return
        active.moved = true
        suppressClick.current = true
        useDirectorPlaybackStore.getState().setPlaying(false)
      }
      const range = clipRange(active, (event.clientX - active.clientX) / active.scale, minimumDuration)
      if (active.resize) {
        event.currentTarget.style.setProperty('--director-clip-preview-width', `${Math.max(18, (range.end - range.start) * active.scale)}px`)
      } else {
        event.currentTarget.style.setProperty('--director-clip-offset', `${(range.start - active.start) * active.scale}px`)
      }
    }}
    onPointerUp={event => {
      const active = drag.current
      if (active?.pointerId !== event.pointerId) return
      drag.current = null
      clearPreview(event.currentTarget)
      const moved = active.moved || Math.abs(event.clientX - active.clientX) >= 4
      if (moved) suppressClick.current = true
      if (moved && !locked && start === active.start && end === active.end) {
        commit(clipRange(active, (event.clientX - active.clientX) / active.scale, minimumDuration))
      }
    }}
    onLostPointerCapture={event => {
      if (drag.current?.pointerId !== event.pointerId) return
      drag.current = null
      clearPreview(event.currentTarget)
    }}
    onPointerCancel={event => {
      if (drag.current?.pointerId !== event.pointerId) return
      drag.current = null
      suppressClick.current = true
      clearPreview(event.currentTarget)
    }}
  ><span>{children}</span><i aria-hidden="true" /></button>
}
