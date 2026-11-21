import type { StudioCanvasNode, StudioNodeKind } from '@/types/studio'

/** 图片节点：非 grid_storyboard；有 prompt / 构图 / 媒体之一则可尝试整组执行 */
export function isStudioNodeBatchExecutable(node: StudioCanvasNode): boolean {
  if (node.data.kind !== 'image') return false
  if (node.data.imagePurpose === 'grid_storyboard') return false
  if (node.data.composerHidden) return false
  if (node.data.flowBinding) return false
  const prompt = node.data.prompt?.trim()
  return Boolean(prompt || node.data.storyboardCompositionId || node.data.mediaUrl)
}

export function listGroupExecutableNodeIds(
  nodes: StudioCanvasNode[],
  childIds: string[]
): string[] {
  const byId = new Map(nodes.map((node) => [node.id, node]))
  return childIds.filter((id) => {
    const node = byId.get(id)
    return node ? isStudioNodeBatchExecutable(node) : false
  })
}

export function listGroupDownloadableMedia(
  nodes: StudioCanvasNode[],
  childIds: string[]
): Array<{ url: string; title: string; kind: StudioNodeKind }> {
  const byId = new Map(nodes.map((node) => [node.id, node]))
  const items: Array<{ url: string; title: string; kind: StudioNodeKind }> = []
  for (const id of childIds) {
    const node = byId.get(id)
    const url = node?.data.mediaUrl?.trim()
    if (!node || !url) continue
    items.push({
      url,
      title: node.data.title || id,
      kind: node.data.kind
    })
  }
  return items
}
