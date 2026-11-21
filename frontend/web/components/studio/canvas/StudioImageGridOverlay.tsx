'use client'

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent
} from 'react'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioGridCellCoord, StudioImageEditSession } from '@/types/studio'
import {
  cellsEqual,
  cellsInRect,
  mergeCellSelection,
  pointerToGridCell,
  toggleCellSelection
} from '@/utils/studio/studioImageGridSession'
import type { StudioMaskSurfaceLayout } from '@/utils/studio/studioImageMaskOverlayLayout'
import '@/components/studio/composer/studio-image-ai-tools.css'

export function StudioImageGridOverlay({
  layout
}: {
  layout: StudioMaskSurfaceLayout | null
}) {
  const session = useStudioUiStore((state) => state.imageEditSession)
  const patchImageEditSession = useStudioUiStore((state) => state.patchImageEditSession)
  if (!session || session.mode !== 'grid' || !layout) return null
  if (session.splitMode === 'REGIONS') return <StudioImageRegionOverlay session={session} patchImageEditSession={patchImageEditSession} />
  return (
    <StudioImageGridOverlayInner
      session={session}
      patchImageEditSession={patchImageEditSession}
    />
  )
}

function StudioImageRegionOverlay({ session, patchImageEditSession }: {
  session: StudioImageEditSession
  patchImageEditSession: (patch: Partial<StudioImageEditSession>) => void
}) {
  const hostRef = useRef<HTMLDivElement>(null)
  const startRef = useRef<{ x: number; y: number } | null>(null)
  const point = (event: ReactPointerEvent) => {
    const box = hostRef.current?.getBoundingClientRect()
    if (!box) return null
    return {
      x: Math.max(0, Math.min(1, (event.clientX - box.left) / box.width)),
      y: Math.max(0, Math.min(1, (event.clientY - box.top) / box.height))
    }
  }
  return <div ref={hostRef} className="studio-image-grid-overlay nodrag nopan nowheel" style={{ width: '100%', height: '100%', position: 'relative' }}
    aria-label="拖动选择原图像素区域"
    onPointerDown={(event) => { event.preventDefault(); event.stopPropagation(); startRef.current = point(event); event.currentTarget.setPointerCapture(event.pointerId); patchImageEditSession({ regionSelected: false }) }}
    onPointerMove={(event) => {
      const start = startRef.current
      const end = point(event)
      if (!start || !end) return
      patchImageEditSession({ rect: { x: Math.min(start.x, end.x), y: Math.min(start.y, end.y), w: Math.abs(end.x - start.x), h: Math.abs(end.y - start.y) } })
    }}
    onPointerUp={(event) => {
      const start = startRef.current
      const end = point(event)
      startRef.current = null
      if (!start || !end) return
      const rect = { x: Math.min(start.x, end.x), y: Math.min(start.y, end.y), w: Math.abs(end.x - start.x), h: Math.abs(end.y - start.y) }
      patchImageEditSession({ rect, regionSelected: rect.w > 0 && rect.h > 0 })
    }}>
    {session.regionSelected ? <div className="studio-image-crop-overlay__frame" style={{ position: 'absolute', left: `${session.rect.x * 100}%`, top: `${session.rect.y * 100}%`, width: `${session.rect.w * 100}%`, height: `${session.rect.h * 100}%`, pointerEvents: 'none' }} /> : null}
    <div className="studio-image-grid-overlay__hint">拖动选择自定义裁切区域</div>
  </div>
}

