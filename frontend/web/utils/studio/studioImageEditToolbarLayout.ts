import type { StudioAgentDockRect } from '@/utils/studio/studioNodeAgentDockLayout'

const VIEWPORT_MARGIN = 16
export const STUDIO_IMAGE_EDIT_TOOLBAR_GAP_PX = 14

export function placeStudioImageEditToolbar(
  selection: StudioAgentDockRect,
  viewport: StudioAgentDockRect,
  size: { width: number; height: number }
): { left: number; top: number; width: number; height: number } {
  const width = Math.min(size.width, Math.max(1, viewport.width - VIEWPORT_MARGIN * 2))
  const height = size.height
  const centeredLeft = selection.left + selection.width / 2 - width / 2
  const left = Math.max(
    viewport.left + VIEWPORT_MARGIN,
    Math.min(centeredLeft, viewport.right - width - VIEWPORT_MARGIN)
  )
  const top = selection.bottom + STUDIO_IMAGE_EDIT_TOOLBAR_GAP_PX
  return { left, top, width, height }
}
