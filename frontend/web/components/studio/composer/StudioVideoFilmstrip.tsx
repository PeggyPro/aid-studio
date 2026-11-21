'use client'

import { memo, useEffect, useRef } from 'react'
import { resolveStudioVideoFilmstripSource } from '@/utils/mediaFetch'

function assignFilmstripSource(
  video: HTMLVideoElement,
  src: string,
  crossOrigin?: 'anonymous'
) {
  if (crossOrigin) video.crossOrigin = crossOrigin
  else video.removeAttribute('crossorigin')
  video.src = src
}

/** One decoder per strip, released after sampling or unmount; never twelve live videos. */
export const StudioVideoFilmstrip = memo(function StudioVideoFilmstrip({ url, onDuration }: {
  url: string
  onDuration: (duration: number) => void
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const durationCallback = useRef(onDuration)
  useEffect(() => { durationCallback.current = onDuration }, [onDuration])
  useEffect(() => {
    const canvas = canvasRef.current
    const context = canvas?.getContext('2d')
    if (!canvas || !context) return
    const video = document.createElement('video')
    video.muted = true
    video.playsInline = true
    video.preload = 'auto'
    let disposed = false
    let index = 0
    let timer: ReturnType<typeof setTimeout>
    let animation = 0
    const release = () => {
      if (disposed) return
      disposed = true
      clearTimeout(timer)
      cancelAnimationFrame(animation)
      video.onloadedmetadata = null
      video.onseeked = null
      video.onerror = null
      video.pause()
      video.removeAttribute('src')
      video.load()
    }
    const seek = () => {
      if (disposed) return
      clearTimeout(timer)
      timer = setTimeout(release, 15000)
      video.currentTime = Math.min(video.duration - 0.001, video.duration * (index + 0.5) / 12)
    }
    video.onloadedmetadata = () => {
      if (!Number.isFinite(video.duration) || video.duration <= 0 || !video.videoWidth) { release(); return }
      durationCallback.current(video.duration)
      seek()
    }
    video.onseeked = () => {
      if (disposed) return
      // Fixed small output size bounds both memory and main-thread drawing work.
      const cellWidth = canvas.width / 12
      const scale = Math.max(cellWidth / video.videoWidth, canvas.height / video.videoHeight)
      const sw = cellWidth / scale, sh = canvas.height / scale
      try {
        context.drawImage(video, (video.videoWidth - sw) / 2, (video.videoHeight - sh) / 2,
          sw, sh, index * cellWidth, 0, cellWidth, canvas.height)
      } catch { release(); return }
      index += 1
      if (index === 12) release()
      else animation = requestAnimationFrame(seek)
    }
    const source = resolveStudioVideoFilmstripSource(url)
    video.onerror = release
    context.clearRect(0, 0, canvas.width, canvas.height)
    timer = setTimeout(release, 15000)
    if (!source.src) { release(); return }
    assignFilmstripSource(video, source.src, source.crossOrigin)
    return release
  }, [url])
  return <canvas ref={canvasRef} width={1536} height={96} aria-hidden="true" />
})
