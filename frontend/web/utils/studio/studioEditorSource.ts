import type { StudioCanvasNode, StudioEditorSourceIdentity, StudioNodeEditorSession } from '@/types/studio'

export function studioEditorSource(node: StudioCanvasNode | undefined): StudioEditorSourceIdentity | undefined {
  return node ? { resultId: node.data.canvasCurrentResultId, resourceId: node.data.canvasPrimaryResourceId,
    mediaUrl: node.data.mediaUrl } : undefined
}

export function studioEditorSourceChanged(source: StudioEditorSourceIdentity | undefined, node: StudioCanvasNode): boolean {
  if (!source) return false
  if (source.resultId && node.data.canvasCurrentResultId) return source.resultId !== node.data.canvasCurrentResultId
  if (source.resourceId && node.data.canvasPrimaryResourceId) return source.resourceId !== node.data.canvasPrimaryResourceId
  return source.mediaUrl !== node.data.mediaUrl
}

/** Explicitly accepting another source remounts coordinate editors; ordinary snapshots do not. */
export function studioEditorSessionKey(session: StudioNodeEditorSession & { nodeId: string }): string {
  return session.nodeId + ':' + JSON.stringify(session.sourceIdentity ?? null)
}
