import { studioNodeVisualRect } from './studioNodeVisualBounds'

export const STUDIO_NODE_AGENT_DOCK_WIDTH_PX = 660
export const STUDIO_NODE_AGENT_DOCK_MAX_HEIGHT_PX = 400
export const STUDIO_NODE_LIGHTING_DOCK_WIDTH_PX = 550
export const STUDIO_NODE_LIGHTING_DOCK_HEIGHT_PX = 400
export const STUDIO_NODE_MULTI_ANGLE_DOCK_WIDTH_PX = 680
export const STUDIO_NODE_MULTI_ANGLE_DOCK_HEIGHT_PX = 420
export const STUDIO_NODE_HD_DOCK_WIDTH_PX = 520
export const STUDIO_NODE_HD_DOCK_HEIGHT_PX = 260
export const STUDIO_NODE_AGENT_DOCK_GAP_PX = 12
export const STUDIO_NODE_AGENT_DOCK_EXPAND_ONLY_THRESHOLD_PX = 160
export const STUDIO_NODE_AGENT_DOCK_RECOVERY_HEIGHT_PX = 64
/**
 * 选中后贴在卡片下方的默认输入区高度（屏幕像素）。
 * 内边距 14+16、顶部操作行 32、间距 11、提示区最小 88、再一档间距、底部栏 36。
 */
export const STUDIO_NODE_AGENT_DOCK_RESTING_HEIGHT_PX = 14 + 16 + 32 + 11 + 88 + 11 + 36

export type StudioAgentDockPlacement = 'bottom' | 'top' | 'center'
export type StudioNodeAgentDockMode = 'composer' | 'emotion' | 'lighting' | 'multiAngle' | 'hd' | 'videoDepth' | 'audio'

export interface StudioAgentDockRect {
  left: number
  top: number
  right: number
  bottom: number
  width: number
  height: number
}

export interface StudioAgentDockPosition {
  left: number
  top: number
  width: number
  height: number
  placement: StudioAgentDockPlacement
}

const VIEWPORT_MARGIN = 16

/** Screen-space canvas viewport, independent of the selected node and zoom. */
export function placeCenteredStudioNodeComposer(viewport: StudioAgentDockRect): StudioAgentDockPosition {
  const width = Math.min(920, Math.max(1, viewport.width - 48))
  const height = Math.min(760, Math.max(1, viewport.height - 48))
  return {
    left: viewport.left + (viewport.width - width) / 2,
    top: viewport.top + (viewport.height - height) / 2,
    width,
    height,
    placement: 'center'
  }
}

/** 两种画布共享同一套完整 Composer 尺寸，窄视口由定位器继续收缩。 */
export function resolveStudioNodeAgentDockSize(
  measured: Pick<StudioAgentDockRect, 'width' | 'height'>,
  mode: StudioNodeAgentDockMode = 'composer'
): { width: number; height: number } {
  if (mode === 'emotion') return { width: 600, height: Math.max(measured.height, 410) }
  if (mode === 'videoDepth') return { width: 350, height: Math.max(measured.height, 176) }
  if (mode === 'audio') return { width: 440, height: Math.max(measured.height, 56) }
  if (mode === 'lighting') {
    return {
      width: STUDIO_NODE_LIGHTING_DOCK_WIDTH_PX,
      height: STUDIO_NODE_LIGHTING_DOCK_HEIGHT_PX
    }
  }
  if (mode === 'multiAngle') {
    return {
      width: STUDIO_NODE_MULTI_ANGLE_DOCK_WIDTH_PX,
      height: STUDIO_NODE_MULTI_ANGLE_DOCK_HEIGHT_PX
    }
  }
  if (mode === 'hd') {
    return {
      width: STUDIO_NODE_HD_DOCK_WIDTH_PX,
      height: STUDIO_NODE_HD_DOCK_HEIGHT_PX
    }
  }
  return {
    width: STUDIO_NODE_AGENT_DOCK_WIDTH_PX,
    height: Math.min(STUDIO_NODE_AGENT_DOCK_MAX_HEIGHT_PX, Math.max(measured.height, STUDIO_NODE_AGENT_DOCK_RESTING_HEIGHT_PX))
  }
}

