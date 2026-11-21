'use client'

import { useEffect, useRef, useState, type CSSProperties, type PointerEvent as ReactPointerEvent, type Ref } from 'react'
import {
  Mesh,
  OrthographicCamera,
  PlaneGeometry,
  Scene,
  ShaderMaterial,
  Vector2,
  Vector3,
  Vector4,
  WebGLRenderer
} from 'three'
import { createLiquidOrbController, type LiquidOrbMode } from './liquidOrbController'
import { LIQUID_ORB_FRAGMENT_SHADER, LIQUID_ORB_VERTEX_SHADER } from './liquidOrbShader'
import { prefersReducedMotion, readBackdropRgb, supportsWebGL } from './liquidOrbSupport'
import './liquid-orb.css'

export type { LiquidOrbMode }

export interface LiquidOrbProps {
  className?: string
  mode?: LiquidOrbMode
  size?: number | string
  theme?: 'dark' | 'light'
  interactive?: boolean
  /** 装饰性小球只用 CSS。每次开关对话都新建 WebGL 上下文会在主线程编译着色器，卡住数秒。 */
  webgl?: boolean
  autoCycle?: boolean
  ariaHidden?: boolean
  ariaLabel?: string
  activationFeedback?: boolean
  onActivate?: () => void
}

function toCssSize(size: number | string | undefined, fallback: string) {
  if (typeof size === 'number' && Number.isFinite(size)) return `${size}px`
  const value = String(size || '').trim()
  return value || fallback
}

