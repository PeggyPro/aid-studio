'use client'

import { ArrowRightOutlined, CloseOutlined, GiftOutlined } from '@ant-design/icons'
import { createPortal } from 'react-dom'
import { getAidPortalRoot } from '~/utils/portalRoot'
import './InvitePromoPanel.css'

interface InvitePromoPanelProps {
  rebateRatioText: string
  onClose: () => void
  onInvite: () => void
}

export default function InvitePromoPanel({ rebateRatioText, onClose, onInvite }: InvitePromoPanelProps) {
  const portalRoot = getAidPortalRoot()
  if (!portalRoot) return null

  return createPortal(
    <aside className="invite-promo-panel" aria-label="邀请有礼推广">
      <button type="button" className="invite-promo-panel__close" aria-label="关闭邀请卡片" onClick={onClose}>
        <CloseOutlined aria-hidden />
      </button>
      <span className="invite-promo-panel__icon"><GiftOutlined aria-hidden /></span>
      <h3>邀请有礼</h3>
      <p>好友通过你的邀请注册并充值，你可获得 <strong>{rebateRatioText}%</strong> 的积分返利。</p>
      <button type="button" className="invite-promo-panel__cta" onClick={onInvite}>
        立即邀请 <ArrowRightOutlined aria-hidden />
      </button>
    </aside>,
    portalRoot
  )
}
