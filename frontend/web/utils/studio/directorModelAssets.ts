import type { DirectorModelAsset, DirectorSceneData } from '@/types/studio'
import { createDirectorActor, createDirectorProp, resolveDirectorObjectSpawnPosition } from './directorScene'
export { DIRECTOR_BUILTIN_MODELS, createDirectorBuiltinModelAsset, directorBuiltinModelUrl, type DirectorBuiltinModel } from './directorModelCatalog'

/** Register one source, create independent scene instances for every explicit add. */
export function addDirectorModelAssetInstance(scene: DirectorSceneData, asset: DirectorModelAsset) {
  const assets = scene.modelAssets ?? []
  const registered = assets.find(item => item.id === asset.id)
  if (!registered && assets.length >= 100) throw new Error('模型资产最多 100 个，请先移除不再使用的模型')
  if (scene.objects.length >= 2000) throw new Error('场景对象最多 2000 个，请先移除不再使用的对象')
  const source = registered ?? asset
  const count = scene.objects.filter(object => object.kind === source.kind).length + 1
  const base = source.kind === 'actor' ? createDirectorActor(count, source.name) : createDirectorProp(count)
  const object = {
    ...base, name: source.name, modelAssetId: source.id, visible: true, locked: false,
    transform: {
      ...base.transform,
      scale: source.source === 'builtin' || source.source === 'library' ? [1, 1, 1] as [number, number, number] : base.transform.scale,
      position: resolveDirectorObjectSpawnPosition(count, source.kind)
    }
  }
  return {
    object,
    scene: { ...scene, modelAssets: registered ? assets : [...assets, source], objects: [...scene.objects, object], updatedAt: new Date().toISOString() }
  }
}
