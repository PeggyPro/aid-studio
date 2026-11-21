'use client'

import { useEffect, useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import Link from 'next/link'
import { Button, Progress, Spin } from 'antd'
import type { CanvasPublicationDetail } from '@/types/canvas-api'
import { CaseDetailLayout } from '@/components/case/CaseDetailLayout'
import { CaseDetailMedia } from '@/components/case/CaseDetailMedia'
import { useCanvasPublicationCopy } from '@/hooks/useCanvasPublicationCopy'
import { useUserStore } from '@/stores/user'
import { openLoginModal } from '@/stores/loginModal'
import { apiErrorMessage } from '@/utils/apiError'
import { canvasPublicationDetail, canvasPublicationVersionDetail } from '@/utils/canvas/canvasPublicationApi'
import { canvasPublicationFileUrl, createCanvasResourceAccess } from '@/utils/canvas/canvasPublicationFiles'
import { canViewCanvasPublicationProcess, canvasPublicationHref } from '@/utils/canvas/canvasPublicationNavigation'
import { openCanvasProjectPage } from '@/utils/canvas/canvasProjectNavigation'
import { stableCanvasJson } from '@/utils/canvas/canvasWorkspaceAdapter'
import { setPageSeo } from '@/utils/seoHead'
import './canvas-publications.css'

function CopyPanel({
  detail,
  publicationId,
}: {
  detail: CanvasPublicationDetail | null
  publicationId: string
}) {
  const user = useUserStore((s) => s.user),
    token = useUserStore((s) => s.token)
  const copy = useCanvasPublicationCopy(publicationId)
  const own = String(user?.id || '') === detail?.ownerUserId
  const job = copy.job
  const open = () => {
    if (job?.projectId) openCanvasProjectPage(job.projectId)
  }
  return (
    <div className="canvas-publication-work__progress">
      {job?.status === 'SUCCEEDED' ? (
        <Button type="primary" onClick={open}>
          打开我的副本
        </Button>
      ) : !detail ? (
        <span>原作品已不可访问，仍可核实已有复制任务</span>
      ) : !detail.allowCopy ? (
        <span>作者未开放复制</span>
      ) : own ? (
        <span>这是你发布的画布</span>
      ) : (
        <Button
          type="primary"
          disabled={
            (!!token && !user) ||
            copy.busy ||
            job?.status === 'QUEUED' ||
            job?.status === 'COPYING'
          }
          onClick={() => {
            if (!token) openLoginModal()
            else void copy.start()
          }}
        >
          复制到我的画布
        </Button>
      )}
      {job && ['QUEUED', 'COPYING'].includes(job.status) && (
        <>
          <span>
            {job.status === 'QUEUED'
              ? '复制排队中'
              : job.cancelRequested
                ? '正在取消复制'
                : '正在复制'}{' '}
            · {job.completedResources || 0}/{job.totalResources || 0} 份素材
            {' · '}
            {job.completedBytes || '0'}/{job.totalBytes || '0'} 字节
          </span>
          <Progress
            percent={job.progress || 0}
            size="small"
            style={{ maxWidth: 220 }}
          />
          <Button
            disabled={copy.busy || job.cancelRequested}
            onClick={() => void copy.cancel()}
          >
            取消复制
          </Button>
        </>
      )}
      {job && ['FAILED', 'CANCELLED'].includes(job.status) && (
        <>
          <span role="status">
            {job.status === 'FAILED'
              ? `复制失败：${job.errorCode || '请重试'}`
              : '复制已取消'}
          </span>
          <Button disabled={copy.busy} onClick={() => void copy.retry()}>
            重试原复制任务
          </Button>
        </>
      )}
      {job?.status === 'NOT_FOUND' && (
        <Button disabled={copy.busy} onClick={() => void copy.start()}>
          核实并重试原请求
        </Button>
      )}
      {copy.error && <span role="alert">{copy.error}</span>}
    </div>
  )
}

export function CanvasPublicationPage() {
  const router = useRouter()
  const query = useSearchParams()
  const id = query.get('publicationId') || ''
  const history = query.get('mode') === 'history'
  const view = query.get('view')
  const processRequested = history ? view !== 'media' : view === 'process'
  const processHref = canvasPublicationHref(id, { history, process: true })
  useEffect(() => {
    if (processRequested) router.replace(processHref)
  }, [processRequested, processHref, router])
  const [revision, setRevision] = useState(0)
  const token = useUserStore((s) => s.token)
  const accessToken = history ? token : ''
  const validId = /^[1-9]\d*$/.test(id)
  const scope = `${history ? 'history' : 'public'}:${id}:${processRequested ? 'process' : 'media'}:${revision}`
  const [request, setRequest] = useState<{ scope: string; token: string; detail: CanvasPublicationDetail | null; error: string } | null>(null)
  const requestMatches = request?.scope === scope && request.token === accessToken
  const detail = requestMatches ? request.detail : null
  const loading = validId && !requestMatches
  const error = !validId ? '作品编号无效' : requestMatches ? request.error : ''
  const retry = () => setRevision((value) => value + 1)

  useEffect(() => {
    if (!validId || processRequested) return
    let active = true
    let inflight = false
    let timer: ReturnType<typeof setTimeout> | undefined
    let access = createCanvasResourceAccess()
    async function load() {
      if (!active || inflight) return
      inflight = true
      try {
        let value = await (history ? canvasPublicationVersionDetail(id) : canvasPublicationDetail(id))
        if (!active) return
        if (value.publicationId !== id) throw new Error('作品版本与请求不一致')
        if (history && !processRequested) {
          // 历史成片、封面需要作者认证。仅在媒体视图准备这两份文件。
          const { videoResourceId, coverResourceId, resources } = value.canvas
          const prepared = await access.prepare(resources.filter((resource) =>
            resource.resourceId === videoResourceId || resource.resourceId === coverResourceId))
          value = {
            ...value,
            videoUrl: prepared.find((resource) => resource.resourceId === videoResourceId)?.displayUrl || value.videoUrl,
            coverUrl: prepared.find((resource) => resource.resourceId === coverResourceId)?.displayUrl || value.coverUrl,
          }
        }
        if (active) setRequest((previous) => previous?.scope === scope && previous.token === accessToken &&
          stableCanvasJson(previous.detail) === stableCanvasJson(value) ? previous : { scope, token: accessToken, detail: value, error: '' })
      } catch (cause) {
        if (active) {
          access.dispose()
          access = createCanvasResourceAccess()
          setRequest({ scope, token: accessToken, detail: null, error: apiErrorMessage(cause, '作品已停止公开或不可访问') })
        }
      } finally {
        inflight = false
        if (active) timer = setTimeout(() => void load(), document.hidden ? 60000 : 15000)
      }
    }
    const resume = () => {
      if (document.hidden) return
      clearTimeout(timer)
      void load()
    }
    void load()
    window.addEventListener('focus', resume)
    document.addEventListener('visibilitychange', resume)
    return () => {
      active = false
      access.dispose()
      clearTimeout(timer)
      window.removeEventListener('focus', resume)
      document.removeEventListener('visibilitychange', resume)
    }
  }, [history, id, scope, accessToken, validId, processRequested])

  const canView = Boolean(detail?.canvas && canViewCanvasPublicationProcess(detail))
  const cover = canvasPublicationFileUrl(detail?.coverUrl || '')
  useEffect(() => setPageSeo(`canvas-work:${id}`, {
    title: detail?.projectName ? `${detail.projectName} - 画布作品` : '画布作品',
    description: detail?.projectDesc,
    imageUrl: cover || undefined,
    robots: !history && canView ? 'index,follow' : 'noindex,nofollow',
  }), [canView, cover, detail, history, id])

  if (processRequested) return <div className="studio-loading-screen" role="status"><Spin aria-label="正在打开只读画布" /></div>

  const title = detail?.projectName || '画布作品'
  return <CaseDetailLayout
    title={title}
    author="画布创作者"
    category={history ? '作者发布记录' : detail?.isDerivative ? '画布 · 衍生作品' : '画布作品'}
    description={detail?.projectDesc}
    cover={cover}
    loading={loading}
    error={error}
    onRetry={validId ? retry : undefined}
    backHref={history ? '/canvas' : '/'}
    media={<CaseDetailMedia title={title} videoUrl={canvasPublicationFileUrl(detail?.videoUrl || '')} imageUrl={cover} />}
    actions={detail && !history && canView ? <>
      <Link className="case-detail-project-action case-detail-project-action--link"
        href={canvasPublicationHref(id, { process: true })}>查看制作过程</Link>
      {detail.allowCopy || token ? <CopyPanel key={`${id}:${token}`} publicationId={id} detail={detail} /> : null}
    </> : null}
    errorActions={!history && validId && token ? <CopyPanel key={`${id}:${token}`} publicationId={id} detail={null} /> : null}
  />
}
