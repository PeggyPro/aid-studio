import type { ApiEnvelope } from '~/types/business-api'

/** discovery 分页统一使用 AjaxResult 顶层 total + data[]。 */
export type DiscoveryPageAjaxResult<Item> = ApiEnvelope<Item[]> & {
  total: number
  data: Item[]
}

export function parseDiscoveryPageAjaxResult<Item>(
  response: DiscoveryPageAjaxResult<Item>
): { total: number; data: Item[] } {
  const total = Number(response.total)
  return {
    total: Number.isFinite(total) && total >= 0 ? total : 0,
    data: Array.isArray(response.data) ? response.data : []
  }
}
