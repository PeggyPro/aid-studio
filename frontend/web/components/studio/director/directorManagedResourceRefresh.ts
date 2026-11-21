import type { CanvasModelBundle } from '@/types/canvas-api'
import type { DirectorSceneData } from '@/types/studio'
import type { CanvasRegisteredModelResource } from '@/utils/canvas/canvasRegisteredModelResources'
import { canvasPublicationFileUrl } from '@/utils/canvas/canvasPublicationFiles'

type ManagedSource = { resourceId?: string; bundle?: CanvasModelBundle; url: string; format: string }

function sources(scene: DirectorSceneData): ManagedSource[] {
  return [...(scene.modelAssets ?? []), ...(scene.scenePackage ? [scene.scenePackage] : []), ...(scene.scenePackages ?? [])]
}

export function directorManagedResourceIds(scene: DirectorSceneData): string[] {
  const ids = new Set<string>()
  for (const source of sources(scene)) {
    if (source.resourceId) {
      if (!/^[1-9]\d{0,18}$/.test(String(source.resourceId))) throw new Error('模型资源编号无效，请检查场景')
      ids.add(String(source.resourceId))
    } else if (source.bundle) throw new Error('模型缺少已登记的根资源编号，请检查场景')
  }
  if (ids.size > 200) throw new Error('场景模型资源超过读取上限')
  return [...ids].sort()
}

function bundleIdentity(bundle: CanvasModelBundle): string {
  return JSON.stringify([bundle.format, bundle.rootPath,
    bundle.files.map(file => [file.path, String(file.resourceId)]).sort(),
    bundle.references.map(ref => [ref.ownerPath, ref.uri, ref.path]).sort()])
}

function validUrl(value: string): boolean {
  if (typeof value !== 'string') return false
  try {
    // Local storage and authenticated publication files use the same gateway mapping
    // as loading. Arbitrary relative addresses still fail closed.
    const mapped = canvasPublicationFileUrl(value)
    const relativeManaged = /^\/(?:profile\/|api\/user\/canvas\/publication\/)/.test(value)
    const url = relativeManaged ? new URL(mapped, window.location.href) : new URL(value)
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password
  }
  catch { return false }
}

function freshSource<T extends ManagedSource>(source: T, row: CanvasRegisteredModelResource): T {
  const bundle = row.bundle
  if (!bundle || bundle.format !== source.format || !Array.isArray(bundle.files) || !bundle.files.length || bundle.files.length > 256
    || !Array.isArray(bundle.references) || bundle.references.length > 4096
    || new Set(bundle.files.map(file => file.path)).size !== bundle.files.length
    || bundle.files.some(file => !/^[1-9]\d{0,18}$/.test(String(file.resourceId)) || !validUrl(file.resourceUrl)))
    throw new Error('模型资源闭包不完整，请重新读取')
  const root = bundle.files.find(file => file.path === bundle.rootPath)
  if (!root || String(root.resourceId) !== String(source.resourceId)
    || (source.bundle && bundleIdentity(source.bundle) !== bundleIdentity(bundle)))
    throw new Error('模型资源身份已变化，请保留场景并重新读取')
  return { ...source, url: root.resourceUrl, bundle: structuredClone(bundle) }
}

/** Project only fresh access links onto the latest editor state. Authored scene data and fixed IDs stay untouched. */
export function refreshDirectorManagedResourceLinks(
  scene: DirectorSceneData,
  rows: CanvasRegisteredModelResource[],
  requestedIds = directorManagedResourceIds(scene),
): DirectorSceneData {
  const byId = new Map(rows.map(row => [String(row.resourceId), row]))
  if (byId.size !== rows.length) throw new Error('模型资源读取回执重复，请重试')
  const requested = new Set(requestedIds)
  const update = <T extends ManagedSource>(source: T): T => {
    const id = source.resourceId && String(source.resourceId)
    if (!id || !requested.has(id)) return source
    const row = byId.get(id)
    if (!row) throw new Error('模型资源暂不可读取，场景已保留，请重试')
    return freshSource(source, row)
  }
  return {
    ...scene,
    ...(scene.modelAssets ? { modelAssets: scene.modelAssets.map(update) } : {}),
    ...(scene.scenePackage ? { scenePackage: update(scene.scenePackage) } : {}),
    ...(scene.scenePackages ? { scenePackages: scene.scenePackages.map(update) } : {}),
  }
}
