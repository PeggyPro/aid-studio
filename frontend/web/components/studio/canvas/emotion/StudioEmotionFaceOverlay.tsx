'use client'

import { useEffect, useRef, useState, type PointerEvent } from 'react'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioEmotionFaceRect, StudioEmotionSession } from '@/types/studio'
import { studioId } from '@/utils/studio/studioGraph'
import { detectEmotionFaces, normalizeEmotionRect } from '@/utils/studio/studioEmotionSession'
import './StudioEmotionFaceOverlay.css'

const rectStyle = (r: StudioEmotionFaceRect) => ({
  left: `${r.x * 100}%`,
  top: `${r.y * 100}%`,
  width: `${r.w * 100}%`,
  height: `${r.h * 100}%`
})

export function StudioEmotionFaceOverlay({ session }: { session: StudioEmotionSession }) {
  const root = useRef<HTMLDivElement>(null)
  const drag = useRef<{ x: number; y: number; pointerId: number } | null>(null)
  const [draft, setDraft] = useState<StudioEmotionFaceRect | null>(null)
  const [bounds, setBounds] = useState({ left: 0, top: 0, width: 0, height: 0 })
  const patch = useStudioUiStore((s) => s.patchEmotionSession)
  const close = useStudioUiStore((s) => s.closeEmotionSession)
  useEffect(() => {
    // Generation content can be display: contents: its DOM parent has no box.
    // Discover the image in the stable media stage, but position against the
    // overlay's actual CSS containing block (which can change after reveal).
    const stage = root.current?.closest('.studio-node-card__media-stage')
    if (!stage) return
    let img: HTMLImageElement | null = null
    const measure = () => {
      const host = root.current?.offsetParent
      if (!(host instanceof HTMLElement) || !img) return
      // Offset sizes stay in canvas coordinates; pointer conversion uses screen bounds.
      const hostBox = host.getBoundingClientRect()
      const imageBox = img.getBoundingClientRect()
      if (!host.offsetWidth || !host.offsetHeight || !hostBox.width || !hostBox.height || !imageBox.width || !imageBox.height) return
      const scaleX = hostBox.width / host.offsetWidth
      const scaleY = hostBox.height / host.offsetHeight
      const fit = getComputedStyle(img).objectFit
      const ratio = img.naturalWidth / img.naturalHeight
      let width = imageBox.width,
        height = imageBox.height
      if ((fit === 'contain' || fit === 'cover') && ratio > 0) {
        width = fit === 'cover' ? Math.max(width, height * ratio) : Math.min(width, height * ratio)
        height = width / ratio
      }
      const nextBounds = {
        left: (imageBox.left - hostBox.left + (imageBox.width - width) / 2) / scaleX - host.clientLeft + host.scrollLeft,
        top: (imageBox.top - hostBox.top + (imageBox.height - height) / 2) / scaleY - host.clientTop + host.scrollTop,
        width: width / scaleX,
        height: height / scaleY
      }
      setBounds((previous) =>
        Object.keys(nextBounds).every(
          (key) => previous[key as keyof typeof previous] === nextBounds[key as keyof typeof nextBounds]
        )
          ? previous
          : nextBounds
      )
    }
    const observer = new ResizeObserver(measure)
    observer.observe(stage)
    const attach = () => {
      const next = stage.querySelector<HTMLImageElement>('img[data-studio-source-image]')
      if (next !== img) {
        if (img) {
          img.removeEventListener('load', measure)
          observer.unobserve(img)
        }
        img = next
        if (img) {
          img.addEventListener('load', measure)
          observer.observe(img)
        }
      }
      measure()
    }
    const children = new MutationObserver(attach)
    children.observe(stage, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style', 'src'] })
    attach()
    return () => {
      children.disconnect()
      observer.disconnect()
      img?.removeEventListener('load', measure)
    }
  }, [session.id])
  useEffect(() => {
    const controller = new AbortController()
    const applyFaces = (faces: StudioEmotionSession['faces'] | null, complete: boolean) => {
      const current = useStudioUiStore.getState().emotionSession
      if (
        controller.signal.aborted ||
        current?.id !== session.id ||
        current.phase !== 'picking' ||
        current.faces.some((face) => face.source === 'manual')
      )
        return
      const detectionStatus = !complete ? 'pending' : faces === null ? 'failed' : faces.length ? 'ready' : 'empty'
      // A completed automatic pass never replaces a user's active manual gesture.
      if (current.manualMode) {
        patch(session.id, { detectionStatus })
        return
      }
      patch(session.id, { faces: faces ?? [], detectionStatus, manualMode: complete && !faces?.length })
    }
    void detectEmotionFaces(session.sourceUrl, controller.signal, (faces) => applyFaces(faces, false))
      .then((faces) => applyFaces(faces, true))
    return () => controller.abort()
  }, [patch, session.id, session.sourceUrl])
  useEffect(() => {
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation()
        close(session.id)
      }
    }
    window.addEventListener('keydown', escape, true)
    return () => window.removeEventListener('keydown', escape, true)
  }, [close, session.id])
  const point = (event: PointerEvent<HTMLDivElement>) => {
    const b = event.currentTarget.getBoundingClientRect()
    return {
      x: Math.max(0, Math.min(1, (event.clientX - b.left) / b.width)),
      y: Math.max(0, Math.min(1, (event.clientY - b.top) / b.height))
    }
  }
  const makeRect = (event: PointerEvent<HTMLDivElement>) => {
    if (!drag.current) return null
    const end = point(event)
    return normalizeEmotionRect({
      x: drag.current.x,
      y: drag.current.y,
      w: end.x - drag.current.x,
      h: end.y - drag.current.y
    })
  }
  return (
    <div
      ref={root}
      className="studio-emotion-overlay nodrag nopan nowheel"
      data-manual-mode={session.manualMode || undefined}
      style={bounds}
      onClick={(e) => e.stopPropagation()}
      onDoubleClick={(e) => e.stopPropagation()}
      onPointerDown={(e) => {
        e.stopPropagation()
        if (!session.manualMode || e.button !== 0) return
        e.preventDefault()
        drag.current = { ...point(e), pointerId: e.pointerId }
        e.currentTarget.setPointerCapture(e.pointerId)
      }}
      onPointerMove={(e) => {
        if (drag.current?.pointerId === e.pointerId) setDraft(makeRect(e))
      }}
      onPointerUp={(e) => {
        if (drag.current?.pointerId !== e.pointerId) return
        const rect = makeRect(e)
        drag.current = null
        setDraft(null)
        e.currentTarget.releasePointerCapture(e.pointerId)
        if (!rect) return
        const face = {
          id: studioId('face'),
          label: `角色${session.faces.filter((f) => f.source !== 'default').length + 1}`,
          rect,
          source: 'manual' as const,
          affinity: 0,
          arousal: 0
        }
        patch(session.id, {
          faces: [...session.faces.filter((f) => f.source !== 'default'), face],
          selectedFaceId: face.id,
          phase: 'editing',
          manualMode: false
        })
      }}
      onPointerCancel={() => {
        drag.current = null
        setDraft(null)
      }}
      onLostPointerCapture={() => {
        drag.current = null
        setDraft(null)
      }}
    >
      {!session.manualMode &&
        session.faces.map((face) => (
          <button
            type="button"
            key={face.id}
            className={`studio-emotion-face${session.selectedFaceId === face.id ? ' is-selected' : ''}`}
            style={rectStyle(face.rect)}
            aria-label={`选择${face.label}${face.source === 'default' ? '（默认框，可手动调整）' : ''}`}
            aria-pressed={session.selectedFaceId === face.id}
            onPointerDown={(e) => e.stopPropagation()}
            onClick={() => patch(session.id, { selectedFaceId: face.id, phase: 'editing' })}
          >
            <span>
              {face.label}
              {face.source === 'default' ? ' · 默认框' : ''}
            </span>
          </button>
        ))}
      {draft && <div className="studio-emotion-face is-draft" style={rectStyle(draft)} />}
    </div>
  )
}
