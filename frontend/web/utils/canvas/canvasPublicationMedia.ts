import type { StudioCanvasNode } from '@/types/studio'

export type CanvasPublicationMediaType = 'VIDEO' | 'IMAGE'

/** Publishing uses only a node's current, durable media, never a stale version or a local placeholder. */
export function canvasPublicationMediaNodes(nodes: StudioCanvasNode[], type: CanvasPublicationMediaType) {
  return nodes.filter(({ data }) => {
    if (data.kind === 'frame_breakdown') return false
    const kind = data.mediaKind || (data.kind === 'video' ? 'video' : data.kind === 'image' ? 'image' : '')
    const registered=/^[1-9]\d*$/.test(data.canvasPrimaryResourceId||'')
    return kind === type.toLowerCase() && !!data.mediaUrl && !/^data:/i.test(data.mediaUrl) && (!/^blob:/i.test(data.mediaUrl)||registered)
      && !data.canvasTransient && data.status !== 'generating' && data.status !== 'queued'
  })
}

export function canvasPublicationSourceKey(node: StudioCanvasNode) {
  // Registering the same media may fill canvasPrimaryResourceId; that is not a source change.
  return JSON.stringify([node.id, node.data.mediaUrl, node.data.mediaKind, node.data.canvasCurrentResultId])
}
