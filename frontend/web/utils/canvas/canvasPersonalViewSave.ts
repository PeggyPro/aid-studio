import type { CanvasWorkspaceView } from '@/types/canvas-api'

export type CanvasPersonalView = { x: number; y: number; zoom: number }

export class CanvasPersonalViewSaveConflictError extends Error {
  readonly currentVersion: number
  readonly currentView: CanvasPersonalView | null

  constructor(error: unknown, currentVersion: number, currentView: CanvasPersonalView | null) {
    super(error instanceof Error ? error.message : '个人视角版本冲突')
    this.name = 'CanvasPersonalViewSaveConflictError'
    this.currentVersion = currentVersion
    this.currentView = currentView
  }
}

export function canvasPersonalViewCoordinates(view: Record<string, unknown> | null): CanvasPersonalView | null {
  if (!view) return null
  const coordinate = (key: keyof CanvasPersonalView, fallback: number) => {
    const value = Number(view[key])
    return Number.isFinite(value) ? value : fallback
  }
  return { x: coordinate('x', 0), y: coordinate('y', 0), zoom: coordinate('zoom', 1) }
}

function sameView(first: CanvasPersonalView | null, second: CanvasPersonalView): boolean {
  return Boolean(first && first.x === second.x && first.y === second.y && first.zoom === second.zoom)
}

/** Avoid no-op writes and reconcile one optimistic-lock conflict without allowing an unbounded write loop. */
export async function saveCanvasPersonalView(input: {
  view: CanvasPersonalView
  version: number
  savedView: CanvasPersonalView | null
  save: (expectedVersion: number, view: CanvasPersonalView) => Promise<{ viewVersion: number }>
  load: () => Promise<CanvasWorkspaceView>
}): Promise<{ viewVersion: number; view: CanvasPersonalView }> {
  if (sameView(input.savedView, input.view)) return { viewVersion: input.version, view: input.view }
  try {
    const saved = await input.save(input.version, input.view)
    return { viewVersion: saved.viewVersion, view: input.view }
  } catch (error) {
    const current = await input.load().catch(() => null)
    if (!current) throw error
    const currentView = canvasPersonalViewCoordinates(current.view)
    if (sameView(currentView, input.view)) return { viewVersion: current.viewVersion, view: input.view }
    if (current.viewVersion === input.version) throw error

    try {
      const saved = await input.save(current.viewVersion, input.view)
      return { viewVersion: saved.viewVersion, view: input.view }
    } catch (retryError) {
      const latest = await input.load().catch(() => current)
      const latestView = canvasPersonalViewCoordinates(latest.view)
      if (sameView(latestView, input.view)) return { viewVersion: latest.viewVersion, view: input.view }
      throw new CanvasPersonalViewSaveConflictError(retryError, latest.viewVersion, latestView)
    }
  }
}
