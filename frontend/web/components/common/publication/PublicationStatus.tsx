'use client'

import { CheckCircleOutlined, CloseCircleOutlined, ClockCircleOutlined, MinusCircleOutlined } from '@ant-design/icons'
import './publication-feedback.css'

export interface PublicationReviewSummary {
  reviewStatus?: string
  reviewReason?: string
  visibility?: string
  isPublic: boolean
}

export function publicationTone(status?: string, offline = false) {
  return offline ? 'warning' : status === 'REJECTED' ? 'danger' : status === 'APPROVED' ? 'success' : status === 'PENDING' ? 'info' : 'neutral'
}

export function PublicationBadge({ label, status, offline = false }: { label: string; status?: string; offline?: boolean }) {
  const tone = publicationTone(status, offline)
  const Icon = tone === 'success' ? CheckCircleOutlined : tone === 'danger' ? CloseCircleOutlined : tone === 'info' ? ClockCircleOutlined : MinusCircleOutlined
  return <span className={`studio-publication-badge is-${tone}`}><Icon aria-hidden /><span>{label}</span></span>
}

/** The caller supplies the authoritative current-state label; past approval does not imply public visibility. */
export function PublicationSummary({ state, label }: { state: PublicationReviewSummary; label: string }) {
  return <div className={`studio-publication-summary is-${publicationTone(state.reviewStatus, state.visibility === 'ADMIN_OFFLINE')}`} role="status">
    <PublicationBadge label={label} status={state.reviewStatus} offline={state.visibility === 'ADMIN_OFFLINE'} />
    {state.reviewStatus === 'REJECTED' && state.reviewReason ? <p>{state.reviewReason}</p> : null}
    {state.isPublic && state.reviewStatus === 'REJECTED' ? <small>本次申请未通过，上一版本仍公开展示。</small> : null}
  </div>
}
