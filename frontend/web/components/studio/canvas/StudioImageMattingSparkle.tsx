'use client'

import { useEffect, useMemo, useRef } from 'react'
import '@/components/studio/composer/studio-image-ai-tools.css'

const DOT_COUNT = 48

/** 抠图进行中的繁星点点流光动效（节点内铺满）。 */
export function StudioImageMattingSparkle({ label = '抠图中' }: { label?: string }) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const dots = useMemo(() => (
    Array.from({ length: DOT_COUNT }, (_, index) => ({
      x: (Math.sin(index * 12.9898) * 43758.5453) % 1,
      y: (Math.cos(index * 78.233) * 12345.6789) % 1,
      r: 0.6 + ((index * 17) % 7) * 0.35,
      speed: 0.35 + ((index * 13) % 9) * 0.08,
      phase: (index * 0.37) % (Math.PI * 2)
    })).map((dot) => ({
      ...dot,
      x: Math.abs(dot.x),
      y: Math.abs(dot.y)
    }))
  ), [])

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    let frame = 0
    let raf = 0
    const reduced = typeof window !== 'undefined'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches

    const resize = () => {
      const parent = canvas.parentElement
      if (!parent) return
      const rect = parent.getBoundingClientRect()
      const dpr = Math.min(2, window.devicePixelRatio || 1)
      canvas.width = Math.max(1, Math.round(rect.width * dpr))
      canvas.height = Math.max(1, Math.round(rect.height * dpr))
      canvas.style.width = `${rect.width}px`
      canvas.style.height = `${rect.height}px`
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    }
    resize()
    const observer = typeof ResizeObserver !== 'undefined'
      ? new ResizeObserver(resize)
      : null
    if (canvas.parentElement && observer) observer.observe(canvas.parentElement)

    const draw = (time: number) => {
      const w = canvas.clientWidth
      const h = canvas.clientHeight
      ctx.clearRect(0, 0, w, h)
      ctx.fillStyle = '#0a0b0e'
      ctx.fillRect(0, 0, w, h)

      const t = reduced ? 0 : time * 0.001
      for (const dot of dots) {
        const driftX = Math.sin(t * dot.speed + dot.phase) * 8
        const driftY = Math.cos(t * dot.speed * 0.85 + dot.phase) * 6
        const pulse = 0.35 + 0.65 * (0.5 + 0.5 * Math.sin(t * (1.2 + dot.speed) + dot.phase))
        const x = (dot.x % 1) * w + driftX
        const y = (dot.y % 1) * h + driftY
        const radius = dot.r * (0.8 + pulse * 0.9)
        const gradient = ctx.createRadialGradient(x, y, 0, x, y, radius * 3.2)
        gradient.addColorStop(0, `rgba(255,255,255,${0.95 * pulse})`)
        gradient.addColorStop(0.35, `rgba(210,225,255,${0.45 * pulse})`)
        gradient.addColorStop(1, 'rgba(255,255,255,0)')
        ctx.fillStyle = gradient
        ctx.beginPath()
        ctx.arc(x, y, radius * 3.2, 0, Math.PI * 2)
        ctx.fill()
      }

      // 轻微横向流光带
      if (!reduced) {
        const bandY = (h * 0.2) + ((Math.sin(t * 0.7) * 0.5 + 0.5) * h * 0.6)
        const band = ctx.createLinearGradient(0, bandY - 18, w, bandY + 18)
        band.addColorStop(0, 'rgba(255,255,255,0)')
        band.addColorStop(0.45, 'rgba(180,210,255,0.08)')
        band.addColorStop(0.55, 'rgba(255,255,255,0.14)')
        band.addColorStop(1, 'rgba(255,255,255,0)')
        ctx.fillStyle = band
        ctx.fillRect(0, bandY - 20, w, 40)
      }

      frame += 1
      raf = window.requestAnimationFrame(draw)
    }
    raf = window.requestAnimationFrame(draw)
    return () => {
      window.cancelAnimationFrame(raf)
      observer?.disconnect()
      void frame
    }
  }, [dots])

  return (
    <div className="studio-image-matting-sparkle" aria-label={label} role="status">
      <canvas ref={canvasRef} aria-hidden />
      <span className="studio-image-matting-sparkle__label">{label}</span>
    </div>
  )
}

export default StudioImageMattingSparkle
