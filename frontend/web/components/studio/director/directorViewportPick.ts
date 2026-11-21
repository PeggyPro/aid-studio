export const DIRECTOR_VIEWPORT_CLICK_PX = 5
export const DIRECTOR_VIEWPORT_DOUBLE_CLICK_MS = 350

export function isDirectorViewportClick(dx: number, dy: number, threshold = DIRECTOR_VIEWPORT_CLICK_PX): boolean {
  return (dx * dx + dy * dy) <= threshold * threshold
}

export type DirectorViewportPickAction =
  | { type: 'keep' }
  | { type: 'select'; id: string | null }

/** 点到变换手柄且没命中对象时保持原选中；否则按命中结果选中或取消选中。 */
export function resolveDirectorViewportPick(helperHit: boolean, objectId: string | null): DirectorViewportPickAction {
  if (helperHit && !objectId) return { type: 'keep' }
  return { type: 'select', id: objectId }
}

/** 点到的不是当前挂着手柄的对象时，临时停用变换手柄，但保留轨道拖动事件。 */
export function shouldSuspendDirectorTransformForPointer(options: {
  pickedId: string | null
  selectedId: string | null
  dragging: boolean
}): boolean {
  if (options.dragging || !options.pickedId) return false
  return options.pickedId !== options.selectedId
}

export function shouldApplyDirectorStoredCameraPose(orbitEcho: boolean): boolean {
  return !orbitEcho
}

export function isDirectorViewportDoubleClick(
  previous: { id: string; at: number } | null,
  nextId: string,
  now: number,
  windowMs = DIRECTOR_VIEWPORT_DOUBLE_CLICK_MS
): boolean {
  return Boolean(previous && previous.id === nextId && now - previous.at <= windowMs)
}
