import {
  WORKS_PAGE_TAB_FILM,
  WORKS_PAGE_TAB_CANVAS,
  WORKS_PAGE_TAB_QUERY_KEY,
  WORKS_PAGE_TAB_SERIES,
  WORKS_PAGE_TAB_SUBMISSION
} from './createFlowRoutes'

export type WorksPageTab =
  | typeof WORKS_PAGE_TAB_FILM
  | typeof WORKS_PAGE_TAB_CANVAS
  | typeof WORKS_PAGE_TAB_SERIES
  | typeof WORKS_PAGE_TAB_SUBMISSION

export function resolveWorksPageTab(
  tabRaw?: string | null,
  typeRaw?: string | null
): WorksPageTab {
  const raw = String(tabRaw || typeRaw || '').trim().toLowerCase()
  if (raw === WORKS_PAGE_TAB_CANVAS) return WORKS_PAGE_TAB_CANVAS
  if (raw === WORKS_PAGE_TAB_SUBMISSION) return WORKS_PAGE_TAB_SUBMISSION
  if (raw === WORKS_PAGE_TAB_SERIES || raw === 'tv') return WORKS_PAGE_TAB_SERIES
  if (raw === WORKS_PAGE_TAB_FILM || raw === 'movie') return WORKS_PAGE_TAB_FILM
  return WORKS_PAGE_TAB_FILM
}

export function buildWorksPageHref(currentSearch: string, tab: WorksPageTab): string {
  const params = new URLSearchParams(
    currentSearch.startsWith('?') ? currentSearch.slice(1) : currentSearch
  )
  params.delete('type')
  if (tab === WORKS_PAGE_TAB_CANVAS) {
    params.set(WORKS_PAGE_TAB_QUERY_KEY, WORKS_PAGE_TAB_CANVAS)
  } else if (tab === WORKS_PAGE_TAB_SERIES) {
    params.set(WORKS_PAGE_TAB_QUERY_KEY, WORKS_PAGE_TAB_SERIES)
  } else if (tab === WORKS_PAGE_TAB_SUBMISSION) {
    params.set(WORKS_PAGE_TAB_QUERY_KEY, WORKS_PAGE_TAB_SUBMISSION)
  } else {
    params.delete(WORKS_PAGE_TAB_QUERY_KEY)
  }
  const qs = params.toString()
  return qs ? `/works?${qs}` : '/works'
}

export function isWorksProjectTab(tab: WorksPageTab): boolean {
  return tab === WORKS_PAGE_TAB_FILM || tab === WORKS_PAGE_TAB_SERIES
}
