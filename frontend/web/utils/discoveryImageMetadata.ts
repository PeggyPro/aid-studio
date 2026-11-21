export interface DiscoveryImageDimensions {
  mediaWidth: number
  mediaHeight: number
}

function validDimensions(width: number, height: number): DiscoveryImageDimensions | null {
  const mediaWidth = Math.round(width)
  const mediaHeight = Math.round(height)
  return mediaWidth > 0 && mediaHeight > 0 ? { mediaWidth, mediaHeight } : null
}

function readImageElementDimensions(
  source: string,
  timeoutMs: number
): Promise<DiscoveryImageDimensions> {
  return new Promise((resolve, reject) => {
    if (typeof document === 'undefined' || typeof window === 'undefined') {
      reject(new Error('当前环境无法读取封面信息'))
      return
    }
    const image = document.createElement('img')
    let settled = false
    const finish = (result: DiscoveryImageDimensions | Error) => {
      if (settled) return
      settled = true
      window.clearTimeout(timer)
      image.onload = null
      image.onerror = null
      if (result instanceof Error) reject(result)
      else resolve(result)
    }
    const timer = window.setTimeout(
      () => finish(new Error('读取封面信息超时，请重新选择')),
      timeoutMs
    )
    image.onload = () => {
      const dimensions = validDimensions(image.naturalWidth, image.naturalHeight)
      finish(dimensions ?? new Error('无法识别封面尺寸，请更换文件'))
    }
    image.onerror = () => finish(new Error('封面文件无法读取，请更换文件'))
    image.src = source
  })
}

/** 从本地封面读取像素尺寸；优先使用 ImageBitmap，失败时回退 img 对象 URL。 */
export async function readDiscoveryImageDimensions(
  file: File,
  timeoutMs = 15_000
): Promise<DiscoveryImageDimensions> {
  if (typeof window !== 'undefined' && typeof window.createImageBitmap === 'function') {
    try {
      const bitmap = await window.createImageBitmap(file)
      try {
        const dimensions = validDimensions(bitmap.width, bitmap.height)
        if (dimensions) return dimensions
      } finally {
        bitmap.close()
      }
    } catch {
      // 部分浏览器无法用 ImageBitmap 解码特定格式，继续回退到 img。
    }
  }
  if (typeof document === 'undefined' || typeof URL === 'undefined') {
    throw new Error('当前环境无法读取封面信息')
  }
  const objectUrl = URL.createObjectURL(file)
  try {
    return await readImageElementDimensions(objectUrl, timeoutMs)
  } finally {
    URL.revokeObjectURL(objectUrl)
  }
}

/** 编辑已有视频投稿时预读封面尺寸，以支持删除视频后仅保留封面重新投稿。 */
export function readDiscoveryImageUrlDimensions(
  url: string,
  timeoutMs = 15_000
): Promise<DiscoveryImageDimensions> {
  const source = String(url || '').trim()
  if (!source) return Promise.reject(new Error('封面地址为空'))
  return readImageElementDimensions(source, timeoutMs)
}
