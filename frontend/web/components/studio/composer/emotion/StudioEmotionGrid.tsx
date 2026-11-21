'use client'
import { useRef, useState, type PointerEvent } from 'react'
import { emotionDotVisual } from '@/utils/studio/studioEmotionGridVisual'
import { emotionGridPoint, STUDIO_EMOTION_LABELS } from '@/utils/studio/studioEmotionGrid'
export function StudioEmotionGrid({
  affinity,
  arousal,
  onChange,
  onPreview
}: {
  affinity: number
  arousal: number
  onPreview: (point: { affinity: number; arousal: number } | null) => void
  onChange: (affinity: number, arousal: number) => void
}) {
  const pointer = useRef<number | null>(null)
  const [dragPoint, setDragPoint] = useState<{ affinity: number; arousal: number } | null>(null)
  const selected = emotionGridPoint(affinity, arousal)
  const move = (e: PointerEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect()
    const raw = {
      affinity: Math.max(-1, Math.min(1, ((e.clientX - rect.left) / rect.width) * 2 - 1)),
      arousal: Math.max(-1, Math.min(1, 1 - ((e.clientY - rect.top) / rect.height) * 2))
    }
    setDragPoint(raw)
    onPreview(raw)
    const point = emotionGridPoint(raw.affinity, raw.arousal)
    if (point.affinity !== affinity || point.arousal !== arousal) onChange(point.affinity, point.arousal)
  }
  const finish = () => {
    pointer.current = null
    setDragPoint(null)
    onPreview(null)
  }
  const cursor = dragPoint ?? selected

  return (
    <div className="studio-emotion-grid-wrap">
      <span className="studio-emotion-axis is-top">激动</span>
      <span className="studio-emotion-axis is-bottom">平静</span>
      <span className="studio-emotion-axis is-left">亲近</span>
      <span className="studio-emotion-axis is-right">疏离</span>
      <div
        className={`studio-emotion-grid${dragPoint ? ' is-dragging' : ''}`}
        role="group"
        aria-label="情绪坐标，方向键调整"
        onPointerDown={(e) => {
          if (e.button !== 0) return
          e.preventDefault()
          pointer.current = e.pointerId
          e.currentTarget.setPointerCapture(e.pointerId)
          move(e)
        }}
        onPointerMove={(e) => {
          if (pointer.current === e.pointerId) move(e)
        }}
        onPointerUp={(e) => {
          if (pointer.current !== e.pointerId) return
          move(e)
          finish()
          e.currentTarget.releasePointerCapture(e.pointerId)
        }}
        onPointerCancel={finish}
        onLostPointerCapture={finish}
        onKeyDown={(e) => {
          const delta = {
            ArrowLeft: [-0.5, 0],
            ArrowRight: [0.5, 0],
            ArrowUp: [0, 0.5],
            ArrowDown: [0, -0.5]
          }[e.key]
          if (!delta) return
          e.preventDefault()
          const next = emotionGridPoint(affinity + delta[0], arousal + delta[1])
          onChange(next.affinity, next.arousal)
          e.currentTarget
            .querySelector<HTMLButtonElement>(`[data-row="${next.row}"][data-column="${next.column}"]`)
            ?.focus()
        }}
      >
        {STUDIO_EMOTION_LABELS.flatMap((row, y) =>
          row.map((label, x) => (
            <button
              type="button"
              key={`${y}-${x}`}
              data-row={y}
              data-column={x}
              className={`studio-emotion-dot${emotionDotVisual(x, y, cursor.affinity, cursor.arousal).highlighted ? ' is-axis' : ''}`}
              aria-label={label}
              aria-pressed={selected.row === y && selected.column === x}
              tabIndex={selected.row === y && selected.column === x ? 0 : -1}
              style={{ left: `${x * 25}%`, top: `${y * 25}%` }}
              onClick={(event) => {
                if (event.detail === 0) onChange(x / 2 - 1, 1 - y / 2)
              }}
            >
              <i
                style={{
                  transform: `scale(${emotionDotVisual(x, y, cursor.affinity, cursor.arousal).scale})`
                }}
              />
            </button>
          ))
        )}
        <span
          className="studio-emotion-cursor"
          aria-hidden
          style={{ left: `${(cursor.affinity + 1) * 50}%`, top: `${(1 - cursor.arousal) * 50}%` }}
        />
      </div>
    </div>
  )
}
