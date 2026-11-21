import type {
  StudioLayerSplitRect,
  StudioLayerSplitRegion,
  StudioLayerSplitSession
} from '@/types/studio'

export const STUDIO_LAYER_SPLIT_MIN_SIZE = 0.04
export const STUDIO_LAYER_SPLIT_CREDIT_DISPLAY = 3

function cloneRegions(regions: StudioLayerSplitRegion[]): StudioLayerSplitRegion[] {
  return regions.map((region) => ({
    ...region,
    rect: { ...region.rect }
  }))
}

export function createEmptyLayerSplitSession(nodeId: string): StudioLayerSplitSession {
  return {
    nodeId,
    regions: [],
    activeRegionId: null,
    draftPrompt: '',
    historyPast: [],
    historyFuture: []
  }
}

function round01(n: number): number {
  return Math.round(n * 1e6) / 1e6
}

export function clampLayerSplitRect(rect: StudioLayerSplitRect): StudioLayerSplitRect {
  let { x, y, w, h } = rect
  w = Math.max(0, Math.min(1, round01(w)))
  h = Math.max(0, Math.min(1, round01(h)))
  x = Math.max(0, Math.min(1 - w, round01(x)))
  y = Math.max(0, Math.min(1 - h, round01(y)))
  return { x, y, w, h }
}

export type StudioLayerSplitHandle =
  | 'nw' | 'n' | 'ne'
  | 'w' | 'e'
  | 'sw' | 's' | 'se'

export const STUDIO_LAYER_SPLIT_HANDLES: StudioLayerSplitHandle[] = [
  'nw', 'n', 'ne', 'w', 'e', 'sw', 's', 'se'
]

/** 按角点/边中手柄拖拽调整矩形（归一化坐标）。 */
export function resizeLayerSplitRectByHandle(
  origin: StudioLayerSplitRect,
  handle: StudioLayerSplitHandle,
  pointer: { x: number; y: number }
): StudioLayerSplitRect {
  const right = origin.x + origin.w
  const bottom = origin.y + origin.h
  let x1 = origin.x
  let y1 = origin.y
  let x2 = right
  let y2 = bottom
  const moveWest = handle === 'w' || handle === 'nw' || handle === 'sw'
  const moveEast = handle === 'e' || handle === 'ne' || handle === 'se'
  const moveNorth = handle === 'n' || handle === 'nw' || handle === 'ne'
  const moveSouth = handle === 's' || handle === 'sw' || handle === 'se'
  if (moveWest) x1 = pointer.x
  if (moveEast) x2 = pointer.x
  if (moveNorth) y1 = pointer.y
  if (moveSouth) y2 = pointer.y
  return clampLayerSplitRect(normalizeLayerSplitRect({ x: x1, y: y1 }, { x: x2, y: y2 }))
}

export function normalizeLayerSplitRect(
  a: { x: number; y: number },
  b: { x: number; y: number }
): StudioLayerSplitRect {
  const x = Math.min(a.x, b.x)
  const y = Math.min(a.y, b.y)
  const w = Math.abs(a.x - b.x)
  const h = Math.abs(a.y - b.y)
  return clampLayerSplitRect({ x, y, w, h })
}

export function isLayerSplitRectMeaningful(rect: StudioLayerSplitRect): boolean {
  return rect.w >= STUDIO_LAYER_SPLIT_MIN_SIZE && rect.h >= STUDIO_LAYER_SPLIT_MIN_SIZE
}

export function pushLayerSplitHistory(session: StudioLayerSplitSession): StudioLayerSplitSession {
  return {
    ...session,
    historyPast: [...session.historyPast, cloneRegions(session.regions)],
    historyFuture: []
  }
}

export function undoLayerSplitSession(session: StudioLayerSplitSession): StudioLayerSplitSession {
  if (session.historyPast.length === 0) return session
  const previous = session.historyPast[session.historyPast.length - 1]!
  return {
    ...session,
    regions: cloneRegions(previous),
    historyPast: session.historyPast.slice(0, -1),
    historyFuture: [cloneRegions(session.regions), ...session.historyFuture],
    activeRegionId: previous.some((item) => item.id === session.activeRegionId)
      ? session.activeRegionId
      : previous.at(-1)?.id ?? null
  }
}

export function redoLayerSplitSession(session: StudioLayerSplitSession): StudioLayerSplitSession {
  if (session.historyFuture.length === 0) return session
  const next = session.historyFuture[0]!
  return {
    ...session,
    regions: cloneRegions(next),
    historyPast: [...session.historyPast, cloneRegions(session.regions)],
    historyFuture: session.historyFuture.slice(1),
    activeRegionId: next.some((item) => item.id === session.activeRegionId)
      ? session.activeRegionId
      : next.at(-1)?.id ?? null
  }
}

export function addLayerSplitRegion(
  session: StudioLayerSplitSession,
  region: StudioLayerSplitRegion
): StudioLayerSplitSession {
  const regions = [...session.regions, { ...region, rect: { ...region.rect } }]
  return {
    ...session,
    regions,
    activeRegionId: region.id
  }
}

export function patchActiveLayerSplitPrompt(
  session: StudioLayerSplitSession,
  prompt: string
): StudioLayerSplitSession {
  if (!session.activeRegionId) {
    return { ...session, draftPrompt: prompt }
  }
  return {
    ...session,
    draftPrompt: prompt,
    regions: session.regions.map((region) => (
      region.id === session.activeRegionId ? { ...region, prompt } : region
    ))
  }
}

export function buildLayerSplitRequest(input: {
  image: string
  projectId?: number
  regions: StudioLayerSplitRegion[]
  draftPrompt: string
}): {
  image: string
  projectId?: number
  regions: Array<{ id: string; rect: StudioLayerSplitRect; prompt?: string }>
  autoDetect?: boolean
} {
  const draft = input.draftPrompt.trim()
  if (input.regions.length === 0 && !draft) {
    return {
      image: input.image,
      ...(input.projectId !== undefined ? { projectId: input.projectId } : {}),
      regions: [],
      autoDetect: true
    }
  }

  return {
    image: input.image,
    ...(input.projectId !== undefined ? { projectId: input.projectId } : {}),
    regions: input.regions.map((region) => {
      const prompt = region.prompt.trim()
      return {
        id: region.id,
        rect: { ...region.rect },
        ...(prompt ? { prompt } : {})
      }
    })
  }
}
