/** 球体属于工作区级悬浮层，只保留防止焦点环被裁切的最小安全距。 */
export const LIQUID_ORB_DOCK_GAP = 6
export const LIQUID_ORB_DOCK_SIZE = 72
export const LIQUID_ORB_DRAG_THRESHOLD = 8
export const LIQUID_ORB_EMPTY_GUIDE_GAP = 18
/** v4 使用完整 Studio 工作区坐标，并按画布项目作用域隔离位置。 */
export const LIQUID_ORB_DOCK_STORAGE_KEY = 'aid-studio-liquid-orb-dock:v4'

export type LiquidOrbDockPoint = { x: number; y: number }
export type LiquidOrbDockViewport = { width: number; height: number }
export type LiquidOrbDockAnchor = { left: number; top: number; width: number; height: number }

function clampAxis(value: number, size: number, span: number, gap: number) {
  const max = span - size - gap
  if (max <= gap) return Math.max(0, (span - size) / 2)
  return Math.min(Math.max(value, gap), max)
}

export function clampLiquidOrbDock(
  point: LiquidOrbDockPoint,
  size: number,
  viewport: LiquidOrbDockViewport,
  gap = LIQUID_ORB_DOCK_GAP
): LiquidOrbDockPoint {
  return {
    x: clampAxis(point.x, size, viewport.width, gap),
    y: clampAxis(point.y, size, viewport.height, gap)
  }
}

export function defaultLiquidOrbDock(
  size: number,
  viewport: LiquidOrbDockViewport,
  gap = LIQUID_ORB_DOCK_GAP,
  emptyGuideAnchor?: LiquidOrbDockAnchor | null
): LiquidOrbDockPoint {
  const centerX = emptyGuideAnchor
    ? emptyGuideAnchor.left + emptyGuideAnchor.width / 2
    : viewport.width / 2
  return clampLiquidOrbDock({
    x: centerX - size / 2,
    y: emptyGuideAnchor
      ? emptyGuideAnchor.top - size - LIQUID_ORB_EMPTY_GUIDE_GAP
      : (viewport.height - size) / 2
  }, size, viewport, gap)
}

/** 有内容的画布默认停靠工作区右侧中线，空画布则继续使用引导锚点。 */
export function rightLiquidOrbDock(
  size: number,
  viewport: LiquidOrbDockViewport,
  gap = LIQUID_ORB_DOCK_GAP
): LiquidOrbDockPoint {
  return clampLiquidOrbDock({
    x: viewport.width - size - gap,
    y: (viewport.height - size) / 2
  }, size, viewport, gap)
}

/** 松手后贴到最近的一条边，另一轴保持不动。拖动过程中不要调用。 */
export function snapLiquidOrbDock(
  point: LiquidOrbDockPoint,
  size: number,
  viewport: LiquidOrbDockViewport,
  gap = LIQUID_ORB_DOCK_GAP
): LiquidOrbDockPoint {
  const clamped = clampLiquidOrbDock(point, size, viewport, gap)
  const left = clampAxis(gap, size, viewport.width, gap)
  const right = clampAxis(viewport.width - size - gap, size, viewport.width, gap)
  const top = clampAxis(gap, size, viewport.height, gap)
  const bottom = clampAxis(viewport.height - size - gap, size, viewport.height, gap)
  const nearest = [
    { distance: Math.abs(clamped.x - left), point: { x: left, y: clamped.y } },
    { distance: Math.abs(clamped.x - right), point: { x: right, y: clamped.y } },
    { distance: Math.abs(clamped.y - top), point: { x: clamped.x, y: top } },
    { distance: Math.abs(clamped.y - bottom), point: { x: clamped.x, y: bottom } }
  ].reduce((best, candidate) => candidate.distance < best.distance ? candidate : best)
  return nearest.point
}

export function isLiquidOrbDrag(dx: number, dy: number, threshold = LIQUID_ORB_DRAG_THRESHOLD) {
  return dx * dx + dy * dy >= threshold * threshold
}

function browserStorage(): Storage | null {
  if (typeof window === 'undefined') return null
  try {
    return window.localStorage
  } catch {
    return null
  }
}

export function liquidOrbDockStorageKey(scopeKey: string): string {
  return `${LIQUID_ORB_DOCK_STORAGE_KEY}:${encodeURIComponent(scopeKey.trim() || 'default')}`
}

export function readLiquidOrbDock(
  scopeKey: string,
  storage: Pick<Storage, 'getItem'> | null = browserStorage()
): LiquidOrbDockPoint | null {
  try {
    const raw = storage?.getItem(liquidOrbDockStorageKey(scopeKey))
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<LiquidOrbDockPoint>
    if (!Number.isFinite(parsed.x) || !Number.isFinite(parsed.y)) return null
    return { x: parsed.x as number, y: parsed.y as number }
  } catch {
    return null
  }
}

export function writeLiquidOrbDock(
  scopeKey: string,
  point: LiquidOrbDockPoint,
  storage: Pick<Storage, 'setItem'> | null = browserStorage()
) {
  try {
    storage?.setItem(liquidOrbDockStorageKey(scopeKey), JSON.stringify(point))
  } catch {
    // 隐私模式或存储不可用时，仅保留当前页面中的位置。
  }
}
