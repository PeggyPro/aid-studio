'use client'

import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useImperativeHandle,
  useRef,
  forwardRef
} from 'react'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode, StudioImageEditSession, StudioImageMaskTool } from '@/types/studio'
import { STUDIO_MASK_BRUSH_DEFAULT } from '@/utils/studio/studioImageAiTools'
import {
  resolveStudioMaskCanvasSize,
  type StudioMaskSurfaceLayout
} from '@/utils/studio/studioImageMaskOverlayLayout'
import { registerStudioMaskOverlay, notifyStudioMaskOverlay } from '@/utils/studio/studioMaskOverlayRegistry'
import '@/components/studio/composer/studio-image-ai-tools.css'

export interface StudioImageMaskOverlayHandle {
  getMaskCanvas: () => HTMLCanvasElement | null
  undo: () => void
  redo: () => void
  canUndo: () => boolean
  canRedo: () => boolean
  hasPaint: () => boolean
  clear: () => void
}

const MAX_HISTORY = 24

export const StudioImageMaskOverlay = forwardRef<
  StudioImageMaskOverlayHandle,
  { nodeId: string; layout: StudioMaskSurfaceLayout | null }
>(function StudioImageMaskOverlay({ nodeId, layout }, ref) {
  const session = useStudioUiStore((state) => state.imageEditSession)
  const node = useStudioUiStore((state) => state.nodes.find((item) => item.id === nodeId))
  if (!session || (session.mode !== 'erase' && session.mode !== 'repaint') || session.nodeId !== nodeId || !node || !layout) {
    return null
  }
  return <StudioImageMaskOverlayInner ref={ref} node={node} session={session} layout={layout} />
})

const StudioImageMaskOverlayInner = forwardRef<
  StudioImageMaskOverlayHandle,
  { node: StudioCanvasNode; session: StudioImageEditSession; layout: StudioMaskSurfaceLayout }
