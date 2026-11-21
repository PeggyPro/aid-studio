'use client'

import { useEffect, useRef } from 'react'
import { createHomeHeroDotField } from './homeHeroDotField'
import { createHomeHeroSweepClock } from './homeHeroSweepClock'
import { watchHomeMotion } from './watchHomeMotion'

const SWEEP_MS = 7000
const SWEEP_RUN = 0.55
const SOLID = '.home-title-row, .home-seg, .home-composer-box, .home-composer-actions'

function settle(value: number, target: number, speed: number, epsilon: number) {
  return Math.abs(target - value) <= epsilon ? target : value + (target - value) * speed
}

/** 首屏点阵。点的颜色读主题变量，浅色主题不用改绘制逻辑。 */
export function HomeHeroField() {
  const ref = useRef<HTMLCanvasElement | null>(null)

  useEffect(() => {
    const element = ref.current
    const heroNode = element?.closest('.home-composer-hero')
    const drawingContext = element?.getContext('2d')
    if (!element || !(heroNode instanceof HTMLElement) || !drawingContext) return undefined
    const canvas = element
    const context = drawingContext
    const hero = heroNode
    const main = hero.closest('.home-new-main')
    const scroller = main instanceof HTMLElement ? main : null

    const motion = window.matchMedia('(prefers-reduced-motion: reduce)')
    const pointer = { x: 0, y: 0, strength: 0 }
    let target: { x: number; y: number; strength: number } | null = null
    let width = 0
    let height = 0
    let ratio = 0
    let frame = 0
    let timer = 0
    let idleSince: number | null = null
    let visible = false
    let faded = false
    let origin: DOMRect | null = null
    let field: ReturnType<typeof createHomeHeroDotField> | null = null
    let palette: { cool: string; hot: string } | null = null
    let ripple: { x: number; y: number; t: number } | null = null
    const sweepClock = createHomeHeroSweepClock(SWEEP_MS, SWEEP_RUN)

    function colors() {
      const style = getComputedStyle(canvas)
      return {
        cool: style.getPropertyValue('--home-dot').trim() || '#ffffff',
        hot: style.getPropertyValue('--home-dot-hot').trim() || '#bff3fb'
      }
    }

    function resize() {
      const rect = canvas.getBoundingClientRect()
      origin = rect
      const nextRatio = Math.min(window.devicePixelRatio || 1, 2)
      if (width === rect.width && height === rect.height && ratio === nextRatio) return
      width = rect.width
      height = rect.height
      ratio = nextRatio
      canvas.width = Math.max(1, Math.round(width * ratio))
      canvas.height = Math.max(1, Math.round(height * ratio))
      context.setTransform(ratio, 0, 0, ratio, 0, 0)
      field = createHomeHeroDotField(context, width, height)
      wake()
    }

    const canDraw = () => visible && !faded && width > 0 && height > 0

    function phaseAt(now: number) {
      const idleTime = idleSince === null ? undefined : now - idleSince
      idleSince = null
      return sweepClock.phase(now, idleTime)
    }

    function pause() {
      phaseAt(performance.now())
      sweepClock.pause()
      cancelAnimationFrame(frame)
      window.clearTimeout(timer)
      frame = timer = 0
    }

    function wake() {
      window.clearTimeout(timer)
      timer = 0
      if (canDraw() && !frame) frame = requestAnimationFrame(render)
    }

    function render(now: number) {
      frame = 0
      if (!canDraw() || !field) return
      const reduce = motion.matches
      const phase = phaseAt(now)
      let easing = false
      if (target && !reduce) {
        pointer.x = settle(pointer.x, target.x, 0.12, 0.05)
        pointer.y = settle(pointer.y, target.y, 0.12, 0.05)
        pointer.strength = settle(pointer.strength, target.strength, 0.1, 0.001)
        if (pointer.strength === 0) {
          pointer.x = target.x
          pointer.y = target.y
        }
        easing = pointer.strength !== target.strength ||
          (pointer.strength > 0.01 && (pointer.x !== target.x || pointer.y !== target.y))
      }
      const radius = ripple ? Math.max(0, now - ripple.t) * 0.6 : 0
      if (radius >= Math.min(800, Math.max(width, height))) ripple = null
      const span = width * 0.8 + height * 0.6
      const sweep = !reduce && phase < SWEEP_RUN ? -160 + (phase / SWEEP_RUN) * (span + 320) : null
      field.draw(palette ??= colors(), pointer, sweep, ripple && { ...ripple, radius })
      if (reduce) return

      const delay = sweepClock.delayUntilSweep()
      if (easing || ripple || delay === 0) wake()
      else if (Number.isFinite(delay)) {
        // There are no moving pixels between sweeps. Resume from the elapsed quiet time.
        idleSince = now
        timer = window.setTimeout(wake, Math.ceil(delay))
      }
    }

    const onPointerMove = (event: PointerEvent) => {
      if (!canDraw() || motion.matches) return
      const rect = origin ??= canvas.getBoundingClientRect()
      const element = event.target instanceof Element ? event.target : null
      const next = { x: event.clientX - rect.left, y: event.clientY - rect.top, strength: element?.closest(SOLID) ? 0 : 1 }
      if (target?.x === next.x && target.y === next.y && target.strength === next.strength) return
      if (!target) {
        pointer.x = next.x
        pointer.y = next.y
      }
      target = next
      phaseAt(performance.now())
      sweepClock.finishThenHold()
      wake()
    }
    const onPointerLeave = () => {
      if (motion.matches) return
      if (target) target.strength = 0
      sweepClock.play()
      wake()
    }
    const onFocusIn = (event: FocusEvent) => {
      if (!canDraw() || motion.matches) return
      const target = event.target instanceof Element ? event.target : null
      const composer = target?.closest('.home-composer-box')
      if (!composer) return
      const composerRect = composer.getBoundingClientRect()
      const canvasRect = canvas.getBoundingClientRect()
      ripple = {
        x: composerRect.left + composerRect.width / 2 - canvasRect.left,
        y: composerRect.top + composerRect.height / 2 - canvasRect.top,
        t: performance.now()
      }
      wake()
    }
    const onScroll = () => {
      if (!scroller) return
      const opacity = Math.max(0, 1 - scroller.scrollTop / 520)
      canvas.style.opacity = opacity.toFixed(3)
      origin = null
      const next = opacity === 0
      if (next === faded) return
      faded = next
      if (faded) pause()
      else wake()
    }
    const onMotionChange = () => {
      pause()
      target = ripple = null
      pointer.strength = 0
      if (!motion.matches) sweepClock.play()
      wake()
    }

    hero.addEventListener('pointerenter', onPointerMove)
    hero.addEventListener('pointermove', onPointerMove)
    hero.addEventListener('pointerleave', onPointerLeave)
    hero.addEventListener('focusin', onFocusIn)
    scroller?.addEventListener('scroll', onScroll, { passive: true })
    const stopWatching = watchHomeMotion(canvas, (shown) => {
      visible = shown
      if (visible) wake()
      else pause()
    })
    const themeObserver = new MutationObserver(() => {
      palette = null
      wake()
    })
    themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-v10-theme'] })
    const theme = canvas.closest('[data-home-theme]')
    if (theme) themeObserver.observe(theme, { attributes: true, attributeFilter: ['data-home-theme'] })
    motion.addEventListener('change', onMotionChange)
    window.addEventListener('resize', resize)
    const resizeObserver = new ResizeObserver(resize)
    resizeObserver.observe(canvas)
    onScroll()
    resize()

    return () => {
      cancelAnimationFrame(frame)
      window.clearTimeout(timer)
      hero.removeEventListener('pointerenter', onPointerMove)
      hero.removeEventListener('pointermove', onPointerMove)
      hero.removeEventListener('pointerleave', onPointerLeave)
      hero.removeEventListener('focusin', onFocusIn)
      scroller?.removeEventListener('scroll', onScroll)
      window.removeEventListener('resize', resize)
      motion.removeEventListener('change', onMotionChange)
      stopWatching()
      themeObserver.disconnect()
      resizeObserver.disconnect()
    }
  }, [])

  return <canvas ref={ref} className="home-hero-field" aria-hidden="true" />
}
