'use client'

import { fetchMediaBlob } from '@/utils/mediaFetch'

export interface StudioCanvasImageSource {
  drawable: CanvasImageSource
  width: number
  height: number
  dispose: () => void
}

/** 拉取 OSS/CDN 图片并解码，供 Canvas 裁剪 / 蒙版 / 抠图使用。 */
export async function loadStudioCanvasImage(url: string, signal?: AbortSignal): Promise<StudioCanvasImageSource> {
  const remote = String(url || '').trim()
  if (!remote) throw new Error('图片地址为空')
  const blob = await fetchMediaBlob(remote, signal)
  if (!blob) throw new Error('图片读取失败，请检查网络或媒体代理配置')
  return decodeStudioCanvasImageBlob(blob)
}

export async function decodeStudioCanvasImageBlob(blob: Blob): Promise<StudioCanvasImageSource> {
  if (typeof window !== 'undefined' && typeof window.createImageBitmap === 'function') {
    try {
      const bitmap = await window.createImageBitmap(blob)
      return {
        drawable: bitmap,
        width: bitmap.width,
        height: bitmap.height,
        dispose: () => bitmap.close()
      }
    } catch {
      // 部分浏览器对 createImageBitmap 支持不完整，回退到 img + objectUrl
    }
  }
  if (typeof document === 'undefined' || typeof URL === 'undefined') {
    throw new Error('当前环境不支持浏览器图片解码')
  }
  const objectUrl = URL.createObjectURL(blob)
  const image = document.createElement('img')
  try {
    await new Promise<void>((resolve, reject) => {
      image.onload = () => resolve()
      image.onerror = () => reject(new Error('图片格式无法解码'))
      image.src = objectUrl
    })
    if (image.naturalWidth < 1 || image.naturalHeight < 1) {
      throw new Error('无效的图片尺寸')
    }
    return {
      drawable: image,
      width: image.naturalWidth,
      height: image.naturalHeight,
      dispose: () => URL.revokeObjectURL(objectUrl)
    }
  } catch (error) {
    URL.revokeObjectURL(objectUrl)
    throw error
  }
}
