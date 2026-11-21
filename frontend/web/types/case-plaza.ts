/** 共用案例卡片模型。画布保留服务端 Long ID 字符串，电影和发现继续使用数字。 */
export interface CasePlazaCardItem {
  id: string | number
  href?: string
  title: string
  coverImage: string
  previewVideoUrl: string
  authorName: string
  authorAvatar?: string | null
  projectDesc: string
  viewCount: number
  categoryLabel: string
  sourceKind: 'discovery' | 'project' | 'canvas'
  mediaType: 'image' | 'video'
  sourceType: string
  publishTime?: string | null
  mediaWidth?: number | null
  mediaHeight?: number | null
  aspectRatio?: string | null
  ratioSource?: 'measured' | 'declared' | 'fallback' | null
}

export const casePlazaCardKey = (item: Pick<CasePlazaCardItem, 'sourceKind' | 'id'>) => `${item.sourceKind}:${item.id}`
