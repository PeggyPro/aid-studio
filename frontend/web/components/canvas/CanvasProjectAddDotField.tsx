'use client'

import { useEffect, useRef } from 'react'

const GAP = 18
const CYCLE_MS = 3000
const WAVE_MS = 1250
const HOVER_RADIUS = 48

/** One lightweight canvas paints both the resting dots and the interactive highlight. */
export function CanvasProjectAddDotField() {
  const canvasRef = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    const host = canvas?.parentElement
    const context = canvas?.getContext('2d')
    if (!canvas || !host || !context) return

    const motion = window.matchMedia('(prefers-reduced-motion: reduce)')
    const pointer = { x: 0, y: 0, targetX: 0, targetY: 0, inside: false }
    let width = 0
    let height = 0
    let visible = true
    let frame = 0
    let cycleStart = performance.now()
    const style = getComputedStyle(host)
    const accent = style.getPropertyValue('--canvas-card-accent').trim() || '#4ae7fd'
    const rest = style.getPropertyValue('--canvas-card-dot').trim() || '#b4bfcb'

    const resize = () => {
      const rect = canvas.getBoundingClientRect()
      width = rect.width
      height = rect.height
      const ratio = Math.min(window.devicePixelRatio || 1, 2)
      canvas.width = Math.max(1, Math.round(width * ratio))
      canvas.height = Math.max(1, Math.round(height * ratio))
      context.setTransform(ratio, 0, 0, ratio, 0, 0)
      if (motion.matches) paint(performance.now())
    }

    const paint = (now: number) => {
      context.clearRect(0, 0, width, height)
      if (!width || !height) return
      const phase = ((now - cycleStart + CYCLE_MS) % CYCLE_MS) / WAVE_MS
      const wavePosition = Math.min(phase, 1)
      pointer.x += (pointer.targetX - pointer.x) * 0.3
      pointer.y += (pointer.targetY - pointer.y) * 0.3
      for (let y = GAP / 2; y < height; y += GAP) {
        for (let x = GAP / 2; x < width; x += GAP) {
          const vertical = Math.exp(-Math.pow((y - height / 2) / (height * 0.5), 2) * 1.3)
          const distanceFromCenter = Math.abs(x - width / 2) / Math.max(width / 2, 1)
          const wave = !motion.matches && !pointer.inside && phase < 1
            ? Math.exp(-Math.pow((distanceFromCenter - wavePosition) / 0.16, 2)) * vertical
            : 0
          const hoverDistance = Math.hypot(x - pointer.x, y - pointer.y)
          const hover = pointer.inside ? Math.exp(-Math.pow(hoverDistance / HOVER_RADIUS, 2)) : 0
          const light = Math.max(wave * 0.85, hover * 0.92)
          context.globalAlpha = 0.14 + light * 0.76
          context.fillStyle = light > 0.1 ? accent : rest
          context.beginPath()
          context.arc(x, y, 0.85 + light * 0.6, 0, Math.PI * 2)
          context.fill()
        }
      }
      context.globalAlpha = 1
    }

    const tick = (now: number) => {
      frame = 0
      if (!visible || document.hidden) return
      paint(now)
      if (!motion.matches) frame = requestAnimationFrame(tick)
    }
    const schedule = () => { if (!frame && visible && !document.hidden) frame = requestAnimationFrame(tick) }
    const move = (event: PointerEvent) => {
      const rect = host.getBoundingClientRect()
      pointer.targetX = event.clientX - rect.left
      pointer.targetY = event.clientY - rect.top
      if (!pointer.inside) {
        pointer.x = pointer.targetX
        pointer.y = pointer.targetY
      }
      pointer.inside = true
      if (motion.matches) paint(performance.now())
      else schedule()
    }
    const leave = () => {
      pointer.inside = false
      cycleStart = performance.now() + 600
      if (motion.matches) paint(performance.now())
    }
    const onMotionChange = () => {
      if (motion.matches) {
        cancelAnimationFrame(frame)
        frame = 0
        paint(performance.now())
      } else schedule()
    }
    const resizeObserver = new ResizeObserver(resize)
    const intersectionObserver = new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting
      if (visible) schedule()
      else { cancelAnimationFrame(frame); frame = 0 }
    })
    resizeObserver.observe(canvas)
    intersectionObserver.observe(host)
    host.addEventListener('pointermove', move)
    host.addEventListener('pointerleave', leave)
    motion.addEventListener('change', onMotionChange)
    document.addEventListener('visibilitychange', schedule)
    resize()
    schedule()
    return () => {
      cancelAnimationFrame(frame)
      resizeObserver.disconnect()
      intersectionObserver.disconnect()
      host.removeEventListener('pointermove', move)
      host.removeEventListener('pointerleave', leave)
      motion.removeEventListener('change', onMotionChange)
      document.removeEventListener('visibilitychange', schedule)
    }
  }, [])

  return <canvas ref={canvasRef} className="canvas-project-card__dot-field" aria-hidden="true" />
}
