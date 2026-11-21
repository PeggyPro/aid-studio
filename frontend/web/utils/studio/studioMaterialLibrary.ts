import type { StudioMaterialNodeKind } from './studioNodeKind'
import type { CanvasResource } from '@/types/canvas-api'
import { canvasResourceDisplayUrl } from '@/utils/canvas/canvasPublicationFiles'
import { isMergedAssetOfficial } from '@/utils/mergedAssetSource'
import type { MergedAssetVO } from '@/types/business-api'
import type { StudioComposerRef, StudioNodeData } from '@/types/studio'
import { buildStyleLibraryCardId } from '@/hooks/usePromptDictionary'
import { studioStyleSelectionToNodeData, type StudioStyleSelection } from '@/utils/studio/studioStyleLibraryNode'

export const STUDIO_MATERIAL_OFFICIAL_FALLBACK = [
  { code: 'reference_character', name: '角色' },
  { code: 'reference_scene', name: '场景' },
  { code: 'reference_prop', name: '道具' },
  { code: 'pose', name: '姿势' },
  { code: 'expression', name: '表情' },
  { code: 'effect', name: '特效' }
] as const

/** 个人与官方、不同素材类型的数字 id 会重复，列表 key 必须带上来源和类型。 */
export function studioMaterialAssetKey(asset: Pick<MergedAssetVO, 'id' | 'sourceFlag' | 'assetType'>): string {
  return `${buildStyleLibraryCardId(asset.sourceFlag, asset.id)}:${asset.assetType || ''}`
}

/** 翻页结果按稳定 key 去重，避免同一条素材被追加两次。 */
export function mergeStudioMaterialCatalog(current: MergedAssetVO[], incoming: MergedAssetVO[]): MergedAssetVO[] {
  const seen = new Set<string>()
  const merged: MergedAssetVO[] = []
  for (const asset of [...current, ...incoming]) {
    const key = studioMaterialAssetKey(asset)
    if (seen.has(key)) continue
    seen.add(key)
    merged.push(asset)
  }
  return merged
}

/** 风格单独成区，不混进官方素材分类。 */
export function studioMaterialOfficialTypes(types: Array<{ code: string; name: string }>) {
  const usable = types.filter((item) => item.code && item.code !== 'style')
  return usable.length ? usable : [...STUDIO_MATERIAL_OFFICIAL_FALLBACK]
}

export function studioOfficialAssetToNodeData(asset: Pick<MergedAssetVO, 'assetName' | 'imageUrl'>): Partial<StudioNodeData> | null {
  const mediaUrl = String(asset.imageUrl || '').trim()
  if (!mediaUrl) return null
  const title = asset.assetName || '官方素材'
  return {
    title,
    status: 'success',
    mediaKind: 'image',
    mediaUrl,
    composerHidden: true,
    resultSummary: `官方素材：${title}`
  }
}

export function studioStyleCardToNodeData(style: StudioStyleSelection): Partial<StudioNodeData> | null {
  if (!style.thumbnail) return null
  return studioStyleSelectionToNodeData(style)
}

export type StudioCatalogMaterialPick = {
  kind: 'node'
  nodeKind: StudioMaterialNodeKind
  data: Partial<StudioNodeData>
  ref: StudioComposerRef
}

export function studioCatalogMaterialReference(asset: MergedAssetVO, data: Partial<StudioNodeData>): StudioComposerRef {
  return {
    id: studioMaterialAssetKey(asset),
    kind: isMergedAssetOfficial(asset.sourceFlag) ? 'library' : 'asset',
    assetId: String(asset.id),
    title: data.title ?? asset.assetName,
    mediaKind: 'image',
    previewUrl: data.mediaUrl
  }
}

/** Shared workspaces persist the registered resource ID. Catalog URLs are display hints only. */
export async function resolveStudioCatalogMaterial(
  pick: StudioCatalogMaterialPick,
  resolveReference: (ref: StudioComposerRef, options: { isCurrent: () => boolean }) => Promise<CanvasResource>,
  isCurrent: () => boolean
): Promise<Partial<StudioNodeData>> {
  const resource = await resolveReference(pick.ref, { isCurrent })
  const resourceId = String(resource.resourceId || '')
  if (!/^\d+$/.test(resourceId) || resourceId === '0' || !resource.resourceUrl || resource.mediaType !== 'IMAGE') {
    throw new Error('素材图片暂时无法使用，请重新选择')
  }
  const mediaUrl = canvasResourceDisplayUrl(resource)
  return {
    ...pick.data,
    mediaUrl,
    ...(pick.nodeKind === 'style_library' ? {
      generationOptions: { ...pick.data.generationOptions, stylePreviewUrl: mediaUrl }
    } : {}),
    canvasResourceIds: [resourceId],
    canvasPrimaryResourceId: resourceId,
    canvasTransient: false,
    errorMessage: undefined
  }
}
