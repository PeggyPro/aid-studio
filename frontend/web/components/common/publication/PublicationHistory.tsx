'use client'

import { useState } from 'react'
import { Button } from 'antd'
import { PublicationBadge } from './PublicationStatus'

export interface PublicationHistoryVersion {
  publicationId: string
  projectName: string
  reviewStatus: string
  reviewReason?: string
  isPublic: boolean
  visibility?: string
}

export function PublicationHistory({ versions, loading, error, onRetry, versionHref }: {
  versions: PublicationHistoryVersion[] | null
  loading: boolean
  error: string
  onRetry: () => void
  versionHref: (id: string) => string
}) {
  const [limit, setLimit] = useState(20)
  return <section className="studio-publication-history" aria-label="发布记录" aria-busy={loading}>
    <p className="studio-publication__hint">每条记录对应提交时的固定版本，查看历史不会修改当前草稿。</p>
    {error ? <div role="alert" className="studio-publication__error">{error}<Button onClick={onRetry}>重新加载</Button></div> : null}
    {loading && !versions ? <p role="status">正在加载发布记录…</p> : null}
    {!loading && versions?.length === 0 ? <div className="studio-publication-history__empty">暂无发布记录</div> : null}
    {versions?.slice(0, limit).map(version => {
      const href = versionHref(version.publicationId)
      const internalHref = href.startsWith('/') && !href.startsWith('//') ? href : undefined
      return <article className="studio-publication-history__item" key={version.publicationId}>
        <div className="studio-publication-history__heading"><strong>{version.projectName}</strong>
          <PublicationBadge status={version.reviewStatus} label={({ PENDING: '待审核', APPROVED: '审核通过', REJECTED: '审核未通过', CANCELLED: '已取消' } as Record<string, string>)[version.reviewStatus] || '未提交'} />
        </div>
        <div className="studio-publication-history__meta"><span>版本 {version.publicationId}</span>
          <span>{version.isPublic ? '当前公开' : version.visibility === 'ADMIN_OFFLINE' ? '已下架' : version.reviewStatus === 'APPROVED' ? '历史版本 · 未公开' : '未公开'}</span>
        </div>
        {version.reviewReason ? <details className={`studio-publication-history__reason is-${version.reviewStatus === 'REJECTED' ? 'danger' : 'neutral'}`} open={version.reviewStatus === 'REJECTED'}><summary>审核说明</summary><p>{version.reviewReason}</p></details> : null}
        {internalHref ? <a className="studio-publication-history__link" href={internalHref} target="_blank" rel="noopener noreferrer">查看固定版本 ↗</a> : null}
      </article>
    })}
    {versions && versions.length > limit ? <Button block onClick={() => setLimit(value => value + 20)}>加载更多记录</Button> : null}
  </section>
}
