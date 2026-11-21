import type { CSSProperties } from 'react'

/** V10 的浮层位移用 .3s spring，卸载须等最长的过渡结束。其他主题沿用原时长。 */
export function flowPopoverDuration(fallback: number): number {
  if (typeof document === 'undefined' || document.documentElement.dataset.aidUi !== 'v10') return fallback
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 0 : 300
}

/** 用实际显示顺序提供延迟，分页、排序及第五条以后的条目也遵循同一入场规则。 */
export function flowRowMotionStyle(index: number): CSSProperties {
  return { '--v10-row-index': Math.max(0, Math.floor(index)) } as CSSProperties
}
