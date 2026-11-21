'use client'

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import { casePlazaCardKey, type CasePlazaCardItem } from '~/types/case-plaza'
import type { CasePlazaFeedStatus } from '~/utils/casePlazaFeedState'
import {
  distributeCasePlazaItemsToColumns,
  resolveCasePlazaColumnCount
} from '~/utils/casePlazaMasonryLayout'
import CasePlazaCard from './CasePlazaCard'
import { casePlazaFeedbackHeight } from '@/utils/casePlazaViewport'
import './CasePlazaMasonry.css'

export interface CasePlazaMasonryProps<T extends CasePlazaCardItem> {
  items: T[]
  status: CasePlazaFeedStatus
  hasMore: boolean
  error: string
  onLoadMore?: () => void
  onReload?: () => void
  onOpen?: (item: T) => void
  appearance?: 'default' | 'home-v4'
}

const HOME_CASE_GUTTER = 8

function CasePlazaPending() {
  const [visible, setVisible] = useState(false)
  useEffect(() => {
    const timer = window.setTimeout(() => setVisible(true), 200)
    return () => window.clearTimeout(timer)
  }, [])
  return <div className="case-plaza-pending" role="status" aria-label="正在加载案例">
    {visible ? <span className="case-plaza-pending__bar" aria-hidden="true" /> : null}
  </div>
}

