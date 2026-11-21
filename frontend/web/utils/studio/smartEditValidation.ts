import type { SmartEditAudioClip, SmartEditRect, SmartEditTimeline, SmartEditTrack, StudioSmartEditState, StudioCanvasNode, StudioFlowEdge } from '@/types/studio'
import { defaultStudioSmartEditState, packMainVideoOnLoad, SMART_EDIT_OVERLAY_RECT, smartEditConnectedIds, smartEditMediaClips, timelineDurationSeconds } from './studioSmartEdit'

const object = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
const number = (value: unknown, fallback: number, min = 0, max = 86400) => typeof value === 'number' && Number.isFinite(value) ? Math.min(max, Math.max(min, value)) : fallback
const text = (value: unknown, max = 2000) => typeof value === 'string' ? value.slice(0, max) : ''
const array = (value: unknown) => Array.isArray(value) ? value.slice(0, 200) : []
function rect(value: unknown, fallback: SmartEditRect): SmartEditRect {
  const raw = object(value), w = number(raw.w, fallback.w, 0.02, 1), h = number(raw.h, fallback.h, 0.02, 1)
  return { x: number(raw.x, Math.min(fallback.x, 1 - w), 0, 1 - w), y: number(raw.y, Math.min(fallback.y, 1 - h), 0, 1 - h), w, h }
}
/** 仅在存档边界使用：不信任 metadata 的 JSON 形状，作业身份不跨页面恢复。 */
export function normalizeSmartEditState(value: unknown): StudioSmartEditState {
  const raw = object(value), state = defaultStudioSmartEditState(), timeline = object(raw.timeline)
  const ids = new Set<string>()
  const idFor = (item: Record<string, unknown>) => { const id = text(item.id, 256); if (!id || ids.has(id)) return null; ids.add(id); return id }
  for (const lane of ['videoClips', 'overlayClips', 'audioClips'] as const) {
    const items = array(timeline[lane]).flatMap((value) => {
      const item = object(value), id = idFor(item)
      if (!id) return []
      const range = object(item.range), sourceDurationSeconds = number(item.sourceDurationSeconds, 0.1, 0.001)
      const start = number(range.startSeconds, 0, 0, Math.max(0, sourceDurationSeconds - 0.001))
      const end = number(range.endSeconds, sourceDurationSeconds, start + 0.001, sourceDurationSeconds)
      const clip: SmartEditAudioClip = { id, sourceNodeId: text(item.sourceNodeId, 256), sourceResourceId: text(item.sourceResourceId, 64) || undefined, sourceUrl: text(item.sourceUrl, 16384), sourceDurationSeconds, timelineStartSeconds: number(item.timelineStartSeconds, 0), range: { startSeconds: start, endSeconds: end }, volume: number(item.volume, lane === 'overlayClips' ? 0 : 1, 0, 1), invalid: Boolean(item.invalid) || !item.sourceNodeId || item.sourceDurationSeconds !== sourceDurationSeconds || range.startSeconds !== start || range.endSeconds !== end }
      if (lane === 'audioClips') return [clip]
      const transition = object(item.transitionOut)
      return [{ ...clip, track: lane === 'overlayClips' ? 'overlay' : 'video', ...(lane === 'overlayClips' ? { overlayRect: rect(item.overlayRect, SMART_EDIT_OVERLAY_RECT) } : {}), transitionOut: { type: ['fade', 'dissolve', 'wipe'].includes(String(transition.type)) ? transition.type : 'cut', durationSeconds: number(transition.durationSeconds, 0, 0, end - start) } }]
    })
    Object.assign(state.timeline, { [lane]: items })
  }
  for (const lane of ['subtitles', 'texts'] as const) {
    Object.assign(state.timeline, { [lane]: array(timeline[lane]).flatMap((value) => {
      const item = object(value), id = idFor(item)
      if (!id) return []
      const cue = { id, timelineStartSeconds: number(item.timelineStartSeconds, 0), durationSeconds: number(item.durationSeconds, 3, 0.001), text: text(item.text) }
      return [lane === 'texts' ? { ...cue, fontSize: number(item.fontSize, 48, 8, 200), color: /^#[0-9a-f]{6}$/i.test(String(item.color)) ? item.color : '#ffffff', rect: rect(item.rect, { x: 0.1, y: 0.25, w: 0.8, h: 0.25 }), animation: ['fade', 'slide'].includes(String(item.animation)) ? item.animation : 'none' } : cue]
    }) })
  }
  state.timeline.durationSeconds = timelineDurationSeconds(state.timeline)
  // 载入时整理旧存档的主视频，不经过命令，所以不进入撤销。
  state.timeline = packMainVideoOnLoad(state.timeline)
  state.timelineInitialized = raw.timelineInitialized === true || Object.values(state.timeline).some((items) => Array.isArray(items) && items.length > 0)
  // 只有“已初始化且缺字段”才是旧存档，留给 seedLegacySmartEditDismissals 结合连线补齐；未初始化的节点还没导入过，名单为空。
  state.dismissedSourceIds = Array.isArray(raw.dismissedSourceIds) ? [...new Set(array(raw.dismissedSourceIds).map((id) => text(id, 256)).filter(Boolean))] : state.timelineInitialized ? undefined : []
  const compose = object(raw.compose), download = object(raw.composeDownload)
  if (text(compose.jobId, 64) && text(compose.fingerprint, 200000)) state.compose = { jobId: text(compose.jobId, 64), mode: compose.mode === 'canvas' ? 'canvas' : 'local', fingerprint: text(compose.fingerprint, 200000) }
  if (text(download.jobId, 64) && text(download.resourceId, 64) && text(download.resourceUrl, 16384)) state.composeDownload = { jobId: text(download.jobId, 64), resourceId: text(download.resourceId, 64), resourceUrl: text(download.resourceUrl, 16384) }
  return { ...state, playbook: raw.playbook === 'talk' || raw.playbook === 'explain' ? raw.playbook : 'mix', chrome: raw.chrome === 'expanded' ? 'expanded' : 'docked', timelineZoom: number(raw.timelineZoom, 1, 0.25, 4), snapping: raw.snapping !== false, hiddenTracks: array(raw.hiddenTracks).filter((track) => ['video', 'overlay', 'audio', 'subtitle', 'text'].includes(String(track))) as SmartEditTrack[] }
}
export function pruneDismissedSourceIds(ids: string[] | undefined, connectedIds: string[]): string[] {
  const connected = new Set(connectedIds)
  return (ids ?? []).filter((id) => connected.has(id))
}
/** Drops dismissed ids whose reference edge and composer ref are both gone; returns the same array when nothing changes. */
export function pruneSmartEditDismissals(nodes: StudioCanvasNode[], edges: StudioFlowEdge[]): StudioCanvasNode[] {
  if (!nodes.some((node) => node.data.smartEdit?.dismissedSourceIds?.length)) return nodes
  const edgesByTarget = new Map<string, StudioFlowEdge[]>()
  for (const edge of edges) { const list = edgesByTarget.get(edge.target); if (list) list.push(edge); else edgesByTarget.set(edge.target, [edge]) }
  let changed = false
  const next = nodes.map((node) => {
    const ids = node.data.smartEdit?.dismissedSourceIds
    if (!ids?.length) return node
    const kept = pruneDismissedSourceIds(ids, smartEditConnectedIds(node, node.id, edgesByTarget.get(node.id) ?? []))
    if (kept.length === ids.length) return node
    changed = true
    return { ...node, data: { ...node.data, smartEdit: { ...node.data.smartEdit!, dismissedSourceIds: kept } } }
  })
  return changed ? next : nodes
}
/** 旧版打开时会导入全部已连接素材，所以已初始化时间轴上缺片段的已连接视频/音频是用户删掉的。 */
export function seedLegacySmartEditDismissals(nodes: StudioCanvasNode[], edges: StudioFlowEdge[]): StudioCanvasNode[] {
  const byId = new Map(nodes.map((node) => [node.id, node]))
  return nodes.map((node) => {
    const state = node.data.smartEdit
    if (node.data.kind !== 'smart_edit' || !state || state.timelineInitialized !== true || state.dismissedSourceIds !== undefined) return node
    const clipped = new Set(smartEditMediaClips(state.timeline).map((clip) => clip.sourceNodeId))
    const dismissedSourceIds = smartEditConnectedIds(node, node.id, edges).filter((id) => {
      const source = byId.get(id)
      return id !== node.id && (source?.data.kind === 'video' || source?.data.kind === 'voice') && !clipped.has(id)
    })
    return { ...node, data: { ...node.data, smartEdit: { ...state, dismissedSourceIds } } }
  })
}
export function invalidateMissingSmartEditSources(nodes: StudioCanvasNode[]): StudioCanvasNode[] {
  const ids = new Set(nodes.map((node) => node.id))
  return nodes.map((node) => {
    const state = node.data.smartEdit
    if (!state) return node
    let changed = false
    const timeline: SmartEditTimeline = { ...state.timeline }
    for (const lane of ['videoClips', 'overlayClips', 'audioClips'] as const) Object.assign(timeline, { [lane]: timeline[lane].map((clip) => { if (clip.invalid || ids.has(clip.sourceNodeId)) return clip; changed = true; return { ...clip, invalid: true } }) })
    return changed ? { ...node, data: { ...node.data, smartEdit: { ...state, timeline } } } : node
  })
}
