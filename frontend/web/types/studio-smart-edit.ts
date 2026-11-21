export type SmartEditPlaybook = 'mix' | 'talk' | 'explain'
export type SmartEditTransitionType = 'cut' | 'fade' | 'dissolve' | 'wipe'
export type SmartEditTextAnim = 'none' | 'fade' | 'slide'
export type SmartEditTrack = 'video' | 'overlay' | 'audio' | 'subtitle' | 'text'
export interface SmartEditClipRange { startSeconds: number; endSeconds: number }
export interface SmartEditRect { x: number; y: number; w: number; h: number }
export interface SmartEditAudioClip {
  id: string
  sourceNodeId: string
  sourceUrl: string
  sourceResourceId?: string
  sourceDurationSeconds: number
  timelineStartSeconds: number
  range: SmartEditClipRange
  volume: number
  invalid?: boolean
}
export interface SmartEditVideoClip extends SmartEditAudioClip {
  track: 'video' | 'overlay'
  overlayRect?: SmartEditRect
  transitionOut?: { type: SmartEditTransitionType; durationSeconds: number }
}
export interface SmartEditSubtitleCue {
  id: string
  timelineStartSeconds: number
  durationSeconds: number
  text: string
}
export interface SmartEditTextItem extends SmartEditSubtitleCue {
  fontSize: number
  color: string
  rect: SmartEditRect
  animation: SmartEditTextAnim
}
export interface SmartEditTimeline {
  durationSeconds: number
  videoClips: SmartEditVideoClip[]
  overlayClips: SmartEditVideoClip[]
  audioClips: SmartEditAudioClip[]
  subtitles: SmartEditSubtitleCue[]
  texts: SmartEditTextItem[]
}
export type SmartEditOutputMode = 'local' | 'canvas'
/** 已被服务端接受、尚未处理结果的成片任务；指纹是提交时的时间轴快照。 */
export interface SmartEditComposeTicket { jobId: string; mode: SmartEditOutputMode; fingerprint: string }
export interface SmartEditComposeResult { jobId: string; resourceId: string; resourceUrl: string }
export interface StudioSmartEditState {
  playbook: SmartEditPlaybook
  /** Legacy snapshot field; the active editor shell is local to the current session. */
  chrome: 'docked' | 'expanded'
  timelineZoom: number
  snapping: boolean
  hiddenTracks?: SmartEditTrack[]
  timeline: SmartEditTimeline
  /** Prevent reopening an intentionally emptied timeline from importing sources again. */
  timelineInitialized?: boolean
  /** Sources whose last media clip the user removed; connected again only after a disconnect. */
  dismissedSourceIds?: string[]
  jobId?: string
  compose?: SmartEditComposeTicket
  /** Latest server output; offered for download even when it no longer matches the timeline. */
  composeDownload?: SmartEditComposeResult
}
export type SmartEditCommand =
  | { type: 'setPlaybook'; playbook: SmartEditPlaybook }
  | { type: 'setChrome'; chrome: 'docked' | 'expanded' }
  | { type: 'setTimelineZoom'; zoom: number }
  | { type: 'setSnapping'; enabled: boolean }
  | { type: 'setTrackHidden'; track: SmartEditTrack; hidden: boolean }
  | { type: 'addVideoClip'; clip: SmartEditVideoClip }
  | { type: 'addAudioClip'; clip: SmartEditAudioClip }
  | { type: 'addSubtitle'; cue: SmartEditSubtitleCue }
  | { type: 'addText'; item: SmartEditTextItem }
  | { type: 'updateText'; clipId: string; patch: Partial<Omit<SmartEditTextItem, 'id'>> }
  | { type: 'trimClip'; clipId: string; range: SmartEditClipRange; timelineStartSeconds?: number }
  | { type: 'moveClip'; clipId: string; timelineStartSeconds: number }
  | { type: 'reorderVideoClip'; clipId: string; toIndex: number }
  | { type: 'removeClip'; clipId: string }
  | { type: 'splitClip'; clipId: string; atSeconds: number; mode: 'at' | 'keepAfter' | 'keepBefore' }
  | { type: 'setTransition'; clipId: string; transitionOut: SmartEditVideoClip['transitionOut'] }
  | { type: 'setVolume'; clipId: string; volume: number }
  | { type: 'setOverlayRect'; clipId: string; rect: SmartEditRect }
  | { type: 'markInvalid'; clipId: string; invalid: boolean }
  | { type: 'replaceTimeline'; timeline: SmartEditTimeline }
