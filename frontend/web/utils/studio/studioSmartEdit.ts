import type { StudioCanvasNode, StudioFlowEdge, SmartEditAudioClip, SmartEditClipRange, SmartEditPlaybook, SmartEditTimeline, SmartEditVideoClip, StudioSmartEditState } from '@/types/studio'

export const SMART_EDIT_PLAYBOOKS: ReadonlyArray<{ id: SmartEditPlaybook; label: string }> = [
  { id: 'mix', label: '素材混剪' }, { id: 'talk', label: '口播视频' }, { id: 'explain', label: '讲解视频' }
]
export const SMART_EDIT_OVERLAY_RECT = { x: 0.62, y: 0.08, w: 0.32, h: 0.32 }
// 服务端 NODE 最多 25 个资源；预留一个给成片，不能静默截断轨道资源。
export const SMART_EDIT_SOURCE_LIMIT = 24
export function emptySmartEditTimeline(): SmartEditTimeline {
  return { durationSeconds: 0.1, videoClips: [], overlayClips: [], audioClips: [], subtitles: [], texts: [] }
}
export function defaultStudioSmartEditState(): StudioSmartEditState {
  return { playbook: 'mix', chrome: 'docked', timelineZoom: 1, snapping: true, hiddenTracks: [], dismissedSourceIds: [], timeline: emptySmartEditTimeline() }
}
export function isSmartEditSourceCandidate(targetNodeId: string, candidate: Pick<StudioCanvasNode, 'id' | 'data'>): boolean {
  return candidate.id !== targetNodeId && ['video', 'voice', 'image'].includes(candidate.data.kind) && Boolean(candidate.data.mediaUrl?.trim())
}
/** Raw connections: reference edges into the node, then bottom composer refs; media readiness is not considered. */
export function smartEditConnectedIds(target: Pick<StudioCanvasNode, 'id' | 'data'> | undefined, nodeId: string, edges: StudioFlowEdge[]): string[] {
  return [...new Set([
    ...edges.filter((edge) => edge.target === nodeId && edge.data?.kind === 'reference').map((edge) => edge.source),
    ...(target?.data.composerRefs || []).flatMap((ref) => ref.nodeId ? [ref.nodeId] : [])
  ])]
}
export function smartEditSources(nodeId: string, nodes: StudioCanvasNode[], edges: StudioFlowEdge[]): StudioCanvasNode[] {
  const byId = new Map(nodes.map((node) => [node.id, node]))
  return smartEditConnectedIds(byId.get(nodeId), nodeId, edges).flatMap((id) => { const node = byId.get(id); return node && isSmartEditSourceCandidate(nodeId, node) ? [node] : [] })
}
export function clipEndSeconds(clip: { timelineStartSeconds: number; range: SmartEditClipRange } | { timelineStartSeconds: number; durationSeconds: number }): number {
  return clip.timelineStartSeconds + ('range' in clip ? clip.range.endSeconds - clip.range.startSeconds : clip.durationSeconds)
}
export function sequenceMainVideo(clips: SmartEditVideoClip[]): SmartEditVideoClip[] {
  let cursor = 0
  let changed = false
  const next = clips.map((clip) => {
    const start = cursor
    cursor += clip.range.endSeconds - clip.range.startSeconds
    if (clip.timelineStartSeconds !== start) { changed = true; return { ...clip, timelineStartSeconds: start } }
    return clip
  })
  return changed ? next : clips
}
export function packMainVideoOnLoad(timeline: SmartEditTimeline): SmartEditTimeline {
  const sorted = [...timeline.videoClips].sort((a, b) => a.timelineStartSeconds - b.timelineStartSeconds || a.id.localeCompare(b.id))
  const sameOrder = sorted.every((clip, index) => clip === timeline.videoClips[index])
  const videoClips = sequenceMainVideo(sameOrder ? timeline.videoClips : sorted)
  if (videoClips === timeline.videoClips) return timeline
  return { ...timeline, videoClips, durationSeconds: timelineDurationSeconds({ ...timeline, videoClips }) }
}
export function smartEditMediaClips(timeline: SmartEditTimeline): SmartEditAudioClip[] {
  return [...timeline.videoClips, ...timeline.overlayClips, ...timeline.audioClips]
}
export function smartEditDistinctSourceCount(timeline: SmartEditTimeline): number {
  return new Set(smartEditMediaClips(timeline).map((clip) => clip.sourceResourceId || clip.sourceNodeId)).size
}
export function timelineDurationSeconds(timeline: SmartEditTimeline): number {
  return Math.max(0.1, ...smartEditMediaClips(timeline).map(clipEndSeconds), ...[...timeline.subtitles, ...timeline.texts].map((cue) => cue.timelineStartSeconds + cue.durationSeconds))
}
/** 成片对应的时间轴身份：结果只在指纹一致时写回节点。 */
export function smartEditExportFingerprint(edit: Pick<StudioSmartEditState, 'timeline' | 'hiddenTracks'>, aspectRatio?: string): string {
  return JSON.stringify({ timeline: edit.timeline, hiddenTracks: edit.hiddenTracks, aspect: aspectRatio })
}
export function setSmartEditPlaybook(state: StudioSmartEditState, playbook: SmartEditPlaybook): StudioSmartEditState {
  return state.playbook === playbook ? state : { ...state, playbook }
}