/**
 * 输入框始终贴在选区下方外侧，绝不压住节点，也不翻到上方。
 * 双击放大后视口下方不够时仍落在节点底部；可超出视口，由画布平移承接。
 */
export function placeStudioNodeAgentDock(
  selection: StudioAgentDockRect,
  viewport: StudioAgentDockRect,
  size: { width: number; height: number }
): StudioAgentDockPosition {
  const width = Math.min(size.width, Math.max(1, viewport.width - VIEWPORT_MARGIN * 2))
  const height = size.height
  const centeredLeft = selection.left + selection.width / 2 - width / 2
  const left = clamp(
    centeredLeft,
    viewport.left + VIEWPORT_MARGIN,
    viewport.right - width - VIEWPORT_MARGIN
  )
  const top = selection.bottom + STUDIO_NODE_AGENT_DOCK_GAP_PX

  return { left, top, width, height, placement: 'bottom' }
}

/** Free-canvas creation panels follow the node bottom, including expanded and offscreen states.
 * Limit the dock to the real space below the node. If only its recovery entry fits,
 * keep that entry inside the viewport without moving the canvas or selected node.
 * Editing popovers retain their separate viewport collision policy.
 */
export function placeStudioNodeCreationDock(
  selection: StudioAgentDockRect,
  viewport: StudioAgentDockRect,
  size: { width: number; height: number },
  options: { expanded?: boolean; constrainAvailableHeight?: boolean; keepRecoveryEntryVisible?: boolean } = {}
): StudioNodeAgentExpandedPosition {
  const expanded = Boolean(options.expanded)
  const height = expanded ? Math.max(size.height, 430) : Math.max(1, size.height)
  const position = placeStudioNodeAgentDock(selection, viewport, { ...size, height })
  const minimumRecoveryTop = Math.max(
    viewport.top + VIEWPORT_MARGIN,
    viewport.bottom - VIEWPORT_MARGIN - STUDIO_NODE_AGENT_DOCK_RECOVERY_HEIGHT_PX
  )
  const top = options.keepRecoveryEntryVisible ? Math.min(position.top, minimumRecoveryTop) : position.top
  const availableBelow = Math.max(1, viewport.bottom - top - VIEWPORT_MARGIN)
  return {
    ...position,
    top,
    availableHeight: options.constrainAvailableHeight ? availableBelow : Math.max(height, availableBelow)
  }
}

/** Expanded editors prefer the side that can keep the fixed action bar in view. */
export function placeExpandedStudioNodeAgentDock(
  selection: StudioAgentDockRect,
  viewport: StudioAgentDockRect,
  measured: { width: number; height: number }
): StudioNodeAgentExpandedPosition {
  const base = placeStudioNodeAgentDock(selection, viewport, measured)
  const below = viewport.bottom - base.top - VIEWPORT_MARGIN
  const above = selection.top - viewport.top - STUDIO_NODE_AGENT_DOCK_GAP_PX - VIEWPORT_MARGIN
  if (below >= 320 || below >= above) {
    return { ...base, availableHeight: Math.max(140, below) }
  }
  const availableHeight = Math.max(140, above)
  const desiredHeight = Math.min(640, Math.max(measured.height, 430), viewport.height * .72, availableHeight)
  return {
    ...base,
    top: selection.top - STUDIO_NODE_AGENT_DOCK_GAP_PX - desiredHeight,
    placement: 'top',
    availableHeight
  }
}

export interface StudioNodeAgentExpandedPosition extends StudioAgentDockPosition {
  availableHeight: number
}

export type StudioNodeAgentDockViewportLayout = 'full' | 'scroll' | 'expand-only'

/**
 * Keep the complete dock when it fits, scroll only its content when it is short,
 * and leave the centered-editor entry reachable when even the fixed chrome cannot fit.
 */
export function resolveStudioNodeAgentDockViewportLayout(
  availableHeight: number,
  desiredHeight: number,
  companionHeight = 0
): StudioNodeAgentDockViewportLayout {
  const composerHeight = availableHeight - Math.max(0, companionHeight)
  if (composerHeight < STUDIO_NODE_AGENT_DOCK_EXPAND_ONLY_THRESHOLD_PX) return 'expand-only'
  return availableHeight < desiredHeight ? 'scroll' : 'full'
}

