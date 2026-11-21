import type { StudioCanvasNode, StudioComposerRef } from '@/types/studio'

/** 收集节点及其引用上可能存在的 blob: URL（用于删除前回收）。 */
export function collectStudioNodeBlobUrls(nodes: Iterable<StudioCanvasNode>): string[] {
  const urls: string[] = []
  for (const node of nodes) {
    const mediaUrl = node.data.mediaUrl
    if (mediaUrl?.startsWith('blob:')) urls.push(mediaUrl)
    for (const ref of node.data.composerRefs ?? []) {
      pushComposerRefBlob(urls, ref)
    }
    const start = node.data.generationOptions?.startFrameUrl
    const end = node.data.generationOptions?.endFrameUrl
    if (start?.startsWith('blob:')) urls.push(start)
    if (end?.startsWith('blob:')) urls.push(end)
  }
  return urls
}

function pushComposerRefBlob(urls: string[], ref: StudioComposerRef): void {
  if (ref.previewUrl?.startsWith('blob:')) urls.push(ref.previewUrl)
}
