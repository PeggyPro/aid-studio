export type StudioLightingDirection = 'front' | 'left' | 'right' | 'top' | 'bottom' | 'back'

/** Sized to fit the stage under tabs / above footer with clear margin. */
export const SPHERE_RADIUS = 1.82
/** Flashlight sits just outside the frosted shell. */
export const LIGHT_RADIUS = 1.86
export const CENTER_PLANE_SIZE = 1.74

/**
 * Drawn latitude rings (mid bands only).
 * Meridians run pole-to-pole and meet at a single vertex — no near-pole horizontal belt.
 */
export const GUIDE_LATITUDE_BANDS = [-35, 0, 35] as const
/** Snap seats still include ±70 for 顶部/底部 presets. */
export const LATITUDE_BANDS = [-70, -35, 0, 35, 70] as const
/** Meridians converge at the poles (single vertex, no top/bottom belt ring). */
export const MERIDIAN_ELEV_MIN = -90
export const MERIDIAN_ELEV_MAX = 90
export const LONGITUDE_MERIDIANS = [
  -180, -150, -120, -90, -60, -30, 0, 30, 60, 90, 120, 150
] as const

/** Default rim seat: back-right silhouette, opposite the left key, clear of the card. */
export const DEFAULT_RIM_LIGHT_POSE = { azimuth: 120, elevation: 35 } as const
/** @deprecated use DEFAULT_RIM_LIGHT_POSE */
export const RIM_LIGHT_POSE = DEFAULT_RIM_LIGHT_POSE

/** Image faces +Z; back hemisphere has negative Z on the shell. */
export function sphericalZ(azimuthDeg: number, elevationDeg: number): number {
  const az = (azimuthDeg * Math.PI) / 180
  const el = (elevationDeg * Math.PI) / 180
  return Math.cos(el) * Math.cos(az)
}

export function isBackHemisphere(azimuthDeg: number, elevationDeg: number): boolean {
  return sphericalZ(azimuthDeg, elevationDeg) < 0
}

export function isFrontHemisphere(azimuthDeg: number, elevationDeg: number): boolean {
  return sphericalZ(azimuthDeg, elevationDeg) >= 0
}

/** Keep main key drag on the front shell only (mirror back hits to the front). */
export function clampToFrontHemisphere(azimuth: number, elevation: number) {
  const el = Math.max(-78, Math.min(78, elevation))
  let az = normalizeAzimuth(azimuth)
  if (!isFrontHemisphere(az, el)) {
    az = normalizeAzimuth(az + 180)
  }
  return { azimuth: az, elevation: el }
}

/** Keep rim drag on the rear shell only (mirror front hits to the back). */
export function clampToBackHemisphere(azimuth: number, elevation: number) {
  const el = Math.max(-78, Math.min(78, elevation))
  let az = normalizeAzimuth(azimuth)
  if (!isBackHemisphere(az, el)) {
    az = normalizeAzimuth(az + 180)
  }
  return { azimuth: az, elevation: el }
}

/** Snap rim onto rear guide crosses only (12 azimuths × latitudes, z < 0). */
export function snapRimFlashlightToGuide(azimuth: number, elevation: number) {
  const clamped = clampToBackHemisphere(azimuth, elevation)
  let best: { azimuth: number; elevation: number } = DEFAULT_RIM_LIGHT_POSE
  let bestScore = Number.POSITIVE_INFINITY
  for (const pose of SNAP_LIGHT_POSES) {
    const az = pose.azimuth === -180 ? 180 : pose.azimuth
    if (!isBackHemisphere(az, pose.elevation)) continue
    const score = azimuthDistance(clamped.azimuth, az) + Math.abs(clamped.elevation - pose.elevation) * 1.2
    if (score < bestScore) {
      bestScore = score
      best = { azimuth: az, elevation: pose.elevation }
    }
  }
  return {
    azimuth: best.azimuth === -180 ? 180 : best.azimuth,
    elevation: best.elevation
  }
}

