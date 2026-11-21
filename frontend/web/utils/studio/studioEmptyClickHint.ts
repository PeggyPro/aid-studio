/** One press inside a real double-click, not a slow single nod. */
export const EMPTY_CLICK_DOWN_MS = 70
export const EMPTY_CLICK_HOLD_MS = 40
export const EMPTY_CLICK_UP_MS = 55
/** Gap between the two clicks of one dblclick. */
export const EMPTY_CLICK_GAP_MS = 75
/** Two concentric rings expand after the dblclick completes. */
export const EMPTY_CLICK_RIPPLE_MS = 900
export const EMPTY_CLICK_RING_STAGGER_MS = 90
/** Quiet time after one dblclick before the next demonstration. */
export const EMPTY_CLICK_REST_MS = 1000

export const EMPTY_CLICK_STROKE_MS =
  EMPTY_CLICK_DOWN_MS + EMPTY_CLICK_HOLD_MS + EMPTY_CLICK_UP_MS

export type EmptyClickHintTimeline = {
  click1: number
  click2: number
  pairEnd: number
  lastRippleEnd: number
  cycle: number
  ripple: number
  ringStagger: number
}

/** One cycle is: dblclick → two rings → 1s rest. Rest cannot stretch the two clicks apart. */
export function emptyClickHintTimeline(): EmptyClickHintTimeline {
  const click1 = 0
  const click2 = EMPTY_CLICK_STROKE_MS + EMPTY_CLICK_GAP_MS
  const pairEnd = click2 + EMPTY_CLICK_STROKE_MS
  const lastRippleEnd = pairEnd + EMPTY_CLICK_RIPPLE_MS + EMPTY_CLICK_RING_STAGGER_MS
  const cycle = lastRippleEnd + EMPTY_CLICK_REST_MS
  return {
    click1,
    click2,
    pairEnd,
    lastRippleEnd,
    cycle,
    ripple: EMPTY_CLICK_RIPPLE_MS,
    ringStagger: EMPTY_CLICK_RING_STAGGER_MS
  }
}

const POINTER_REST = 'translate(2px, 3px) rotate(-12deg)'
const POINTER_DOWN = 'translate(2px, 10px) rotate(-20deg)'
const RIPPLE_ORIGIN = 'translate(-50%, -50%)'

function offset(ms: number, cycle: number) {
  return Math.min(0.999, Math.max(0, ms / cycle))
}

function monotonic(frames: Keyframe[]): Keyframe[] {
  const next: Keyframe[] = []
  for (const frame of frames) {
    const current = Number(frame.offset)
    const previous = next[next.length - 1]
    if (previous && Number(previous.offset) >= current) {
      next[next.length - 1] = frame
      continue
    }
    next.push(frame)
  }
  if (next[0]) next[0].offset = 0
  if (next[next.length - 1]) next[next.length - 1].offset = 1
  return next
}

function pointerKeyframes(timeline: EmptyClickHintTimeline): Keyframe[] {
  const { click2, pairEnd, cycle } = timeline
  const stroke = EMPTY_CLICK_STROKE_MS
  return monotonic([
    { transform: POINTER_REST, offset: 0 },
    { transform: POINTER_DOWN, offset: offset(EMPTY_CLICK_DOWN_MS, cycle) },
    { transform: POINTER_DOWN, offset: offset(EMPTY_CLICK_DOWN_MS + EMPTY_CLICK_HOLD_MS, cycle) },
    { transform: POINTER_REST, offset: offset(stroke, cycle) },
    { transform: POINTER_REST, offset: offset(click2, cycle) },
    { transform: POINTER_DOWN, offset: offset(click2 + EMPTY_CLICK_DOWN_MS, cycle) },
    { transform: POINTER_DOWN, offset: offset(click2 + EMPTY_CLICK_DOWN_MS + EMPTY_CLICK_HOLD_MS, cycle) },
    { transform: POINTER_REST, offset: offset(pairEnd, cycle) },
    { transform: POINTER_REST, offset: 1 }
  ])
}

function rippleKeyframes(startMs: number, timeline: EmptyClickHintTimeline, ring: 'inner' | 'outer'): Keyframe[] {
  const { cycle, ripple } = timeline
  const begin = offset(startMs, cycle)
  const end = offset(startMs + ripple, cycle)
  const after = Math.min(1, end + 0.002)
  const finalScale = ring === 'outer' ? 3.2 : 2
  const peakOpacity = ring === 'outer' ? 0.5 : 0.8
  return monotonic([
    { transform: `${RIPPLE_ORIGIN} scale(0.35)`, opacity: 0, offset: 0 },
    { transform: `${RIPPLE_ORIGIN} scale(0.35)`, opacity: 0, offset: Math.max(0, begin - 0.002) },
    { transform: `${RIPPLE_ORIGIN} scale(0.35)`, opacity: peakOpacity, offset: begin },
    { transform: `${RIPPLE_ORIGIN} scale(${finalScale})`, opacity: 0, offset: end },
    { transform: `${RIPPLE_ORIGIN} scale(0.35)`, opacity: 0, offset: after },
    { transform: `${RIPPLE_ORIGIN} scale(0.35)`, opacity: 0, offset: 1 }
  ])
}

function play(element: Element, keyframes: Keyframe[], duration: number) {
  if (typeof element.animate !== 'function') return null
  return element.animate(keyframes, {
    duration,
    iterations: Infinity,
    easing: 'linear'
  })
}

/** Drive nod and ripples from the composed timeline so changing rest cannot crush the press. */
export function playEmptyClickHint(root: HTMLElement) {
  const timeline = emptyClickHintTimeline()
  const pointer = root.querySelector<HTMLElement>('[data-studio-click-pointer]')
  const ripples = [...root.querySelectorAll<HTMLElement>('[data-studio-click-ripple]')]
  const players: Animation[] = []
  if (pointer) {
    const animation = play(pointer, pointerKeyframes(timeline), timeline.cycle)
    if (animation) players.push(animation)
  }
  for (const ring of ripples) {
    const ringType = ring.dataset.studioClickRing === 'outer' ? 'outer' : 'inner'
    const stagger = ringType === 'outer' ? timeline.ringStagger : 0
    const animation = play(ring, rippleKeyframes(timeline.pairEnd + stagger, timeline, ringType), timeline.cycle)
    if (animation) players.push(animation)
  }
  return () => {
    for (const animation of players) animation.cancel()
  }
}
