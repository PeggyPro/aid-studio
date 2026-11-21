'use client'

import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react'
import type { CasePlazaCardItem } from '@/types/case-plaza'
import type { PublicDiscoveryItem, PublicProjectVideoRow } from '~/types/business-api'
import { publicDiscoveryPage, publicProjectVideoList } from '~/utils/businessApi'
import {
  createCasePlazaFeedState,
  reduceCasePlazaFeed,
  selectCasePlazaHasMore,
  selectCasePlazaFeedScope,
  type CasePlazaFeedStatus
} from '~/utils/casePlazaFeedState'
import { emptyImageIconUrl } from '~/utils/emptyImageIcon'
import { resolveDiscoveryCardCover } from '~/utils/discoveryMedia'
import {
  buildCasePlazaPageRequest,
  scheduleCasePlazaCriteriaCommit,
  type CasePlazaSearchCriteria,
  type CasePlazaSourceFilter
} from '~/utils/casePlazaSearchCriteria'

export const CASE_PLAZA_PAGE_SIZE = 24
export const MOBILE_CASE_PLAZA_PAGE_SIZE = 10

export type CasePlazaFilter = CasePlazaSourceFilter

export interface CasePlazaFeedItem extends CasePlazaCardItem {
  id: number
  sourceKind: 'discovery' | 'project'
}

export interface UseCasePlazaFeedOptions {
  activeTab: CasePlazaFilter
  searchQuery: string
  pageSize?: number
}

export interface UseCasePlazaFeedResult {
  items: CasePlazaFeedItem[]
  status: CasePlazaFeedStatus
  hasMore: boolean
  error: string
  loadMore: () => void
  reload: () => void
}

export function normalizeCasePlazaFeedItem(row: PublicProjectVideoRow): CasePlazaFeedItem {
  return {
    id: row.id,
    title: row.projectName || `公开项目 #${row.id}`,
    coverImage: String(row.coverUrl || '').trim() || emptyImageIconUrl,
    previewVideoUrl: String(row.previewVideoUrl || row.finalVideoUrl || '').trim(),
    authorName: String(row.authorNickname || '').trim() || '作者',
    authorAvatar: null,
    projectDesc: String(row.projectDesc || '').trim(),
    viewCount: Number(row.episodeCount) > 0 ? Number(row.episodeCount) : 0,
    categoryLabel: row.projectType === 'series' ? '连续剧' : '电影/短片',
    sourceKind: 'project',
    mediaType: 'video',
    sourceType: 'project',
    publishTime: row.publishTime,
    mediaWidth: row.mediaWidth,
    mediaHeight: row.mediaHeight,
    aspectRatio: row.aspectRatio,
    ratioSource: row.ratioSource
  }
}

export function normalizeDiscoveryFeedItem(row: PublicDiscoveryItem): CasePlazaFeedItem {
  const mediaType = row.mediaType === 'image' ? 'image' : 'video'
  return {
    id: row.id,
    title: String(row.name || '').trim() || `发现作品 #${row.id}`,
    coverImage: resolveDiscoveryCardCover(row) || emptyImageIconUrl,
    previewVideoUrl:
      mediaType === 'video'
        ? String(row.previewVideoUrl || row.mediaUrl || '').trim()
        : '',
    authorName:
      String(row.publisherName || '').trim() ||
      (String(row.sourceType || '').toUpperCase() === 'ADMIN' ? 'AID精选' : '用户'),
    authorAvatar: String(row.publisherAvatar || '').trim() || null,
    projectDesc: String(row.description || '').trim(),
    viewCount: 0,
    categoryLabel: mediaType === 'video' ? '视频' : '图片',
    sourceKind: 'discovery',
    mediaType,
    sourceType: String(row.sourceType || '').trim(),
    publishTime: row.publishTime,
    mediaWidth: row.mediaWidth,
    mediaHeight: row.mediaHeight,
    aspectRatio: row.aspectRatio,
    ratioSource: row.ratioSource
  }
}

