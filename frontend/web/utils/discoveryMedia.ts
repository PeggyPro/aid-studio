import type { PublicDiscoveryItem } from '~/types/business-api'

export function resolveDiscoveryCardCover(item: PublicDiscoveryItem): string {
  const coverUrl = String(item.coverUrl || '').trim()
  if (coverUrl) return coverUrl
  return item.mediaType === 'image' ? String(item.mediaUrl || '').trim() : ''
}

/** 详情主媒体缺失时必须回退封面，兼容历史图片记录 mediaUrl=null。 */
export function resolveDiscoveryDetailMediaUrl(item: PublicDiscoveryItem | null): string {
  return String(item?.mediaUrl || item?.coverUrl || '').trim()
}
