import type { StudioTouchEditRect } from '@/types/studio'

/** 从图片 URL 裁剪归一化矩形缩略图（data URL）。失败返回空串。 */
export async function cropTouchEditThumbnail(
  imageUrl: string,
  rect: StudioTouchEditRect,
  size = 48
): Promise<string> {
  if (!imageUrl || rect.w <= 0 || rect.h <= 0) return ''
  return new Promise((resolve) => {
    const img = new Image()
    img.crossOrigin = 'anonymous'
    img.onload = () => {
      try {
        const sx = Math.max(0, Math.floor(rect.x * img.naturalWidth))
        const sy = Math.max(0, Math.floor(rect.y * img.naturalHeight))
        const sw = Math.max(1, Math.floor(rect.w * img.naturalWidth))
        const sh = Math.max(1, Math.floor(rect.h * img.naturalHeight))
        const canvas = document.createElement('canvas')
        canvas.width = size
        canvas.height = size
        const ctx = canvas.getContext('2d')
        if (!ctx) {
          resolve('')
          return
        }
        ctx.drawImage(img, sx, sy, sw, sh, 0, 0, size, size)
        resolve(canvas.toDataURL('image/jpeg', 0.85))
      } catch {
        resolve('')
      }
    }
    img.onerror = () => resolve('')
    img.src = imageUrl
  })
}
