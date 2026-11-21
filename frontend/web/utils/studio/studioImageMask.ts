/** 擦除 / 重绘蒙版：导出带标记的参考图（供 image_edit 识别标记区域）。 */

const MARK_COLOR = { r: 255, g: 64, b: 180, a: 0.55 }

/** 将蒙版 canvas（白=涂抹区）叠加到源图上，导出 PNG File。 */
export async function buildMaskedEditReferenceFile(
  mediaUrl: string,
  maskCanvas: HTMLCanvasElement,
  fileBaseName: string
): Promise<File> {
  const { loadStudioCanvasImage } = await import('@/utils/studio/studioCanvasImageLoad')
  const source = await loadStudioCanvasImage(mediaUrl)
  try {
    const width = source.width
    const height = source.height

    const out = document.createElement('canvas')
    out.width = width
    out.height = height
    const ctx = out.getContext('2d')
    if (!ctx) throw new Error('无法创建画布')

    ctx.drawImage(source.drawable, 0, 0, width, height)

    const scaled = document.createElement('canvas')
    scaled.width = width
    scaled.height = height
    const sctx = scaled.getContext('2d')
    if (!sctx) throw new Error('无法创建蒙版画布')
    sctx.drawImage(maskCanvas, 0, 0, width, height)
    const mask = sctx.getImageData(0, 0, width, height)
    const pixels = ctx.getImageData(0, 0, width, height)

    for (let i = 0; i < mask.data.length; i += 4) {
      const coverage = mask.data[i]! / 255
      if (coverage <= 0.02) continue
      const a = MARK_COLOR.a * coverage
      pixels.data[i] = Math.round(pixels.data[i]! * (1 - a) + MARK_COLOR.r * a)
      pixels.data[i + 1] = Math.round(pixels.data[i + 1]! * (1 - a) + MARK_COLOR.g * a)
      pixels.data[i + 2] = Math.round(pixels.data[i + 2]! * (1 - a) + MARK_COLOR.b * a)
    }
    ctx.putImageData(pixels, 0, 0)

    const blob = await new Promise<Blob>((resolve, reject) => {
      out.toBlob((value) => (value ? resolve(value) : reject(new Error('导出失败'))), 'image/png')
    })
    return new File([blob], `${fileBaseName}-mask-ref.png`, { type: 'image/png' })
  } finally {
    source.dispose()
  }
}

/** Export a separate binary selection mask at the source image's oriented pixel size. */
export async function buildCanvasImageEditMaskFile(mediaUrl: string, maskCanvas: HTMLCanvasElement): Promise<File> {
  const { loadStudioCanvasImage } = await import('@/utils/studio/studioCanvasImageLoad')
  const source = await loadStudioCanvasImage(mediaUrl)
  try {
    if (source.width * source.height > 20_000_000) throw new Error('原图超过图片编辑像素上限')
    const scaled = document.createElement('canvas')
    scaled.width = source.width
    scaled.height = source.height
    const context = scaled.getContext('2d', { willReadFrequently: true })
    if (!context) throw new Error('无法创建蒙版画布')
    context.drawImage(maskCanvas, 0, 0, source.width, source.height)
    const pixels = context.getImageData(0, 0, source.width, source.height)
    let painted = false
    for (let index = 0; index < pixels.data.length; index += 4) {
      const selected = pixels.data[index + 3]! >= 128
      painted ||= selected
      pixels.data[index] = selected ? 255 : 0
      pixels.data[index + 1] = selected ? 255 : 0
      pixels.data[index + 2] = selected ? 255 : 0
      pixels.data[index + 3] = 255
    }
    if (!painted) throw new Error('蒙版没有有效选区')
    context.putImageData(pixels, 0, 0)
    const blob = await new Promise<Blob>((resolve, reject) => {
      scaled.toBlob((value) => value ? resolve(value) : reject(new Error('蒙版导出失败')), 'image/png')
    })
    if (blob.size > 50 * 1024 * 1024) throw new Error('蒙版文件超过 50 MiB')
    return new File([blob], 'image-edit-mask.png', { type: 'image/png' })
  } finally {
    source.dispose()
  }
}

/** 蒙版是否有有效涂抹（任意非透明像素）。 */
export function maskCanvasHasPaint(maskCanvas: HTMLCanvasElement): boolean {
  const ctx = maskCanvas.getContext('2d', { willReadFrequently: true })
  if (!ctx) return false
  const { width, height } = maskCanvas
  if (width < 1 || height < 1) return false
  const data = ctx.getImageData(0, 0, width, height).data
  for (let i = 3; i < data.length; i += 16) {
    if ((data[i] ?? 0) > 8) return true
  }
  return false
}
