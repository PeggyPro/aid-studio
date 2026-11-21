import type { CanvasPublicationCard } from '@/types/canvas-api'

export function canvasPublicationHref(publicationId: string, options: { process?: boolean; history?: boolean } = {}) {
  const query = new URLSearchParams({ publicationId })
  if (options.history) query.set('mode', 'history')
  if (options.process) {
    query.set('studioMode', 'free')
    return `/create/studio?${query}`
  }
  if (options.history) query.set('view', 'media')
  return `/canvas/work?${query}`
}

/** 已审核的历史版本和允许复制均不能替代当前公开授权。 */
export function canViewCanvasPublicationProcess(detail: Pick<CanvasPublicationCard, 'isPublic' | 'visibility'> | null) {
  return detail?.isPublic === true && detail.visibility === 'PUBLIC'
}
