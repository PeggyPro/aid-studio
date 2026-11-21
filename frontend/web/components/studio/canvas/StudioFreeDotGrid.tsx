'use client'

import { useEffect, useRef, type MutableRefObject } from 'react'
import { useStoreApi } from '@xyflow/react'
import logoSvg from '@/assets/logo-animation/logo.svg'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'
import { assetUrl } from '@/utils/assetUrl'
import { studioNodeDimensions } from '@/utils/studio/studioNodeLod'
import {
  DOT_GRID_AMBIENT,
  DOT_GRID_MIN_PX,
  DOT_GRID_UNIT,
  NODE_BIRTH_DURATION_MS,
  dotGridAxisPositions,
  locallyCreatedNodes,
  nodeBirthField,
  nodeBirthLight,
  nodeBirthTint
} from '@/utils/studio/studioNodeBirthDots'
import type { StudioFreeTheme } from '@/utils/studio/studioFreeTheme'
import { useStudioFreeTheme } from '../theme/StudioFreeThemeProvider'

const MASK_SIZE = 280
/** Extra radius at full intensity; lit dots stay as fine as the lattice. */
const NODE_BIRTH_DOT_GROWTH = 0.3
/** Mask blur in mask pixels so the AID edge fades over a couple of lattice points. */
const MASK_BLUR_PX = 7

export type StudioFreeDotBirth = { node: StudioCanvasNode; started: number }
type Rgb = readonly [number, number, number]

export type StudioFreeDotFrame = {
  theme: StudioFreeTheme
  animate: boolean
  x: number
  y: number
  zoom: number
  births: StudioFreeDotBirth[]
}

function blendRgb(start: Rgb, end: Rgb, t: number, alpha = 1): string {
  const mix = (index: 0 | 1 | 2) => Math.round(start[index] + (end[index] - start[index]) * t)
  if (alpha >= 1) return `rgb(${mix(0)} ${mix(1)} ${mix(2)})`
  return `rgba(${mix(0)}, ${mix(1)}, ${mix(2)}, ${alpha})`
}

/** Bilinear alpha so the scaling mark's edge glides across the lattice instead of stepping. */
function sampleMaskAlpha(pixels: ImageData, x: number, y: number): number {
  const fx = Math.min(MASK_SIZE - 1, Math.max(0, x - 0.5))
  const fy = Math.min(MASK_SIZE - 1, Math.max(0, y - 0.5))
  const x0 = Math.floor(fx)
  const y0 = Math.floor(fy)
  const x1 = Math.min(MASK_SIZE - 1, x0 + 1)
  const y1 = Math.min(MASK_SIZE - 1, y0 + 1)
  const tx = fx - x0
  const ty = fy - y0
  const at = (px: number, py: number) => pixels.data[(py * MASK_SIZE + px) * 4 + 3]
  const top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * tx
  const bottom = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * tx
  return (top + (bottom - top) * ty) / 255
}

