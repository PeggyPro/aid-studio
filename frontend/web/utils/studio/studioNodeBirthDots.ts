import type { StudioCanvasNode, StudioMode } from '@/types/studio'
import type { StudioFreeTheme } from '@/utils/studio/studioFreeTheme'

export type NodeBirthState = {
  scopeKey: string
  studioMode: StudioMode
  hydrated: boolean
  dirtyRevision: number
  nodes: readonly StudioCanvasNode[]
}

/** AID mark shares the traveling radius so it grows from the node, not a fixed peak icon. */
export const NODE_BIRTH_DURATION_MS = 1400
/** The card lands first; the lattice answers a beat later. */
export const NODE_BIRTH_DELAY_MS = 90
/** Wash brightens over this window instead of flashing on the first frame. */
export const NODE_BIRTH_ATTACK_MS = 260
/** Brightness starts to settle back into the lattice from here to the end. */
export const NODE_BIRTH_DECAY_FROM_MS = 420
export const NODE_BIRTH_TRAVEL_MS = 900
/** Diameter starts at the node’s longest side. */
export const NODE_BIRTH_RADIUS_START_SPAN = 0.5
export const NODE_BIRTH_RADIUS_PEAK_SPAN = 2.2
export const NODE_BIRTH_MAX_RADIUS = 740
export const NODE_BIRTH_GAUSS = 2.2
/** Low gain keeps a center-to-edge gradient inside the mark instead of a flat plateau. */
export const NODE_BIRTH_PAINT_GAIN = 1.8
/** Peak highlight opacity: lit dots read about 3x the lattice, not a solid white stamp. */
export const NODE_BIRTH_PEAK_ALPHA = 0.5

export type NodeBirthField = {
  maskSize: number
  radius: number
  fade: number
}

type Rgb = readonly [number, number, number]
type BirthTintStop = { readonly at: number; readonly color: Rgb }

/** The wordmark sits in the middle of the mask, so the wash is measured across that band. */
const BIRTH_TINT_ORIGIN_X = 0.06
const BIRTH_TINT_SPAN_X = 0.82
const BIRTH_TINT_VERTICAL = 0.18

/**
 * AID lattice ink, loosely the reference wash: amber, gold, yellow, then blue.
 * Stops stay muted so a two-color mix does not turn the middle gray or neon.
 */
export const NODE_BIRTH_HIGHLIGHT: Record<StudioFreeTheme, readonly BirthTintStop[]> = {
  'soft-mist': [
    { at: 0, color: [212, 112, 42] },
    { at: 0.22, color: [208, 150, 44] },
    { at: 0.4, color: [198, 178, 62] },
    { at: 0.58, color: [92, 170, 186] },
    { at: 0.78, color: [58, 158, 206] },
    { at: 1, color: [46, 132, 196] }
  ],
  opal: [
    { at: 0, color: [176, 96, 36] },
    { at: 0.22, color: [168, 122, 32] },
    { at: 0.4, color: [154, 136, 40] },
    { at: 0.58, color: [42, 124, 138] },
    { at: 0.78, color: [32, 108, 176] },
    { at: 1, color: [28, 86, 164] }
  ]
}

/** Color of one lit AID dot. x/y are 0 at the mark's top-left and 1 at its bottom-right. */
export function nodeBirthTint(x: number, y: number, theme: StudioFreeTheme): Rgb {
  const across = (x - BIRTH_TINT_ORIGIN_X) / BIRTH_TINT_SPAN_X + (y - 0.5) * BIRTH_TINT_VERTICAL
  const along = Math.min(1, Math.max(0, across))
  const stops = NODE_BIRTH_HIGHLIGHT[theme]
  for (let index = 0; index < stops.length - 1; index += 1) {
    const start = stops[index]
    const end = stops[index + 1]
    if (along > end.at && index < stops.length - 2) continue
    const width = end.at - start.at
    const mix = width === 0 ? 0 : Math.min(1, Math.max(0, (along - start.at) / width))
    return [
      Math.round(start.color[0] + (end.color[0] - start.color[0]) * mix),
      Math.round(start.color[1] + (end.color[1] - start.color[1]) * mix),
      Math.round(start.color[2] + (end.color[2] - start.color[2]) * mix)
    ] as const
  }
  return stops[stops.length - 1].color
}

