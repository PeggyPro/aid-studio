import type {
  PublicDiscoveryPageRequest,
  PublicProjectVideoListRequest
} from '~/types/business-api'

export const CASE_PLAZA_SEARCH_DEBOUNCE_MS = 300

export type CasePlazaSourceFilter = 'discovery' | 'movie'

/** 案例广场默认选中「发现」。 */
export const CASE_PLAZA_DEFAULT_TAB: CasePlazaSourceFilter = 'discovery'

export interface CasePlazaSearchCriteria {
  activeTab: CasePlazaSourceFilter
  searchQuery: string
}

export type CasePlazaPageRequest =
  | { source: 'discovery'; body: PublicDiscoveryPageRequest }
  | { source: 'movie'; body: PublicProjectVideoListRequest }

/** Tab 切换 0ms 提交；同 Tab 的原始搜索词变化约 300ms 提交。 */
export function scheduleCasePlazaCriteriaCommit(
  current: CasePlazaSearchCriteria,
  next: CasePlazaSearchCriteria,
  commit: (criteria: CasePlazaSearchCriteria) => void
): () => void {
  if (current.activeTab === next.activeTab && current.searchQuery === next.searchQuery) {
    return () => {}
  }
  const delay = current.activeTab === next.activeTab ? CASE_PLAZA_SEARCH_DEBOUNCE_MS : 0
  const timer = setTimeout(() => commit(next), delay)
  return () => clearTimeout(timer)
}

export function buildCasePlazaPageRequest(
  criteria: CasePlazaSearchCriteria,
  pageNum: number,
  pageSize: number
): CasePlazaPageRequest {
  const name = criteria.searchQuery.trim() || undefined
  if (criteria.activeTab === 'discovery') {
    return {
      source: 'discovery',
      body: { name, pageNum, pageSize }
    }
  }
  return {
    source: 'movie',
    body: {
      projectName: name,
      projectType: criteria.activeTab,
      pageNum,
      pageSize
    }
  }
}
