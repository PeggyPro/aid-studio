'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { useParams, useRouter, useSearchParams } from 'next/navigation'
import { Suspense } from 'react'
import { message } from 'antd'
import { CaseDetailLayout, CaseDetailLoading } from '@/components/case/CaseDetailLayout'
import { CaseDetailMedia } from '@/components/case/CaseDetailMedia'
import { emptyImageIconUrl } from '~/utils/emptyImageIcon'
import {
  createProjectCopyRequestId,
  publicProjectDetail,
  userProjectSnapshotCopy
} from '~/utils/businessApi'
import { buildCopiedProjectPreviewHref } from '~/utils/publicProjectPreviewNavigation'
import { openLoginModal } from '@/stores/loginModal'
import { useUserStore } from '~/stores/user'
import type { PublicProjectDetailRow, PublicProjectEpisodeItem } from '~/types/business-api'
import { resolvePublicProjectAccess } from '~/utils/publicProjectAccess'
import { setPageSeo } from '~/utils/seoHead'
import './case-detail-episodes.css'


interface ProtagonistItem {
  name?: string
  imageUrl?: string
}

function CaseDetailContent() {
  const params = useParams<{ id: string }>()
  const router = useRouter()
  const searchParams = useSearchParams()

  const [detailRequest, setDetailRequest] = useState<{
    projectId: number
    detail: PublicProjectDetailRow | null
    errorMessage: string
  }>({ projectId: 0, detail: null, errorMessage: '' })
  const [activeEpisodeId, setActiveEpisodeId] = useState<number | null>(null)
  const [copyingProjectId, setCopyingProjectId] = useState<number | null>(null)
  const detailLoadGenRef = useRef(0)
  const copyInFlightRef = useRef(false)
  const copyGenerationRef = useRef(0)
  const copyRequestRef = useRef<{ projectId: number; requestId: string } | null>(null)

  const projectId = useMemo(() => {
    const raw = params?.id ?? searchParams.get('id')
    const value = Array.isArray(raw) ? raw[0] : raw
    const parsed = Number(value)
    return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : 0
  }, [params?.id, searchParams])

  const requestMatchesRoute = detailRequest.projectId === projectId
  const loading = projectId > 0 && !requestMatchesRoute
  const errorMessage = !projectId
    ? '无效的案例 ID'
    : requestMatchesRoute
      ? detailRequest.errorMessage
      : ''
  const detail = requestMatchesRoute ? detailRequest.detail : null

  const projectName = detail?.projectName || '影片名称'
  const episodes = useMemo<PublicProjectEpisodeItem[]>(
    () => (Array.isArray(detail?.episodes) ? detail.episodes : []),
    [detail]
  )

  const activeEpisode = useMemo(() => {
    if (!episodes.length) return null
    const hit = episodes.find((ep) => ep.episodeId === activeEpisodeId)
    return hit || episodes[0] || null
  }, [episodes, activeEpisodeId])

  const activeCoverUrl = (() => {
    const epCover = String(activeEpisode?.coverUrl || '').trim()
    if (epCover) return epCover
    return detail?.coverUrl || ''
  })()

  const activeVideoUrl = (() => {
    const epVideo = String(activeEpisode?.videoUrl || '').trim()
    if (epVideo) return epVideo
    return detail?.finalVideoUrl?.trim() || detail?.previewVideoUrl?.trim() || ''
  })()

  const videoTypeLabel = detail?.videoStyleType || '影片的类型'
  const authorName = String(detail?.authorNickname || '').trim() || '用户'
  const descriptionText = detail?.projectDesc?.trim() || '剧情介绍...'

  const protagonistItems = useMemo<ProtagonistItem[]>(() => {
    const row = detail as
      | (PublicProjectDetailRow & {
          characterList?: ProtagonistItem[]
          protagonists?: ProtagonistItem[]
          roleList?: ProtagonistItem[]
        })
      | null
    const list = row?.characterList || row?.protagonists || row?.roleList
    if (Array.isArray(list) && list.length) {
      return list.slice(0, 8)
    }
    return []
  }, [detail])

  const showProtagonists = protagonistItems.length > 0
  const projectAccess = resolvePublicProjectAccess(detail)
  const canCopyProject = projectAccess.canCopy
  const copyingProject = copyingProjectId === projectId

  async function copyProject() {
    if (!canCopyProject || copyInFlightRef.current || !projectId) return
    const token = useUserStore.getState().token || (
      typeof window !== 'undefined' ? localStorage.getItem('token') || '' : ''
    )
    if (!token) {
      const redirect = typeof window !== 'undefined'
        ? `${window.location.pathname}${window.location.search}`
        : `/case/${projectId}`
      openLoginModal({ redirect })
      return
    }

    copyInFlightRef.current = true
    const actionGeneration = copyGenerationRef.current
    setCopyingProjectId(projectId)
    const operation = copyRequestRef.current?.projectId === projectId
      ? copyRequestRef.current
      : { projectId, requestId: createProjectCopyRequestId() }
    copyRequestRef.current = operation
    try {
      const copied = await userProjectSnapshotCopy({
        id: projectId,
        requestId: operation.requestId
      })
      if (actionGeneration !== copyGenerationRef.current) return
      copyRequestRef.current = null
      message.success(copied.reused ? '工程已复制，正在打开' : '复制工程成功')
      router.push(buildCopiedProjectPreviewHref(copied.projectId))
    } catch (error) {
      if (actionGeneration !== copyGenerationRef.current) return
      const detail = error as { msg?: string; message?: string; code?: string }
      if (detail?.code !== 'ERR_CANCELED') {
        message.error(detail?.msg || detail?.message || '复制工程失败，请重试')
      }
    } finally {
      if (actionGeneration === copyGenerationRef.current) {
        copyInFlightRef.current = false
        setCopyingProjectId(null)
      }
    }
  }

  // 路由 id 变化：重新加载详情并自动播放（对齐原 watch immediate）
  useEffect(() => {
    const gen = ++detailLoadGenRef.current
    copyGenerationRef.current += 1
    copyInFlightRef.current = false
    if (!projectId) return

    void publicProjectDetail(projectId)
      .then((row) => {
        if (gen !== detailLoadGenRef.current) return
        setDetailRequest({ projectId, detail: row, errorMessage: '' })
        const firstEp = Array.isArray(row.episodes) ? row.episodes[0] : null
        setActiveEpisodeId(firstEp?.episodeId ?? null)
      })
      .catch(() => {
        if (gen !== detailLoadGenRef.current) return
        setDetailRequest({
          projectId,
          detail: null,
          errorMessage: '加载案例详情失败，请稍后重试'
        })
        setActiveEpisodeId(null)
      })
    return () => {
      detailLoadGenRef.current += 1
      copyGenerationRef.current += 1
    }
  }, [projectId])

  // 页面级元信息拥有独立优先级，异步站点配置不能覆盖作品信息。
  useEffect(() => {
    return setPageSeo(`case:${projectId || 'invalid'}`, {
      title: detail?.projectName ? `${detail.projectName} - 案例详情` : '案例详情',
      description: detail?.projectDesc || '公开作品预览',
      keywords: [detail?.projectName, detail?.videoStyleType, 'AI视频作品'].filter(Boolean).join(','),
      canonicalUrl: projectId ? `${window.location.origin}/case/${projectId}` : undefined,
      imageUrl: detail?.coverUrl || undefined,
      robots: detail && !errorMessage ? 'index,follow' : 'noindex,nofollow'
    })
  }, [detail, errorMessage, projectId])

  return (
    <CaseDetailLayout
      title={projectName}
      author={authorName}
      category={videoTypeLabel}
      description={descriptionText}
      cover={activeCoverUrl}
      loading={loading}
      error={errorMessage}
      backHref={searchParams.get('from') === 'legacy' ? '/index-legacy' : '/'}
      media={<CaseDetailMedia key={activeEpisode?.episodeId ?? projectId} videoUrl={activeVideoUrl} imageUrl={activeCoverUrl} title={projectName} />}
      mediaFooter={episodes.length > 1 ? (
        <div className="case-detail-episodes" aria-label="剧集列表">
          {episodes.map((ep) => (
            <button
              key={ep.episodeId}
              type="button"
              className={`case-detail-episode-btn${activeEpisode?.episodeId === ep.episodeId ? ' is-active' : ''}`}
              aria-pressed={activeEpisode?.episodeId === ep.episodeId}
              onClick={() => setActiveEpisodeId(ep.episodeId)}
            >
              {ep.coverUrl ? (
                <img
                  src={ep.coverUrl}
                  alt={ep.title || `第${ep.episodeNo}集`}
                  className="case-detail-episode-cover"
                />
              ) : null}
              <span className="case-detail-episode-title">
                {ep.title || `第${ep.episodeNo}集`}
              </span>
            </button>
          ))}
        </div>
      ) : null}
      information={showProtagonists ? (
      <section aria-labelledby="case-protagonists-title">
        <h2 id="case-protagonists-title" className="case-detail-section-title">
          故事主角
        </h2>
        <div className="case-detail-protagonists">
          {protagonistItems.map((item, index) => (
            <div
              key={`${item.imageUrl}-${index}`}
              className={`case-detail-protagonist${item.imageUrl ? '' : ' case-detail-protagonist--empty'}`}
            >
              {item.imageUrl ? (
                <img src={item.imageUrl} alt={item.name || '故事主角'} loading="lazy" />
              ) : (
                <img
                  src={emptyImageIconUrl}
                  alt=""
                  className="empty-image-icon empty-image-icon--md case-detail-protagonist__empty-icon"
                />
              )}
            </div>
          ))}
        </div>
      </section>
    ) : null}
      actions={detail ? (
        <button
          type="button"
          className={`case-detail-project-action${canCopyProject ? '' : ' is-unavailable'}`}
          disabled={!canCopyProject || copyingProject}
          title={projectAccess.copyReasonText || (projectAccess.copyRequiresLogin ? '登录后复制工程' : undefined)}
          onClick={() => void copyProject()}
        >
          {copyingProject ? '复制中…' : canCopyProject ? '复制工程' : projectAccess.copyReasonText}
        </button>
      ) : null}
    />
  )
}

/** 案例详情全屏播放页（无壳） */
export default function CaseDetailPage() {
  return (
    <Suspense fallback={<CaseDetailLoading />}>
      <CaseDetailContent />
    </Suspense>
  )
}
