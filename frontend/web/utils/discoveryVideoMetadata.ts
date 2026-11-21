export interface DiscoveryVideoDimensions {
  mediaWidth: number
  mediaHeight: number
}

/** 从本地视频读取像素尺寸；对象 URL 在成功、失败和超时路径都会释放。 */
export function readDiscoveryVideoDimensions(
  file: File,
  timeoutMs = 15_000
): Promise<DiscoveryVideoDimensions> {
  return new Promise((resolve, reject) => {
    if (typeof document === 'undefined' || typeof URL === 'undefined') {
      reject(new Error('当前环境无法读取视频信息'))
      return
    }

    const video = document.createElement('video')
    const objectUrl = URL.createObjectURL(file)
    let settled = false
    const finish = (
      result: { ok: true; dimensions: DiscoveryVideoDimensions } | { ok: false; error: Error }
    ) => {
      if (settled) return
      settled = true
      window.clearTimeout(timer)
      video.removeAttribute('src')
      video.load()
      URL.revokeObjectURL(objectUrl)
      if (result.ok) resolve(result.dimensions)
      else reject(result.error)
    }
    const timer = window.setTimeout(
      () => finish({ ok: false, error: new Error('读取视频信息超时，请重新选择') }),
      timeoutMs
    )

    video.preload = 'metadata'
    video.muted = true
    video.onloadedmetadata = () => {
      const mediaWidth = Math.round(video.videoWidth)
      const mediaHeight = Math.round(video.videoHeight)
      if (mediaWidth <= 0 || mediaHeight <= 0) {
        finish({ ok: false, error: new Error('无法识别视频尺寸，请更换文件') })
        return
      }
      finish({ ok: true, dimensions: { mediaWidth, mediaHeight } })
    }
    video.onerror = () =>
      finish({ ok: false, error: new Error('视频文件无法读取，请更换文件') })
    video.src = objectUrl
  })
}
