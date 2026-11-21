import type {
  StudioImageFineness,
  StudioImageHdScale
} from '@/types/studio'

export const STUDIO_IMAGE_AI_TOOLS = [
  { key: 'hd', label: '高清', ready: true },
  { key: 'expand', label: '扩图', ready: true },
  { key: 'repaint', label: '重绘', ready: true },
  { key: 'erase', label: '擦除', ready: true },
  { key: 'matting', label: '抠图', ready: true },
  { key: 'crop', label: '裁剪', ready: true }
] as const

export const STUDIO_MASK_BRUSH_MIN = 0.02
export const STUDIO_MASK_BRUSH_MAX = 0.28
export const STUDIO_MASK_BRUSH_DEFAULT = 0.08

export const STUDIO_HD_PROVIDERS = [
  { value: 'Topazlabs', label: 'Topazlabs' }
] as const

export const STUDIO_HD_SCALES: StudioImageHdScale[] = [2, 4, 6]

export const STUDIO_CROP_ASPECT_RATIOS = [
  { value: 'original', label: '原图比例' },
  { value: '1:1', label: '1 : 1' },
  { value: '4:3', label: '4 : 3' },
  { value: '3:4', label: '3 : 4' },
  { value: '16:9', label: '16 : 9' },
  { value: '9:16', label: '9 : 16' },
  { value: '21:9', label: '21 : 9' },
  { value: 'custom', label: '自定义...' }
] as const

export const STUDIO_EXPAND_QUALITIES = [
  { value: '2K', label: '2K' },
  { value: '4K', label: '4K' }
] as const

export const STUDIO_EXPAND_COUNTS = [1, 2, 4] as const

export const STUDIO_EXPAND_FINENESS: Array<{ value: StudioImageFineness; label: string }> = [
  { value: 'high', label: '高' },
  { value: 'medium', label: '中' },
  { value: 'low', label: '低' }
]

export function parseAspectRatio(value: string): number | null {
  if (value === 'original' || value === 'custom') return null
  const match = value.match(/^(\d+(?:\.\d+)?)\s*:\s*(\d+(?:\.\d+)?)$/)
  if (!match) return null
  const w = Number(match[1])
  const h = Number(match[2])
  if (!Number.isFinite(w) || !Number.isFinite(h) || w <= 0 || h <= 0) return null
  return w / h
}

/** 铺满内容区的裁剪框（默认无留白）。 */
export function fullCropRect(): { x: number; y: number; w: number; h: number } {
  return { x: 0, y: 0, w: 1, h: 1 }
}

/** 像素宽高比转换为相对原图 [0,1] 坐标的宽高比。 */
export function normalizedImageRectAspect(aspect: number | null, sourceAspect = 1): number | null {
  if (!aspect || !Number.isFinite(aspect) || aspect <= 0) return null
  return aspect / (Number.isFinite(sourceAspect) && sourceAspect > 0 ? sourceAspect : 1)
}

/** 在原图归一化内容区内按实际像素比例裁剪；无比例时铺满。 */
export function centeredCropRect(aspect: number | null, sourceAspect = 1): { x: number; y: number; w: number; h: number } {
  aspect = normalizedImageRectAspect(aspect, sourceAspect)
  if (!aspect || !Number.isFinite(aspect) || aspect <= 0) {
    return fullCropRect()
  }
  if (aspect >= 1) {
    const w = 1
    const h = Math.min(1, w / aspect)
    return { x: 0, y: (1 - h) / 2, w, h }
  }
  const h = 1
  const w = Math.min(1, h * aspect)
  return { x: (1 - w) / 2, y: 0, w, h }
}

export function clampCropRect(
  rect: { x: number; y: number; w: number; h: number },
  aspect: number | null,
  minSize = 0.12
): { x: number; y: number; w: number; h: number } {
  let { x, y, w, h } = rect
  w = Math.max(minSize, Math.min(1, w))
  h = Math.max(minSize, Math.min(1, h))
  if (aspect && aspect > 0) {
    if (w / h > aspect) w = h * aspect
    else h = w / aspect
    w = Math.max(minSize, Math.min(1, w))
    h = Math.max(minSize, Math.min(1, h))
  }
  x = Math.max(0, Math.min(1 - w, x))
  y = Math.max(0, Math.min(1 - h, y))
  return { x, y, w, h }
}

/** 扩图外框：相对原图 [0,1]²，允许负坐标 / 超出 1，但必须始终包住原图。 */
export const STUDIO_EXPAND_MAX_PAD = 1.2

export function initialExpandRect(): { x: number; y: number; w: number; h: number } {
  return { x: -0.1, y: -0.1, w: 1.2, h: 1.2 }
}

/** 生成包住原图、并尽量贴近目标宽高比的扩图外框。 */
export function centeredExpandRect(aspect: number | null): { x: number; y: number; w: number; h: number } {
  if (!aspect || !Number.isFinite(aspect) || aspect <= 0) {
    return initialExpandRect()
  }
  // 最小包络：宽或高至少为 1，另一边按比例放大到 ≥1
  let w: number
  let h: number
  if (aspect >= 1) {
    h = 1
    w = Math.max(1, h * aspect)
  } else {
    w = 1
    h = Math.max(1, w / aspect)
  }
  // 略微外扩一点，避免贴边看不出外框
  const pad = 0.06
  w += pad * 2
  h += pad * 2
  return clampExpandRect({ x: (1 - w) / 2, y: (1 - h) / 2, w, h }, aspect)
}