export default function CasePlazaMasonry<T extends CasePlazaCardItem>({
  items,
  status,
  hasMore,
  error,
  onLoadMore,
  onReload,
  onOpen,
  appearance = 'default'
}: CasePlazaMasonryProps<T>) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const sentinelRef = useRef<HTMLDivElement | null>(null)
  const [columnCount, setColumnCount] = useState(4)
  const loadingFirst = status === 'loadingFirst'
  const loadingMore = status === 'loadingMore'
  const feedback = loadingFirst || status === 'errorFirst' || !items.length
  const [measurements, setMeasurements] = useState<Record<string, { cover: string; mediaWidth: number; mediaHeight: number }>>({})
  const measure = useCallback((item: T, width: number, height: number) => {
    if (width <= 0 || height <= 0) return
    const key = casePlazaCardKey(item)
    setMeasurements((current) => {
      const old = current[key]
      if (old?.cover === item.coverImage && old.mediaWidth === width && old.mediaHeight === height) return current
      return { ...current, [key]: { cover: item.coverImage, mediaWidth: width, mediaHeight: height } }
    })
  }, [])

  useLayoutEffect(() => {
    const container = containerRef.current
    if (!container) return

    const updateColumnCount = () => {
      const width = container.clientWidth
      if (width <= 0) return
      setColumnCount(appearance === 'home-v4'
        ? Math.max(2, Math.floor((width + HOME_CASE_GUTTER) / (260 + HOME_CASE_GUTTER)))
        : resolveCasePlazaColumnCount(width))
      if (feedback) {
        const height = `${casePlazaFeedbackHeight(container)}px`
        if (container.style.getPropertyValue('--case-plaza-available-height') !== height) {
          container.style.setProperty('--case-plaza-available-height', height)
        }
      }
    }
    updateColumnCount()
    let frame = 0
    const scheduleMeasurement = () => {
      if (!frame) frame = window.requestAnimationFrame(() => { frame = 0; updateColumnCount() })
    }
    const removeListeners = () => {
      window.cancelAnimationFrame(frame)
      window.removeEventListener('resize', scheduleMeasurement)
      window.removeEventListener('scroll', scheduleMeasurement, true)
      window.visualViewport?.removeEventListener('resize', scheduleMeasurement)
    }
    window.addEventListener('resize', scheduleMeasurement)
    window.addEventListener('scroll', scheduleMeasurement, { capture: true, passive: true })
    window.visualViewport?.addEventListener('resize', scheduleMeasurement)
    if (typeof ResizeObserver === 'undefined') {
      return removeListeners
    }

    const observer = new ResizeObserver(scheduleMeasurement)
    observer.observe(container)
    for (let parent = container.parentElement; parent && parent !== document.body; parent = parent.parentElement) {
      observer.observe(parent)
      if (/(auto|scroll)/.test(getComputedStyle(parent).overflowY)) break
    }
    return () => {
      observer.disconnect()
      removeListeners()
    }
  }, [appearance, feedback])

  useEffect(() => {
    const sentinel = sentinelRef.current
    if (!sentinel || !hasMore || status !== 'ready') return
    if (typeof IntersectionObserver === 'undefined') return

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry?.isIntersecting) onLoadMore?.()
      },
      { rootMargin: '1200px 0px', threshold: 0 }
    )
    observer.observe(sentinel)
    return () => observer.disconnect()
  }, [hasMore, onLoadMore, status])

  const columns = useMemo(
    () => distributeCasePlazaItemsToColumns(items.map((item) => {
      const size = measurements[casePlazaCardKey(item)]
      return size?.cover === item.coverImage ? { ...item, mediaWidth: size.mediaWidth, mediaHeight: size.mediaHeight } : item
    }), columnCount, appearance === 'home-v4' ? HOME_CASE_GUTTER : undefined),
    [appearance, columnCount, items, measurements]
  )

  const themed = appearance === 'home-v4'
  return (
    <div ref={containerRef} className={`case-plaza-feed${themed ? ' home-case-feed' : ''}${feedback ? ' is-feedback' : ''}`} aria-busy={loadingFirst || loadingMore}
      style={themed ? { '--home-case-gutter': `${HOME_CASE_GUTTER}px` } as CSSProperties : undefined}>
      {loadingFirst ? <CasePlazaPending />
        : status === 'errorFirst' ? <CasePlazaFeedback error={error || '案例列表加载失败'} onRetry={onReload} />
        : !items.length ? <CasePlazaFeedback /> : <>
      <div className="case-plaza-masonry case-plaza-feed__results" role="list">
        {columns.map((columnItems, columnIndex) => (
          <div key={columnIndex} className="case-plaza-masonry__column">
            {columnItems.map((item) => (
              <div key={casePlazaCardKey(item)} className="case-plaza-masonry__item" role="listitem">
                <CasePlazaCard item={item} onOpen={onOpen} onMeasure={measure} appearance={appearance} />
              </div>
            ))}
          </div>
        ))}
      </div>
      <div ref={sentinelRef} className="h-px w-full" aria-hidden="true" />

      {loadingMore ? (
        <div className="case-plaza-feed__footer" role="status">
          <span className="case-plaza-feed__pulse" aria-hidden="true" />
          正在加载更多
        </div>
      ) : null}

      {status === 'errorMore' ? (
        <div className="case-plaza-feed__footer" role="alert">
          <span>{error || '加载更多失败'}</span>
          <button
            type="button"
            className="case-plaza-feed__retry"
            onClick={onLoadMore}
          >
            重试
          </button>
        </div>
      ) : null}

      {!hasMore && status === 'ready' ? (
        <p className="case-plaza-feed__footer">已经到底了</p>
      ) : null}
      </>}
    </div>
  )
}

function CasePlazaFeedback({ error, onRetry }: { error?: string; onRetry?: () => void }) {
  return <div className="case-plaza-feedback" role={error ? 'alert' : 'status'}>
    <svg width="64" height="56" viewBox="0 0 64 56" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
      <rect x="6" y="9" width="34" height="34" rx="7" transform="rotate(-9 6 9)" opacity=".3" />
      <rect x="23" y="8" width="34" height="42" rx="7" /><path d="m33 21 13 8-13 8z" strokeLinejoin="round" />
    </svg>
    <strong>{error ? '作品暂时无法加载' : '暂无符合条件的案例'}</strong>
    <p>{error || '试试其他分类，或换个关键词探索更多作品'}</p>
    {error && onRetry ? <button type="button" className="case-plaza-feed__retry" onClick={onRetry}>
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true"><path d="M20 7v5h-5M20 12a8 8 0 1 0-2 5" /></svg>重新加载
    </button> : null}
  </div>
}
