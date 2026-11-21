'use client'

import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react'
import type { CasePlazaCardItem } from '@/types/case-plaza'
import { apiErrorMessage } from '@/utils/apiError'
import { canvasPublicationList } from '@/utils/canvas/canvasPublicationApi'
import { canvasPublicationFileUrl } from '@/utils/canvas/canvasPublicationFiles'
import { canvasPublicationHref } from '@/utils/canvas/canvasPublicationNavigation'
import { createCasePlazaFeedState, reduceCasePlazaFeed, selectCasePlazaHasMore, selectCasePlazaFeedScope } from '@/utils/casePlazaFeedState'
import { emptyImageIconUrl } from '@/utils/emptyImageIcon'

export function useCanvasPublicationFeed(enabled: boolean, query: string) {
  const [keyword, setKeyword] = useState(query.trim())
  const [state, dispatch] = useReducer(reduceCasePlazaFeed<CasePlazaCardItem>, undefined, createCasePlazaFeedState<CasePlazaCardItem>)
  const scopeRef = useRef(0)
  const pending = useRef<string | null>(null)
  const scopeKey = useMemo(() => JSON.stringify([enabled, keyword]), [enabled, keyword])
  const visibleState = selectCasePlazaFeedScope(state, scopeKey)

  useEffect(() => {
    const timer = setTimeout(() => setKeyword(query.trim()), 250)
    return () => clearTimeout(timer)
  }, [query])

  const load = useCallback(async (pageNum: number, scope: number) => {
    const key = `${scope}:${pageNum}`
    if (pending.current === key) return
    pending.current = key
    if (pageNum > 1) dispatch({ type: 'loadMoreStarted', scopeKey })
    try {
      const page = await canvasPublicationList({ pageNum, pageSize: 24, ...(keyword ? { keyword } : {}) })
      if (scope !== scopeRef.current) return
      dispatch({ type: 'pageSucceeded', scopeKey, pageNum, total: page.total, rows: page.items.map((item) => ({
        id: item.publicationId,
        href: canvasPublicationHref(item.publicationId),
        title: item.projectName,
        coverImage: canvasPublicationFileUrl(item.coverUrl) || emptyImageIconUrl,
        previewVideoUrl: canvasPublicationFileUrl(item.videoUrl),
        authorName: '画布创作者',
        projectDesc: item.projectDesc,
        viewCount: 0,
        categoryLabel: '画布',
        sourceKind: 'canvas',
        mediaType: 'video',
        sourceType: 'CANVAS',
      })) })
    } catch (error) {
      if (scope === scopeRef.current) dispatch({ type: 'pageFailed', scopeKey, pageNum, error: apiErrorMessage(error, '画布作品加载失败') })
    } finally {
      if (pending.current === key) pending.current = null
    }
  }, [keyword, scopeKey])

  const reload = useCallback(() => {
    if (!enabled) return
    dispatch({ type: 'reset', scopeKey })
    void load(1, ++scopeRef.current)
  }, [enabled, load, scopeKey])

  useEffect(() => {
    reload()
    return () => { scopeRef.current += 1 }
  }, [reload])

  const hasMore = selectCasePlazaHasMore(visibleState)
  const loadMore = useCallback(() => {
    if (!enabled || state.scopeKey !== scopeKey || !hasMore || (state.status !== 'ready' && state.status !== 'errorMore')) return
    void load(state.pageNum + 1, scopeRef.current)
  }, [enabled, hasMore, load, scopeKey, state.pageNum, state.status, state.scopeKey])

  return { ...visibleState, hasMore, loadMore, reload }
}
