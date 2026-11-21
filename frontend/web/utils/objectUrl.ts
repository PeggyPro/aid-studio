/**
 * 成对管理 ObjectURL，避免预览/下载后泄漏。
 * createTrackedObjectUrl 登记；revokeObjectUrl 统一释放（含未登记的 blob: 兼容旧路径）。
 */

const trackedObjectUrls = new Set<string>()

export function createTrackedObjectUrl(blob: Blob): string {
  const url = URL.createObjectURL(blob)
  trackedObjectUrls.add(url)
  return url
}

export function revokeObjectUrl(url: string | null | undefined): void {
  if (!url || !url.startsWith('blob:')) return
  trackedObjectUrls.delete(url)
  try {
    URL.revokeObjectURL(url)
  } catch {
    /* ignore */
  }
}

export function revokeObjectUrls(urls: Iterable<string | null | undefined>): void {
  for (const url of urls) revokeObjectUrl(url)
}

/** 测试用：当前登记数量（不暴露 Set 本身）。 */
export function trackedObjectUrlCount(): number {
  return trackedObjectUrls.size
}

/** 在同步使用 ObjectURL 后立即释放（如下载链接 click） */
export function withObjectUrl(blob: Blob, consume: (url: string) => void): void {
  const url = createTrackedObjectUrl(blob)
  try {
    consume(url)
  } finally {
    revokeObjectUrl(url)
  }
}
