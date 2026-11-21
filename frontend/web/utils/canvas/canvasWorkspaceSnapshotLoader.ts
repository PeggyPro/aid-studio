import type { CanvasWorkspaceLoad } from '@/types/canvas-api'
import { canvasWorkspaceChanges, canvasWorkspaceLoad } from '@/utils/business/canvas'
import { CanvasDeltaGapError, mergeCanvasWorkspaceDelta } from './canvasWorkspaceDelta'

/** Load one authoritative workspace version, falling back only for a broken delta. */
export async function loadCanvasWorkspaceSnapshot(
  projectId: string,
  workspaceId: string,
  previous: CanvasWorkspaceLoad | null,
  api = { load: canvasWorkspaceLoad, changes: canvasWorkspaceChanges }
): Promise<CanvasWorkspaceLoad> {
  if (!previous || String(previous.workspace.projectId) !== projectId) return api.load(projectId)

  // A failed HTTP request is not evidence that the delta is incomplete.
  const latest = await api.load(projectId, previous.workspace.eventSeq)
  if (latest.mode === 'SNAPSHOT') return latest

  try {
    const events = [...(latest.changes || [])]
    let cursor = Number(latest.lastSeq ?? previous.workspace.eventSeq)
    let hasMore = Boolean(latest.hasMore)
    let pages = 0
    while (hasMore && pages < 20) {
      const page = await api.changes({ workspaceId, afterSeq: cursor, limit: 200 })
      if (!page.items?.length || Number(page.lastSeq) <= cursor) throw new CanvasDeltaGapError()
      events.push(...page.items)
      cursor = Number(page.lastSeq)
      hasMore = Boolean(page.hasMore)
      pages += 1
    }
    if (hasMore || cursor > Number(latest.currentSeq ?? latest.workspace.eventSeq)) throw new CanvasDeltaGapError()
    return mergeCanvasWorkspaceDelta(previous, latest, events)
  } catch (error) {
    if (!(error instanceof CanvasDeltaGapError)) throw error
    return api.load(projectId)
  }
}
