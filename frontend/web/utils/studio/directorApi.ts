import type { DirectorSceneData } from '@/types/studio'

export function persistentDirectorScene(scene: DirectorSceneData): DirectorSceneData {
  if (scene.modelAssets?.some((asset) => /^(blob:|data:)/i.test(asset.url)))
    throw new Error('请等待模型资源上传完成后再保存工程')
  const { modeling, spaceGenJob: _retiredJob, ...base } = scene
  const cleaned = {
    ...base,
    captures: scene.captures?.filter((c) => !/^blob:/i.test(c.mediaUrl)),
    panoramaHistory: scene.panoramaHistory?.filter((url) => !/^blob:/i.test(url)),
  }
  const persistent = JSON.parse(JSON.stringify(cleaned, (_key, value) => (typeof value === 'string' && /^blob:/i.test(value) ? undefined : value))) as DirectorSceneData
  return modeling ? { ...persistent, modeling } : persistent
}