export function LiquidOrb({
  className = '',
  mode = 'idle',
  size = 68,
  theme = 'dark',
  interactive = false,
  webgl = true,
  autoCycle = true,
  ariaHidden,
  ariaLabel = 'AI 助手',
  activationFeedback = true,
  onActivate
}: LiquidOrbProps) {
  const hostRef = useRef<HTMLElement>(null)
  const runtimeRef = useRef<{
    goThinking: () => void
    applyMode: (next: LiquidOrbMode) => void
    setPressed: (value: boolean) => void
  } | null>(null)
  const modeRef = useRef(mode)
  const [fallbackOnly, setFallbackOnly] = useState(true)
  const resolvedTheme = theme === 'light' ? 'light' : 'dark'
  const hiddenFromTree = interactive ? false : ariaHidden !== false
  modeRef.current = mode

  useEffect(() => {
    runtimeRef.current?.applyMode(mode)
  }, [mode])

  useEffect(() => {
    const host = hostRef.current
    if (!host || !webgl) return
    if (prefersReducedMotion() || !supportsWebGL()) {
      setFallbackOnly(true)
      return
    }

    let renderer: WebGLRenderer
    try {
      renderer = new WebGLRenderer({
        alpha: true,
        antialias: true,
        premultipliedAlpha: true,
        powerPreference: 'high-performance'
      })
    } catch {
      setFallbackOnly(true)
      return
    }

    setFallbackOnly(false)
    renderer.setClearColor(0, 0)
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
    renderer.domElement.style.position = 'absolute'
    renderer.domElement.style.inset = '0'
    renderer.domElement.style.width = '100%'
    renderer.domElement.style.height = '100%'
    renderer.domElement.style.display = 'block'
    renderer.domElement.style.pointerEvents = 'none'
    renderer.domElement.style.zIndex = '2'
    host.appendChild(renderer.domElement)

    const scene = new Scene()
    const camera = new OrthographicCamera(-1, 1, 1, -1, 0, 1)
    const geometry = new PlaneGeometry(2, 2)
    const uniforms = {
      uResolution: { value: new Vector2(1, 1) },
      uBallRadius: { value: 1 },
      uEffectSize: { value: 1 },
      uGlassPx: { value: new Vector3(1, -1, 1) },
      uBackdrop: { value: new Vector3(0.04, 0.045, 0.052) },
      uTime: { value: 0 },
      uWavePhase: { value: 0 },
      uSurfaceA: { value: new Vector4(0, 0, -1, 0) },
      uSurfaceB: { value: new Vector4(1, 0, 0, 0) },
      uBands: { value: new Vector3(0, 0, 0) },
      uProgress: { value: new Float32Array(6) }
    }
    const material = new ShaderMaterial({
      vertexShader: LIQUID_ORB_VERTEX_SHADER,
      fragmentShader: LIQUID_ORB_FRAGMENT_SHADER,
      transparent: true,
      premultipliedAlpha: true,
      depthTest: false,
      depthWrite: false,
      toneMapped: false,
      uniforms
    })
    scene.add(new Mesh(geometry, material))

    const controller = createLiquidOrbController(modeRef.current)
    let disposed = false
    let frame = 0
    let running = false
    let elapsed = 0
    let last = window.performance.now()

    const syncBackdrop = () => {
      const [r, g, b] = readBackdropRgb(host, resolvedTheme)
      uniforms.uBackdrop.value.set(r, g, b)
    }

    const isVisible = () => {
      if (document.hidden) return false
      const rect = host.getBoundingClientRect()
      return rect.width > 2 && rect.height > 2
    }

    const syncSize = () => {
      if (disposed) return
      const rect = host.getBoundingClientRect()
      const width = Math.max(1, Math.floor(rect.width))
      const height = Math.max(1, Math.floor(rect.height))
      renderer.setSize(width, height, false)
      const pixelRatio = renderer.getPixelRatio()
      const bufferW = Math.max(1, Math.round(width * pixelRatio))
      const bufferH = Math.max(1, Math.round(height * pixelRatio))
      uniforms.uResolution.value.set(bufferW, bufferH)
      const radius = Math.max(2, (0.5 * Math.min(bufferW, bufferH) - pixelRatio - 1) / 1.018)
      uniforms.uBallRadius.value = radius
      const scale = (2 * radius) / 128
      uniforms.uEffectSize.value = Math.max(1, Math.round(151.04 * scale))
      uniforms.uGlassPx.value.set(18 * scale, -62 * scale, 2.35 * scale)
    }

    const paint = (advance: boolean) => {
      const now = window.performance.now()
      const dt = advance ? Math.min(Math.max((now - last) / 1000, 0), 0.1) : 1 / 60
      last = now
      if (advance) elapsed = (elapsed + dt) % 1e5
      const snapshot = controller.tick(advance ? dt : 1 / 60)
      const surface = snapshot.surface
      uniforms.uTime.value = elapsed
      uniforms.uWavePhase.value = surface.wavePhase
      uniforms.uSurfaceA.value.set(surface.sharedResolved, surface.waveLayerOpacity, surface.dotsResolved, surface.dotsAppear)
      uniforms.uSurfaceB.value.set(surface.effectScale, surface.press, 0.92 * Math.max(0, Math.min(1, surface.sharedResolved)), 0)
      uniforms.uBands.value.set(0.42 * snapshot.bands.low, 0.42 * snapshot.bands.mid, 0.42 * snapshot.bands.high)
      for (let i = 0; i < 6; i += 1) uniforms.uProgress.value[i] = snapshot.progress[i]
      renderer.render(scene, camera)
    }

    const stopLoop = () => {
      running = false
      window.cancelAnimationFrame(frame)
    }

    const loop = () => {
      if (disposed || !running) return
      if (!isVisible()) {
        stopLoop()
        return
      }
      paint(true)
      frame = window.requestAnimationFrame(loop)
    }

    const startLoop = () => {
      if (disposed || running || !autoCycle || !isVisible()) return
      running = true
      last = window.performance.now()
      frame = window.requestAnimationFrame(loop)
    }

    const onVisibility = () => {
      if (document.hidden) stopLoop()
      else startLoop()
    }

    const onContextLost = (event: Event) => {
      event.preventDefault()
      stopLoop()
      setFallbackOnly(true)
    }

    const resizeObserver = new ResizeObserver(() => {
      syncSize()
      startLoop()
    })
    const intersectionObserver = typeof IntersectionObserver === 'undefined'
      ? null
      : new IntersectionObserver((entries) => {
        if (entries.some((entry) => entry.isIntersecting && entry.intersectionRatio > 0)) startLoop()
        else stopLoop()
      })

    resizeObserver.observe(host)
    intersectionObserver?.observe(host)
    document.addEventListener('visibilitychange', onVisibility)
    renderer.domElement.addEventListener('webglcontextlost', onContextLost)
    syncSize()
    syncBackdrop()
    if (autoCycle) {
      for (let i = 0; i < 90; i += 1) controller.tick(1 / 60)
      paint(false)
      startLoop()
    } else {
      paint(false)
    }

    runtimeRef.current = {
      goThinking: () => controller.goThinking(5000),
      applyMode: (next) => {
        if (!disposed) controller.applyMode(next)
      },
      setPressed: (value) => controller.setPressed(value)
    }

    return () => {
      disposed = true
      stopLoop()
      resizeObserver.disconnect()
      intersectionObserver?.disconnect()
      document.removeEventListener('visibilitychange', onVisibility)
      renderer.domElement.removeEventListener('webglcontextlost', onContextLost)
      controller.dispose()
      runtimeRef.current = null
      geometry.dispose()
      material.dispose()
      renderer.dispose()
      renderer.domElement.remove()
    }
  }, [autoCycle, resolvedTheme, webgl])

  const releasePress = () => {
    runtimeRef.current?.setPressed(false)
  }

  const handlePointerDown = (event: ReactPointerEvent<HTMLElement>) => {
    if (!interactive) return
    event.stopPropagation()
    if (activationFeedback) runtimeRef.current?.setPressed(true)
  }

  const handleActivate = () => {
    if (activationFeedback) runtimeRef.current?.goThinking()
    onActivate?.()
  }

  const cssSize = toCssSize(size, '68px')
  const style = { '--liquid-orb-size': cssSize } as CSSProperties
  const classNameValue = [
    'liquid-orb',
    fallbackOnly ? 'is-fallback-only' : 'is-webgl-ready',
    interactive ? 'is-interactive' : '',
    className
  ].filter(Boolean).join(' ')
  const content = (
    <>
      <span className="liquid-orb__halo" aria-hidden="true" />
      <span className="liquid-orb__fallback" aria-hidden="true" />
    </>
  )

  if (interactive) {
    return (
      <button
        ref={hostRef as Ref<HTMLButtonElement>}
        type="button"
        className={classNameValue}
        data-theme={resolvedTheme}
        data-mode={mode}
        style={style}
        aria-label={ariaLabel}
        onPointerDown={handlePointerDown}
        onPointerUp={releasePress}
        onPointerCancel={releasePress}
        onClick={(event) => {
          event.stopPropagation()
          handleActivate()
        }}
      >
        {content}
      </button>
    )
  }

  return (
    <span
      ref={hostRef as Ref<HTMLSpanElement>}
      className={classNameValue}
      data-theme={resolvedTheme}
      data-mode={mode}
      style={style}
      aria-hidden={hiddenFromTree ? true : undefined}
    >
      {content}
    </span>
  )
}

export default LiquidOrb
