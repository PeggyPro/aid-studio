import type {
  StudioLayerSplitRect,
  StudioVideoEditSession,
  StudioVideoSubtitleMode,
  StudioVideoSubtitleRegion
} from '@/types/studio'
import {
  clampLayerSplitRect,
  isLayerSplitRectMeaningful,
  normalizeLayerSplitRect
} from '@/utils/studio/studioLayerSplit'

function cloneRegions(regions: StudioVideoSubtitleRegion[]): StudioVideoSubtitleRegion[] {
  return regions.map((region) => ({ ...region, rect: { ...region.rect } }))
}

export function createStudioVideoEditSession(
  nodeId: string,
  mode: StudioVideoSubtitleMode
): StudioVideoEditSession {
  return { nodeId, mode, regions: [], historyPast: [], historyFuture: [] }
}

export function normalizeStudioVideoSubtitleRect(
  start: { x: number; y: number },
  end: { x: number; y: number }
): StudioLayerSplitRect {
  return clampLayerSplitRect(normalizeLayerSplitRect(start, end))
}

export function isStudioVideoSubtitleRectMeaningful(rect: StudioLayerSplitRect): boolean {
  return isLayerSplitRectMeaningful(rect)
}

export function addStudioVideoSubtitleRegion(
  session: StudioVideoEditSession,
  region: StudioVideoSubtitleRegion
): StudioVideoEditSession {
  if (!isStudioVideoSubtitleRectMeaningful(region.rect)) return session
  return {
    ...session,
    regions: [...cloneRegions(session.regions), { ...region, rect: { ...region.rect } }],
    historyPast: [...session.historyPast, cloneRegions(session.regions)],
    historyFuture: []
  }
}

export function undoStudioVideoSubtitleSelection(
  session: StudioVideoEditSession
): StudioVideoEditSession {
  const previous = session.historyPast.at(-1)
  if (!previous) return session
  return {
    ...session,
    regions: cloneRegions(previous),
    historyPast: session.historyPast.slice(0, -1),
    historyFuture: [cloneRegions(session.regions), ...session.historyFuture]
  }
}

export function redoStudioVideoSubtitleSelection(
  session: StudioVideoEditSession
): StudioVideoEditSession {
  const next = session.historyFuture[0]
  if (!next) return session
  return {
    ...session,
    regions: cloneRegions(next),
    historyPast: [...session.historyPast, cloneRegions(session.regions)],
    historyFuture: session.historyFuture.slice(1)
  }
}

export function resetStudioVideoSubtitleSelection(
  session: StudioVideoEditSession
): StudioVideoEditSession {
  if (!session.regions.length) return session
  return {
    ...session,
    regions: [],
    historyPast: [...session.historyPast, cloneRegions(session.regions)],
    historyFuture: []
  }
}

export function studioVideoSubtitleRequestRegions(regions: StudioVideoSubtitleRegion[]) {
  return regions.map(({ rect }) => ({
    x: rect.x,
    y: rect.y,
    width: rect.w,
    height: rect.h
  }))
}