/** Angular half-extent (deg) of the soft cross glow along guide lines. */
export const CROSS_HIGHLIGHT_ARC_DEG = 16

/** Right-panel 主光源 presets (6 quick buttons). */
export const LIGHT_DIRECTION_ANGLES: Record<StudioLightingDirection, { azimuth: number; elevation: number }> = {
  front: { azimuth: 0, elevation: 0 },
  back: { azimuth: 180, elevation: 0 },
  left: { azimuth: -90, elevation: 0 },
  right: { azimuth: 90, elevation: 0 },
  top: { azimuth: 0, elevation: 70 },
  bottom: { azimuth: 0, elevation: -70 }
}

/**
 * All drag-snap seats: 12 azimuths (6 main + 6 intermediate) × latitude bands,
 * plus explicit top/bottom mains. Flashlight rests on these crosses.
 */
export const SNAP_LIGHT_POSES: ReadonlyArray<{ azimuth: number; elevation: number }> = (() => {
  const poses: Array<{ azimuth: number; elevation: number }> = []
  for (const azimuth of LONGITUDE_MERIDIANS) {
    for (const elevation of LATITUDE_BANDS) {
      poses.push({ azimuth, elevation })
    }
  }
  return poses
})()

/** Default pose: left-side key light like TapNow three-quarter reference. */
export const DEFAULT_LIGHT_POSE = {
  direction: 'left' as StudioLightingDirection,
  azimuth: -90,
  elevation: 0
}

export function positionFromSpherical(azimuthDeg: number, elevationDeg: number, radius = LIGHT_RADIUS) {
  const azimuth = (azimuthDeg * Math.PI) / 180
  const elevation = (elevationDeg * Math.PI) / 180
  return {
    x: radius * Math.cos(elevation) * Math.sin(azimuth),
    y: radius * Math.sin(elevation),
    z: radius * Math.cos(elevation) * Math.cos(azimuth)
  }
}

export function sphericalFromPosition(x: number, y: number, z: number) {
  const radius = Math.sqrt(x * x + y * y + z * z) || LIGHT_RADIUS
  const elevation = (Math.asin(Math.max(-1, Math.min(1, y / radius))) * 180) / Math.PI
  let azimuth = (Math.atan2(x, z) * 180) / Math.PI
  if (azimuth > 180) azimuth -= 360
  if (azimuth <= -180) azimuth += 360
  return { azimuth, elevation }
}

export function normalizeAzimuth(azimuth: number): number {
  let value = azimuth
  while (value > 180) value -= 360
  while (value <= -180) value += 360
  return value
}

export function azimuthDistance(a: number, b: number): number {
  let d = Math.abs(normalizeAzimuth(a) - normalizeAzimuth(b))
  if (d > 180) d = 360 - d
  return d
}

export function nearestLightingDirection(azimuth: number, elevation: number): StudioLightingDirection {
  let best: StudioLightingDirection = 'front'
  let bestScore = Number.POSITIVE_INFINITY
  for (const [key, value] of Object.entries(LIGHT_DIRECTION_ANGLES) as Array<[StudioLightingDirection, { azimuth: number; elevation: number }]>) {
    const score = azimuthDistance(azimuth, value.azimuth) + Math.abs(elevation - value.elevation) * 1.15
    if (score < bestScore) {
      bestScore = score
      best = key
    }
  }
  return best
}

/** Snap onto nearest guide cross (12 azimuths × latitude bands). */
export function snapFlashlightToGuide(
  azimuth: number,
  elevation: number
): { azimuth: number; elevation: number; direction: StudioLightingDirection } {
  let best = SNAP_LIGHT_POSES[0]
  let bestScore = Number.POSITIVE_INFINITY
  for (const pose of SNAP_LIGHT_POSES) {
    const score = azimuthDistance(azimuth, pose.azimuth) + Math.abs(elevation - pose.elevation) * 1.2
    if (score < bestScore) {
      bestScore = score
      best = pose
    }
  }
  // Normalize -180 → 180 so it matches the「后方」preset.
  const snappedAzimuth = best.azimuth === -180 ? 180 : best.azimuth
  return {
    azimuth: snappedAzimuth,
    elevation: best.elevation,
    direction: nearestLightingDirection(snappedAzimuth, best.elevation)
  }
}

