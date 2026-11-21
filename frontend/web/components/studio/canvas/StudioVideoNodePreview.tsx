'use client'

/* eslint-disable @next/next/no-img-element -- 画布节点需预览运行时 OSS URL。 */
import type { MouseEvent, RefObject } from 'react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  AudioMutedOutlined,
  CameraOutlined,
  DownloadOutlined,
  ExpandAltOutlined,
  PauseOutlined,
  PlayCircleOutlined,
  SoundOutlined
} from '@ant-design/icons'
import { Dropdown, Tooltip } from 'antd'
import type { MenuProps } from 'antd'
import { resolveMediaPlaybackUrl } from '@/utils/mediaFetch'
import { inspectMediaUrl } from '@/utils/mediaUrlPolicy'
import { isImagePreviewUrl } from './StudioMediaPreview'
import { useStudioUiStore } from '@/stores/studioUi'
import { useStudioMediaInView } from '@/hooks/studio/useStudioMediaInView'
import { StudioNodeMediaPlaceholder } from './StudioNodeMediaPlaceholder'

function formatPlaybackClock(seconds: number): string {
  const safe = Number.isFinite(seconds) ? Math.max(0, seconds) : 0
  const minutes = Math.floor(safe / 60)
  const secs = Math.floor(safe % 60)
  return `${minutes}:${String(secs).padStart(2, '0')}`
}

