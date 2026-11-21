import { clamp01, Spring, springFromBounce, springFromResponse } from './liquidOrbMath'

export type LiquidOrbMode = 'idle' | 'listening' | 'thinking'

export interface LiquidOrbSurface {
  waveOpacity: number
  wavePhase: number
  sharedResolved: number
  dotsAppear: number
  dotsResolved: number
  effectScale: number
  waveLayerOpacity: number
  press: number
}

export interface LiquidOrbBands {
  low: number
  mid: number
  high: number
}

const MODE_FLAGS: Record<LiquidOrbMode, { waveActive: boolean; fluidDotsActive: boolean }> = {
  idle: { waveActive: true, fluidDotsActive: false },
  listening: { waveActive: true, fluidDotsActive: false },
  thinking: { waveActive: false, fluidDotsActive: true }
}

const WAVE_IN = springFromResponse(0.314, 1)
const WAVE_OUT = springFromResponse(0.3, 1)
const PRESS = springFromResponse(0.28, 1)
const DOTS_APPEAR = springFromResponse(0.314, 1)
const BAND = springFromResponse(0.2, 1)
const DOT_FLIP = springFromBounce(0.9, 0.55)

function targetsFor(mode: LiquidOrbMode) {
  const flags = MODE_FLAGS[mode]
  return {
    fluidDots: flags.fluidDotsActive ? 1 : -1,
    effectScale: flags.fluidDotsActive ? 2 / 3 : 1
  }
}

export function createLiquidOrbController(initialMode: LiquidOrbMode = 'idle') {
  const waveOpacity = new Spring(0, WAVE_IN)
  const dotsAppear = new Spring(0, DOTS_APPEAR)
  const press = new Spring(0, PRESS)
  const low = new Spring(0, BAND)
  const mid = new Spring(0, BAND)
  const high = new Spring(0, BAND)
  const dots = Array.from({ length: 6 }, () => new Spring(0, DOT_FLIP))

  let mode: LiquidOrbMode = initialMode
  let homeMode: LiquidOrbMode = initialMode
  const current = targetsFor(initialMode)
  let target = { ...current }
  let velocity = { fluidDots: 0, effectScale: 0 }
  let thinkingClock = 0
  let flipFrom = 0
  let flipTo = 0
  let flipAge = Number.POSITIVE_INFINITY
  let bandClock = 0
  let bandsLive = false
  let restoreTimer: ReturnType<typeof setTimeout> | null = null

  const surface: LiquidOrbSurface = {
    waveOpacity: 0,
    wavePhase: 0,
    sharedResolved: 0,
    dotsAppear: 0,
    dotsResolved: current.fluidDots,
    effectScale: current.effectScale,
    waveLayerOpacity: 0,
    press: 0
  }

  function clearThinkingFlip() {
    thinkingClock = 0
    flipFrom = 0
    flipTo = 0
    flipAge = Number.POSITIVE_INFINITY
    for (const spring of dots) spring.setTarget(0, DOT_FLIP)
  }

  function select(next: LiquidOrbMode) {
    const flags = MODE_FLAGS[next]
    const nextTarget = targetsFor(next)
    const changed = target.fluidDots !== nextTarget.fluidDots || target.effectScale !== nextTarget.effectScale
    mode = next
    thinkingClock = 0
    waveOpacity.setTarget(flags.waveActive ? 1 : 0, flags.waveActive ? WAVE_IN : WAVE_OUT)
    target = nextTarget
    if (changed) velocity = { fluidDots: 0, effectScale: 0 }
    if (next !== 'thinking') clearThinkingFlip()
  }

  select(initialMode)

  function setPressed(value: boolean) {
    press.setTarget(value ? 1 : 0, PRESS)
  }

  function cancelRestore() {
    if (restoreTimer == null) return
    clearTimeout(restoreTimer)
    restoreTimer = null
  }

  function goThinking(restoreAfterMs = 5000) {
    select('thinking')
    cancelRestore()
    if (restoreAfterMs <= 0) return
    restoreTimer = setTimeout(() => {
      restoreTimer = null
      if (mode === 'thinking') select(homeMode)
    }, restoreAfterMs)
  }

  function applyMode(next: LiquidOrbMode) {
    homeMode = next
    cancelRestore()
    select(next)
  }

  function tick(dt: number): { surface: LiquidOrbSurface; progress: number[]; bands: LiquidOrbBands } {
    const step = Math.min(Math.max(dt, 0), 0.1)
    surface.waveOpacity = waveOpacity.step(step)
    surface.press = press.step(step)

    let remaining = step
    while (remaining > 0) {
      const slice = Math.min(remaining, 1 / 30)
      for (const key of ['fluidDots', 'effectScale'] as const) {
        const accel = -((current[key] - target[key]) * 400) + -40 * velocity[key]
        velocity[key] += accel * slice
        current[key] += velocity[key] * slice
      }
      remaining -= slice
    }
    surface.dotsResolved = current.fluidDots
    surface.effectScale = current.effectScale
    surface.sharedResolved = Math.max(2 * surface.waveOpacity - 1, surface.dotsResolved, 0)
    surface.waveLayerOpacity = 0.98 * clamp01(surface.waveOpacity)

    const listening = mode === 'listening'
    if (listening) {
      bandClock += step
      low.setTarget(clamp01(0.52 + 0.36 * Math.sin(1.05 * bandClock)))
      mid.setTarget(clamp01(0.4 + 0.34 * Math.sin(1.7 * bandClock + 1.4)))
      high.setTarget(clamp01(0.3 + 0.38 * Math.sin(2.9 * bandClock + 0.7)))
      bandsLive = true
    } else {
      if (bandsLive) {
        low.jump(0)
        mid.jump(0)
        high.jump(0)
      }
      bandClock = 0
      low.setTarget(0)
      mid.setTarget(0)
      high.setTarget(0)
      bandsLive = false
    }
    const bands = {
      low: low.step(step),
      mid: mid.step(step),
      high: high.step(step)
    }
    const drive = listening ? Math.max(bands.low, bands.mid, bands.high) : 0.22
    surface.wavePhase = (surface.wavePhase + (-1.05 + -5.2 * clamp01(drive * 0.4)) * step) % 62.831848
    if (surface.wavePhase < 0) surface.wavePhase += 62.831848

    dotsAppear.setTarget(Math.max(surface.dotsResolved, 0), DOTS_APPEAR)
    surface.dotsAppear = dotsAppear.step(step)

    if (mode === 'thinking' && surface.dotsResolved > 0) {
      thinkingClock += step
      if (thinkingClock >= 5) {
        thinkingClock = 0
        flipFrom = flipTo
        flipTo = flipTo > 0.5 ? 0 : 1
        flipAge = 0
      }
    } else {
      clearThinkingFlip()
    }
    flipAge += step
    const progress = dots.map((spring, index) => {
      const delayed = 0.2 * index > flipAge ? flipFrom : flipTo
      spring.setTarget(delayed, DOT_FLIP)
      return spring.step(step)
    })

    return { surface, progress, bands }
  }

  function dispose() {
    cancelRestore()
  }

  return {
    get mode() {
      return mode
    },
    get homeMode() {
      return homeMode
    },
    get surface() {
      return surface
    },
    select,
    applyMode,
    setPressed,
    goThinking,
    cancelRestore,
    tick,
    dispose
  }
}

export type LiquidOrbController = ReturnType<typeof createLiquidOrbController>
