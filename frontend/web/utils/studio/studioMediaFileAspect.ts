/** 从本地图片/视频文件读取宽高比，供乐观入库节点立刻定尺寸。 */
export function readMediaFileAspectRatio(file: File): Promise<number | null> {
  if (file.type.startsWith('image/')) {
    return new Promise((resolve) => {
      const url = URL.createObjectURL(file)
      const img = new Image()
      img.onload = () => {
        const ratio = img.naturalWidth > 0 && img.naturalHeight > 0
          ? img.naturalWidth / img.naturalHeight
          : null
        URL.revokeObjectURL(url)
        resolve(ratio && Number.isFinite(ratio) && ratio > 0 ? ratio : null)
      }
      img.onerror = () => {
        URL.revokeObjectURL(url)
        resolve(null)
      }
      img.src = url
    })
  }
  if (file.type.startsWith('video/')) {
    return new Promise((resolve) => {
      const url = URL.createObjectURL(file)
      const video = document.createElement('video')
      video.preload = 'metadata'
      video.onloadedmetadata = () => {
        const ratio = video.videoWidth > 0 && video.videoHeight > 0
          ? video.videoWidth / video.videoHeight
          : null
        URL.revokeObjectURL(url)
        resolve(ratio && Number.isFinite(ratio) && ratio > 0 ? ratio : null)
      }
      video.onerror = () => {
        URL.revokeObjectURL(url)
        resolve(null)
      }
      video.src = url
    })
  }
  return Promise.resolve(null)
}
