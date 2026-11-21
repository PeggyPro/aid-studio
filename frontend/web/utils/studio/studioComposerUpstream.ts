import type { StudioCanvasNode, StudioFlowEdge, StudioMediaKind } from '@/types/studio'

export type StudioComposerUpstreamThumb = {
  key: string
  previewUrl: string
  title: string
  nodeId: string
  mediaKind: StudioMediaKind
}

/** 本节点入边上游、带图片 media 的缩略图候选（纯派生，无 store）。 */
export function listStudioComposerUpstreamThumbs(
  nodeId: string,
  nodes: StudioCanvasNode[],
  edges: StudioFlowEdge[]
): StudioComposerUpstreamThumb[] {
  const byId = new Map(nodes.map((item) => [item.id, item]))
  const target = byId.get(nodeId)
  const acceptsSourceVideo = target?.data.videoPurpose === 'reshoot'
    || target?.data.videoPurpose === 'continuation'
  const result: StudioComposerUpstreamThumb[] = []
  for (const edge of edges) {
    if (edge.target !== nodeId) continue
    const source = byId.get(edge.source)
    if (!source?.data.mediaUrl) continue
    const mediaKind = source.data.mediaKind
      ?? (source.data.kind === 'video' ? 'video' : source.data.kind === 'image' ? 'image' : undefined)
    if (mediaKind !== 'image' && mediaKind !== 'video') continue
    if (mediaKind === 'video' && !acceptsSourceVideo) continue
    result.push({
      key: source.id,
      nodeId: source.id,
      previewUrl: source.data.mediaUrl,
      title: source.data.title,
      mediaKind
    })
  }
  return result
}

/** 订阅用签名：上游媒体身份未变则字符串不变，避免 Composer 跟整表 nodes 抖动。 */
export function studioComposerUpstreamSignature(
  nodeId: string,
  nodes: StudioCanvasNode[],
  edges: StudioFlowEdge[]
): string {
  return listStudioComposerUpstreamThumbs(nodeId, nodes, edges)
    .map((item) => `${item.nodeId}\0${item.previewUrl}\0${item.title}\0${item.mediaKind}`)
    .join('\n')
}
