/** 缩略图悬停大图：按真实比例放入上限框，完整可见不裁切。 */

export type StudioThumbPreviewLimits = {
  maxWidth: number
  maxHeight: number
}

/** 对齐主流画布 Composer 悬停预览体量：等比完整可见，但保持紧凑。 */
export const STUDIO_THUMB_PREVIEW_LIMITS: StudioThumbPreviewLimits = {
  maxWidth: 240,
  maxHeight: 180
}

/**
 * 将自然宽高缩放到不超过 maxWidth × maxHeight，保持比例；
 * 按素材比例填入上限框；缩略素材也会适度放大，悬停预览始终清晰可辨。
 */
export function fitStudioThumbPreviewSize(
  naturalWidth: number,
  naturalHeight: number,
  limits: StudioThumbPreviewLimits = STUDIO_THUMB_PREVIEW_LIMITS
): { width: number; height: number } {
  const nw = Math.max(1, naturalWidth)
  const nh = Math.max(1, naturalHeight)
  const scale = Math.min(limits.maxWidth / nw, limits.maxHeight / nh)
  return {
    width: Math.max(1, Math.round(nw * scale)),
    height: Math.max(1, Math.round(nh * scale))
  }
}

/** 将预览框锚在缩略图上方，并夹入视口。 */
export function placeStudioThumbPreview(
  thumbRect: Pick<DOMRect, 'left' | 'top' | 'bottom'>,
  size: { width: number; height: number },
  viewport: { width: number; height: number } = {
    width: typeof window !== 'undefined' ? window.innerWidth : 1280,
    height: typeof window !== 'undefined' ? window.innerHeight : 720
  },
  gap = 8
): { top: number; left: number } {
  const left = Math.min(
    Math.max(gap, thumbRect.left),
    Math.max(gap, viewport.width - size.width - gap)
  )
  const above = thumbRect.top - size.height - gap
  const top = above >= gap
    ? above
    : Math.min(viewport.height - size.height - gap, thumbRect.bottom + gap)
  return {
    top: Math.max(gap, top),
    left
  }
}
