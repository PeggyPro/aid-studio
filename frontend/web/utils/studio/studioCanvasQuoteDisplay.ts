import type { BillingQuoteVO } from '@/types/business-api'

/** 画布报价不把未命中规则或小于 1 的金额误显示为免费。 */
export function studioCanvasQuoteCredits(quote: BillingQuoteVO | null | undefined): number | null {
  if (!quote || quote.matched === false) return null
  if (quote.isFree) return 0
  const amount = quote.preHoldAmount ?? quote.amount
  if (amount == null || !Number.isFinite(Number(amount))) return null
  return Math.round(Number(amount) * 10_000) / 10_000
}