function StudioImageGridOverlayInner({
  session,
  patchImageEditSession
}: {
  session: StudioImageEditSession
  patchImageEditSession: (patch: Partial<StudioImageEditSession>) => void
}) {
  const rows = Math.max(1, session.gridRows ?? 1)
  const columns = Math.max(1, session.gridColumns ?? 1)
  const selected = useMemo(() => session.selectedCells ?? [], [session.selectedCells])
  const hostRef = useRef<HTMLDivElement>(null)
  const dragRef = useRef<{ start: StudioGridCellCoord; additive: boolean } | null>(null)
  const [hoverCell, setHoverCell] = useState<StudioGridCellCoord | null>(null)
  const [dragPreview, setDragPreview] = useState<StudioGridCellCoord[] | null>(null)

  const localFromEvent = useCallback((event: ReactPointerEvent | PointerEvent) => {
    const host = hostRef.current
    if (!host) return null
    const rect = host.getBoundingClientRect()
    return {
      x: event.clientX - rect.left,
      y: event.clientY - rect.top,
      width: rect.width,
      height: rect.height
    }
  }, [])

  const pickCell = useCallback((event: ReactPointerEvent | PointerEvent) => {
    const local = localFromEvent(event)
    if (!local) return null
    return pointerToGridCell(local.x, local.y, local.width, local.height, rows, columns)
  }, [columns, localFromEvent, rows])

  useEffect(() => {
    const onMove = (event: PointerEvent) => {
      const drag = dragRef.current
      if (!drag) return
      const end = pickCell(event)
      if (!end) return
      const rectCells = cellsInRect(drag.start, end, rows, columns)
      setDragPreview(mergeCellSelection(drag.additive ? selected : [], rectCells, drag.additive))
    }
    const onUp = (event: PointerEvent) => {
      const drag = dragRef.current
      if (!drag) return
      dragRef.current = null
      const end = pickCell(event)
      if (!end) {
        setDragPreview(null)
        return
      }
      const rectCells = cellsInRect(drag.start, end, rows, columns)
      const isClick = cellsEqual(drag.start, end)
      const next = isClick
        ? toggleCellSelection(selected, end, drag.additive)
        : mergeCellSelection(selected, rectCells, drag.additive)
      patchImageEditSession({ selectedCells: next })
      setDragPreview(null)
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    return () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
    }
  }, [columns, patchImageEditSession, pickCell, rows, selected])

  const previewSet = dragPreview ?? selected

  const onPointerDown = (event: ReactPointerEvent) => {
    event.preventDefault()
    event.stopPropagation()
    const cell = pickCell(event)
    if (!cell) return
    if (event.shiftKey) {
      dragRef.current = { start: cell, additive: true }
      setDragPreview(mergeCellSelection(selected, [cell], true))
      return
    }
    dragRef.current = { start: cell, additive: false }
    setDragPreview([cell])
  }

  const onPointerMove = (event: ReactPointerEvent) => {
    const cell = pickCell(event)
    setHoverCell(cell)
  }

  return (
    <div
      ref={hostRef}
      className="studio-image-grid-overlay nodrag nopan nowheel"
      style={{ width: '100%', height: '100%' }}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerLeave={() => setHoverCell(null)}
    >
      <svg className="studio-image-grid-overlay__lines" aria-hidden viewBox={`0 0 ${columns} ${rows}`} preserveAspectRatio="none">
        {Array.from({ length: columns - 1 }, (_, index) => (
          <line key={`v-${index + 1}`} x1={index + 1} y1={0} x2={index + 1} y2={rows} />
        ))}
        {Array.from({ length: rows - 1 }, (_, index) => (
          <line key={`h-${index + 1}`} x1={0} y1={index + 1} x2={columns} y2={index + 1} />
        ))}
      </svg>
      <div
        className="studio-image-grid-overlay__cells"
        style={{ gridTemplateColumns: `repeat(${columns}, 1fr)`, gridTemplateRows: `repeat(${rows}, 1fr)` }}
      >
        {Array.from({ length: rows * columns }, (_, index) => {
          const row = Math.floor(index / columns)
          const col = index % columns
          const cell = { row, col }
          const active = previewSet.some((item) => cellsEqual(item, cell))
          const hovered = hoverCell && cellsEqual(hoverCell, cell)
          return (
            <div
              key={`${row}-${col}`}
              className={`studio-image-grid-overlay__cell${active ? ' is-selected' : ''}${hovered ? ' is-hover' : ''}`}
            >
              {hovered ? <span className="studio-image-grid-overlay__label">{row + 1}-{col + 1}</span> : null}
            </div>
          )
        })}
      </div>
      {!selected.length ? (
        <div className="studio-image-grid-overlay__hint">按住 Shift 键可批量选择</div>
      ) : null}
    </div>
  )
}