function mountStudioFreeDotLattice(
  canvas: HTMLCanvasElement,
  ambient: HTMLDivElement,
  read: () => StudioFreeDotFrame,
  assignRedraw: (redraw: () => void) => void
) {
  const context = canvas.getContext('2d')
  if (!context) return () => {}

  let width = 0
  let height = 0
  let frame = 0
  let logoPixels: ImageData | null = null
  let logoRequested = false
  let lastStep = 0
  let lastRadius = 0
  let lastTheme: StudioFreeTheme | null = null
  let hasHighlight = false
  const motion = window.matchMedia('(prefers-reduced-motion: reduce)')
  const logoImage = new Image()
  logoImage.onload = () => {
    const buffer = document.createElement('canvas')
    buffer.width = MASK_SIZE
    buffer.height = MASK_SIZE
    const bufferContext = buffer.getContext('2d', { willReadFrequently: true })
    if (!bufferContext) return
    bufferContext.filter = `blur(${MASK_BLUR_PX}px)`
    bufferContext.drawImage(logoImage, 0, 0, MASK_SIZE, MASK_SIZE)
    bufferContext.filter = 'none'
    try { logoPixels = bufferContext.getImageData(0, 0, MASK_SIZE, MASK_SIZE) }
    catch { logoPixels = null }
    const pending = read()
    if (pending.births.length) {
      const started = performance.now()
      pending.births = pending.births.map((birth) => ({ ...birth, started }))
    }
    requestDraw()
  }
  const requestDraw = () => {
    if (!frame) frame = requestAnimationFrame(draw)
  }
  const ensureLogo = (animate: boolean) => {
    if (!animate || logoRequested) return
    logoRequested = true
    logoImage.src = assetUrl(logoSvg)
  }

  const draw = (now: number) => {
    frame = 0
    const current = read()
    ensureLogo(current.animate)
    const { x, y, zoom } = current
    const radius = Math.max(0.85, zoom)
    current.births = current.births.filter((birth) => now - birth.started < NODE_BIRTH_DURATION_MS)
    const active = !document.hidden && !motion.matches && current.animate && logoPixels ? current.births : []

    const highlights = active.flatMap(({ node, started }) => {
      const dimensions = studioNodeDimensions(node)
      const field = nodeBirthField(now - started, dimensions.width, dimensions.height, zoom)
      if (!field) return []
      return [{
        centerX: x + (node.position.x + dimensions.width / 2) * zoom,
        centerY: y + (node.position.y + dimensions.height / 2) * zoom,
        field
      }]
    })

    // CSS tiles the ambient lattice. Only the brief birth highlight needs a canvas.
    const unit = DOT_GRID_UNIT * Math.max(zoom, 0.01)
    const step = unit * Math.max(1, Math.ceil(DOT_GRID_MIN_PX / unit))
    const phase = (translation: number) => ((translation + unit / 2) % step + step) % step - step / 2
    if (lastStep !== step) {
      lastStep = step
      ambient.style.maskSize = `${step}px ${step}px`
    }
    if (lastRadius !== radius) {
      lastRadius = radius
      ambient.style.setProperty('--studio-grid-dot-radius', `${radius}px`)
    }
    if (lastTheme !== current.theme) {
      lastTheme = current.theme
      const tint = lastTheme === 'opal' ? DOT_GRID_AMBIENT.opal : DOT_GRID_AMBIENT.dark
      ambient.style.backgroundImage = `linear-gradient(90deg, ${blendRgb(tint.warm, tint.cool, 0, tint.alpha)}, ${blendRgb(tint.warm, tint.cool, 1, tint.alpha)})`
    }
    ambient.style.maskPosition = `${phase(x)}px ${phase(y)}px`

    if (hasHighlight || highlights.length) context.clearRect(0, 0, width, height)
    hasHighlight = highlights.length > 0
    if (hasHighlight) {
      const columns = dotGridAxisPositions(width, x, zoom)
      const rows = dotGridAxisPositions(height, y, zoom)
      for (const highlight of highlights) {
        const half = highlight.field.maskSize / 2
        for (const dotY of rows) {
          if (Math.abs(dotY - highlight.centerY) > half) continue
          for (const dotX of columns) {
            const offsetX = dotX - highlight.centerX
            const offsetY = dotY - highlight.centerY
            if (Math.abs(offsetX) > half || Math.abs(offsetY) > half) continue
            const dx = offsetX / highlight.field.maskSize
            const dy = offsetY / highlight.field.maskSize
            const alpha = sampleMaskAlpha(logoPixels!, (dx + 0.5) * MASK_SIZE, (dy + 0.5) * MASK_SIZE)
            const intensity = nodeBirthLight(offsetX, offsetY, highlight.field, alpha)
            if (!intensity) continue
            context.globalAlpha = intensity
            const tint = nodeBirthTint(dx + 0.5, dy + 0.5, current.theme)
            context.fillStyle = blendRgb(tint, tint, 0)
            context.beginPath()
            context.arc(dotX, dotY, radius + intensity * NODE_BIRTH_DOT_GROWTH, 0, Math.PI * 2)
            context.fill()
          }
        }
      }
    }
    context.globalAlpha = 1
    if (current.births.length) requestDraw()
  }

  const resize = () => {
    const rect = canvas.getBoundingClientRect()
    width = rect.width
    height = rect.height
    const ratio = Math.min(window.devicePixelRatio || 1, 1.5)
    canvas.width = Math.max(1, Math.round(width * ratio))
    canvas.height = Math.max(1, Math.round(height * ratio))
    context.setTransform(ratio, 0, 0, ratio, 0, 0)
    requestDraw()
  }

  const onVisibilityChange = () => requestDraw()
  const onMotionChange = () => requestDraw()
  if (read().animate) ensureLogo(true)
  const resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(canvas)
  document.addEventListener('visibilitychange', onVisibilityChange)
  motion.addEventListener('change', onMotionChange)
  assignRedraw(requestDraw)
  resize()
  return () => {
    assignRedraw(() => {})
    cancelAnimationFrame(frame)
    resizeObserver.disconnect()
    document.removeEventListener('visibilitychange', onVisibilityChange)
    motion.removeEventListener('change', onMotionChange)
    logoImage.onload = null
  }
}