>(function StudioImageMaskOverlayInner({ node, session, layout }, ref) {
  const hostRef = useRef<HTMLDivElement>(null)
  const displayRef = useRef<HTMLCanvasElement>(null)
  const maskRef = useRef<HTMLCanvasElement | null>(null)
  const maskSizeRef = useRef<{ width: number; height: number } | null>(null)
  const historyRef = useRef<ImageData[]>([])
  const historyIndexRef = useRef(-1)
  const drawingRef = useRef<{
    tool: StudioImageMaskTool
    lastX: number
    lastY: number
    startX: number
    startY: number
    snapshot: ImageData | null
  } | null>(null)
  const paintFrameRef = useRef<number | null>(null)

  const ensureMask = useCallback((screenW: number, screenH: number) => {
    const nextSize = resolveStudioMaskCanvasSize(screenW, screenH)
    const prevSize = maskSizeRef.current
    let canvas = maskRef.current
    if (
      !canvas
      || !prevSize
      || prevSize.width !== nextSize.width
      || prevSize.height !== nextSize.height
    ) {
      const prev = canvas
      canvas = document.createElement('canvas')
      canvas.width = nextSize.width
      canvas.height = nextSize.height
      maskRef.current = canvas
      maskSizeRef.current = nextSize
      const ctx = canvas.getContext('2d')
      if (ctx) {
        ctx.clearRect(0, 0, nextSize.width, nextSize.height)
        if (prev && prev.width > 0 && prev.height > 0) {
          ctx.drawImage(prev, 0, 0, nextSize.width, nextSize.height)
        }
        const snapshot = ctx.getImageData(0, 0, nextSize.width, nextSize.height)
        historyRef.current = [snapshot]
        historyIndexRef.current = 0
      } else {
        historyRef.current = []
        historyIndexRef.current = -1
      }
    }
    return canvas
  }, [])

  const displaySizeRef = useRef<{ w: number; h: number; dpr: number } | null>(null)

  const paintDisplay = useCallback(() => {
    const display = displayRef.current
    const mask = maskRef.current
    if (!display || !mask || layout.width < 1 || layout.height < 1) return
    const dpr = Math.min(2, window.devicePixelRatio || 1)
    const cssW = Math.max(1, Math.round(layout.width))
    const cssH = Math.max(1, Math.round(layout.height))
    const prev = displaySizeRef.current
    if (!prev || prev.w !== cssW || prev.h !== cssH || prev.dpr !== dpr) {
      display.width = Math.round(cssW * dpr)
      display.height = Math.round(cssH * dpr)
      display.style.width = `${cssW}px`
      display.style.height = `${cssH}px`
      displaySizeRef.current = { w: cssW, h: cssH, dpr }
    }
    const ctx = display.getContext('2d')
    if (!ctx) return
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, cssW, cssH)
    // LibTV 风格：先画半透明白色笔迹，再叠色调，避免 source-in 单步合成在部分环境下不可见
    ctx.save()
    ctx.globalAlpha = 0.55
    ctx.drawImage(mask, 0, 0, cssW, cssH)
    ctx.globalAlpha = 1
    ctx.globalCompositeOperation = 'source-atop'
    ctx.fillStyle = session.mode === 'repaint'
      ? 'rgba(255, 64, 180, 0.55)'
      : 'rgba(255, 92, 112, 0.5)'
    ctx.fillRect(0, 0, cssW, cssH)
    ctx.restore()
  }, [layout.height, layout.width, session.mode])

  const schedulePaintDisplay = useCallback((immediate = false) => {
    if (immediate) {
      if (paintFrameRef.current != null) {
        window.cancelAnimationFrame(paintFrameRef.current)
        paintFrameRef.current = null
      }
      paintDisplay()
      return
    }
    if (paintFrameRef.current != null) return
    paintFrameRef.current = window.requestAnimationFrame(() => {
      paintFrameRef.current = null
      paintDisplay()
    })
  }, [paintDisplay])

  useLayoutEffect(() => {
    ensureMask(layout.width, layout.height)
    schedulePaintDisplay(true)
  }, [ensureMask, layout.height, layout.width, schedulePaintDisplay])

  useEffect(() => () => {
    if (paintFrameRef.current != null) window.cancelAnimationFrame(paintFrameRef.current)
  }, [])

  const pushHistory = useCallback(() => {
    const canvas = maskRef.current
    const ctx = canvas?.getContext('2d', { willReadFrequently: true })
    if (!canvas || !ctx) return
    const snapshot = ctx.getImageData(0, 0, canvas.width, canvas.height)
    const trimmed = historyRef.current.slice(0, historyIndexRef.current + 1)
    trimmed.push(snapshot)
    if (trimmed.length > MAX_HISTORY) trimmed.shift()
    historyRef.current = trimmed
    historyIndexRef.current = trimmed.length - 1
    notifyStudioMaskOverlay()
  }, [])

  const applyHistory = useCallback((index: number) => {
    const canvas = maskRef.current
    const ctx = canvas?.getContext('2d')
    const snap = historyRef.current[index]
    if (!canvas || !ctx || !snap) return
    ctx.putImageData(snap, 0, 0)
    schedulePaintDisplay()
    notifyStudioMaskOverlay()
  }, [schedulePaintDisplay])

  const hasPaint = useCallback(() => {
    const canvas = maskRef.current
    if (!canvas) return false
    const ctx = canvas.getContext('2d', { willReadFrequently: true })
    if (!ctx) return false
    const data = ctx.getImageData(0, 0, canvas.width, canvas.height).data
    for (let i = 3; i < data.length; i += 16) {
      if ((data[i] ?? 0) > 8) return true
    }
    return false
  }, [])

  const overlayHandleRef = useRef<StudioImageMaskOverlayHandle | null>(null)
  overlayHandleRef.current = {
    getMaskCanvas: () => maskRef.current,
    undo: () => {
      if (historyIndexRef.current <= 0) return
      historyIndexRef.current -= 1
      applyHistory(historyIndexRef.current)
    },
    redo: () => {
      if (historyIndexRef.current >= historyRef.current.length - 1) return
      historyIndexRef.current += 1
      applyHistory(historyIndexRef.current)
    },
    canUndo: () => historyIndexRef.current > 0,
    canRedo: () => historyIndexRef.current < historyRef.current.length - 1,
    hasPaint,
    clear: () => {
      const canvas = maskRef.current
      const ctx = canvas?.getContext('2d')
      if (!canvas || !ctx) return
      ctx.clearRect(0, 0, canvas.width, canvas.height)
      pushHistory()
      schedulePaintDisplay()
    }
  }

  useImperativeHandle(ref, () => overlayHandleRef.current!)

  useEffect(() => {
    const handle: StudioImageMaskOverlayHandle = {
      getMaskCanvas: () => maskRef.current,
      undo: () => overlayHandleRef.current?.undo(),
      redo: () => overlayHandleRef.current?.redo(),
      canUndo: () => overlayHandleRef.current?.canUndo() ?? false,
      canRedo: () => overlayHandleRef.current?.canRedo() ?? false,
      hasPaint: () => overlayHandleRef.current?.hasPaint() ?? false,
      clear: () => overlayHandleRef.current?.clear()
    }
    registerStudioMaskOverlay(node.id, handle)
    return () => registerStudioMaskOverlay(node.id, null)
  }, [node.id])

  const screenToMask = useCallback((screenX: number, screenY: number) => {
    const canvas = maskRef.current
    if (!canvas || layout.width < 1 || layout.height < 1) return null
    return {
      x: (screenX / layout.width) * canvas.width,
      y: (screenY / layout.height) * canvas.height,
      maskW: canvas.width,
      maskH: canvas.height
    }
  }, [layout.height, layout.width])

  const pointerToLocal = useCallback((event: React.PointerEvent | PointerEvent) => {
    const host = hostRef.current
    if (!host) return null
    const box = host.getBoundingClientRect()
    return {
      x: event.clientX - box.left,
      y: event.clientY - box.top,
      w: box.width,
      h: box.height
    }
  }, [])

  const strokeBrush = (
    ctx: CanvasRenderingContext2D,
    fromX: number,
    fromY: number,
    toX: number,
    toY: number,
    radius: number,
    erase: boolean
  ) => {
    ctx.save()
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'
    ctx.lineWidth = Math.max(2, radius * 2)
    if (erase) {
      ctx.globalCompositeOperation = 'destination-out'
      ctx.strokeStyle = 'rgba(0,0,0,1)'
    } else {
      ctx.globalCompositeOperation = 'source-over'
      ctx.strokeStyle = 'rgba(255,255,255,1)'
    }
    ctx.beginPath()
    ctx.moveTo(fromX, fromY)
    ctx.lineTo(toX, toY)
    ctx.stroke()
    ctx.restore()
  }

  const onPointerDown = (event: React.PointerEvent) => {
    if (event.button !== 0) return
    event.preventDefault()
    event.stopPropagation()
    const local = pointerToLocal(event)
    if (!local) return
    const canvas = ensureMask(layout.width, layout.height)
    const mapped = screenToMask(local.x, local.y)
    if (!mapped) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    const tool = session.maskTool ?? 'brush'
    const brushSize = session.brushSize ?? STUDIO_MASK_BRUSH_DEFAULT
    const radius = Math.max(2, brushSize * Math.min(mapped.maskW, mapped.maskH) / 2)
    hostRef.current?.setPointerCapture(event.pointerId)

    if (tool === 'rect') {
      drawingRef.current = {
        tool,
        lastX: mapped.x,
        lastY: mapped.y,
        startX: mapped.x,
        startY: mapped.y,
        snapshot: ctx.getImageData(0, 0, canvas.width, canvas.height)
      }
      return
    }

    drawingRef.current = {
      tool,
      lastX: mapped.x,
      lastY: mapped.y,
      startX: mapped.x,
      startY: mapped.y,
      snapshot: null
    }
    strokeBrush(ctx, mapped.x, mapped.y, mapped.x, mapped.y, radius, tool === 'eraser')
    schedulePaintDisplay(true)
  }

  const onPointerMove = (event: React.PointerEvent) => {
      const drawing = drawingRef.current
      if (!drawing) return
      event.stopPropagation()
      const local = pointerToLocal(event)
      const canvas = maskRef.current
      const ctx = canvas?.getContext('2d')
      if (!local || !canvas || !ctx) return
      const mapped = screenToMask(local.x, local.y)
      if (!mapped) return
      const brushSize = session.brushSize ?? STUDIO_MASK_BRUSH_DEFAULT
      const radius = Math.max(2, brushSize * Math.min(mapped.maskW, mapped.maskH) / 2)

      if (drawing.tool === 'rect' && drawing.snapshot) {
        ctx.putImageData(drawing.snapshot, 0, 0)
        const x = Math.min(drawing.startX, mapped.x)
        const y = Math.min(drawing.startY, mapped.y)
        const w = Math.abs(mapped.x - drawing.startX)
        const h = Math.abs(mapped.y - drawing.startY)
        ctx.fillStyle = 'rgba(255,255,255,1)'
        ctx.fillRect(x, y, w, h)
        schedulePaintDisplay(true)
        return
      }

      strokeBrush(
        ctx,
        drawing.lastX,
        drawing.lastY,
        mapped.x,
        mapped.y,
        radius,
        drawing.tool === 'eraser'
      )
      drawing.lastX = mapped.x
      drawing.lastY = mapped.y
      schedulePaintDisplay(true)
  }
  const finishPointer = () => {
    if (!drawingRef.current) return
    drawingRef.current = null
    pushHistory()
    schedulePaintDisplay(true)
  }

  void node.data.mediaUrl

  const maskCursorClass = session.maskTool === 'eraser'
    ? 'studio-image-mask-overlay--eraser'
    : session.maskTool === 'rect'
      ? 'studio-image-mask-overlay--rect'
      : 'studio-image-mask-overlay--brush'

  return (
    <div
      ref={hostRef}
      className={`studio-image-mask-overlay nodrag nopan nowheel ${maskCursorClass} studio-image-mask-overlay--${session.mode}`}
      aria-label={session.mode === 'erase' ? '擦除蒙版' : '重绘蒙版'}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={(event) => { event.stopPropagation(); finishPointer() }}
      onPointerCancel={finishPointer}
      onLostPointerCapture={finishPointer}
    >
      <canvas ref={displayRef} className="studio-image-mask-overlay__canvas" />
    </div>
  )
})
