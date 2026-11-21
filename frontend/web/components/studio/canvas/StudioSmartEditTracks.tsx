'use client'

import { useRef, useState, type PointerEvent } from 'react'
import { EyeInvisibleOutlined, EyeOutlined } from '@ant-design/icons'
import type { SmartEditCommand, SmartEditTrack, SmartEditVideoClip, StudioSmartEditState } from '@/types/studio'
import { useStudioSmartEdit } from '@/stores/studioSmartEdit'
import { clipEndSeconds, sequenceMainVideo } from '@/utils/studio/studioSmartEdit'
import { SMART_EDIT_PIXELS_PER_SECOND, smartEditClock, snapSmartEditTime } from '@/utils/studio/smartEditPlayback'
import type { SmartEditItem } from '@/utils/studio/smartEditCommands'

const lanes = [
  { key: 'texts', id: 'text', label: '文字 / 特效' }, { key: 'subtitles', id: 'subtitle', label: '字幕' },
  { key: 'overlayClips', id: 'overlay', label: '画中画' }, { key: 'videoClips', id: 'video', label: '主视频' }, { key: 'audioClips', id: 'audio', label: '音频' }
] as const
type Draft = { id: string; start: number; end: number; mode?: 'move' | 'in' | 'out' }

function videoDropIndex(clips: SmartEditVideoClip[], id: string, start: number, end: number) {
  const others = sequenceMainVideo(clips.filter((clip) => clip.id !== id))
  const index = others.filter((clip) => (clip.timelineStartSeconds + clipEndSeconds(clip)) / 2 < (start + end) / 2).length
  return { index, at: index < others.length ? others[index].timelineStartSeconds : others.length ? clipEndSeconds(others[others.length - 1]) : 0 }
}