/** Ambient lattice shared by the free canvas and any embedded canvas preview. */
export function StudioFreeDotLattice({
  read,
  redrawRef
}: {
  read: MutableRefObject<() => StudioFreeDotFrame>
  redrawRef?: MutableRefObject<() => void>
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const ambientRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    const ambient = ambientRef.current
    if (!canvas || !ambient) return
    return mountStudioFreeDotLattice(canvas, ambient, () => read.current(), (redraw) => {
      if (redrawRef) redrawRef.current = redraw
    })
  }, [read, redrawRef])

  return <div className="studio-free-dot-grid" aria-hidden="true">
    <div ref={ambientRef} className="studio-free-dot-grid__ambient" />
    <canvas ref={canvasRef} />
  </div>
}

/** Paint the ambient lattice and its temporary highlight on the same world-aligned points. */
export function StudioFreeDotGrid({ animate }: { animate: boolean }) {
  const flow = useStoreApi()
  const { theme } = useStudioFreeTheme()
  const source = useRef<StudioFreeDotFrame>({ theme, animate, x: 0, y: 0, zoom: 1, births: [] })
  const read = useRef<() => StudioFreeDotFrame>(() => source.current)
  const redrawRef = useRef<() => void>(() => {})
  source.current.theme = theme
  source.current.animate = animate
  read.current = () => {
    const [x, y, zoom] = flow.getState().transform
    source.current.theme = theme
    source.current.animate = animate
    source.current.x = x
    source.current.y = y
    source.current.zoom = zoom
    return source.current
  }

  useEffect(() => {
    redrawRef.current()
  }, [theme])

  useEffect(() => {
    const motion = window.matchMedia('(prefers-reduced-motion: reduce)')
    const unsubscribeViewport = flow.subscribe((current, previous) => {
      if (current.transform !== previous.transform) redrawRef.current()
    })
    const unsubscribe = useStudioUiStore.subscribe((current, previous) => {
      if (current.scopeKey !== previous.scopeKey) source.current.births = []
      if (!source.current.animate || motion.matches || document.hidden) return
      const created = locallyCreatedNodes(previous, current)
      if (!created.length) return
      const started = performance.now()
      source.current.births = [...source.current.births, ...created.slice(-3).map((node) => ({ node, started }))].slice(-3)
      redrawRef.current()
    })
    return () => {
      source.current.births = []
      unsubscribeViewport()
      unsubscribe()
    }
  }, [animate, flow])

  return <StudioFreeDotLattice read={read} redrawRef={redrawRef} />
}
