'use client'

import { useEffect, useRef, useState } from 'react'
import './case-media-loading.css'

/** Shared feedback for actual image decoding and video buffering, never a simulated percentage. */
export function CaseMediaLoading({ label = '正在加载画面', variant = 'status', compact = false, placement = 'corner', error = false, onRetry }: {
  label?: string; variant?: 'cover' | 'status'; compact?: boolean; placement?: 'center' | 'corner'; error?: boolean; onRetry?: () => void
}) {
  const coverSkeleton = variant === 'cover' && !error
  return <div className={`case-media-loading${variant === 'cover' ? ' is-cover' : ''}${compact ? ' is-compact' : ''}${placement === 'center' ? ' is-centered' : ''}${error ? ' is-error' : ''}`} role={error ? 'alert' : 'status'}>
    {coverSkeleton ? <span className="case-media-loading__sr-only">{label}</span> : (
      <div className="case-media-loading__content">
        <span className="case-media-loading__message">
          {!error ? <i className="case-media-loading__spinner" aria-hidden="true" /> : null}
          <span>{label}</span>
        </span>
        {onRetry ? <button type="button" onClick={onRetry}>重新加载</button> : null}
      </div>
    )}
  </div>
}

function DecodedImage({ src, alt, className, onReady }: {
  src: string; alt: string; className?: string; onReady?: (width: number, height: number) => void
}) {
  const ref = useRef<HTMLImageElement>(null)
  const callback = useRef(onReady)
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  useEffect(() => { callback.current = onReady }, [onReady])
  useEffect(() => {
    let active = true
    const image = ref.current
    if (!image) return
    const ready = async () => {
      if (!image.naturalWidth) { if (active) setStatus('error'); return }
      // Keep the skeleton over partially downloaded/decoded images, including cached images.
      try { await image.decode?.() } catch { /* Some browsers cannot decode an otherwise valid image. */ }
      if (!active) return
      setStatus('ready')
      callback.current?.(image.naturalWidth, image.naturalHeight)
    }
    const failed = () => { if (active) setStatus('error') }
    image.addEventListener('load', ready)
    image.addEventListener('error', failed)
    if (image.complete) {
      if (image.naturalWidth) void ready()
      else if (image.currentSrc) failed()
    }
    return () => { active = false; image.removeEventListener('load', ready); image.removeEventListener('error', failed) }
  }, [])
  return <>
    {/* eslint-disable-next-line @next/next/no-img-element -- Runtime public media URLs, static export supports direct sources. */}
    <img ref={ref} src={src} alt={alt} className={`${className ?? ''} case-decoded-image`} data-ready={status === 'ready'} loading="lazy" decoding="async" />
    {status !== 'ready' ? <CaseMediaLoading variant="cover" label={status === 'error' ? '图片暂时无法加载' : '正在加载画面'} error={status === 'error'} /> : null}
  </>
}

export function CaseMediaImage(props: { src: string; alt: string; className?: string; onReady?: (width: number, height: number) => void }) {
  return <DecodedImage key={props.src} {...props} />
}
