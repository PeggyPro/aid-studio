export interface StudioMenuRect {
  left: number
  top: number
  right: number
  bottom: number
}

/** All inputs are measured screen pixels, independent of canvas zoom and rem sizing. */
export function placeStudioContextMenu(input: {
  bounds: StudioMenuRect
  anchor: StudioMenuRect
  size: { width: number; height: number }
  submenu?: boolean
}) {
  const { bounds, anchor, size, submenu = false } = input
  const gap = submenu ? 6 : 8
  const right = anchor.right + gap
  const left = anchor.left - size.width - gap
  const fitsRight = right + size.width <= bounds.right
  const fitsLeft = left >= bounds.left
  const side = fitsRight || (!fitsLeft && bounds.right - anchor.right >= anchor.left - bounds.left)
    ? 'right' : 'left'
  const below = submenu ? anchor.top : anchor.bottom + gap
  const above = submenu ? anchor.bottom - size.height : anchor.top - size.height - gap
  const top = below + size.height <= bounds.bottom ? below : above
  return {
    x: clamp(side === 'right' ? right : left, bounds.left, bounds.right - size.width),
    y: clamp(top, bounds.top, bounds.bottom - size.height),
    side
  }
}

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(value, Math.max(min, max)))
}
