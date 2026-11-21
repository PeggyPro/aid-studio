'use client'

import type { ReactNode } from 'react'
import { useRouter } from 'next/navigation'
import { HtmlShellClass } from '@/components/app/HtmlShellClass'
import { assetUrl } from '@/utils/assetUrl'
import backIcon from '@/assets/img/icon/icon-return.svg'
import avatarIcon from '@/assets/img/home/Group-avtor.svg'
import '@/assets/css/case-detail-page.css'
import { CaseMediaLoading } from './CaseMediaLoading'

interface CaseDetailLayoutProps {
  title: string
  author?: string
  avatar?: string | null
  category?: string
  description?: string
  cover?: string
  loading?: boolean
  error?: string
  onRetry?: () => void
  backHref?: string
  media: ReactNode
  mediaFooter?: ReactNode
  information?: ReactNode
  actions?: ReactNode
  errorActions?: ReactNode
}

/** 案例详情的同一套页面骨架；不同内容只提供媒体、信息和服务端准许的操作。 */
export function CaseDetailLayout({ title, author = '创作者', avatar, category, description,
  cover, loading, error, onRetry, backHref = '/', media, mediaFooter, information, actions, errorActions }: CaseDetailLayoutProps) {
  const router = useRouter()
  const back = () => router.replace(backHref)
  const backButton = <button type="button" className="case-detail-back-btn" aria-label="返回案例广场" onClick={back}>
    <img src={assetUrl(backIcon)} alt="" width={36} height={36} />
  </button>
  return <div className="case-detail-root">
    <HtmlShellClass classes="case-detail-html" />
    <div className="case-detail-bg" aria-hidden="true">
      {cover ? <img src={cover} alt="" className="case-detail-bg-img" /> : null}
      <div className="case-detail-bg-glow" />
    </div>
    {loading ? <div className="case-detail-page case-detail-page--loading" aria-busy="true">
        <section className="case-detail-main" aria-label="作品加载中">
          <header className="case-detail-nav">{backButton}<span className="case-detail-nav-label">作品详情</span></header>
          <div className="case-detail-player-area"><div className="case-detail-video-wrap"><CaseMediaLoading label="正在准备作品" /></div></div>
        </section>
        <aside className="case-detail-sidebar case-detail-sidebar--skeleton" aria-hidden="true"><i /><b /><span /><span /></aside>
      </div>
      : error ? <div className="case-detail-error" role="alert"><p>{error}</p>
        {onRetry ? <button type="button" className="case-detail-retry-btn" onClick={onRetry}>重新加载</button> : null}
        {backButton}
        {errorActions}
      </div> : <div className="case-detail-page">
        <section className="case-detail-main" aria-label="作品媒体">
          <header className="case-detail-nav">{backButton}<span className="case-detail-nav-label">作品详情</span></header>
          <div className={mediaFooter ? 'case-detail-player-area case-detail-player-area--with-footer' : 'case-detail-player-area'} data-onboarding="case-video-player">{media}{mediaFooter}</div>
        </section>
        <aside className="case-detail-sidebar" aria-label="作品信息" data-onboarding="case-info-panel">
          <div className="case-detail-user">
            <img src={avatar || assetUrl(avatarIcon)} alt="" className="case-detail-user-avatar" width={36} height={36} />
            <span className="case-detail-user-name">{author}</span>
          </div>
          <h1 className="case-detail-title">{title}</h1>
          {category ? <p className="case-detail-type">{category}</p> : null}
          <p className="case-detail-desc">{description || '作者暂未填写作品介绍'}</p>
          {information}
          {actions ? <div className="case-detail-project-actions" aria-label="作品操作">{actions}</div> : null}
        </aside>
      </div>}
  </div>
}

export function CaseDetailLoading() {
  return <CaseDetailLayout title="正在加载作品" loading media={null} />
}
