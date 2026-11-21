'use client'

import { LoadingOutlined } from '@ant-design/icons'
import starlightCoinRaw from '~/assets/img/home/starlightCoin.svg'
import type { BillingQuoteVO } from '~/types/business-api'
import { assetUrl } from '~/utils/assetUrl'
import { billingQuoteAmountForDisplay, billingQuoteTextForDisplay } from '~/utils/billingQuoteDisplay'
import './BillingQuoteHint.css'

const starlightCoinUrl = assetUrl(starlightCoinRaw)

interface Props {
  quote?: BillingQuoteVO | null
  loading?: boolean
  error?: string
  className?: string
  /** 未形成完整报价请求时不显示占位。 */
  active?: boolean
  /** 已选模型但业务必填参数未齐时的简短提示。 */
  idleText?: string
}

/** 提交区附近的只读权威报价提示；不控制、也不禁用提交按钮。 */
export function BillingQuoteHint({
  quote,
  loading = false,
  error = '',
  className = '',
  active = true,
  idleText = ''
}: Props) {
  if (!active) return null
  const classes = ['billing-quote-hint', className].filter(Boolean).join(' ')
  const serverText = billingQuoteTextForDisplay(quote)
  const credits = billingQuoteAmountForDisplay(quote)

  if (loading) {
    return (
      <div className={`${classes} is-loading`} role="status" aria-live="polite">
        <LoadingOutlined spin aria-hidden="true" />
        <span>估价中…</span>
      </div>
    )
  }

  if (quote?.isFree) {
    return (
      <div
        className={`${classes} is-free`}
        role="status"
        aria-live="polite"
        title={serverText || '免费'}
      >
        <img className="billing-quote-hint__coin" src={starlightCoinUrl} alt="" aria-hidden="true" />
        <span>免费</span>
      </div>
    )
  }

  if (credits != null) {
    return (
      <div
        className={`${classes}${quote?.estimated ? ' is-estimated' : ''}`}
        role="status"
        aria-live="polite"
        aria-label={serverText || `预估 ${credits} 积分`}
        title={serverText || quote?.skuName || undefined}
      >
        <span className="billing-quote-hint__label">预估</span>
        <img className="billing-quote-hint__coin" src={starlightCoinUrl} alt="" aria-hidden="true" />
        <span className="billing-quote-hint__value">{credits}</span>
      </div>
    )
  }

  if (serverText) {
    return (
      <div
        className={`${classes}${quote?.estimated ? ' is-estimated' : ''}`}
        role="status"
        aria-live="polite"
        title={quote?.skuName || undefined}
      >
        <img className="billing-quote-hint__coin" src={starlightCoinUrl} alt="" aria-hidden="true" />
        <span>{serverText}</span>
      </div>
    )
  }

  if (error) {
    return (
      <div className={`${classes} is-unavailable`} title={error}>
        报价暂不可用
      </div>
    )
  }

  if (idleText) {
    return <div className={`${classes} is-idle`}>{idleText}</div>
  }

  return null
}

export default BillingQuoteHint
