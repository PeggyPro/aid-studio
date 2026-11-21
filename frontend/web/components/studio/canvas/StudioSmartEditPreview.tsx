'use client'

import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import { LoadingOutlined } from '@ant-design/icons'
import type { SmartEditAudioClip, StudioSmartEditState } from '@/types/studio'
import { useStudioSmartEdit } from '@/stores/studioSmartEdit'
import { smartEditScene, smartEditTextMotion, type SmartEditVideoLayer } from '@/utils/studio/smartEditPlayback'
import { resolveMediaPlaybackUrl } from '@/utils/mediaFetch'

const NEXT_CLIP_PRELOAD_SECONDS = 2

function Media({ clip, time, playing, running = playing, video, style, silent, onReady }: {
  clip: SmartEditAudioClip; time: number; playing: boolean; running?: boolean; video?: boolean; style?: CSSProperties; silent?: boolean
  onReady?: (key: string, ready: boolean) => void
}) {
  const ref = useRef<HTMLVideoElement & HTMLAudioElement>(null)
  const url = resolveMediaPlaybackUrl(clip.sourceUrl)
  const key = `${clip.id}:${url}`
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [hasFrame, setHasFrame] = useState(false)
  const [showWait, setShowWait] = useState(false)
  const positioned = useRef(false)
  const report = useCallback((next: typeof status) => {
    setStatus(next)
    onReady?.(key, next !== 'loading')
  }, [key, onReady])
  useEffect(() => () => onReady?.(key, false), [key, onReady])
  useEffect(() => {
    if (status !== 'loading') return
    const timer = window.setTimeout(() => setShowWait(true), 250)
    return () => { window.clearTimeout(timer); setShowWait(false) }
  }, [status])
  const sync = useCallback(() => {
    const element = ref.current
    if (!element || !url) return
    element.volume = silent ? 0 : Math.min(1, clip.volume)
    // 共同等待时冻结各轨道。已发出的 seek 完成前不重复跳转，避免打断浏览器解码。
    if (!running && !element.paused) element.pause()
    const canSeek = !element.seeking && element.readyState >= HTMLMediaElement.HAVE_METADATA
      && (!playing || !positioned.current || (running && element.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA))
    if (canSeek) {
      const tolerance = playing && positioned.current ? 0.25 : 0.015
      positioned.current = true
      if (Math.abs(element.currentTime - time) > tolerance) {
        element.currentTime = time
        report('loading')
      }
    }
    if (running && !element.seeking && element.paused) void element.play().catch(() => undefined)
  }, [time, playing, running, clip.volume, silent, url, report])
  useEffect(sync, [sync])
  const mediaReady = () => {
    const element = ref.current
    if (!element || element.seeking || element.readyState < HTMLMediaElement.HAVE_CURRENT_DATA) return
    if (video) setHasFrame(true)
    if (!playing || element.readyState >= HTMLMediaElement.HAVE_FUTURE_DATA || element.duration - element.currentTime <= 0.05) report('ready')
  }
  const events = {
    onLoadedMetadata: sync,
    onLoadedData: mediaReady, onCanPlay: mediaReady, onPlaying: mediaReady,
    onSeeked: () => { sync(); mediaReady() },
    onWaiting: () => { if (ref.current && ref.current.readyState < HTMLMediaElement.HAVE_FUTURE_DATA) report('loading') },
    onError: () => report('error')
  }
  const retry = () => { positioned.current = false; setHasFrame(false); report('loading'); ref.current?.load() }
  if (!video) return <audio ref={ref} src={url || undefined} preload="auto" {...events} />
  return <div className="studio-smart-edit-preview__layer" style={style}>
    <video ref={ref} src={url || undefined} muted={silent} playsInline preload="auto" {...events} />
    {status === 'loading' && showWait ? <div className={`studio-smart-edit-preview__loading${hasFrame ? ' is-buffering' : ''}`} role="status"><LoadingOutlined spin /><span>视频加载中…</span></div> : null}
    {status === 'error' ? <div className="studio-smart-edit-preview__error" role="alert">素材读取失败<button type="button" onClick={retry}>重新加载</button></div> : null}
  </div>
}

export function StudioSmartEditSourcePreview({ url }: { url: string }) {
  return <div className="studio-smart-edit-preview studio-smart-edit-preview--source" aria-label="视频首帧">
    <Media key={url} clip={{ id: 'source-preview', sourceNodeId: '', sourceUrl: url, sourceDurationSeconds: 1, timelineStartSeconds: 0, range: { startSeconds: 0, endSeconds: 1 }, volume: 0 }} time={0} playing={false} video silent />
  </div>
}

