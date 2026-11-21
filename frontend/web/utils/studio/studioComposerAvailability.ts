import type { StudioCanvasNode, StudioMode } from '@/types/studio'
import type { StudioNodeAgentDockMode } from './studioNodeAgentDockLayout'

/**
 * Kinds that already own a dedicated free-canvas surface. The node-bottom
 * composer is the generation prompt, so these kinds must not grow a second one.
 */
const FREE_CANVAS_SURFACE_KINDS = new Set<StudioCanvasNode['data']['kind']>(['director', 'smart_edit'])

/**
 * `composerHidden` is persisted workflow intent. A generated media URL must not
 * turn a one-way tool result back into another editable composer entry point.
 */
export function shouldHideStudioNodeComposer(
  node: StudioCanvasNode | undefined,
  dockMode: StudioNodeAgentDockMode = 'composer',
  studioMode?: StudioMode
) {
  if (dockMode !== 'composer') return false
  if (studioMode === 'free' && node && FREE_CANVAS_SURFACE_KINDS.has(node.data.kind)) return true
  return Boolean(node?.data.composerHidden && !node.data.storyboardCompositionId)
}
