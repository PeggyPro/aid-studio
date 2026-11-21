import type { DirectorCameraBodyId, DirectorLensSeriesId, DirectorLensSpec } from '@/types/studio'
import { normalizeDirectorLensSpec } from '@/utils/studio/directorCameraCatalog'

export type DirectorCameraVisualPart = 'body' | 'lens' | 'focal' | 'aperture' | 'reel' | 'frustum' | 'target'

export interface DirectorCameraVisualProfile {
  lens: DirectorLensSpec
  bodySize: [number, number, number]
  bodyCenterZ: number
  lensLength: number
  lensRadiusX: number
  lensRadiusY: number
  apertureRadius: number
  bodyColor: string
  lensColor: string
  focalColor: string
  apertureColor: string
  bodyDetail: 'reels' | 'monitor' | 'handle'
}

export const DIRECTOR_CAMERA_FOCAL_COLOR = '#43D7FF'
export const DIRECTOR_CAMERA_APERTURE_COLOR = '#FFC857'

// Calibrated against a 30 cm wide cinema camera in the metre-based stage.
const CAMERA_BODY_METRE_SCALE = 0.30 / 0.44

const BODY_PROFILES: Record<DirectorCameraBodyId, Pick<DirectorCameraVisualProfile, 'bodySize' | 'bodyColor' | 'bodyDetail'>> = {
  'virtual-production': {
    bodySize: [0.58, 0.38, 0.64],
    bodyColor: '#67E0FF',
    bodyDetail: 'monitor',
  },
  'lightweight-handheld': {
    bodySize: [0.3, 0.24, 0.42],
    bodyColor: '#73E0A4',
    bodyDetail: 'handle',
  },
  'digital-cinema': {
    bodySize: [0.44, 0.34, 0.56],
    bodyColor: '#82A8FF',
    bodyDetail: 'reels',
  },
}

const LENS_COLORS: Record<DirectorLensSeriesId, string> = {
  'cine-prime': '#B7A8FF',
  'cine-zoom': '#9A87FF',
  'anamorphic-prime': '#E28CFF',
  'documentary-zoom': '#8BC7FF',
}

export function resolveDirectorCameraVisualProfile(input?: Partial<DirectorLensSpec>): DirectorCameraVisualProfile {
  const lens = normalizeDirectorLensSpec(input)
  const body = BODY_PROFILES[lens.cameraBody ?? 'digital-cinema']
  const focalRatio = normalizeRange(lens.focalLengthMm, 18, 200)
  const apertureRatio = 1 - normalizeRange(lens.apertureF ?? 2.8, 1.4, 16)
  const zoom = lens.lensType === 'zoom'
  const anamorphic = lens.lensSeries === 'anamorphic-prime'
  const lensLength = (zoom ? 0.38 : 0.25) + focalRatio * (zoom ? 0.2 : 0.12)
  const lensRadiusX = (anamorphic ? 0.21 : zoom ? 0.15 : 0.135) * CAMERA_BODY_METRE_SCALE
  const lensRadiusY = (anamorphic ? 0.115 : zoom ? 0.15 : 0.135) * CAMERA_BODY_METRE_SCALE
  const bodySize: DirectorCameraVisualProfile['bodySize'] = body.bodySize.map(value => value * CAMERA_BODY_METRE_SCALE) as DirectorCameraVisualProfile['bodySize']

  return {
    lens,
    ...body,
    bodySize,
    bodyCenterZ: -bodySize[2] / 2,
    lensLength: lensLength * CAMERA_BODY_METRE_SCALE,
    lensRadiusX,
    lensRadiusY,
    apertureRadius: Math.min(lensRadiusX, lensRadiusY) * (0.32 + apertureRatio * 0.42),
    lensColor: LENS_COLORS[lens.lensSeries ?? 'cine-prime'],
    focalColor: DIRECTOR_CAMERA_FOCAL_COLOR,
    apertureColor: DIRECTOR_CAMERA_APERTURE_COLOR,
  }
}

export function resolveDirectorCameraVisualPartColor(
  profile: DirectorCameraVisualProfile,
  part: DirectorCameraVisualPart,
): string {
  if (part === 'body' || part === 'reel') return profile.bodyColor
  if (part === 'lens') return profile.lensColor
  if (part === 'aperture') return profile.apertureColor
  if (part === 'focal' || part === 'frustum') return profile.focalColor
  return '#B9C4D4'
}

function normalizeRange(value: number, min: number, max: number): number {
  return Math.max(0, Math.min(1, (value - min) / (max - min)))
}
