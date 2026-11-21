import type { CasePlazaCardItem } from '~/types/case-plaza'
import { resolveCasePlazaAspectRatio } from '~/utils/casePlazaAspectRatio'

export const CASE_PLAZA_MASONRY_GUTTER = 6

/** 案例广场瀑布流列数：与容器宽度档位一致。 */
export function resolveCasePlazaColumnCount(width: number): number {
  if (width <= 640) return 2
  if (width <= 960) return 3
  if (width < 1600) return 4
  return 5
}

/**
 * 按卡片宽高比把条目分配到最短列。
 * 高度只用于「选哪一列」，真实渲染高度仍由 CSS aspect-ratio 决定，
 * 避免 masonic 高度缓存 / CSS column-count 挤到左侧等问题。
 */
export function distributeCasePlazaItemsToColumns<
  T extends Pick<CasePlazaCardItem, 'sourceKind' | 'mediaWidth' | 'mediaHeight' | 'aspectRatio'>
>(items: T[], columnCount: number, gutter = CASE_PLAZA_MASONRY_GUTTER): T[][] {
  const count = Math.max(1, Math.floor(columnCount) || 1)
  const columns: T[][] = Array.from({ length: count }, () => [])
  const heights = new Array<number>(count).fill(0)

  for (const item of items) {
    const { ratio } = resolveCasePlazaAspectRatio(item)
    // 用相对高度（1/ratio）即可比较列高；列宽恒等，可约掉
    const relativeHeight = ratio > 0 ? 1 / ratio : 9 / 16

    let shortest = 0
    for (let i = 1; i < count; i += 1) {
      if (heights[i]! < heights[shortest]!) shortest = i
    }

    columns[shortest]!.push(item)
    heights[shortest]! += relativeHeight + gutter / 1000
  }

  return columns
}