/** @deprecated use snapFlashlightToGuide — still snaps to full 12-azimuth guide set. */
export function snapFlashlightToMainLight(
  azimuth: number,
  elevation: number
): { azimuth: number; elevation: number; direction: StudioLightingDirection } {
  return snapFlashlightToGuide(azimuth, elevation)
}

/** @deprecated use snapFlashlightToGuide */
export function snapFlashlightToGrid(azimuth: number, elevation: number) {
  const snapped = snapFlashlightToGuide(azimuth, elevation)
  return { azimuth: snapped.azimuth, elevation: snapped.elevation }
}

export function lerpAngle(from: number, to: number, t: number): number {
  let delta = normalizeAzimuth(to) - normalizeAzimuth(from)
  if (delta > 180) delta -= 360
  if (delta < -180) delta += 360
  return normalizeAzimuth(from + delta * t)
}

export function easeOutCubic(t: number): number {
  return 1 - (1 - t) ** 3
}

export type LightingBeamRole = 'key' | 'rim'

/** Role optics share energy; rim is a slightly tighter cone, not a dimmer light. */
export interface LightingBeamOptic {
  baseRadius: number
  tipRadius: number
  throwScale: number
  density: number
  glowSize: number
  spotIntensity: number
  spotAngle: number
}

const SHARED_BEAM_DENSITY = 0.52
const SHARED_BEAM_SPOT = 1.9
const SHARED_BEAM_ANGLE = Math.PI / 6.5

export const LIGHTING_BEAM_OPTIC: Record<LightingBeamRole, LightingBeamOptic> = {
  key: {
    baseRadius: 0.5,
    tipRadius: 0.042,
    throwScale: 1,
    density: SHARED_BEAM_DENSITY,
    glowSize: 0.24,
    spotIntensity: SHARED_BEAM_SPOT,
    spotAngle: SHARED_BEAM_ANGLE
  },
  rim: {
    baseRadius: 0.42,
    tipRadius: 0.038,
    throwScale: 1,
    density: SHARED_BEAM_DENSITY,
    glowSize: 0.22,
    spotIntensity: SHARED_BEAM_SPOT,
    spotAngle: SHARED_BEAM_ANGLE
  }
}

/** Soft cool-white beam like TapNow (visual only; prompt may still use temperature). */
export function beamVisualColor(): string {
  return '#f4f6fa'
}

export function kelvinToColor(kelvin: number): string {
  const t = Math.max(2000, Math.min(10000, kelvin))
  // Daylight 5600K → neutral cool-white (#f4f6fa). Warmer below, cooler above.
  if (t <= 5600) {
    const mix = (t - 2000) / 3600
    const r = Math.round(255 + mix * (244 - 255))
    const g = Math.round(168 + mix * (246 - 168))
    const b = Math.round(96 + mix * (250 - 96))
    return `rgb(${r}, ${g}, ${b})`
  }
  const mix = (t - 5600) / 4400
  const r = Math.round(244 - mix * (244 - 208))
  const g = Math.round(246 - mix * (246 - 228))
  const b = 255
  return `rgb(${r}, ${g}, ${b})`
}

export function beamStrength(brightness: number, role: LightingBeamRole = 'key') {
  const t = Math.max(0, Math.min(100, brightness)) / 100
  return LIGHTING_BEAM_OPTIC[role].density * (0.38 + t * 0.62)
}

export function beamSpotIntensity(brightness: number, role: LightingBeamRole = 'key') {
  const t = Math.max(0, Math.min(100, brightness)) / 100
  return LIGHTING_BEAM_OPTIC[role].spotIntensity * (0.18 + t * 0.82)
}

