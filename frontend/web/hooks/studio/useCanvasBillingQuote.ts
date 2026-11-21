'use client'

import type { BillingQuoteRequest } from '@/types/business-api'
import { canvasUserBillingQuote } from '@/utils/business/billing'
import { useCanvasQuote } from './useCanvasQuote'

export function useCanvasBillingQuote(request: BillingQuoteRequest | null) {
  return useCanvasQuote('billing', request, canvasUserBillingQuote)
}
