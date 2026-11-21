import type { SmartEditAudioClip, SmartEditCommand, SmartEditRect, SmartEditSubtitleCue, SmartEditTextItem, SmartEditTimeline, SmartEditVideoClip, StudioSmartEditState } from '@/types/studio'
import { studioId } from './studioGraph'
import { clipEndSeconds, sequenceMainVideo, SMART_EDIT_OVERLAY_RECT, smartEditMediaClips, timelineDurationSeconds } from './studioSmartEdit'

export interface SmartEditHistory { past: StudioSmartEditState[]; future: StudioSmartEditState[] }
export type SmartEditItem = SmartEditVideoClip | SmartEditAudioClip | SmartEditSubtitleCue | SmartEditTextItem
export const SMART_EDIT_LANES = ['videoClips', 'overlayClips', 'audioClips', 'subtitles', 'texts'] as const
const finiteNonnegative = (value: number) => Number.isFinite(value) && value >= 0 && value <= 86400
function validRect(rect: SmartEditRect): boolean {
  return [rect.x, rect.y, rect.w, rect.h].every(finiteNonnegative) && rect.w > 0 && rect.h > 0 && rect.x + rect.w <= 1.00001 && rect.y + rect.h <= 1.00001
}
function validItem(item: SmartEditItem): boolean {
  if (!item.id || !finiteNonnegative(item.timelineStartSeconds)) return false
  if ('track' in item && !['video', 'overlay'].includes(item.track)) return false
  if ('fontSize' in item && (!Number.isFinite(item.fontSize) || item.fontSize < 8 || item.fontSize > 200 || !/^#[0-9a-f]{6}$/i.test(item.color) || !validRect(item.rect) || !['none', 'fade', 'slide'].includes(item.animation))) return false
  if ('overlayRect' in item && item.overlayRect && !validRect(item.overlayRect)) return false
  if ('range' in item) return finiteNonnegative(item.sourceDurationSeconds) && finiteNonnegative(item.range.startSeconds)
    && Number.isFinite(item.range.endSeconds) && item.range.endSeconds > item.range.startSeconds && item.range.endSeconds <= item.sourceDurationSeconds
    && finiteNonnegative(item.volume) && item.volume <= 1
  return Number.isFinite(item.durationSeconds) && item.durationSeconds > 0 && typeof item.text === 'string' && item.text.length <= 2000
}
export function findSmartEditItem(timeline: SmartEditTimeline, id: string | null): SmartEditItem | undefined {
  return SMART_EDIT_LANES.flatMap((lane) => timeline[lane] as SmartEditItem[]).find((item) => item.id === id)
}
function finish(state: StudioSmartEditState, timeline: SmartEditTimeline): StudioSmartEditState {
  const clampTransition = (clip: SmartEditVideoClip): SmartEditVideoClip => clip.transitionOut && clip.transitionOut.durationSeconds > clip.range.endSeconds - clip.range.startSeconds
    ? { ...clip, transitionOut: { ...clip.transitionOut, durationSeconds: clip.range.endSeconds - clip.range.startSeconds } } : clip
  const videoClips = sequenceMainVideo(timeline.videoClips.map(clampTransition))
  return { ...state, timelineInitialized: true, dismissedSourceIds: state.dismissedSourceIds ?? [], timeline: { ...timeline, videoClips, overlayClips: timeline.overlayClips.map(clampTransition), durationSeconds: timelineDurationSeconds({ ...timeline, videoClips }) } }
}
export function applySmartEditCommand(state: StudioSmartEditState, command: SmartEditCommand): StudioSmartEditState {
  switch (command.type) {
    case 'setPlaybook': return ['mix', 'talk', 'explain'].includes(command.playbook) && state.playbook !== command.playbook ? { ...state, playbook: command.playbook } : state
    case 'setChrome': return ['docked', 'expanded'].includes(command.chrome) && state.chrome !== command.chrome ? { ...state, chrome: command.chrome } : state
    case 'setTimelineZoom': return Number.isFinite(command.zoom) ? { ...state, timelineZoom: Math.max(0.25, Math.min(4, command.zoom)) } : state
    case 'setSnapping': return { ...state, snapping: command.enabled }
    case 'setTrackHidden': return { ...state, hiddenTracks: command.hidden ? [...new Set([...(state.hiddenTracks || []), command.track])] : (state.hiddenTracks || []).filter((track) => track !== command.track) }
    case 'replaceTimeline': {
      const items = SMART_EDIT_LANES.flatMap((lane) => command.timeline[lane] as SmartEditItem[])
      return items.every(validItem) && new Set(items.map((item) => item.id)).size === items.length ? finish(state, command.timeline) : state
    }
    case 'addVideoClip':
    case 'addAudioClip':
    case 'addSubtitle':
    case 'addText': {
      const item = command.type === 'addSubtitle' ? command.cue : command.type === 'addText' ? command.item : command.clip
      if (!validItem(item) || findSmartEditItem(state.timeline, item.id)) return state
      const lane = command.type === 'addSubtitle' ? 'subtitles' : command.type === 'addText' ? 'texts' : command.type === 'addAudioClip' ? 'audioClips' : command.clip.track === 'overlay' ? 'overlayClips' : 'videoClips'
      const normalized = lane === 'overlayClips' ? { ...item, overlayRect: ('overlayRect' in item && item.overlayRect) || { ...SMART_EDIT_OVERLAY_RECT } } : item
      return finish(state, { ...state.timeline, [lane]: [...state.timeline[lane], normalized] })
    }
    case 'reorderVideoClip': {
      const clips = state.timeline.videoClips
      const from = clips.findIndex((clip) => clip.id === command.clipId)
      if (from < 0 || !Number.isInteger(command.toIndex) || command.toIndex < 0 || command.toIndex >= clips.length || command.toIndex === from) return state
      const videoClips = clips.slice()
      const [moved] = videoClips.splice(from, 1)
      videoClips.splice(command.toIndex, 0, moved)
      return finish(state, { ...state.timeline, videoClips })
    }
  }
  const item = findSmartEditItem(state.timeline, command.clipId)
  if (!item) return state
  let replacement: SmartEditItem[] = [item]
  switch (command.type) {
    case 'removeClip': replacement = []; break
    case 'moveClip':
      if ('track' in item && item.track === 'video') return state
      if (!finiteNonnegative(command.timelineStartSeconds) || item.timelineStartSeconds === command.timelineStartSeconds) return state
      replacement = [{ ...item, timelineStartSeconds: command.timelineStartSeconds }]; break
    case 'trimClip': {
      if ('range' in item) {
        const next = { ...item, range: command.range, timelineStartSeconds: command.timelineStartSeconds ?? item.timelineStartSeconds }
        if (!validItem(next)) return state
        replacement = [next]
      } else {
        if (!finiteNonnegative(command.range.startSeconds) || command.range.endSeconds <= command.range.startSeconds || !Number.isFinite(command.range.endSeconds)) return state
        replacement = [{ ...item, timelineStartSeconds: command.range.startSeconds, durationSeconds: command.range.endSeconds - command.range.startSeconds }]
      }
      break
    }
    case 'splitClip': {
      if (!('range' in item) || !(command.atSeconds > item.timelineStartSeconds && command.atSeconds < clipEndSeconds(item))) return state
      const cut = item.range.startSeconds + command.atSeconds - item.timelineStartSeconds
      const before = { ...item, range: { ...item.range, endSeconds: cut }, ...('track' in item && command.mode === 'at' ? { transitionOut: { type: 'cut' as const, durationSeconds: 0 } } : {}) }
      const after = { ...item, id: studioId('clip'), timelineStartSeconds: command.atSeconds, range: { ...item.range, startSeconds: cut } }
      replacement = command.mode === 'at' ? [before, after] : command.mode === 'keepBefore' ? [before] : [{ ...after, id: item.id, timelineStartSeconds: item.timelineStartSeconds }]
      break
    }
    case 'setVolume':
      if (!('range' in item) || !finiteNonnegative(command.volume) || command.volume > 1) return state
      replacement = [{ ...item, volume: command.volume }]; break
    case 'markInvalid':
      if (!('range' in item) || item.invalid === command.invalid) return state
      replacement = [{ ...item, invalid: command.invalid }]; break
    case 'setOverlayRect':
      if (!('track' in item) || item.track !== 'overlay' || !validRect(command.rect)) return state
      replacement = [{ ...item, overlayRect: command.rect }]; break
    case 'setTransition':
      if (!('track' in item) || !command.transitionOut || !['cut', 'fade', 'dissolve', 'wipe'].includes(command.transitionOut.type) || !finiteNonnegative(command.transitionOut.durationSeconds)) return state
      replacement = [{ ...item, transitionOut: { ...command.transitionOut, durationSeconds: Math.min(command.transitionOut.durationSeconds, item.range.endSeconds - item.range.startSeconds) } }]; break
    case 'updateText': {
      if (!('text' in item)) return state
      const next = { ...item, ...command.patch, id: item.id }
      if (!validItem(next) || (next.rect !== undefined && !validRect(next.rect)) || (next.animation !== undefined && !['none', 'fade', 'slide'].includes(next.animation))) return state
      replacement = [next]; break
    }
  }
  const timeline = { ...state.timeline }
  for (const lane of SMART_EDIT_LANES) {
    if (timeline[lane].some((clip) => clip.id === item.id)) {
      Object.assign(timeline, { [lane]: (timeline[lane] as SmartEditItem[]).flatMap((clip) => clip.id === item.id ? replacement : [clip]) })
      break
    }
  }
  const finished = finish(state, timeline)
  if (command.type === 'removeClip' && 'sourceNodeId' in item && !smartEditMediaClips(finished.timeline).some((clip) => clip.sourceNodeId === item.sourceNodeId)) {
    return { ...finished, dismissedSourceIds: [...new Set([...(finished.dismissedSourceIds ?? []), item.sourceNodeId])] }
  }
  return finished
}
export function pushSmartEditHistory(history: SmartEditHistory, prev: StudioSmartEditState, next: StudioSmartEditState, command: SmartEditCommand): SmartEditHistory {
  if (prev === next || ['setChrome', 'setTimelineZoom', 'setSnapping', 'markInvalid'].includes(command.type)) return history
  return { past: [...history.past, prev].slice(-50), future: [] }
}
function restoreContent(current: StudioSmartEditState, snapshot: StudioSmartEditState): StudioSmartEditState {
  // 撤销只恢复编辑内容，不能把壳、作业身份和缩放恢复到旧时刻。
  return { ...current, playbook: snapshot.playbook, timeline: snapshot.timeline, hiddenTracks: snapshot.hiddenTracks, dismissedSourceIds: snapshot.dismissedSourceIds }
}
export function undoSmartEdit(state: StudioSmartEditState, history: SmartEditHistory) {
  const previous = history.past.at(-1)
  return previous ? { state: restoreContent(state, previous), history: { past: history.past.slice(0, -1), future: [state, ...history.future].slice(0, 50) } } : { state, history }
}
export function redoSmartEdit(state: StudioSmartEditState, history: SmartEditHistory) {
  const next = history.future[0]
  return next ? { state: restoreContent(state, next), history: { past: [...history.past, state].slice(-50), future: history.future.slice(1) } } : { state, history }
}
