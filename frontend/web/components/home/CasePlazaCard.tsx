'use client'

/* eslint-disable @next/next/no-img-element -- 动态媒体 URL 与本地 SVG 需兼容当前静态导出配置。 */

import type {
  MouseEvent as ReactMouseEvent,
  PointerEvent as ReactPointerEvent
} from 'react'
import { useEffect, useRef, useState } from 'react'
import Link, { useLinkStatus } from 'next/link'
import { CaseMediaImage, CaseMediaLoading } from '@/components/case/CaseMediaLoading'
import groupAvatarRaw from '~/assets/img/home/Group-avtor.svg'
import { useCasePlazaPreviewLock } from '~/hooks/useCasePlazaPreviewLock'
import { casePlazaCardKey, type CasePlazaCardItem } from '~/types/case-plaza'
import { assetUrl } from '~/utils/assetUrl'
import { resolveCasePlazaAspectRatio } from '~/utils/casePlazaAspectRatio'
import { resolveCasePlazaDetailPath } from '~/utils/casePlazaNavigation'
import { acquireMediaLoadSlot } from '~/utils/mediaLoadGate'

const groupAvatarUrl = assetUrl(groupAvatarRaw)

export interface CasePlazaCardProps<T extends CasePlazaCardItem> {
  item: T
  /** Optional pre-navigation notification. The link owns routing, including modified clicks. */
  onOpen?: (item: T) => void
  onMeasure?: (item: T, width: number, height: number) => void
  appearance?: 'default' | 'home-v4'
}

