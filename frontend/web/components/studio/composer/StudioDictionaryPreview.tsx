'use client'

import { useState, type ReactNode } from 'react'
import { resolveMediaPlaybackUrl } from '@/utils/mediaFetch'
import { ShimmerImage } from '@/components/common/ShimmerImage'

/** 预览资源只用于词条展示，不进入生成请求的媒体引用。 */
export function StudioDictionaryPreview({ url, label, fallback }: {
  url: string
  label: string
  fallback: ReactNode
}) {
  const src = resolveMediaPlaybackUrl(url)
  const [failedUrl, setFailedUrl] = useState('')
  const onError = () => setFailedUrl(src)
  if (!src || failedUrl === src) return fallback
  if (/\.(?:mp4|webm|mov|m4v)(?:[?#]|$)/i.test(url)) {
    return <video
      src={src} aria-label={label} muted playsInline loop preload="metadata"
      onLoadedMetadata={(event) => {
        const video = event.currentTarget
        if (Number.isFinite(video.duration) && video.duration > 0) video.currentTime = Math.min(0.05, video.duration / 2)
      }}
      onPointerEnter={(event) => { void event.currentTarget.play().catch(() => {}) }}
      onPointerLeave={(event) => event.currentTarget.pause()}
      onError={onError}
    />
  }
  return <ShimmerImage key={src} src={src} alt={label} objectFit="contain"
    revealDirection="fade" revealMs={180} loadTimeoutMs={20000}
    onError={onError} errorSlot={fallback} />
}
