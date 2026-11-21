'use client'

import { useEffect, useRef, useState, type PointerEvent } from 'react'
import { CaretRightOutlined, PauseOutlined } from '@ant-design/icons'
import { useStudioUiStore } from '@/stores/studioUi'
import { useStudioAudioEditor } from '@/stores/studioAudioEditor'
import { useStudioAudioPeaks } from '@/hooks/studio/useStudioAudioPeaks'
import { resolveMediaPlaybackUrl } from '@/utils/mediaFetch'
import { audioTime } from '@/utils/studio/studioAudioEdit'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'
import './studio-audio-tools.css'

export function StudioAudioNodePreview({ nodeId, url }: { nodeId: string; url: string }) {
  const audioRef = useRef<HTMLAudioElement>(null), trackRef = useRef<HTMLDivElement>(null)
  const drag = useRef<{ mode: 'start' | 'end' | 'move'; origin: number; start: number; end: number } | null>(null)
  const [duration, setDuration] = useState(0), [time, setTime] = useState(0), [playing, setPlaying] = useState(false)
  const [visible, setVisible] = useState(false)
  const [dragMode, setDragMode] = useState<'start' | 'end' | 'move' | null>(null)
  const [failed, setFailed] = useState(false)
  const playbackUrl = resolveMediaPlaybackUrl(url)
  const scope = useStudioUiStore((state) => state.scopeKey)
  const edit = useStudioAudioEditor((state) => {
    const session = state.session
    return session?.scopeKey === scope && session.sourceNodeId === nodeId && session.sourceAudioUrl === url ? session : null
  })
  const peaks = useStudioAudioPeaks(playbackUrl, duration, visible)
  useEffect(() => {
    const track = trackRef.current
    if (!track) return
    if (typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(([entry]) => setVisible(entry.isIntersecting))
    observer.observe(track)
    return () => observer.disconnect()
  }, [])
  useEffect(() => {
    const audio = audioRef.current
    if (audio) { audio.playbackRate = edit?.operation === 'SPEED' ? edit.speed : 1; audio.preservesPitch = true }
  }, [edit?.operation, edit?.speed])
  const seconds = (x: number) => {
    const rect = trackRef.current!.getBoundingClientRect()
    return Math.max(0, Math.min(duration, (x - rect.left) / Math.max(1, rect.width) * duration))
  }
  function start(event: PointerEvent<HTMLElement>, mode: 'start' | 'end' | 'move') {
    if (!edit || !duration || event.button !== 0) return
    event.preventDefault(); event.stopPropagation(); audioRef.current?.pause()
    event.currentTarget.setPointerCapture(event.pointerId)
    drag.current = { mode, origin: seconds(event.clientX), start: edit.startSeconds, end: edit.endSeconds }
    setDragMode(mode)
  }
  function move(event: PointerEvent<HTMLDivElement>) {
    const value = drag.current
    if (!value) return
    const point = seconds(event.clientX), minimum = Math.min(.05, duration)
    let start = value.start, end = value.end
    if (value.mode === 'start') start = Math.max(0, Math.min(end - minimum, point))
    else if (value.mode === 'end') end = Math.min(duration, Math.max(start + minimum, point))
    else { start = Math.max(0, Math.min(duration - (end - start), value.start + point - value.origin)); end = start + value.end - value.start }
    useStudioAudioEditor.getState().patch({ startSeconds: start, endSeconds: end })
  }
  return <div className="studio-audio-preview nowheel" onKeyDown={(event) => event.stopPropagation()}>
    <audio ref={audioRef} src={playbackUrl || undefined} preload="metadata"
      onError={() => { setFailed(true); setPlaying(false); setDuration(0) }}
      onLoadedMetadata={(event) => {
        const next = event.currentTarget.duration
        if (!Number.isFinite(next) || next <= 0) return
        setDuration(next)
        setFailed(false)
        const state = useStudioUiStore.getState()
        const node = state.nodes.find((item) => item.id === nodeId)
        if (node?.data.mediaUrl === url && node.data.audioDurationSeconds !== next) state.updateNodeData(nodeId, { audioDurationSeconds: next }, false)
      }}
      onPlay={() => setPlaying(true)} onPause={() => setPlaying(false)} onEnded={() => setPlaying(false)}
      onTimeUpdate={(event) => {
        const audio = event.currentTarget; setTime(audio.currentTime)
        if (edit?.operation === 'TRIM' && audio.currentTime >= edit.endSeconds) audio.pause()
      }} />
    <div ref={trackRef} className="studio-audio-preview__wave" onClick={(event) => {
      if (edit?.operation === 'TRIM' || !duration || !audioRef.current) return
      audioRef.current.currentTime = seconds(event.clientX)
    }}>
      <svg viewBox="0 0 288 64" preserveAspectRatio="none" aria-label={peaks.length ? '音频波形' : '音频时间轴'}>
        {peaks.length ? peaks.map((peak, i) => <line key={i} x1={i * 3 + 1} x2={i * 3 + 1} y1={32 - Math.max(1, peak * 28)} y2={32 + Math.max(1, peak * 28)} />) : <line x1="0" x2="288" y1="32" y2="32" />}
      </svg>
      <i className="studio-audio-preview__playhead" style={{ left: `${duration ? time / duration * 100 : 0}%` }} />
      {edit?.operation === 'TRIM' && duration > 0 && <div className="studio-audio-preview__range nodrag nopan" data-dragging={dragMode ?? undefined} style={{ left: `${edit.startSeconds / duration * 100}%`, width: `${(edit.endSeconds - edit.startSeconds) / duration * 100}%` }}
        onPointerDown={(event) => start(event, 'move')} onPointerMove={move}
        onPointerUp={(event) => { move(event); drag.current = null; setDragMode(null); if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId) }}
        onPointerCancel={() => { drag.current = null; setDragMode(null) }}>
        <button type="button" aria-label="调整音频裁剪开始" title="拖动调整裁剪起点" className="is-start nodrag nopan" onPointerDown={(event) => start(event, 'start')} />
        <span>{(edit.endSeconds - edit.startSeconds).toFixed(2)}s</span>
        <button type="button" aria-label="调整音频裁剪结束" title="拖动调整裁剪终点" className="is-end nodrag nopan" onPointerDown={(event) => start(event, 'end')} />
      </div>}
    </div>
    <div className="studio-audio-preview__transport"><span>{failed ? '音频加载失败，请检查源文件' : `${audioTime(time)} / ${audioTime(duration)}`}</span>
      <StudioCanvasTooltip title={playing ? '暂停' : '播放音频'}><button type="button" className="nodrag nopan" aria-label={playing ? '暂停音频' : '播放音频'} disabled={!duration} onPointerDown={(event) => event.stopPropagation()} onClick={() => {
        const audio = audioRef.current; if (!audio) return
        if (playing) audio.pause(); else {
          if (edit?.operation === 'TRIM' && (audio.currentTime < edit.startSeconds || audio.currentTime >= edit.endSeconds)) audio.currentTime = edit.startSeconds
          void audio.play().catch(() => setPlaying(false))
        }
      }} data-state={playing ? 'pause' : 'play'}>{playing ? <PauseOutlined /> : <CaretRightOutlined />}</button></StudioCanvasTooltip>
    </div>
  </div>
}
