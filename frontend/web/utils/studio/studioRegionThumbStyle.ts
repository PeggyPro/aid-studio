import type { CSSProperties } from 'react'

/** 归一化矩形选区（相对整图 0–1）。 */
export interface StudioNormRect {
  x: number
  y: number
  w: number
  h: number
}

function cssUrl(imageUrl: string): string {
  return `url("${imageUrl.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}")`
}

/**
 * 用 CSS background 裁剪出选区缩略图，不依赖 canvas / CORS。
 * 与创作流 TouchEditModal 预览思路一致，适用于跨域 OSS 图。
 */
export function studioRegionThumbStyle(
  imageUrl: string,
  rect: StudioNormRect,
  sizePx = 28
): CSSProperties {
  const url = imageUrl.trim()
  if (!url || rect.w <= 0 || rect.h <= 0) {
    return {
      width: sizePx,
      height: sizePx,
      background: '#3a3a40'
    }
  }
  const w = Math.min(1, Math.max(0.0001, rect.w))
  const h = Math.min(1, Math.max(0.0001, rect.h))
  const x = Math.min(1 - w, Math.max(0, rect.x))
  const y = Math.min(1 - h, Math.max(0, rect.y))
  const sizeX = 100 / w
  const sizeY = 100 / h
  const posX = w >= 1 ? 0 : (x / (1 - w)) * 100
  const posY = h >= 1 ? 0 : (y / (1 - h)) * 100
  return {
    width: sizePx,
    height: sizePx,
    backgroundImage: cssUrl(url),
    backgroundRepeat: 'no-repeat',
    backgroundSize: `${sizeX}% ${sizeY}%`,
    backgroundPosition: `${posX}% ${posY}%`,
    backgroundColor: '#3a3a40'
  }
}
