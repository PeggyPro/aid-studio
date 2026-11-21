import type { StudioCanvasNode } from '@/types/studio'
import { studioNodeDimensions } from './studioNodeLod'

export type StudioArrangement = 'grid' | 'horizontal' | 'vertical'

export interface SelectionScreenRect {
  left: number
  top: number
  right: number
  bottom: number
  width: number
  height: number
}

export interface SelectionToolbarPosition {
  left: number
  top: number
  width: number
  height: number
  placement: 'top' | 'bottom'
}

const VIEWPORT_MARGIN = 16
const TOOLBAR_GAP = 8
const NODE_GAP = 24

export function placeStudioSelectionToolbar(
  selection: SelectionScreenRect,
  viewport: SelectionScreenRect,
  size: { width: number; height: number }
): SelectionToolbarPosition {
  const centeredLeft = selection.left + (selection.width - size.width) / 2
  const topCandidate = selection.top - TOOLBAR_GAP - size.height
  const placement = topCandidate >= viewport.top + VIEWPORT_MARGIN ? 'top' : 'bottom'
  const top = placement === 'top' ? topCandidate : selection.bottom + TOOLBAR_GAP
  return {
    left: clamp(centeredLeft, viewport.left + VIEWPORT_MARGIN, viewport.right - size.width - VIEWPORT_MARGIN),
    top: clamp(top, viewport.top + VIEWPORT_MARGIN, viewport.bottom - size.height - VIEWPORT_MARGIN),
    width: size.width,
    height: size.height,
    placement
  }
}

export function arrangeStudioNodes(
  nodes: StudioCanvasNode[],
  selectedIds: string[],
  arrangement: StudioArrangement
): StudioCanvasNode[] {
  const selected = new Set(selectedIds)
  const items = nodes
    .filter((node) => selected.has(node.id))
    .sort((a, b) => a.position.y - b.position.y || a.position.x - b.position.x || a.id.localeCompare(b.id))
  if (items.length < 2) return nodes

  const bounds = selectionBounds(items)
  const maxWidth = Math.max(...items.map((node) => studioNodeDimensions(node).width))
  const maxHeight = Math.max(...items.map((node) => studioNodeDimensions(node).height))
  const positions = new Map<string, { x: number; y: number }>()

  if (arrangement === 'grid') {
    const columns = Math.ceil(Math.sqrt(items.length))
    items.forEach((node, index) => {
      positions.set(node.id, {
        x: bounds.left + (index % columns) * (maxWidth + NODE_GAP),
        y: bounds.top + Math.floor(index / columns) * (maxHeight + NODE_GAP)
      })
    })
  } else if (arrangement === 'horizontal') {
    const centerY = (bounds.top + bounds.bottom) / 2
    let cursorX = bounds.left
    for (const node of items) {
      const size = studioNodeDimensions(node)
      positions.set(node.id, { x: cursorX, y: centerY - size.height / 2 })
      cursorX += size.width + NODE_GAP
    }
  } else {
    const centerX = (bounds.left + bounds.right) / 2
    let cursorY = bounds.top
    for (const node of items) {
      const size = studioNodeDimensions(node)
      positions.set(node.id, { x: centerX - size.width / 2, y: cursorY })
      cursorY += size.height + NODE_GAP
    }
  }

  return nodes.map((node) => {
    const position = positions.get(node.id)
    return position ? { ...node, position } : node
  })
}

function selectionBounds(nodes: StudioCanvasNode[]) {
  const left = Math.min(...nodes.map((node) => node.position.x))
  const top = Math.min(...nodes.map((node) => node.position.y))
  const right = Math.max(...nodes.map((node) => node.position.x + studioNodeDimensions(node).width))
  const bottom = Math.max(...nodes.map((node) => node.position.y + studioNodeDimensions(node).height))
  return { left, top, right, bottom }
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(value, Math.max(min, max)))
}
