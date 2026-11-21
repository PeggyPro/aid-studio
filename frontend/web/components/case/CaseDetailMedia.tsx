'use client'

import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { CaseMediaImage, CaseMediaLoading } from './CaseMediaLoading'

function AutoplayVideo({ src, poster, title }: { src: string; poster?: string; title: string }) {
  const ref = useRef<HTMLVideoElement>(null)
  const attempt = useRef(0)
  const [muted, setMuted] = useState(false)
  const [blocked, setBlocked] = useState(false)
  const [phase, setPhase] = useState<'loading' | 'buffering' | 'ready' | 'error'>('loading')
  const [hasFrame, setHasFrame] = useState(false)
  useEffect(() => {
    const video = ref.current
    if (!video) return
    const generation = ++attempt.current
    const active = () => generation === attempt.current
    // 元素提交后开始播放。浏览器拒绝有声自动播放时，再使用允许的静音播放。
    void video.play().catch(async (error: unknown) => {
      if (!active()) return
      if ((error as { name?: string }).name === 'NotAllowedError') {
        video.muted = true
        setMuted(true)
        try { await video.play(); if (active()) setBlocked(false); return } catch { /* 保留手动入口 */ }
      }
      if (active()) setBlocked(true)
    })
    return () => { attempt.current += 1; video.pause() }
  }, [src])

  async function playWithSound() {
    const video = ref.current
    if (!video) return
    const generation = ++attempt.current
    video.muted = false
    setMuted(false)
    if (video.error) video.load()
    try { await video.play(); if (generation === attempt.current) setBlocked(false) }
    catch { if (generation === attempt.current) setBlocked(true) }
  }
  function ready() { setHasFrame(true); setPhase('ready') }
  function retry() {
    setHasFrame(false)
    setPhase('loading')
    setBlocked(false)
    ref.current?.load()
    void playWithSound()
  }
  return <>
    <video ref={ref} className="case-detail-video case-detail-video--contain" src={src} poster={poster || undefined}
      style={{ opacity: hasFrame ? 1 : 0 }}
      aria-label={title} controls playsInline autoPlay preload="auto"
      onVolumeChange={(event) => setMuted(event.currentTarget.muted)}
      onLoadedData={ready} onCanPlay={ready} onPlaying={() => { ready(); setBlocked(false) }}
      onWaiting={() => setPhase(hasFrame ? 'buffering' : 'loading')}
      onStalled={(event) => { if (!event.currentTarget.paused && event.currentTarget.readyState < 3) setPhase(hasFrame ? 'buffering' : 'loading') }}
      onPause={(event) => { if (hasFrame && !event.currentTarget.error && phase !== 'error') setPhase('ready') }}
      onError={() => { setPhase('error'); setBlocked(false) }} />
    {phase !== 'ready' ? <CaseMediaLoading label={phase === 'error' ? '视频暂时无法播放' : phase === 'buffering' ? '视频缓冲中' : '正在加载视频'} compact={phase === 'buffering'} placement="center" error={phase === 'error'} onRetry={phase === 'error' ? retry : undefined} /> : null}
    {(blocked || muted) && phase !== 'error' ? <button type="button" className="case-detail-play-action" onClick={() => void playWithSound()}>
      {blocked ? '播放视频' : '开启声音'}
    </button> : null}
  </>
}

export function CaseDetailMedia({ videoUrl, imageUrl, title, aspectRatio = 16 / 9 }: {
  videoUrl?: string
  imageUrl?: string
  title: string
  aspectRatio?: number
}) {
  const ratio = Number.isFinite(aspectRatio) && aspectRatio > 0 ? aspectRatio : 16 / 9
  return <div className="case-detail-video-wrap" style={{ '--case-detail-media-ratio': ratio } as CSSProperties}>
    {videoUrl ? <AutoplayVideo key={videoUrl} src={videoUrl} poster={imageUrl} title={title} />
      : imageUrl ? <CaseMediaImage src={imageUrl} alt={title} className="case-detail-cover case-detail-cover--contain" />
        : <p className="case-detail-media-empty">暂无可展示的媒体</p>}
  </div>
}
