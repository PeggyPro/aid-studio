import type { StudioMediaKind } from '@/types/studio'

const FALLBACK_COST: Record<StudioMediaKind, number> = {
  image: 6,
  video: 8,
  audio: 4,
  text: 2
}

export function estimateStudioCredits(input: {
  costCredits?: number | null
  count?: number
  durationSeconds?: number
  mediaKind?: StudioMediaKind
}): number {
  const mediaKind = input.mediaKind ?? 'image'
  const base = Math.max(0, input.costCredits ?? FALLBACK_COST[mediaKind])
  const count = Math.max(1, Math.floor(input.count ?? 1))
  const duration = mediaKind === 'video' ? Math.max(1, input.durationSeconds ?? 1) : 1
  return Math.round(base * count * duration * 10_000) / 10_000
}

export function studioCreditPresentation(
  credits: number,
  options?: { isFree?: boolean; authoritative?: boolean }
): { visible: string; ariaLabel: string } {
  if (options?.isFree) {
    return {
      visible: '免费',
      ariaLabel: '当前模型免费'
    }
  }
  const amount = new Intl.NumberFormat('zh-CN', {
    useGrouping: false,
    maximumFractionDigits: 4
  }).format(Math.max(0, credits))
  if (options?.authoritative) {
    return {
      visible: String(amount),
      ariaLabel: `服务端报价 ${amount} 积分`
    }
  }
  return {
    visible: `约 ${amount}`,
    ariaLabel: `本地预计约 ${amount} 积分，以提交前服务端报价为准`
  }
}