/** Specialized editing popovers keep their action bar inside the visible canvas without moving nodes. */
export function placeVisibleStudioNodeAgentDock(
  selection: StudioAgentDockRect,
  viewport: StudioAgentDockRect,
  size: { width: number; height: number },
  reservedAbove?: StudioAgentDockRect | null
): StudioNodeAgentExpandedPosition {
  const marginX = Math.min(VIEWPORT_MARGIN, Math.max(0, (viewport.width - 1) / 2))
  const marginY = Math.min(VIEWPORT_MARGIN, Math.max(0, (viewport.height - 1) / 2))
  const viewportHeight = Math.max(1, viewport.height - marginY * 2)
  const width = Math.min(size.width, Math.max(1, viewport.width - marginX * 2))
  const height = Math.min(size.height, viewportHeight)
  const minTop = viewport.top + marginY
  const maxBottom = viewport.bottom - marginY
  const belowTop = selection.bottom + STUDIO_NODE_AGENT_DOCK_GAP_PX
  const aboveBottom = Math.min(selection.top - STUDIO_NODE_AGENT_DOCK_GAP_PX,
    reservedAbove ? reservedAbove.top - STUDIO_NODE_AGENT_DOCK_GAP_PX : Number.POSITIVE_INFINITY)
  const below = Math.max(0, maxBottom - belowTop)
  const above = Math.max(0, aboveBottom - minTop)
  const placement: StudioAgentDockPlacement = below >= height ? 'bottom' : above >= height ? 'top' : below >= above ? 'bottom' : 'top'
  const available = placement === 'bottom' ? below : above
  const visibleHeight = available >= 140 ? Math.min(height, available) : height
  const top = clamp(placement === 'bottom' ? belowTop : aboveBottom - visibleHeight, minTop, maxBottom - visibleHeight)
  return {
    left: clamp(selection.left + selection.width / 2 - width / 2, viewport.left + marginX, viewport.right - width - marginX),
    top, width, height: visibleHeight, placement,
    availableHeight: Math.min(viewportHeight, Math.max(visibleHeight, placement === 'top' && top + height <= aboveBottom ? above : maxBottom - top))
  }
}

export function rectsOverlap(
  a: Pick<StudioAgentDockRect, 'left' | 'top' | 'right' | 'bottom'>,
  b: Pick<StudioAgentDockRect, 'left' | 'top' | 'right' | 'bottom'>,
  gap = 0
): boolean {
  return !(
    a.right + gap <= b.left ||
    b.right + gap <= a.left ||
    a.bottom + gap <= b.top ||
    b.bottom + gap <= a.top
  )
}

/** 按真实 DOM 量选区，避免媒体加载后高度变化仍用旧占位尺寸。 */
export function measureStudioNodesInCanvas(
  canvas: HTMLElement,
  nodeIds: string[]
): StudioAgentDockRect | null {
  if (!nodeIds.length) return null
  const canvasRect = canvas.getBoundingClientRect()
  let left = Number.POSITIVE_INFINITY
  let top = Number.POSITIVE_INFINITY
  let right = Number.NEGATIVE_INFINITY
  let bottom = Number.NEGATIVE_INFINITY
  let found = false
  for (const id of nodeIds) {
    const safeId = typeof CSS !== 'undefined' && typeof CSS.escape === 'function'
      ? CSS.escape(id)
      : id.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
    const element = canvas.querySelector(`.react-flow__node[data-id="${safeId}"]`)
    if (!(element instanceof HTMLElement)) continue
    const rect = studioNodeVisualRect(element)
    found = true
    left = Math.min(left, rect.left - canvasRect.left)
    top = Math.min(top, rect.top - canvasRect.top)
    right = Math.max(right, rect.right - canvasRect.left)
    bottom = Math.max(bottom, rect.bottom - canvasRect.top)
  }
  if (!found) return null
  return {
    left,
    top,
    right,
    bottom,
    width: right - left,
    height: bottom - top
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(value, Math.max(min, max)))
}
