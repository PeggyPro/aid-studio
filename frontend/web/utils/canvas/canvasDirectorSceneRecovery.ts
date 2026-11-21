import type { DirectorSceneData, StudioWorkspaceData } from '@/types/studio'
import { stableCanvasJson, type CanvasSerializedObject } from './canvasWorkspaceAdapter'
import { persistentDirectorScene } from '@/utils/studio/directorApi'
import type { ThreePrevizLocalRecord } from '@/utils/studio/threePrevizStorage'

type SceneNodes = StudioWorkspaceData['nodes']
type SceneMap = StudioWorkspaceData['directorScenes']
type Checkpoint = Pick<ThreePrevizLocalRecord, 'key' | 'revision' | 'savedAt'>
type PreparedCheckpoint = Checkpoint & { preparedScene: Record<string, unknown> }

export function canvasDirectorSceneIds(nodes: SceneNodes): Set<string> {
  return new Set(nodes.flatMap(({ data }) =>
    !data.canvasTransient && data.kind === 'director' && data.directorSceneId ? [data.directorSceneId] : []))
}

export function savedCanvasDirectorSceneReferences(objects: Iterable<CanvasSerializedObject>): Map<string, string | undefined> {
  const refs = new Map<string, string | undefined>()
  for (const { objectId, objectType, payload } of objects) {
    if (objectType !== 'NODE' || payload.nodeType !== 'DIRECTOR') continue
    const metadata = payload.metadata as { studioData?: { directorSceneId?: unknown } } | undefined
    const id = metadata?.studioData?.directorSceneId
    refs.set(objectId, typeof id === 'string' && id ? id : undefined)
  }
  return refs
}

function recoveryKey(owner: string, scope: string, sceneId: string) {
  // The editor maintains an independent revision in this same database.
  return JSON.stringify(['canvas-scene-recovery', owner, scope, sceneId])
}

/** Only fill holes for the exact owner, workspace and scene. A cloud scene always wins. */
export async function recoverCanvasDirectorScenes(
  ids: ReadonlySet<string>, scenes: SceneMap, local: SceneMap, owner: string | undefined, scope: string
): Promise<SceneMap> {
  const found = await Promise.all([...ids].filter(id => !Object.hasOwn(scenes, id)).map(async id => {
    const inMemory = Object.hasOwn(local, id) ? local[id] : undefined
    if (inMemory?.id === id) return [id, inMemory] as const
    if (!owner || typeof indexedDB === 'undefined') return
    const storage = await import('@/utils/studio/threePrevizStorage').catch(() => undefined)
    if (!storage) return
    const { readThreePrevizLocal, threePrevizStorageKey } = storage
    const records = await Promise.all([
      readThreePrevizLocal(recoveryKey(owner, scope, id)).catch(() => undefined),
      readThreePrevizLocal(threePrevizStorageKey(owner, scope, id)).catch(() => undefined)
    ])
    const record = records
      .filter((item): item is ThreePrevizLocalRecord => item?.scene.id === id)
      .sort((left, right) => right.savedAt.localeCompare(left.savedAt))[0]
    if (!record) return
    try {
      return [id, persistentDirectorScene(record.scene)] as const
    } catch {
      // A record depending on an expired local file is not a complete recovery source.
      return
    }
  }))
  return Object.fromEntries(found.filter(entry => entry !== undefined))
}

export async function readPreparedCanvasDirectorScenes(
  owner: string | undefined, scope: string, scenes: DirectorSceneData[]
): Promise<Map<string, PreparedCheckpoint>> {
  if (!scenes.length || !owner || typeof indexedDB === 'undefined') return new Map()
  const storage = await import('@/utils/studio/threePrevizStorage').catch(() => undefined)
  if (!storage) return new Map()
  const found = await Promise.all(scenes.map(async scene => {
    const record = await storage.readThreePrevizLocal(recoveryKey(owner, scope, scene.id)).catch(() => undefined)
    if (!record || record.preparedScene?.id !== scene.id) return
    try {
      if (scene.modeling && !record.preparedScene.modeling) return
      if (stableCanvasJson(persistentDirectorScene(record.scene)) === stableCanvasJson(persistentDirectorScene(scene))) {
        return [scene.id, { key: record.key, revision: record.revision, savedAt: record.savedAt, preparedScene: record.preparedScene }] as const
      }
    } catch {
      return
    }
  }))
  return new Map(found.filter(entry => entry !== undefined))
}

export async function checkpointCanvasDirectorScenes(
  owner: string | undefined, scope: string, scenes: DirectorSceneData[],
  document: Record<string, unknown>[], current: () => boolean
): Promise<Checkpoint[]> {
  if (!scenes.length) return []
  if (!owner || typeof indexedDB === 'undefined') throw new Error('暂时无法保存3D预演恢复副本，新节点尚未提交')
  const { readThreePrevizLocal, saveThreePrevizLocal } = await import('@/utils/studio/threePrevizStorage')
  const prepared = new Map(document.map(scene => [scene.id, scene]))
  return Promise.all(scenes.map(async scene => {
    const key = recoveryKey(owner, scope, scene.id)
    const previous = await readThreePrevizLocal(key)
    if (!current()) throw new Error('画布或账号已切换，新节点尚未提交')
    const saved = await saveThreePrevizLocal(key, scene, previous?.revision ?? 0, prepared.get(scene.id))
    return { key, revision: saved.revision, savedAt: saved.savedAt }
  }))
}

export async function clearCanvasDirectorCheckpoints(checkpoints: Checkpoint[]): Promise<void> {
  if (!checkpoints.length) return
  const { deleteThreePrevizLocal } = await import('@/utils/studio/threePrevizStorage')
  await Promise.allSettled(checkpoints.map(({ key, revision, savedAt }) => deleteThreePrevizLocal(key, revision, savedAt)))
}
