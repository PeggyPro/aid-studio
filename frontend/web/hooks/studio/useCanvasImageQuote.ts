'use client'

import type { UserImageGenerationRequest } from '@/types/generation-api'
import { userGenerationImageQuote } from '@/utils/business/generation'
import { useCanvasQuote } from './useCanvasQuote'

/** 预览与显式提交前复核共享 API 单飞，报价自身不提交生成。 */
export function requestCanvasImageQuote(request: UserImageGenerationRequest) {
  return userGenerationImageQuote(request)
}

export function useCanvasImageQuote(request: UserImageGenerationRequest | null) {
  return useCanvasQuote('image', request, userGenerationImageQuote)
}
