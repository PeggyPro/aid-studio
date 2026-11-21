import type { StudioCanvasNode, StudioSmartEditState } from '@/types/studio'
import { applySmartEditCommand, SMART_EDIT_LANES } from './smartEditCommands'
import { readSmartEditMediaDuration } from './smartEditJob'
import { studioId } from './studioGraph'
import { clipEndSeconds, defaultStudioSmartEditState, SMART_EDIT_SOURCE_LIMIT, smartEditDistinctSourceCount, smartEditMediaClips } from './studioSmartEdit'

export type SmartEditAppendSource = { id: string; kind: 'video' | 'voice'; url: string; durationSeconds: number; resourceId?: string }

export function needsSmartEditSourceTimeline(edit: StudioSmartEditState): boolean {
  return !edit.timelineInitialized && !edit.jobId && SMART_EDIT_LANES.every((lane) => edit.timeline[lane].length === 0)
}

export function hasValidSmartEditClip(edit: StudioSmartEditState, sourceId: string): boolean {
  return smartEditMediaClips(edit.timeline).some((clip) => clip.sourceNodeId === sourceId && !clip.invalid)
}

/** Appends each source to the end of its lane; dismissed, present and over-limit sources are skipped, never truncating existing clips. */
export function appendSmartEditSources(edit: StudioSmartEditState, sources: SmartEditAppendSource[]): { state: StudioSmartEditState; skippedForLimit: string[] } {
  let state = edit
  const skippedForLimit: string[] = []
  for (const source of sources) {
    if ((state.dismissedSourceIds ?? []).includes(source.id) || hasValidSmartEditClip(state, source.id)) continue
    const lane = source.kind === 'video' ? state.timeline.videoClips : state.timeline.audioClips
    const clip = { id: studioId('clip'), sourceNodeId: source.id, sourceUrl: source.url, sourceResourceId: source.resourceId, sourceDurationSeconds: source.durationSeconds, timelineStartSeconds: Math.max(0, ...lane.map(clipEndSeconds)), range: { startSeconds: 0, endSeconds: source.durationSeconds }, volume: 1 }
    const next = applySmartEditCommand(state, source.kind === 'video'
      ? { type: 'addVideoClip', clip: { ...clip, track: 'video', transitionOut: { type: 'cut', durationSeconds: 0 } } }
      : { type: 'addAudioClip', clip })
    if (next === state) continue
    if (smartEditDistinctSourceCount(next.timeline) <= SMART_EDIT_SOURCE_LIMIT) state = next
    else skippedForLimit.push(source.id)
  }
  return { state, skippedForLimit }
}

/** Opening the editor only imports existing media; it never starts generation or billing. */
export async function prepareSmartEditSourceTimeline(
  sources: StudioCanvasNode[], signal: AbortSignal, readDuration = readSmartEditMediaDuration
) {
  const media = sources.filter((source) => source.data.kind === 'video' || source.data.kind === 'voice')
  if (media.length > SMART_EDIT_SOURCE_LIMIT) throw new Error('剪辑成片最多引用 24 个素材')
  const ready: SmartEditAppendSource[] = []
  // Bounded sequential metadata reads, cancelled on close, source change or project switch.
  for (const node of media) {
    signal.throwIfAborted()
    const durationSeconds = await readDuration(node, signal)
    signal.throwIfAborted()
    ready.push(smartEditAppendSource(node, durationSeconds))
  }
  return appendSmartEditSources(defaultStudioSmartEditState(), ready).state.timeline
}

export function smartEditAppendSource(node: StudioCanvasNode, durationSeconds: number): SmartEditAppendSource {
  return { id: node.id, kind: node.data.kind === 'video' ? 'video' : 'voice', url: node.data.mediaUrl!, durationSeconds, resourceId: node.data.canvasPrimaryResourceId || node.data.canvasResourceIds?.[0] }
}
