export interface CasePlazaNavigationItem {
  id: number | string
  sourceKind: 'discovery' | 'project' | 'canvas'
  href?: string
}

/** 发现与旧项目 ID 可能重叠，导航必须同时依据来源。 */
export function resolveCasePlazaDetailPath(item: CasePlazaNavigationItem): string {
  if (item.href) return item.href
  if (item.sourceKind === 'canvas') return `/canvas/work?${new URLSearchParams({ publicationId: String(item.id) })}`
  return item.sourceKind === 'discovery' ? `/discovery?id=${item.id}` : `/case?id=${item.id}`
}
