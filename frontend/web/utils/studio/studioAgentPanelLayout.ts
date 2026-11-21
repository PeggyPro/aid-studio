export type StudioAgentPanelMode = 'docked' | 'floating'
export type StudioAgentPanelEdge =
  | 'left'
  | 'right'
  | 'top'
  | 'bottom'
  | 'top-left'
  | 'top-right'
  | 'bottom-left'
  | 'bottom-right'
export type StudioAgentPanelViewport = { width: number; height: number }
export type StudioAgentPanelRect = { x: number; y: number; width: number; height: number }

export interface StudioAgentPanelLayout {
  mode: StudioAgentPanelMode
  dockedWidth: number
  floatingRect: StudioAgentPanelRect
}

export const STUDIO_AGENT_PANEL_STORAGE_KEY = 'aid-studio-agent-panel-layout:v1'
export const STUDIO_AGENT_PANEL_MARGIN = 14
export const STUDIO_AGENT_PANEL_DEFAULT_DOCKED_WIDTH = 440
export const STUDIO_AGENT_PANEL_MIN_WIDTH = 320
export const STUDIO_AGENT_PANEL_MIN_HEIGHT = 360
export const STUDIO_AGENT_PANEL_MAX_DOCKED_WIDTH = 720

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), Math.max(min, max))
}

function finite(value: unknown, fallback: number) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

export function clampStudioAgentDockedWidth(width: number, viewportWidth: number): number {
  const available = Math.max(240, viewportWidth - 320)
  const minimum = Math.min(STUDIO_AGENT_PANEL_MIN_WIDTH, available)
  const maximum = Math.max(minimum, Math.min(STUDIO_AGENT_PANEL_MAX_DOCKED_WIDTH, available))
  return clamp(width, minimum, maximum)
}

export function clampStudioAgentFloatingRect(
  rect: StudioAgentPanelRect,
  viewport: StudioAgentPanelViewport
): StudioAgentPanelRect {
  const availableWidth = Math.max(1, viewport.width - STUDIO_AGENT_PANEL_MARGIN * 2)
  const availableHeight = Math.max(1, viewport.height - STUDIO_AGENT_PANEL_MARGIN * 2)
  const width = clamp(rect.width, Math.min(STUDIO_AGENT_PANEL_MIN_WIDTH, availableWidth), availableWidth)
  const height = clamp(rect.height, Math.min(STUDIO_AGENT_PANEL_MIN_HEIGHT, availableHeight), availableHeight)
  const maxX = Math.max(STUDIO_AGENT_PANEL_MARGIN, viewport.width - STUDIO_AGENT_PANEL_MARGIN - width)
  const maxY = Math.max(STUDIO_AGENT_PANEL_MARGIN, viewport.height - STUDIO_AGENT_PANEL_MARGIN - height)
  return {
    x: clamp(rect.x, STUDIO_AGENT_PANEL_MARGIN, maxX),
    y: clamp(rect.y, STUDIO_AGENT_PANEL_MARGIN, maxY),
    width,
    height
  }
}

export function defaultStudioAgentFloatingRect(viewport: StudioAgentPanelViewport): StudioAgentPanelRect {
  const width = Math.min(STUDIO_AGENT_PANEL_DEFAULT_DOCKED_WIDTH, Math.max(1, viewport.width - STUDIO_AGENT_PANEL_MARGIN * 2))
  const height = Math.min(720, Math.max(1, viewport.height - STUDIO_AGENT_PANEL_MARGIN * 2))
  return clampStudioAgentFloatingRect({
    x: viewport.width - width - 24,
    y: 24,
    width,
    height
  }, viewport)
}

export function moveStudioAgentFloatingRect(
  rect: StudioAgentPanelRect,
  dx: number,
  dy: number,
  viewport: StudioAgentPanelViewport
): StudioAgentPanelRect {
  return clampStudioAgentFloatingRect({ ...rect, x: rect.x + dx, y: rect.y + dy }, viewport)
}