export function StudioSmartEditPreview({ nodeId, edit, aspectRatio = '16/9', active = true }: { nodeId: string; edit: StudioSmartEditState; aspectRatio?: string; active?: boolean }) {
  const time = useStudioSmartEdit((state) => active && state.nodeId === nodeId ? state.playheadSeconds : 0)
  const playing = useStudioSmartEdit((state) => active && state.nodeId === nodeId && state.playing)
  const scene = smartEditScene(edit.timeline, time, edit.hiddenTracks)
  const mainClips = useMemo(() => [...edit.timeline.videoClips].reverse()
    .filter((clip) => !clip.invalid)
    .sort((a, b) => a.timelineStartSeconds - b.timelineStartSeconds), [edit.timeline.videoClips])
  const nextClip = playing && !edit.hiddenTracks?.includes('video')
    ? mainClips.find((clip) => clip.timelineStartSeconds > time && clip.timelineStartSeconds <= time + NEXT_CLIP_PRELOAD_SECONDS)
    : undefined
  const warmLayer: SmartEditVideoLayer | undefined = nextClip && !scene.layers.some((layer) => layer.clip.id === nextClip.id)
    ? { clip: nextClip, sourceTime: nextClip.range.startSeconds, opacity: 1, rect: { x: 0, y: 0, w: 1, h: 1 }, reveal: 1, frozen: true }
    : undefined
  const [readiness, setReadiness] = useState<Record<string, boolean>>({})
  const ready = useCallback((key: string, value: boolean) => setReadiness((state) => state[key] === value ? state : { ...state, [key]: value }), [])
  const audio = active ? scene.audio.filter((clip) => !scene.layers.some((layer) => layer.clip.id === clip.id && !layer.frozen)) : []
  const buffering = [...scene.layers.map((layer) => layer.clip), ...audio].some((clip) => !readiness[`${clip.id}:${resolveMediaPlaybackUrl(clip.sourceUrl)}`])
  const running = playing && !buffering
  useEffect(() => {
    const editor = useStudioSmartEdit.getState()
    if (active && editor.nodeId === nodeId && editor.buffering !== buffering) useStudioSmartEdit.setState({ buffering })
  }, [active, buffering, nodeId])
  const [w, h] = aspectRatio.split(/[:/]/).map(Number)
  const ratio = w > 0 && h > 0 ? w / h : 16 / 9
  return <div className="studio-smart-edit-viewport" style={{ '--smart-edit-ratio': ratio } as CSSProperties}>
    <div className="studio-smart-edit-preview" aria-label="剪辑实时预览">
      {Object.values(edit.timeline).every((items) => !Array.isArray(items) || items.length === 0) ? <div className="studio-smart-edit-preview__loading">时间轴为空，请连接视频或添加轨道内容</div> : null}
      {(warmLayer ? [...scene.layers, warmLayer] : scene.layers).map((layer) => <Media key={`${layer.clip.id}:${layer.clip.sourceUrl}`} clip={layer.clip} time={layer.sourceTime} playing={playing && !layer.frozen} running={running && !layer.frozen} video silent={!active || layer.frozen} onReady={ready} style={{ left: `${layer.rect.x * 100}%`, top: `${layer.rect.y * 100}%`, width: `${layer.rect.w * 100}%`, height: `${layer.rect.h * 100}%`, opacity: layer.opacity, clipPath: `inset(0 ${(1 - layer.reveal) * 100}% 0 0)`, visibility: layer === warmLayer ? 'hidden' : undefined }} />)}
      {audio.map((clip) => <Media key={`${clip.id}:${clip.sourceUrl}`} clip={clip} time={clip.range.startSeconds + time - clip.timelineStartSeconds} playing={playing} running={running} onReady={ready} />)}
      <div className="studio-smart-edit-preview__subtitles">{scene.subtitles.map((cue) => <span key={cue.id}>{cue.text}</span>)}</div>
      {scene.texts.map((item) => { const motion = smartEditTextMotion(item, time); return <div key={item.id} className="studio-smart-edit-preview__text" style={{ left: `${item.rect.x * 100}%`, top: `${item.rect.y * 100}%`, width: `${item.rect.w * 100}%`, height: `${item.rect.h * 100}%`, fontSize: `${item.fontSize / 12.8}cqw`, color: item.color, opacity: motion.opacity, transform: `translateY(${motion.translateY * 100}%)` }}>{item.text}</div> })}
    </div>
  </div>
}