export function StudioVideoNodePreview({
  nodeId,
  mediaUrl,
  directPlayback,
  title,
  videoRef,
  captureBusy,
  onIntrinsicSize,
  onPreview,
  onDownload,
  onCaptureFirst,
  onCaptureCurrent,
  onCaptureLast
}: {
  nodeId?: string
  mediaUrl: string
  directPlayback?: boolean
  title: string
  videoRef?: RefObject<HTMLVideoElement | null>
  captureBusy?: boolean
  onIntrinsicSize?: (width: number, height: number) => void
  onPreview: () => void
  onDownload?: () => void
  onCaptureFirst?: () => void
  onCaptureCurrent?: () => void
  onCaptureLast?: () => void
}) {
  const editing = useStudioUiStore((state) => Boolean(nodeId && state.videoClipSession?.nodeId === nodeId))
  const posterOnly = isImagePreviewUrl(mediaUrl)
  // 静态部署的自由画布没有 Next 媒体代理路由；视频预览直接使用已校验的资源地址。
  const playbackUrl = posterOnly || directPlayback
    ? (inspectMediaUrl(mediaUrl).allowed ? mediaUrl : '')
    : resolveMediaPlaybackUrl(mediaUrl)
  const localVideoRef = useRef<HTMLVideoElement | null>(null)
  const previewRef = useRef<HTMLDivElement | null>(null)
  const inlinePoster = posterOnly && /^data:image\//i.test(playbackUrl)
  const previewRequested = useStudioMediaInView(previewRef, Boolean(directPlayback) && !inlinePoster)
  const [load, setLoad] = useState<{ url: string; failed: boolean } | null>(null)
  const [posterAttempt, setPosterAttempt] = useState(0)
  const mediaSettled = load?.url === playbackUrl
  const mediaFailed = mediaSettled && load.failed
  const [hovered, setHovered] = useState(false)
  const hoveredRef = useRef(false)
  const [captureMenuOpen, setCaptureMenuOpen] = useState(false)
  const [playing, setPlaying] = useState(false)
  const [muted, setMuted] = useState(false)
  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(0)

  const assignVideoRef = useCallback((node: HTMLVideoElement | null) => {
    localVideoRef.current = node
    if (videoRef) videoRef.current = node
  }, [videoRef])

  const getVideo = useCallback(() => localVideoRef.current ?? videoRef?.current ?? null, [videoRef])

  const captureMenu: MenuProps = useMemo(() => ({
    className: 'studio-toolbar-more-menu studio-toolbar-more-menu--canvas',
    selectable: false,
    items: [
      onCaptureFirst ? { key: 'first', label: '截取首帧' } : null,
      onCaptureCurrent ? { key: 'current', label: '截取当前帧' } : null,
      onCaptureLast ? { key: 'last', label: '截取尾帧' } : null
    ].filter((item) => item !== null),
    onClick: ({ key }) => {
      if (key === 'first') onCaptureFirst?.()
      if (key === 'current') onCaptureCurrent?.()
      if (key === 'last') onCaptureLast?.()
    }
  }), [onCaptureCurrent, onCaptureFirst, onCaptureLast])

  const hasCaptureMenu = !posterOnly && captureMenu.items && captureMenu.items.length > 0

  const syncFromVideo = useCallback((video: HTMLVideoElement) => {
    setCurrentTime(Number.isFinite(video.currentTime) ? video.currentTime : 0)
    setDuration(Number.isFinite(video.duration) ? video.duration : 0)
    setPlaying(!video.paused && !video.ended)
    setMuted(video.muted)
  }, [])

  useEffect(() => {
    if (posterOnly) return
    const video = getVideo()
    if (!video) return

    const onTimeUpdate = () => syncFromVideo(video)
    const onMeta = () => syncFromVideo(video)
    const onLoadedMeta = () => syncFromVideo(video)
    const onPlay = () => setPlaying(true)
    const onPause = () => setPlaying(false)
    const onVolume = () => setMuted(video.muted)
    const onEnded = () => {
      setPlaying(false)
      setCurrentTime(Number.isFinite(video.duration) ? video.duration : video.currentTime)
    }

    syncFromVideo(video)
    video.addEventListener('timeupdate', onTimeUpdate)
    video.addEventListener('loadedmetadata', onLoadedMeta)
    video.addEventListener('durationchange', onMeta)
    video.addEventListener('play', onPlay)
    video.addEventListener('pause', onPause)
    video.addEventListener('volumechange', onVolume)
    video.addEventListener('ended', onEnded)
    return () => {
      video.removeEventListener('timeupdate', onTimeUpdate)
      video.removeEventListener('loadedmetadata', onLoadedMeta)
      video.removeEventListener('durationchange', onMeta)
      video.removeEventListener('play', onPlay)
      video.removeEventListener('pause', onPause)
      video.removeEventListener('volumechange', onVolume)
      video.removeEventListener('ended', onEnded)
    }
  }, [getVideo, posterOnly, playbackUrl, syncFromVideo])

  const playVideo = useCallback(async () => {
    const video = getVideo()
    if (!video) return
    try {
      await video.play()
    } catch {
      video.muted = true
      setMuted(true)
      try {
        await video.play()
      } catch {
        /* 浏览器策略或资源未就绪时保持静止 */
      }
    }
  }, [getVideo])

  const pauseAndReset = useCallback(() => {
    const video = getVideo()
    if (!video) return
    video.pause()
    try {
      video.currentTime = 0
    } catch {
      /* ignore seek before metadata */
    }
    setPlaying(false)
    setCurrentTime(0)
  }, [getVideo])

  const handlePointerEnter = useCallback(() => {
    if (posterOnly || hoveredRef.current || (directPlayback && previewRef.current?.closest('.is-viewport-moving'))) return
    hoveredRef.current = true
    setHovered(true)
    if (editing) return
    void playVideo()
  }, [directPlayback, editing, playVideo, posterOnly])

  const handlePointerLeave = useCallback(() => {
    if (posterOnly) return
    hoveredRef.current = false
    if (captureMenuOpen) return
    setHovered(false)
    if (editing) return
    pauseAndReset()
  }, [captureMenuOpen, editing, pauseAndReset, posterOnly])

  const handleCaptureMenuOpenChange = useCallback((open: boolean) => {
    setCaptureMenuOpen(open)
    if (!open && !hoveredRef.current) {
      setHovered(false)
      if (!editing) pauseAndReset()
    }
  }, [editing, pauseAndReset])

  const togglePlay = useCallback((event: MouseEvent) => {
    event.stopPropagation()
    const video = getVideo()
    if (!video) return
    if (video.paused || video.ended) void playVideo()
    else video.pause()
  }, [getVideo, playVideo])

  const toggleMute = useCallback((event: MouseEvent) => {
    event.stopPropagation()
    const video = getVideo()
    if (!video) return
    video.muted = !video.muted
    setMuted(video.muted)
  }, [getVideo])

  const seekTo = useCallback((ratio: number) => {
    const video = getVideo()
    if (!video || !Number.isFinite(video.duration) || video.duration <= 0) return
    const next = Math.min(Math.max(ratio, 0), 1) * video.duration
    video.currentTime = next
    setCurrentTime(next)
  }, [getVideo])

  const progress = duration > 0 ? Math.min(Math.max(currentTime / duration, 0), 1) : 0

  return (
    <div
      ref={previewRef}
      className={`studio-image-node-preview studio-video-node-preview studio-node-card__preview${posterOnly ? ' studio-node-card__preview--video-poster' : ' studio-video-node-preview--playable'}${hovered ? ' is-hovered' : ''}`}
      onPointerEnter={handlePointerEnter}
      onPointerMove={directPlayback ? handlePointerEnter : undefined}
      onPointerLeave={handlePointerLeave}
      onDoubleClick={(event) => {
        if (posterOnly) return
        event.stopPropagation()
        onPreview()
      }}
    >
      {posterOnly ? (
        <img
          key={posterAttempt}
          src={previewRequested ? playbackUrl : undefined}
          alt={`${title}视频缩略图`}
          draggable={false}
          decoding={directPlayback ? 'async' : 'auto'}
          onLoad={event => {
            if (directPlayback) setLoad({ url: playbackUrl, failed: false })
            onIntrinsicSize?.(event.currentTarget.naturalWidth, event.currentTarget.naturalHeight)
          }}
          onError={() => { if (directPlayback) setLoad({ url: playbackUrl, failed: true }) }}
        />
      ) : (
        <video
          ref={assignVideoRef}
          src={playbackUrl || undefined}
          playsInline
          preload={!previewRequested && !hovered && !editing ? 'none' : 'metadata'}
          muted={muted}
          aria-label={`${title}视频预览`}
          onLoadedData={() => { if (directPlayback) setLoad({ url: playbackUrl, failed: false }) }}
          onError={() => { if (directPlayback) setLoad({ url: playbackUrl, failed: true }) }}
          onLoadedMetadata={(event) =>
            onIntrinsicSize?.(event.currentTarget.videoWidth, event.currentTarget.videoHeight)
          }
        >
          当前浏览器无法播放该视频。
        </video>
      )}
      {directPlayback && (!mediaSettled || mediaFailed || !playbackUrl) ? (
        <StudioNodeMediaPlaceholder kind="video" failed={mediaFailed || !playbackUrl} onRetry={playbackUrl ? () => {
          setLoad(null)
          if (posterOnly) setPosterAttempt(attempt => attempt + 1)
          else getVideo()?.load()
        } : undefined} />
      ) : null}
      {posterOnly ? (
        <button
          type="button"
          className="studio-video-node-preview__play nodrag"
          aria-label={`预览${title}视频缩略图`}
          onClick={(event) => {
            event.stopPropagation()
            onPreview()
          }}
        >
          <PlayCircleOutlined />
          <span className="studio-node-card__video-placeholder">仅有视频缩略图</span>
        </button>
      ) : null}
      <div className="studio-image-node-preview__actions nodrag">
        <Tooltip title="放大预览">
          <button
            type="button"
            className="studio-image-node-preview__action"
            aria-label="放大预览"
            onClick={(event) => {
              event.stopPropagation()
              onPreview()
            }}
          >
            <ExpandAltOutlined />
          </button>
        </Tooltip>
        {onDownload ? (
          <Tooltip title="下载">
            <button
              type="button"
              className="studio-image-node-preview__action"
              aria-label="下载视频"
              onClick={(event) => {
                event.stopPropagation()
                onDownload()
              }}
            >
              <DownloadOutlined />
            </button>
          </Tooltip>
        ) : null}
      </div>
      {!posterOnly ? (
        <div
          className={`studio-video-node-preview__bar nodrag nopan nowheel${hovered || editing ? ' is-visible' : ''}`}
          onPointerDown={(event) => event.stopPropagation()}
        >
          <button
            type="button"
            className="studio-video-node-preview__bar-btn"
            aria-label={playing ? '暂停' : '播放'}
            onClick={togglePlay}
          >
            {playing ? <PauseOutlined /> : <PlayCircleOutlined />}
          </button>
          <span className="studio-video-node-preview__time">{formatPlaybackClock(currentTime)}</span>
          <input
            type="range"
            className="studio-video-node-preview__seek"
            min={0}
            max={1}
            step={0.001}
            value={progress}
            aria-label="播放进度"
            onChange={(event) => seekTo(Number(event.currentTarget.value))}
          />
          <span className="studio-video-node-preview__time">{formatPlaybackClock(duration)}</span>
          <button
            type="button"
            className="studio-video-node-preview__bar-btn"
            aria-label={muted ? '取消静音' : '静音'}
            onClick={toggleMute}
          >
            {muted ? <AudioMutedOutlined /> : <SoundOutlined />}
          </button>
          {hasCaptureMenu ? (
            <Dropdown
              menu={captureMenu}
              trigger={['click']}
              placement="topRight"
              open={captureMenuOpen}
              onOpenChange={handleCaptureMenuOpenChange}
            >
              <button
                type="button"
                className="studio-video-node-preview__bar-btn"
                aria-label="截取画面"
                disabled={captureBusy}
                onClick={(event) => event.stopPropagation()}
              >
                <CameraOutlined />
              </button>
            </Dropdown>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}