export function useCasePlazaFeed({
  activeTab,
  searchQuery,
  pageSize = CASE_PLAZA_PAGE_SIZE
}: UseCasePlazaFeedOptions): UseCasePlazaFeedResult {
  const [state, dispatch] = useReducer(
    reduceCasePlazaFeed<CasePlazaFeedItem>,
    undefined,
    createCasePlazaFeedState<CasePlazaFeedItem>
  )
  const stateRef = useRef(state)
  const [searchCriteria, setSearchCriteria] = useState<CasePlazaSearchCriteria>(() => ({
    activeTab,
    searchQuery
  }))
  const requestGenerationRef = useRef(0)
  const requestingPagesRef = useRef(new Set<string>())

  useEffect(() => {
    stateRef.current = state
  }, [state])

  useEffect(
    () =>
      scheduleCasePlazaCriteriaCommit(
        searchCriteria,
        { activeTab, searchQuery },
        setSearchCriteria
      ),
    [activeTab, searchCriteria, searchQuery]
  )

  // Tab 使用 raw query 立即请求；同 Tab 搜索仅使用约 300ms 后提交的 query。
  const effectiveSearchQuery =
    (searchCriteria.activeTab === activeTab ? searchCriteria.searchQuery : searchQuery).trim()
  const scopeKey = useMemo(() => JSON.stringify([activeTab, effectiveSearchQuery, pageSize]), [activeTab, effectiveSearchQuery, pageSize])
  const visibleState = selectCasePlazaFeedScope(state, scopeKey)

  const requestPage = useCallback(
    async (pageNum: number, generation: number) => {
      const requestKey = `${generation}:${pageNum}`
      if (requestingPagesRef.current.has(requestKey)) return
      requestingPagesRef.current.add(requestKey)

      try {
        const pageRequest = buildCasePlazaPageRequest(
          { activeTab, searchQuery: effectiveSearchQuery },
          pageNum,
          pageSize
        )
        if (pageRequest.source === 'discovery') {
          const response = await publicDiscoveryPage(pageRequest.body)
          if (requestGenerationRef.current !== generation) return
          dispatch({
            type: 'pageSucceeded',
            scopeKey,
            pageNum,
            rows: response.data.map(normalizeDiscoveryFeedItem),
            total: response.total
          })
        } else {
          const response = await publicProjectVideoList(pageRequest.body)
          if (requestGenerationRef.current !== generation) return
          dispatch({
            type: 'pageSucceeded',
            scopeKey,
            pageNum,
            rows: response.rows.map(normalizeCasePlazaFeedItem),
            total: response.total
          })
        }
      } catch {
        if (requestGenerationRef.current !== generation) return
        dispatch({ type: 'pageFailed', scopeKey, pageNum, error: '加载失败，请重试' })
      } finally {
        requestingPagesRef.current.delete(requestKey)
      }
    },
    [activeTab, effectiveSearchQuery, pageSize, scopeKey]
  )

  useEffect(() => {
    const generation = requestGenerationRef.current + 1
    requestGenerationRef.current = generation
    dispatch({ type: 'reset', scopeKey })
    void requestPage(1, generation)

    return () => {
      if (requestGenerationRef.current === generation) {
        requestGenerationRef.current += 1
      }
    }
  }, [requestPage, scopeKey])

  const loadMore = useCallback(() => {
    const current = stateRef.current
    if (current.scopeKey !== scopeKey) return
    const isLoading = current.status === 'loadingFirst' || current.status === 'loadingMore'
    if (isLoading || !selectCasePlazaHasMore(current)) return

    const nextPage = current.pageNum + 1
    const generation = requestGenerationRef.current
    const requestKey = `${generation}:${nextPage}`
    if (requestingPagesRef.current.has(requestKey)) return

    dispatch({ type: 'loadMoreStarted', scopeKey })
    void requestPage(nextPage, generation)
  }, [requestPage, scopeKey])

  const reload = useCallback(() => {
    const generation = requestGenerationRef.current + 1
    requestGenerationRef.current = generation
    dispatch({ type: 'reset', scopeKey })
    void requestPage(1, generation)
  }, [requestPage, scopeKey])

  return {
    items: visibleState.items,
    status: visibleState.status,
    hasMore: selectCasePlazaHasMore(visibleState),
    error: visibleState.error,
    loadMore,
    reload
  }
}
