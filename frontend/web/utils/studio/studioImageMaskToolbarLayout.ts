import type { StudioAgentDockRect } from '@/utils/studio/studioNodeAgentDockLayout'
import { STUDIO_IMAGE_EDIT_TOOLBAR_GAP_PX } from '@/utils/studio/studioImageEditToolbarLayout'

const VIEWPORT_MARGIN = 16

/** 擦除 / 重绘顶部工具栏：固定放在节点上方（屏幕坐标，不随 zoom 缩放）。 */
export function placeStudioImageMaskTopToolbar(
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
  // 画笔与矩形选区始终属于图片顶部；顶部空间不足时随节点裁出视口，不翻到底部。
  const top = selection.top - STUDIO_IMAGE_EDIT_TOOLBAR_GAP_PX - height
  return { left, top, width, height }
}
