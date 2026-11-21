import { buildMultiAnglePromptParts } from '@/utils/multiAngleCameraPrompt'

export type MultiAnglePresetId =
  | 'custom'
  | 'fisheye'
  | 'dutch'
  | 'frontHigh'
  | 'frontLow'
  | 'panoramic'
  | 'back'

export interface StudioMultiAngleValues {
  preset: MultiAnglePresetId
  horizontal: number
  vertical: number
  focal: number
  promptHint: boolean
}

export const DEFAULT_MULTI_ANGLE: StudioMultiAngleValues = {
  preset: 'custom',
  horizontal: 0,
  vertical: 0,
  focal: 5,
  promptHint: false
}

export const MULTI_ANGLE_PRESET_TABS: Array<{ id: MultiAnglePresetId; label: string }> = [
  { id: 'custom', label: '自定义' },
  { id: 'fisheye', label: '鱼眼视角' },
  { id: 'dutch', label: '倾斜视角' },
  { id: 'frontHigh', label: '正面俯拍' },
  { id: 'frontLow', label: '正面仰拍' },
  { id: 'panoramic', label: '全景俯视' },
  { id: 'back', label: '背面视角' }
]

export const MULTI_ANGLE_PRESETS: Record<
  Exclude<MultiAnglePresetId, 'custom'>,
  Pick<StudioMultiAngleValues, 'horizontal' | 'vertical' | 'focal'> & { promptExtra: string }
> = {
  fisheye: {
    horizontal: 0,
    vertical: 30,
    focal: 9,
    promptExtra: '极度特写镜头，广角镜头，边缘带有鱼眼畸变效果'
  },
  dutch: {
    horizontal: 45,
    vertical: -30,
    focal: 5,
    promptExtra: 'dutch angle, tilted frame'
  },
  frontHigh: {
    horizontal: 0,
    vertical: 60,
    focal: 5,
    promptExtra: ''
  },
  frontLow: {
    horizontal: 0,
    vertical: -30,
    focal: 5,
    promptExtra: ''
  },
  panoramic: {
    horizontal: 45,
    vertical: 30,
    focal: 1,
    promptExtra: ''
  },
  back: {
    horizontal: 180,
    vertical: 0,
    focal: 5,
    promptExtra: ''
  }
}

export const ORBIT_RADIUS = 1.92
export const FRAME_BOX_SIZE = 1.08
export const FRAME_BOX_ROUNDNESS = 0.14

/** 默认展示用的轻微 3D 倾斜；角度 0/0 时仍能看出立方体，机位仍以有图正面为基准。 */
export const CUBE_PRESENTATION_TILT_X = -22
export const CUBE_PRESENTATION_TILT_Y = -32

export function normalizeHorizontal(deg: number): number {
  let v = deg % 360
  if (v < 0) v += 360
  return v
}

export function clampVertical(deg: number): number {
  return Math.max(-75, Math.min(75, deg))
}

export function clampFocal(value: number): number {
  return Math.max(0, Math.min(10, value))
}

/** 有图的一面为正面；水平环绕 / 垂直俯仰相对该正面映射到 CSS 立方体旋转。 */
export function cubeRotationFromAngles(horizontalDeg: number, verticalDeg: number) {
  return {
    rotateX: CUBE_PRESENTATION_TILT_X - clampVertical(verticalDeg),
    rotateY: CUBE_PRESENTATION_TILT_Y + normalizeHorizontal(horizontalDeg)
  }
}

export function focalToLabel(focal: number): string {
  const f = clampFocal(focal)
  if (f >= 8.5) return '特写'
  if (f >= 6.5) return '近景'
  if (f >= 4) return '中景'
  if (f >= 2) return '远景'
  return '全景'
}

export function cameraPositionFromAngles(
  horizontalDeg: number,
  verticalDeg: number,
  radius = ORBIT_RADIUS
) {
  const theta = (normalizeHorizontal(horizontalDeg) * Math.PI) / 180
  const phi = (clampVertical(verticalDeg) * Math.PI) / 180
  return {
    x: radius * Math.cos(phi) * Math.sin(theta),
    y: radius * Math.sin(phi),
    z: radius * Math.cos(phi) * Math.cos(theta)
  }
}

export function anglesFromCameraPosition(x: number, y: number, z: number) {
  const r = Math.sqrt(x * x + y * y + z * z) || ORBIT_RADIUS
  const vertical = clampVertical((Math.asin(Math.max(-1, Math.min(1, y / r))) * 180) / Math.PI)
  let horizontal = (Math.atan2(x, z) * 180) / Math.PI
  if (horizontal < 0) horizontal += 360
  return { horizontal: normalizeHorizontal(horizontal), vertical }
}

export function applyMultiAnglePreset(preset: MultiAnglePresetId): StudioMultiAngleValues {
  if (preset === 'custom') return { ...DEFAULT_MULTI_ANGLE }
  const p = MULTI_ANGLE_PRESETS[preset]
  return {
    preset,
    horizontal: p.horizontal,
    vertical: p.vertical,
    focal: p.focal,
    promptHint: Boolean(p.promptExtra)
  }
}

export function buildStudioMultiAnglePrompt(values: StudioMultiAngleValues): string {
  const parts = buildMultiAnglePromptParts(values.horizontal, values.vertical, values.focal)
  if (!values.promptHint || values.preset === 'custom') return parts.concat
  const extra = MULTI_ANGLE_PRESETS[values.preset as Exclude<MultiAnglePresetId, 'custom'>]?.promptExtra
  if (!extra) return parts.concat
  return `${parts.concat}, ${extra}`
}

export function presetPromptHint(preset: MultiAnglePresetId): string {
  if (preset === 'custom') return ''
  return MULTI_ANGLE_PRESETS[preset]?.promptExtra ?? ''
}