export function guideCrossArcDeg(role: LightingBeamRole): number {
  return role === 'rim' ? 8 : CROSS_HIGHLIGHT_ARC_DEG
}

export function buildLatitudeRingPoints(elevationDeg: number, radius = SPHERE_RADIUS * 1.004, segments = 96) {
  const elevation = (elevationDeg * Math.PI) / 180
  const ringRadius = Math.cos(elevation) * radius
  const y = Math.sin(elevation) * radius
  const points: Array<[number, number, number]> = []
  for (let s = 0; s <= segments; s++) {
    const t = (s / segments) * Math.PI * 2
    points.push([Math.cos(t) * ringRadius, y, Math.sin(t) * ringRadius])
  }
  return points
}

export function buildMeridianPoints(
  azimuthDeg: number,
  radius = SPHERE_RADIUS * 1.004,
  segments = 64,
  elevMinDeg = MERIDIAN_ELEV_MIN,
  elevMaxDeg = MERIDIAN_ELEV_MAX
) {
  const azimuth = (azimuthDeg * Math.PI) / 180
  const elevMin = (elevMinDeg * Math.PI) / 180
  const elevMax = (elevMaxDeg * Math.PI) / 180
  const points: Array<[number, number, number]> = []
  for (let s = 0; s <= segments; s++) {
    const elevation = elevMin + (s / segments) * (elevMax - elevMin)
    const r = Math.cos(elevation) * radius
    const y = Math.sin(elevation) * radius
    points.push([Math.sin(azimuth) * r, y, Math.cos(azimuth) * r])
  }
  return points
}

export function nearestGuideLatitude(elevation: number): number {
  let best: number = LATITUDE_BANDS[0]
  let bestDist = Number.POSITIVE_INFINITY
  for (const band of LATITUDE_BANDS) {
    const d = Math.abs(elevation - band)
    if (d < bestDist) {
      bestDist = d
      best = band
    }
  }
  return best
}

export function nearestGuideLongitude(azimuth: number): number {
  let best: number = LONGITUDE_MERIDIANS[0]
  let bestDist = Number.POSITIVE_INFINITY
  for (const meridian of LONGITUDE_MERIDIANS) {
    const d = azimuthDistance(azimuth, meridian)
    if (d < bestDist) {
      bestDist = d
      best = meridian
    }
  }
  return best
}

/** Short latitude arc centered on a pose — the glowing parallel of the cross. */
export function buildLatitudeArcPoints(
  elevationDeg: number,
  centerAzimuthDeg: number,
  halfArcDeg = CROSS_HIGHLIGHT_ARC_DEG,
  radius = SPHERE_RADIUS * 1.006,
  segments = 36
) {
  const elevation = (elevationDeg * Math.PI) / 180
  const ringRadius = Math.cos(elevation) * radius
  const y = Math.sin(elevation) * radius
  const points: Array<[number, number, number]> = []
  for (let s = 0; s <= segments; s++) {
    const t = (s / segments) * 2 - 1
    const az = ((centerAzimuthDeg + t * halfArcDeg) * Math.PI) / 180
    points.push([Math.sin(az) * ringRadius, y, Math.cos(az) * ringRadius])
  }
  return points
}

/** Short meridian arc centered on a pose — the glowing meridian of the cross. */
export function buildMeridianArcPoints(
  azimuthDeg: number,
  centerElevationDeg: number,
  halfArcDeg = CROSS_HIGHLIGHT_ARC_DEG,
  radius = SPHERE_RADIUS * 1.006,
  segments = 36
) {
  const azimuth = (azimuthDeg * Math.PI) / 180
  const points: Array<[number, number, number]> = []
  for (let s = 0; s <= segments; s++) {
    const t = (s / segments) * 2 - 1
    const elevation = ((centerElevationDeg + t * halfArcDeg) * Math.PI) / 180
    const r = Math.cos(elevation) * radius
    const y = Math.sin(elevation) * radius
    points.push([Math.sin(azimuth) * r, y, Math.cos(azimuth) * r])
  }
  return points
}
