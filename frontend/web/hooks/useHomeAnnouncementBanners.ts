'use client'

import { useEffect, useState } from 'react'
import type { HomeBannerVO } from '~/types/business-api'
import { userHomeBannerList } from '~/utils/businessApi'
import {
  findLatestHomeAnnouncement,
  mapHomeBannersToAnnouncements,
  type HomeAnnouncementItem
} from '~/utils/homeAnnouncementBanners'

const PAGE_SIZE = 50

export function useHomeAnnouncementBanners() {
  const [items, setItems] = useState<HomeAnnouncementItem[]>([])

  useEffect(() => {
    let cancelled = false
    async function load() {
      const first = await userHomeBannerList({ pageNum: 1, pageSize: PAGE_SIZE })
      const rows: HomeBannerVO[] = [...first.rows]
      for (let pageNum = 2; !cancelled && pageNum <= Math.ceil(first.total / PAGE_SIZE); pageNum += 1) {
        const page = await userHomeBannerList({ pageNum, pageSize: PAGE_SIZE })
        rows.push(...page.rows)
        if (!page.hasMore || !page.rows.length) break
      }
      if (cancelled) return
      setItems(mapHomeBannersToAnnouncements(rows))
    }
    void load().catch(() => { /* An unavailable feed leaves the announcement dialog empty. */ })
    return () => { cancelled = true }
  }, [])

  return { latest: findLatestHomeAnnouncement(items) }
}
