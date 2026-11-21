'use client'

/* eslint-disable @next/next/no-img-element -- 发现媒体来自运行时 OSS 地址。 */

import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useParams, useSearchParams } from 'next/navigation'
import { CaseDetailLayout, CaseDetailLoading } from '@/components/case/CaseDetailLayout'
import { CaseDetailMedia } from '@/components/case/CaseDetailMedia'
import type { PublicDiscoveryItem } from '~/types/business-api'
import { publicDiscoveryDetail } from '~/utils/businessApi'
import { resolveCasePlazaAspectRatio } from '~/utils/casePlazaAspectRatio'
import { resolveDiscoveryDetailMediaUrl } from '~/utils/discoveryMedia'
import { setPageSeo } from '~/utils/seoHead'
function DiscoveryDetailContent() {
  const params = useParams<{ id?: string }>()
  const searchParams = useSearchParams()
  const requestGenerationRef = useRef(0)
  const [request, setRequest] = useState<{
    id: number
    detail: PublicDiscoveryItem | null
    error: string
  }>({ id: 0, detail: null, error: '' })

  const discoveryId = useMemo(() => {
    const raw = params?.id ?? searchParams.get('id')
    const parsed = Number(Array.isArray(raw) ? raw[0] : raw)
    return Number.isInteger(parsed) && parsed > 0 ? parsed : 0
  }, [params?.id, searchParams])

  const loadDetail = useCallback(async () => {
    if (!discoveryId) return
    const generation = ++requestGenerationRef.current
    setRequest({ id: 0, detail: null, error: '' })
    try {
      const detail = await publicDiscoveryDetail({ id: discoveryId })
      if (generation !== requestGenerationRef.current) return
      setRequest({ id: discoveryId, detail, error: '' })
    } catch (error) {
      if (generation !== requestGenerationRef.current) return
      const value = error as { msg?: string; message?: string }
      setRequest({
        id: discoveryId,
        detail: null,
        error: String(value?.msg || value?.message || '发现作品加载失败，请稍后重试')
      })
    }
  }, [discoveryId])

  useEffect(() => {
    const timer = window.setTimeout(() => void loadDetail(), 0)
    return () => {
      window.clearTimeout(timer)
      requestGenerationRef.current += 1
    }
  }, [loadDetail])

  const detail = request.id === discoveryId ? request.detail : null
  const loading = discoveryId > 0 && request.id !== discoveryId
  const error = discoveryId === 0 ? '无效的发现作品 ID' : request.id === discoveryId ? request.error : ''
  const ratio = resolveCasePlazaAspectRatio(detail ?? {})
  const playableVideoUrl = String(detail?.mediaUrl || '').trim()
  const activeMediaUrl = resolveDiscoveryDetailMediaUrl(detail)
  const activeCoverUrl = String(detail?.coverUrl || '').trim()
  const publisherName =
    String(detail?.publisherName || '').trim() ||
    (String(detail?.sourceType || '').toUpperCase() === 'ADMIN' ? 'AID精选' : '用户')
  const publisherAvatar = String(detail?.publisherAvatar || '').trim()

  useEffect(() => {
    return setPageSeo(`discovery:${discoveryId || 'invalid'}`, {
      title: detail?.name ? `${detail.name} - 发现` : '发现作品',
      description: detail?.description || '公开内容预览',
      keywords: [detail?.name, 'AI创作'].filter(Boolean).join(','),
      canonicalUrl: discoveryId ? `${window.location.origin}/discovery/${discoveryId}` : undefined,
      imageUrl: detail?.coverUrl || undefined,
      robots: detail && !error ? 'index,follow' : 'noindex,nofollow'
    })
  }, [detail, error, discoveryId])

  return <CaseDetailLayout
    title={detail?.name || '发现作品'}
    author={publisherName}
    avatar={publisherAvatar}
    category={detail ? [detail.mediaType === 'video' ? '视频' : '图片', detail.publishTime].filter(Boolean).join(' · ') : undefined}
    description={detail?.description}
    cover={activeCoverUrl}
    loading={loading}
    error={error}
    onRetry={discoveryId > 0 ? () => void loadDetail() : undefined}
    media={<CaseDetailMedia title={detail?.name || '发现作品'} aspectRatio={ratio.ratio}
      videoUrl={detail?.mediaType === 'video' ? playableVideoUrl : undefined}
      imageUrl={detail?.mediaType === 'image' ? activeMediaUrl || activeCoverUrl : activeCoverUrl} />}
  />
}

export default function DiscoveryDetailPage() {
  return (
    <Suspense fallback={<CaseDetailLoading />}>
      <DiscoveryDetailContent />
    </Suspense>
  )
}