/** Only local edits in an already loaded free canvas may trigger a birth animation. */
export function locallyCreatedNodes(previous: NodeBirthState, current: NodeBirthState): StudioCanvasNode[] {
  if (!previous.hydrated || !current.hydrated || previous.scopeKey !== current.scopeKey) return []
  if (current.studioMode !== 'free' || previous.studioMode !== 'free') return []
  if (current.dirtyRevision <= previous.dirtyRevision) return []
  const existingIds = new Set(previous.nodes.map((node) => node.id))
  return current.nodes.filter((node) => !existingIds.has(node.id))
}

/** World-space gap between ambient lattice points at zoom 1. Measured 18.0px on the reference crop. */
export const DOT_GRID_UNIT = 18
/** Skip extra points only when a cell would be smaller than this many CSS pixels. */
export const DOT_GRID_MIN_PX = 8

export const DOT_GRID_AMBIENT = {
  dark: { warm: [168, 118, 52], cool: [48, 118, 168], alpha: 0.14 },
  opal: { warm: [126, 102, 78], cool: [70, 96, 132], alpha: 0.16 }
} as const

/** Keep every painted point on the same world-space lattice while panning and zooming. */
export function dotGridAxisPositions(length: number, translation: number, zoom: number): number[] {
  const unit = DOT_GRID_UNIT * Math.max(zoom, 0.01)
  const skip = Math.max(1, Math.ceil(DOT_GRID_MIN_PX / unit))
  const step = unit * skip
  const phase = ((translation + unit / 2) % step + step) % step
  const positions: number[] = []
  for (let point = phase; point < length; point += step) positions.push(point)
  return positions
}

function easeOutCubic(t: number): number {
  return 1 - (1 - t) ** 3
}

function smoothstep(edge0: number, edge1: number, value: number): number {
  const t = Math.min(1, Math.max(0, (value - edge0) / (edge1 - edge0)))
  return t * t * (3 - 2 * t)
}

/** Delayed rise, short hold, long settle — the brightness curve of the whole wash. */
export function nodeBirthFade(elapsedMs: number): number {
  const rise = smoothstep(NODE_BIRTH_DELAY_MS, NODE_BIRTH_DELAY_MS + NODE_BIRTH_ATTACK_MS, elapsedMs)
  const settle = 1 - smoothstep(NODE_BIRTH_DECAY_FROM_MS, NODE_BIRTH_DURATION_MS, elapsedMs)
  return rise * settle
}

/** Traveling world radius; AID UV is the current diameter so the mark grows with the wash. */
export function nodeBirthField(
  elapsedMs: number,
  nodeWidth: number,
  nodeHeight: number,
  zoom: number
): NodeBirthField | null {
  if (elapsedMs >= NODE_BIRTH_DURATION_MS) return null
  const travel = Math.min(1, Math.max(0, elapsedMs - NODE_BIRTH_DELAY_MS) / NODE_BIRTH_TRAVEL_MS)
  const ease = easeOutCubic(travel)
  const span = Math.max(nodeWidth, nodeHeight, 1) * Math.max(zoom, 0.01)
  const peakRadius = Math.min(NODE_BIRTH_MAX_RADIUS, span * NODE_BIRTH_RADIUS_PEAK_SPAN)
  const startRadius = peakRadius * (NODE_BIRTH_RADIUS_START_SPAN / NODE_BIRTH_RADIUS_PEAK_SPAN)
  const radius = startRadius + (peakRadius - startRadius) * ease
  return {
    maskSize: radius * 2,
    radius,
    fade: nodeBirthFade(elapsedMs)
  }
}

/** World-pixel sample from the node center; AID alpha is only a shape gate. */
export function nodeBirthLight(
  offsetX: number,
  offsetY: number,
  field: NodeBirthField,
  logoAlpha: number
): number {
  if (field.fade <= 0 || field.radius <= 0) return 0
  // Soft gate so dots ease in and out at the mark's edge instead of popping.
  const shape = smoothstep(0.15, 0.5, logoAlpha)
  if (!shape) return 0
  const distance = Math.hypot(offsetX, offsetY)
  const falloff = distance / field.radius
  const blob = Math.exp(-(falloff * falloff) * NODE_BIRTH_GAUSS)
  const envelope = shape * blob * field.fade
  if (envelope < 0.004) return 0
  const lift = (1 - Math.exp(-envelope * NODE_BIRTH_PAINT_GAIN)) / (1 - Math.exp(-NODE_BIRTH_PAINT_GAIN))
  return NODE_BIRTH_PEAK_ALPHA * lift
}