export function StudioSmartEditTracks({ edit, disabled, titles, command }: { edit: StudioSmartEditState; disabled: boolean; titles: Record<string, string>; command: (command: SmartEditCommand) => void }) {
  const selected = useStudioSmartEdit((state) => state.selectedClipId)
  const time = useStudioSmartEdit((state) => state.playheadSeconds)
  const drag = useRef<{ id: string; x: number; mode: 'move' | 'in' | 'out'; original: SmartEditItem; draft?: Draft } | null>(null)
  const [draft, setDraftState] = useState<Draft | null>(null)
  const setDraft = (next: Draft) => { if (drag.current) drag.current.draft = next = { ...next, mode: drag.current.mode }; setDraftState(next) }
  const drop = draft?.mode === 'move' && edit.timeline.videoClips.some((clip) => clip.id === draft.id) ? videoDropIndex(edit.timeline.videoClips, draft.id, draft.start, draft.end) : null
  const pps = SMART_EDIT_PIXELS_PER_SECOND * edit.timelineZoom
  const duration = Math.max(10, edit.timeline.durationSeconds + 2)
  const width = Math.max(600, duration * pps)
  const baseStep = edit.timelineZoom < 0.6 ? 5 : edit.timelineZoom > 2 ? 0.5 : 1
  const step = baseStep * Math.max(1, Math.ceil(duration / baseStep / 400))
  const begin = (event: PointerEvent, item: SmartEditItem, mode: 'move' | 'in' | 'out') => {
    event.stopPropagation()
    useStudioSmartEdit.setState({ selectedClipId: item.id, playing: false })
    if (disabled) return
    event.currentTarget.setPointerCapture(event.pointerId)
    drag.current = { id: item.id, x: event.clientX, mode, original: item }
  }
  const move = (event: PointerEvent) => {
    const current = drag.current
    if (!current) return
    const item = current.original
    const start = item.timelineStartSeconds, end = clipEndSeconds(item)
    const delta = (event.clientX - current.x) / pps
    const snap = (value: number) => snapSmartEditTime(value, edit.timeline, item.id, time, edit.timelineZoom, edit.snapping)
    if (current.mode === 'move') {
      let next = snap(start + delta)
      const endSnap = snap(next + end - start)
      if (next === Math.max(0, start + delta) && endSnap !== next + end - start) next = Math.max(0, endSnap - (end - start))
      setDraft({ id: item.id, start: next, end: next + end - start })
    } else if (current.mode === 'in') setDraft({ id: item.id, start: Math.min(end - 0.04, Math.max('range' in item ? start - item.range.startSeconds : 0, snap(start + delta))), end })
    else setDraft({ id: item.id, start, end: Math.max(start + 0.04, Math.min('range' in item ? end + item.sourceDurationSeconds - item.range.endSeconds : Infinity, snap(end + delta))) })
  }
  const finish = (cancel = false) => {
    const current = drag.current, draft = current?.draft
    if (!cancel && current && draft && !disabled) {
      const item = current.original
      if (current.mode === 'move' && 'track' in item && item.track === 'video') command({ type: 'reorderVideoClip', clipId: item.id, toIndex: videoDropIndex(edit.timeline.videoClips, item.id, draft.start, draft.end).index })
      else if (current.mode === 'move') command({ type: 'moveClip', clipId: item.id, timelineStartSeconds: draft.start })
      else if ('range' in item) {
        command({ type: 'trimClip', clipId: item.id, timelineStartSeconds: draft.start, range: { startSeconds: item.range.startSeconds + draft.start - item.timelineStartSeconds, endSeconds: item.range.endSeconds + draft.end - clipEndSeconds(item) } })
      } else command({ type: 'trimClip', clipId: item.id, range: { startSeconds: draft.start, endSeconds: draft.end } })
    }
    drag.current = null; setDraftState(null)
  }
  const seek = (event: PointerEvent<HTMLDivElement>) => {
    const rect = event.currentTarget.getBoundingClientRect()
    useStudioSmartEdit.setState({ playing: false, playheadSeconds: Math.max(0, Math.min(edit.timeline.durationSeconds, (event.clientX - rect.left) / pps)) })
  }
  return <div className="studio-smart-edit-tracks" data-smart-edit-track-viewport>
    <div className="studio-smart-edit-tracks__labels"><div className="studio-smart-edit-tracks__ruler-label">时间轴</div>{lanes.map((lane) => <div key={lane.id}><button type="button" disabled={disabled} aria-label={`${edit.hiddenTracks?.includes(lane.id) ? '显示' : '隐藏'}${lane.label}`} onClick={() => command({ type: 'setTrackHidden', track: lane.id, hidden: !edit.hiddenTracks?.includes(lane.id) })}>{edit.hiddenTracks?.includes(lane.id) ? <EyeInvisibleOutlined /> : <EyeOutlined />}</button>{lane.label}</div>)}</div>
    <div className="studio-smart-edit-tracks__scroll"><div className="studio-smart-edit-tracks__content" style={{ width }}>
      <div className="studio-smart-edit-tracks__ruler" role="slider" tabIndex={0} aria-label="播放头" aria-valuemin={0} aria-valuemax={edit.timeline.durationSeconds} aria-valuenow={time} onPointerDown={(event) => { event.currentTarget.setPointerCapture(event.pointerId); seek(event) }} onPointerMove={(event) => { if (event.currentTarget.hasPointerCapture(event.pointerId)) seek(event) }} onKeyDown={(event) => { if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) { event.preventDefault(); useStudioSmartEdit.setState({ playing: false, playheadSeconds: event.key === 'Home' ? 0 : event.key === 'End' ? edit.timeline.durationSeconds : Math.min(edit.timeline.durationSeconds, Math.max(0, time + (event.key === 'ArrowLeft' ? -1 : 1) / 30)) }) } }}>
        {Array.from({ length: Math.ceil(duration / step) + 1 }, (_, index) => <span key={index} style={{ left: index * step * pps }}>{smartEditClock(index * step)}</span>)}
      </div>
      {lanes.map((lane) => <div key={lane.id} className={`studio-smart-edit-tracks__lane is-${lane.id}${edit.hiddenTracks?.includes(lane.id as SmartEditTrack) ? ' is-hidden' : ''}`} onPointerDown={() => useStudioSmartEdit.setState({ selectedClipId: null })}>
        {(edit.timeline[lane.key] as SmartEditItem[]).map((item, index) => {
          const start = draft?.id === item.id ? draft.start : item.timelineStartSeconds
          const end = draft?.id === item.id ? draft.end : clipEndSeconds(item)
          const sourceTitle = 'sourceNodeId' in item ? titles[item.sourceNodeId] : undefined
          const label = 'text' in item ? item.text || '空字幕' : 'sourceNodeId' in item ? sourceTitle || '来源失效' : `${lane.label} ${index + 1}`
          return <div key={item.id} role="button" tabIndex={0} aria-label={`${lane.label}片段 ${index + 1}${sourceTitle ? `，${sourceTitle}` : ''}${'invalid' in item && item.invalid ? '，来源失效' : ''}`} aria-pressed={selected === item.id} className={`studio-smart-edit-tracks__clip${selected === item.id ? ' is-selected' : ''}${'invalid' in item && item.invalid ? ' is-invalid' : ''}`} style={{ left: start * pps, width: Math.max(6, (end - start) * pps) }} onPointerDown={(event) => begin(event, item, 'move')} onPointerMove={move} onPointerUp={() => finish()} onPointerCancel={() => finish(true)} onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); useStudioSmartEdit.setState({ selectedClipId: item.id }) } }}>
            <span className="studio-smart-edit-tracks__handle" aria-hidden="true" onPointerDown={(event) => begin(event, item, 'in')} />
            <span title={label}>{label}</span>
            <span className="studio-smart-edit-tracks__handle" aria-hidden="true" onPointerDown={(event) => begin(event, item, 'out')} />
          </div>
        })}
        {lane.id === 'video' && drop ? <span className="studio-smart-edit-tracks__drop" aria-hidden="true" style={{ left: drop.at * pps }} /> : null}
      </div>)}
      <div className="studio-smart-edit-tracks__playhead" style={{ left: time * pps }} />
    </div></div>
  </div>
}