export function resizeStudioAgentFloatingRect(
  rect: StudioAgentPanelRect,
  edge: StudioAgentPanelEdge,
  dx: number,
  dy: number,
  viewport: StudioAgentPanelViewport
): StudioAgentPanelRect {
  const current = clampStudioAgentFloatingRect(rect, viewport)
  const availableWidth = Math.max(1, viewport.width - STUDIO_AGENT_PANEL_MARGIN * 2)
  const availableHeight = Math.max(1, viewport.height - STUDIO_AGENT_PANEL_MARGIN * 2)
  const minWidth = Math.min(STUDIO_AGENT_PANEL_MIN_WIDTH, availableWidth)
  const minHeight = Math.min(STUDIO_AGENT_PANEL_MIN_HEIGHT, availableHeight)
  const resizeLeft = edge === 'left' || edge.endsWith('-left')
  const resizeRight = edge === 'right' || edge.endsWith('-right')
  const resizeTop = edge === 'top' || edge.startsWith('top-')
  const resizeBottom = edge === 'bottom' || edge.startsWith('bottom-')
  let next = current

  if (resizeLeft) {
    const fixedRight = current.x + current.width
    const x = clamp(current.x + dx, STUDIO_AGENT_PANEL_MARGIN, fixedRight - minWidth)
    next = { ...next, x, width: fixedRight - x }
  } else if (resizeRight) {
    const right = clamp(
      current.x + current.width + dx,
      current.x + minWidth,
      viewport.width - STUDIO_AGENT_PANEL_MARGIN
    )
    next = { ...next, width: right - current.x }
  }

  if (resizeTop) {
    const fixedBottom = current.y + current.height
    const y = clamp(current.y + dy, STUDIO_AGENT_PANEL_MARGIN, fixedBottom - minHeight)
    next = { ...next, y, height: fixedBottom - y }
  } else if (resizeBottom) {
    const bottom = clamp(
      current.y + current.height + dy,
      current.y + minHeight,
      viewport.height - STUDIO_AGENT_PANEL_MARGIN
    )
    next = { ...next, height: bottom - current.y }
  }

  return next
}

function browserStorage(): Storage | null {
  if (typeof window === 'undefined') return null
  try {
    return window.localStorage
  } catch {
    return null
  }
}

export function readStudioAgentPanelLayout(
  viewport: StudioAgentPanelViewport,
  storage: Pick<Storage, 'getItem'> | null = browserStorage()
): StudioAgentPanelLayout {
  const fallbackRect = defaultStudioAgentFloatingRect(viewport)
  try {
    const raw = storage?.getItem(STUDIO_AGENT_PANEL_STORAGE_KEY)
    if (!raw) throw new Error('missing')
    const parsed = JSON.parse(raw) as Partial<StudioAgentPanelLayout>
    const candidate = parsed.floatingRect as Partial<StudioAgentPanelRect> | undefined
    return {
      mode: parsed.mode === 'floating' ? 'floating' : 'docked',
      dockedWidth: clampStudioAgentDockedWidth(
        finite(parsed.dockedWidth, STUDIO_AGENT_PANEL_DEFAULT_DOCKED_WIDTH),
        viewport.width
      ),
      floatingRect: clampStudioAgentFloatingRect({
        x: finite(candidate?.x, fallbackRect.x),
        y: finite(candidate?.y, fallbackRect.y),
        width: finite(candidate?.width, fallbackRect.width),
        height: finite(candidate?.height, fallbackRect.height)
      }, viewport)
    }
  } catch {
    return {
      mode: 'docked',
      dockedWidth: clampStudioAgentDockedWidth(STUDIO_AGENT_PANEL_DEFAULT_DOCKED_WIDTH, viewport.width),
      floatingRect: fallbackRect
    }
  }
}

export function writeStudioAgentPanelLayout(
  layout: StudioAgentPanelLayout,
  storage: Pick<Storage, 'setItem'> | null = browserStorage()
) {
  try {
    storage?.setItem(STUDIO_AGENT_PANEL_STORAGE_KEY, JSON.stringify(layout))
  } catch {
    // 存储不可用时，拖动和缩放仍在当前页面内生效。
  }
}
