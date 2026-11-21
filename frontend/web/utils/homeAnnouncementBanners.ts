import type { HomeBannerVO } from '~/types/business-api'

export interface HomeAnnouncementItem {
  id: string
  title: string
  summary: string
  media: { type: 'image' | 'gif' | 'video'; url: string; cover: string } | null
  link: { href: string; external: boolean } | null
}

/** The existing banner contract has no announcement category. Only the canvas launch gets the built-in tour. */
export function isHomeCanvasLaunchAnnouncement(item: HomeAnnouncementItem): boolean {
  return /无限画布.*预演台/.test(item.title) && /上线|开源/.test(item.title)
}

function webUrl(value?: string | null, allowRelative = false) {
  const url = value?.trim() ?? ''
  try {
    const base = 'https://aid.invalid'
    if (allowRelative && url.startsWith('/') && !url.startsWith('//')) {
      const parsed = new URL(url, base)
      if (parsed.origin !== base || parsed.pathname.startsWith('//')) return ''
      return parsed.pathname + parsed.search + parsed.hash
    }
    const parsed = new URL(url)
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.href : ''
  } catch {
    return ''
  }
}

export function mapHomeBannersToAnnouncements(
  banners: readonly HomeBannerVO[]
): HomeAnnouncementItem[] {
  const items = new Map<string, HomeAnnouncementItem>()
  for (const banner of banners) {
    const id = String(banner.id)
    const title = banner.title?.trim()
    const summary = banner.summary?.trim() ?? ''
    const { bannerType: type, linkType } = banner
    const url = webUrl(banner.resourceUrl, true)
    const cover = webUrl(banner.coverUrl, true)
    const media = url && (type === 'image' || type === 'gif' || type === 'video')
      ? { type, url, cover } as const
      : cover ? { type: 'image', url: cover, cover: '' } as const : null
    const external = linkType === 'external'
    const href = external ? webUrl(banner.linkUrl) : linkType === 'internal'
      ? webUrl(banner.linkUrl, true)
      : ''
    const link = href && (external || href.startsWith('/')) ? { href, external } : null
    if (!/^\d+$/.test(id) || items.has(id) || (!title && !summary && !media)) continue
    items.set(id, { id, title: title || '首页公告', summary, media, link })
  }
  return Array.from(items.values())
}

/** 公告接口没有发布时间。有效公告中按递增的主键识别最近录入的一条。 */
export function findLatestHomeAnnouncement(items: readonly HomeAnnouncementItem[]) {
  return items.reduce<HomeAnnouncementItem | null>((latest, item) =>
    !latest || BigInt(item.id) > BigInt(latest.id) ? item : latest, null)
}
