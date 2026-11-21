import type { SmartEditAudioClip, SmartEditRect, SmartEditTimeline, SmartEditTrack, SmartEditVideoClip } from '@/types/studio'
import { clipEndSeconds, SMART_EDIT_OVERLAY_RECT } from './studioSmartEdit'
import { SMART_EDIT_LANES, type SmartEditItem } from './smartEditCommands'

export const SMART_EDIT_PIXELS_PER_SECOND = 50
export const smartEditClock = (seconds: number) => `${Math.floor(Math.max(0, seconds) / 60).toString().padStart(2, '0')}:${Math.floor(Math.max(0, seconds) % 60).toString().padStart(2, '0')}`
export const itemActive = (item: SmartEditItem, time: number) => time >= item.timelineStartSeconds && time < clipEndSeconds(item)
export const canSplitSmartEdit = (item: SmartEditItem | undefined, time: number) => Boolean(item && 'range' in item && time > item.timelineStartSeconds && time < clipEndSeconds(item))
export function snapSmartEditTime(time: number, timeline: SmartEditTimeline, excludedId: string, playhead: number, zoom: number, enabled: boolean): number {
  const value = Math.max(0, time)
  if (!enabled) return value
  const points = [0, playhead, ...SMART_EDIT_LANES.flatMap((lane) => (timeline[lane] as SmartEditItem[]).filter((item) => item.id !== excludedId).flatMap((item) => [item.timelineStartSeconds, clipEndSeconds(item)]))]
  const nearest = points.reduce((best, point) => Math.abs(point - value) < Math.abs(best - value) ? point : best, Infinity)
  return Math.abs(nearest - value) <= 8 / (SMART_EDIT_PIXELS_PER_SECOND * zoom) ? nearest : value
}
export interface SmartEditVideoLayer { clip: SmartEditVideoClip; sourceTime: number; opacity: number; rect: SmartEditRect; reveal: number; frozen?: boolean }
export function smartEditScene(timeline: SmartEditTimeline, time: number, hidden: SmartEditTrack[] = []) {
  const layers: SmartEditVideoLayer[] = []
  const audio: SmartEditAudioClip[] = []
  const full = { x: 0, y: 0, w: 1, h: 1 }
  const main = hidden.includes('video') ? undefined : [...timeline.videoClips].reverse().find((clip) => !clip.invalid && itemActive(clip, time))
  if (main) {
    const layer: SmartEditVideoLayer = { clip: main, sourceTime: main.range.startSeconds + time - main.timelineStartSeconds, opacity: 1, rect: full, reveal: 1 }
    const transition = main.transitionOut
    const remaining = clipEndSeconds(main) - time
    if (transition && transition.durationSeconds > 0 && remaining < transition.durationSeconds) {
      const progress = 1 - remaining / transition.durationSeconds
      const next = timeline.videoClips.find((clip) => !clip.invalid && clip.id !== main.id && Math.abs(clip.timelineStartSeconds - clipEndSeconds(main)) < 0.001)
      // 不移动任何片段的入出点：叠化/擦除先显露下一镜头首帧，切点后正常播放。
      if (next && (transition.type === 'dissolve' || transition.type === 'wipe')) layers.push({ clip: next, sourceTime: next.range.startSeconds, opacity: 1, rect: full, reveal: 1, frozen: true })
      if (transition.type === 'fade') layer.opacity = Math.min(1, remaining / (transition.durationSeconds / 2))
      if (transition.type === 'dissolve') layer.opacity = 1 - progress
      if (transition.type === 'wipe') layer.reveal = 1 - progress
    }
    // fade 的前半段渐隐、后半段下一镜头渐显。
    const previous = timeline.videoClips.find((clip) => clip.transitionOut?.type === 'fade' && Math.abs(clipEndSeconds(clip) - main.timelineStartSeconds) < 0.001)
    if (previous?.transitionOut?.durationSeconds) layer.opacity *= Math.min(1, (time - main.timelineStartSeconds) / (previous.transitionOut.durationSeconds / 2))
    layers.push(layer)
    audio.push(main)
  }
  if (!hidden.includes('overlay')) for (const clip of timeline.overlayClips) if (!clip.invalid && itemActive(clip, time)) {
    layers.push({ clip, sourceTime: clip.range.startSeconds + time - clip.timelineStartSeconds, opacity: 1, rect: clip.overlayRect ?? SMART_EDIT_OVERLAY_RECT, reveal: 1 })
    audio.push(clip)
  }
  if (!hidden.includes('audio')) audio.push(...timeline.audioClips.filter((clip) => !clip.invalid && itemActive(clip, time)))
  return { layers, audio, subtitles: hidden.includes('subtitle') ? [] : timeline.subtitles.filter((cue) => itemActive(cue, time)), texts: hidden.includes('text') ? [] : timeline.texts.filter((item) => itemActive(item, time)) }
}
export function smartEditTextMotion(item: { timelineStartSeconds: number; durationSeconds: number; animation: string }, time: number) {
  const edge = Math.min(0.4, item.durationSeconds / 2)
  const progress = Math.max(0, Math.min(1, (time - item.timelineStartSeconds) / edge, (clipEndSeconds(item as SmartEditItem) - time) / edge))
  return { opacity: item.animation === 'fade' ? progress : 1, translateY: item.animation === 'slide' ? (1 - progress) * 0.15 : 0 }
}