export default function CasePlazaCard<T extends CasePlazaCardItem>({ item, onOpen, onMeasure, appearance = 'default' }: CasePlazaCardProps<T>) {
  const cardRef = useRef<HTMLAnchorElement | null>(null)
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const releaseLoadSlotRef = useRef<(() => void) | null>(null)
  const [isInView, setIsInView] = useState(false)
  const [loadGranted, setLoadGranted] = useState(false)
  const [readyPreview, setReadyPreview] = useState('')
  const { isActive, acquire, release } = useCasePlazaPreviewLock(casePlazaCardKey(item))
  const resolvedRatio = resolveCasePlazaAspectRatio(item)
  const canPreview = !!item.previewVideoUrl
  const shouldMountVideo = canPreview && isActive && loadGranted
  const coverUrl = item.coverImage

  function releaseLoadSlot() {
    releaseLoadSlotRef.current?.()
    releaseLoadSlotRef.current = null
  }

  function pauseVideo() {
    const video = videoRef.current
    if (!video) return
    video.pause()
    video.removeAttribute('src')
    video.load()
  }

  useEffect(() => {
    const card = cardRef.current
    if (!card) return
    if (typeof IntersectionObserver === 'undefined') {
      const frame = requestAnimationFrame(() => setIsInView(true))
      return () => cancelAnimationFrame(frame)
    }

    const observer = new IntersectionObserver(
      ([entry]) => {
        const inView = !!entry?.isIntersecting
        setIsInView(inView)
        if (!inView) release()
      },
      { rootMargin: '500px 0px', threshold: 0.01 }
    )
    observer.observe(card)
    return () => observer.disconnect()
  }, [release])

  useEffect(() => {
    if (!isActive || !canPreview) {
      pauseVideo()
      releaseLoadSlot()
      return
    }

    let cancelled = false
    void acquireMediaLoadSlot(1).then((releaseSlot) => {
      if (cancelled) {
        releaseSlot()
        return
      }
      releaseLoadSlotRef.current = releaseSlot
      setLoadGranted(true)
    })

    return () => {
      cancelled = true
      pauseVideo()
      releaseLoadSlot()
    }
  }, [canPreview, isActive])

  useEffect(() => {
    if (!shouldMountVideo) return
    const video = videoRef.current
    if (!video) return
    void video.play().catch(() => {})
  }, [shouldMountVideo])

  function handlePointerEnter(event: ReactPointerEvent<HTMLElement>) {
    if (event.pointerType !== 'mouse' || !isInView || !canPreview) return
    setLoadGranted(false)
    setReadyPreview('')
    acquire()
  }

  function handlePointerLeave() {
    release()
  }

  function handleVideoReady() {
    setReadyPreview(item.previewVideoUrl)
    releaseLoadSlot()
    void videoRef.current?.play().catch(() => {})
  }

  function handleVideoError() {
    releaseLoadSlot()
    setLoadGranted(false)
    release()
  }

  function measureCover(width: number, height: number) {
    if (item.sourceKind === 'canvas' && resolvedRatio.source === 'fallback') {
      onMeasure?.(item, width, height)
    }
  }

  const href = resolveCasePlazaDetailPath(item)
  const openCard = (event: ReactMouseEvent<HTMLAnchorElement>) => {
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
    release()
    onOpen?.(item)
  }

  if (appearance === 'home-v4') {
    const hot = item.categoryLabel.includes('热门')
    return (
      <Link
        ref={cardRef}
        href={href}
        className="home-case"
        aria-label={`查看作品：${item.title}`}
        data-gallery-work-id={item.id}
        onClick={openCard}
        onPointerEnter={handlePointerEnter}
        onPointerLeave={handlePointerLeave}
        onPointerCancel={handlePointerLeave}
      >
        <div className="home-case__frame" style={{ aspectRatio: resolvedRatio.ratio }}>
          <CaseMediaImage
            src={coverUrl}
            alt=""
            className="home-case__media"
            onReady={measureCover}
          />
          {shouldMountVideo ? (
            <video
              ref={videoRef}
              src={item.previewVideoUrl}
              poster={coverUrl}
              className="home-case__media home-case__video"
              data-ready={readyPreview === item.previewVideoUrl}
              muted
              playsInline
              loop
              preload="metadata"
              aria-hidden="true"
              onLoadedData={handleVideoReady}
              onCanPlay={handleVideoReady}
              onError={handleVideoError}
            />
          ) : null}
          {shouldMountVideo && readyPreview !== item.previewVideoUrl ? <CaseMediaLoading compact label="预览加载中" /> : null}
          {item.categoryLabel ? <span className={`home-case__tag${hot ? ' is-hot' : ''}`}>{item.categoryLabel}</span> : null}
          <CardNavigationStatus />
        </div>
        <div className="home-case__meta">
          <span className="home-case__avatar">
            <img src={item.authorAvatar || groupAvatarUrl} alt="" width={20} height={20} />
          </span>
          <b title={item.title}>{item.title}</b>
          {item.viewCount > 0 ? <span className="home-case__stat">{item.viewCount}</span> : null}
        </div>
      </Link>
    )
  }

  return (
    <Link
      ref={cardRef}
      href={href}
      style={{ color: 'inherit', textDecoration: 'none' }}
      data-gallery-work-id={item.id}
      className="group block w-full cursor-pointer overflow-hidden rounded-[4px] border border-white/[0.06] bg-[rgba(22,26,36,0.95)] shadow-sm transition duration-200 hover:-translate-y-0.5 hover:border-cyan-300/30 hover:shadow-[0_12px_32px_rgba(0,0,0,0.35)] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-300 motion-reduce:transform-none motion-reduce:transition-none"
      aria-label={`查看作品：${item.title}`}
      data-ratio-label={resolvedRatio.label}
      data-ratio-source={resolvedRatio.source}
      onClick={openCard}
      onPointerEnter={handlePointerEnter}
      onPointerLeave={handlePointerLeave}
      onPointerCancel={handlePointerLeave}
    >
      <div
        className="relative overflow-hidden bg-white/[0.04]"
        style={{ aspectRatio: resolvedRatio.ratio }}
      >
        <CaseMediaImage
          src={coverUrl}
          alt={item.title}
          className="block h-full w-full object-cover transition duration-300 group-hover:scale-[1.025] motion-reduce:transform-none motion-reduce:transition-none"
          onReady={measureCover}
        />
        {shouldMountVideo ? (
          <video
            ref={videoRef}
            src={item.previewVideoUrl}
            poster={coverUrl}
            className="absolute inset-0 z-10 h-full w-full object-cover"
            style={{ opacity: readyPreview === item.previewVideoUrl ? 1 : 0 }}
            muted
            playsInline
            loop
            preload="metadata"
            aria-hidden="true"
            onLoadedData={handleVideoReady}
            onCanPlay={handleVideoReady}
            onError={handleVideoError}
          />
        ) : null}

        <div
          className="case-plaza-card__overlay pointer-events-none absolute inset-x-0 bottom-0 z-20 translate-y-2 bg-gradient-to-t from-black/95 via-black/75 to-transparent opacity-0 transition duration-200 group-hover:translate-y-0 group-hover:opacity-100 group-focus-visible:translate-y-0 group-focus-visible:opacity-100 motion-reduce:transform-none motion-reduce:transition-none"
          style={{ padding: '56px 16px 16px' }}
        >
          <h3
            className="mb-2 line-clamp-2 text-[15px] font-semibold leading-5 text-white drop-shadow-sm"
            title={item.title}
          >
            {item.title}
          </h3>
          <div className="flex items-center justify-between gap-3">
            <div className="flex min-w-0 flex-1 items-center gap-1.5">
              <span className="case-plaza-card__avatar" aria-hidden="true">
                <img
                  src={item.authorAvatar || groupAvatarUrl}
                  alt=""
                  width={16}
                  height={16}
                  className="case-plaza-card__avatar-img"
                />
              </span>
              <span className="truncate text-xs text-white/75">{item.authorName}</span>
            </div>
            <div className="flex shrink-0 items-center gap-1.5 text-[11px] text-white/70">
              {item.viewCount > 0 ? <span>{item.viewCount}集</span> : null}
              <span>{item.categoryLabel}</span>
            </div>
          </div>
        </div>
      </div>
    </Link>
  )
}

function CardNavigationStatus() {
  const { pending } = useLinkStatus()
  return pending ? <span className="home-case__opening" role="status">正在打开…</span> : null
}
