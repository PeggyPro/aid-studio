import catalog from '@/config/directorModels.json'
import type { DirectorModelAsset } from '@/types/studio'
import { withAppBasePath } from '@/utils/appBasePath'

export const DIRECTOR_BUILTIN_MODELS = catalog
export type DirectorBuiltinModel = typeof catalog[number]

export function directorBuiltinModelUrl(model: DirectorBuiltinModel): string {
  return withAppBasePath(`/models/director/official/${model.file}`)
}

export function findDirectorBuiltinModel(assetId: string): DirectorBuiltinModel | undefined {
  return catalog.find(model => `director-builtin-${model.id}` === assetId)
}

/** Relative model URLs are reserved for catalogued first-party static assets. */
export function isDirectorBuiltinModelUrl(url: string): boolean {
  return catalog.some(model => directorBuiltinModelUrl(model) === url)
}

export function createDirectorBuiltinModelAsset(model: DirectorBuiltinModel): DirectorModelAsset {
  return {
    id: `director-builtin-${model.id}`, name: model.name,
    fileName: model.file.split('/').at(-1)!, url: directorBuiltinModelUrl(model),
    format: 'glb', kind: 'prop', source: 'builtin', createdAt: new Date().toISOString()
  }
}

/** Official identities are portable across hosts and deployment base paths. */
export function restoreDirectorModelAsset(asset: DirectorModelAsset): DirectorModelAsset {
  if (asset.resourceId && asset.bundle) return asset
  if (asset.source !== 'builtin') return asset
  const model = findDirectorBuiltinModel(asset.id)
  if (!model || asset.format !== 'glb' || asset.kind !== 'prop') throw new Error('工程引用了未知或无效的官方模型，请更新客户端')
  return { ...createDirectorBuiltinModelAsset(model), createdAt: asset.createdAt }
}
