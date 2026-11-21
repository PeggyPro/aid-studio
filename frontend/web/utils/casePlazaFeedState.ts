export type CasePlazaFeedStatus =
  | 'loadingFirst'
  | 'ready'
  | 'loadingMore'
  | 'errorFirst'
  | 'errorMore'

export interface CasePlazaFeedState<Item extends { id: string | number }> {
  scopeKey?: string
  items: Item[]
  total: number
  pageNum: number
  status: CasePlazaFeedStatus
  error: string
}

export type CasePlazaFeedAction<Item extends { id: string | number }> =
  ({ scopeKey?: string } & (
  | { type: 'reset' }
  | { type: 'loadMoreStarted' }
  | { type: 'pageSucceeded'; pageNum: number; rows: Item[]; total: number }
  | { type: 'pageFailed'; pageNum: number; error: string }))

export function createCasePlazaFeedState<
  Item extends { id: string | number }
>(scopeKey?: string): CasePlazaFeedState<Item> {
  return {
    ...(scopeKey === undefined ? {} : { scopeKey }),
    items: [],
    total: 0,
    pageNum: 0,
    status: 'loadingFirst',
    error: ''
  }
}

function appendUniqueItems<Item extends { id: string | number }>(current: Item[], incoming: Item[]): Item[] {
  const ids = new Set(current.map((item) => item.id))
  const items = [...current]
  for (const item of incoming) {
    if (ids.has(item.id)) continue
    ids.add(item.id)
    items.push(item)
  }
  return items
}

export function reduceCasePlazaFeed<Item extends { id: string | number }>(
  state: CasePlazaFeedState<Item>,
  action: CasePlazaFeedAction<Item>
): CasePlazaFeedState<Item> {
  if (action.type === 'reset') return createCasePlazaFeedState<Item>(action.scopeKey)
  if (action.scopeKey !== undefined && action.scopeKey !== state.scopeKey) return state

  if (action.type === 'loadMoreStarted') {
    return { ...state, status: 'loadingMore', error: '' }
  }

  if (action.type === 'pageFailed') {
    return {
      ...state,
      status: action.pageNum === 1 ? 'errorFirst' : 'errorMore',
      error: action.error
    }
  }

  const baseItems = action.pageNum === 1 ? [] : state.items
  const items = appendUniqueItems(baseItems, action.rows)
  const reportedTotal = Number.isFinite(action.total) ? Math.max(0, action.total) : items.length
  const total =
    action.pageNum > 1 && action.rows.length === 0
      ? items.length
      : Math.max(items.length, reportedTotal)

  return {
    ...state,
    items,
    total,
    pageNum: action.pageNum,
    status: 'ready',
    error: ''
  }
}

/** A render must never expose data belonging to the previous tab/search, even before effects run. */
export function selectCasePlazaFeedScope<Item extends { id: string | number }>(state: CasePlazaFeedState<Item>, scopeKey: string) {
  return state.scopeKey === scopeKey ? state : createCasePlazaFeedState<Item>(scopeKey)
}

export function selectCasePlazaHasMore<Item extends { id: string | number }>(
  state: CasePlazaFeedState<Item>
): boolean {
  return state.items.length < state.total
}
