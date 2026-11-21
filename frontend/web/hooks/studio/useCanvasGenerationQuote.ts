'use client'

import type { UserGenerationKind, UserImageGenerationRequest, UserTextGenerationRequest, UserVideoGenerationRequest } from '@/types/generation-api'
import { userGenerationQuote } from '@/utils/business/generation'
import { useCanvasQuote } from './useCanvasQuote'

export type CanvasGenerationQuoteInput = {
  kind: UserGenerationKind
  request: UserTextGenerationRequest | UserImageGenerationRequest | UserVideoGenerationRequest
}

export function useCanvasGenerationQuote(input: CanvasGenerationQuoteInput | null, blockedReason = '') {
  const state = useCanvasQuote('generation', input, (value, signal) => userGenerationQuote(value.kind, value.request, signal), blockedReason)
  return blockedReason ? { ...state, quote: null, loading: false, error: blockedReason, status: 'BLOCKED' as const } : state
}
