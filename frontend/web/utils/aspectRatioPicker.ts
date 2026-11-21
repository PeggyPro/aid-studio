export interface AspectRatioOption<T extends string = string> {
  value: T
  label: string
}

export interface AspectRatioSize {
  width: number
  height: number
}

/** 从 `16:9` 这类文案解析宽高；解析失败返回 null。 */
export function parseAspectRatioParts(value: string): AspectRatioSize | null {
  const match = String(value || '')
    .trim()
    .match(/^(\d+(?:\.\d+)?)\s*:\s*(\d+(?:\.\d+)?)$/)
  if (!match) return null
  const width = Number(match[1])
  const height = Number(match[2])
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    return null
  }
  return { width, height }
}

/** 把比例缩放到 maxSize 的方框里，供弹出面板里的比例图标使用。 */
export function fitAspectRatioIconSize(value: string, maxSize = 18): AspectRatioSize {
  const parts = parseAspectRatioParts(value)
  if (!parts) return { width: maxSize, height: maxSize }
  const scale = maxSize / Math.max(parts.width, parts.height)
  return {
    width: Math.max(6, Math.round(parts.width * scale)),
    height: Math.max(6, Math.round(parts.height * scale))
  }
}