export function clampExpandRect(
  rect: { x: number; y: number; w: number; h: number },
  aspect: number | null = null,
  maxPad = STUDIO_EXPAND_MAX_PAD
): { x: number; y: number; w: number; h: number } {
  let { x, y, w, h } = rect
  // 先保证至少包住原图
  let right = Math.max(1, x + w)
  let bottom = Math.max(1, y + h)
  x = Math.min(0, x)
  y = Math.min(0, y)
  w = right - x
  h = bottom - y

  if (aspect && aspect > 0) {
    // 以当前中心扩展到目标比例，同时仍包住原图
    const cx = x + w / 2
    const cy = y + h / 2
    if (w / h > aspect) {
      h = w / aspect
    } else {
      w = h * aspect
    }
    x = cx - w / 2
    y = cy - h / 2
    right = x + w
    bottom = y + h
    if (x > 0) { w += x; x = 0 }
    if (y > 0) { h += y; y = 0 }
    if (right < 1) { w += 1 - right }
    if (bottom < 1) { h += 1 - bottom }
  }

  // 限制最大外扩
  x = Math.max(-maxPad, Math.min(0, x))
  y = Math.max(-maxPad, Math.min(0, y))
  right = Math.min(1 + maxPad, Math.max(1, x + w))
  bottom = Math.min(1 + maxPad, Math.max(1, y + h))
  w = right - x
  h = bottom - y
  return { x, y, w, h }
}

export type StudioCropHandle =
  | 'nw' | 'n' | 'ne'
  | 'w' | 'e'
  | 'sw' | 's' | 'se'

export function applyCropHandleDelta(
  rect: { x: number; y: number; w: number; h: number },
  handle: StudioCropHandle,
  dx: number,
  dy: number,
  aspect: number | null
): { x: number; y: number; w: number; h: number } {
  let { x, y, w, h } = rect
  const right = x + w
  const bottom = y + h

  if (handle.includes('w')) {
    const nextX = Math.max(0, Math.min(right - 0.12, x + dx))
    w = right - nextX
    x = nextX
  }
  if (handle.includes('e')) {
    w = Math.max(0.12, Math.min(1 - x, w + dx))
  }
  if (handle.includes('n')) {
    const nextY = Math.max(0, Math.min(bottom - 0.12, y + dy))
    h = bottom - nextY
    y = nextY
  }
  if (handle.includes('s')) {
    h = Math.max(0.12, Math.min(1 - y, h + dy))
  }

  return clampCropRect({ x, y, w, h }, aspect)
}

/** 在原图范围内平移裁剪框，保持裁剪框尺寸不变。 */
export function moveCropRect(
  rect: { x: number; y: number; w: number; h: number },
  dx: number,
  dy: number
): { x: number; y: number; w: number; h: number } {
  return {
    ...rect,
    x: Math.max(0, Math.min(1 - rect.w, rect.x + dx)),
    y: Math.max(0, Math.min(1 - rect.h, rect.y + dy))
  }
}

/** 扩图拖拽：只能向外扩，向内收时不能越过原图边界。 */
export function applyExpandHandleDelta(
  rect: { x: number; y: number; w: number; h: number },
  handle: StudioCropHandle,
  dx: number,
  dy: number,
  aspect: number | null
): { x: number; y: number; w: number; h: number } {
  let { x, y, w, h } = rect
  let right = x + w
  let bottom = y + h

  if (handle.includes('w')) {
    // 左边缘：可向左扩（x↓），向右收时不可超过 0
    x = Math.min(0, x + dx)
    w = right - x
  }
  if (handle.includes('e')) {
    // 右边缘：可向右扩，向左收时不可小于 1
    right = Math.max(1, right + dx)
    w = right - x
  }
  if (handle.includes('n')) {
    y = Math.min(0, y + dy)
    h = bottom - y
  }
  if (handle.includes('s')) {
    bottom = Math.max(1, bottom + dy)
    h = bottom - y
  }

  return clampExpandRect({ x, y, w, h }, aspect)
}

/** 浏览器端按归一化矩形裁剪图片，返回 File。 */
export async function cropImageUrlToFile(
  mediaUrl: string,
  rect: { x: number; y: number; w: number; h: number },
  fileBaseName: string
): Promise<File> {
  const { loadStudioCanvasImage } = await import('@/utils/studio/studioCanvasImageLoad')
  const source = await loadStudioCanvasImage(mediaUrl)
  try {
    const sx = Math.round(rect.x * source.width)
    const sy = Math.round(rect.y * source.height)
    const sw = Math.max(1, Math.round(rect.w * source.width))
    const sh = Math.max(1, Math.round(rect.h * source.height))
    const canvas = document.createElement('canvas')
    canvas.width = sw
    canvas.height = sh
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('无法创建画布')
    ctx.drawImage(source.drawable, sx, sy, sw, sh, 0, 0, sw, sh)
    const blob = await new Promise<Blob>((resolve, reject) => {
      canvas.toBlob((value) => (value ? resolve(value) : reject(new Error('裁剪导出失败'))), 'image/png')
    })
    return new File([blob], `${fileBaseName}.png`, { type: 'image/png' })
  } finally {
    source.dispose()
  }
}
