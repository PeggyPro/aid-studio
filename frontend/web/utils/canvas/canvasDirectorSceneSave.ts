import type { CanvasResource, CanvasSceneDocument } from '@/types/canvas-api'
import type { StudioWorkspaceData } from '@/types/studio'
import { prepareCanvasSceneDocument } from './canvasSceneDocument'
import { stableCanvasJson, type CanvasSerializedObject } from './canvasWorkspaceAdapter'
import {
  checkpointCanvasDirectorScenes, readPreparedCanvasDirectorScenes, recoverCanvasDirectorScenes, savedCanvasDirectorSceneReferences
} from './canvasDirectorSceneRecovery'

interface CanvasDirectorSceneWrite {
  document: CanvasSceneDocument
  expectedVersion: string | number
  checkpoints: Awaited<ReturnType<typeof checkpointCanvasDirectorScenes>>
}

export async function prepareCanvasDirectorSceneWrite({
  workspaceId, workspace, objects, baseline, snapshot, owner, resources, current
}: {
  workspaceId: string
  workspace: StudioWorkspaceData
  objects: CanvasSerializedObject[]
  baseline: Iterable<CanvasSerializedObject>
  snapshot?: { sceneVersion?: string | number; sceneDocument?: CanvasSceneDocument } | null
  owner: string | undefined
  resources: CanvasResource[]
  current: () => boolean
}): Promise<{ write?: CanvasDirectorSceneWrite; recovered?: StudioWorkspaceData['directorScenes'] }> {
  const refs = savedCanvasDirectorSceneReferences(objects)
  const oldRefs = savedCanvasDirectorSceneReferences(baseline)
  const before: CanvasSceneDocument = snapshot?.sceneDocument?.scenes ? snapshot.sceneDocument : { schemaVersion: 1, scenes: [] }
  if (!refs.size && !oldRefs.size && !before.scenes.length) return {}

  const ids = new Set([...refs.values()].filter((id): id is string => Boolean(id)))
  const recovered = await recoverCanvasDirectorScenes(ids, workspace.directorScenes, {}, owner, workspace.scopeKey)
  const directorScenes = { ...workspace.directorScenes, ...recovered }
  const available = new Set([...ids].filter(id => Object.hasOwn(directorScenes, id) && directorScenes[id].id === id))
  const changedReference = [...refs].some(([nodeId, id]) => !oldRefs.has(nodeId) || oldRefs.get(nodeId) !== id)
  if ([...refs].some(([nodeId, id]) => (!id || !available.has(id)) && (!oldRefs.has(nodeId) || oldRefs.get(nodeId) !== id))) {
    throw new Error('新建或重新关联的3D预演节点缺少对应场景')
  }

  const cloudIds = new Set(before.scenes.map(scene => scene.id))
  const unpublished = [...available].filter(id => !cloudIds.has(id)).map(id => directorScenes[id])
  const pending = await readPreparedCanvasDirectorScenes(owner, workspace.scopeKey, unpublished)
  const preparedScenes = new Map([...pending].map(([id, record]) => [id, record.preparedScene]))
  const document = await prepareCanvasSceneDocument({ ...workspace, directorScenes }, {
    workspaceId, resources, current, referencedSceneIds: available, preparedScenes
  })
  if (before.schemaVersion === 2) document.schemaVersion = 2
  if (!changedReference && refs.size === oldRefs.size && stableCanvasJson(before) === stableCanvasJson(document)) return { recovered }

  if (!current()) throw new Error('画布或身份已切换，未提交场景修改')
  if (snapshot?.sceneVersion == null) throw new Error('场景版本缺失，请重新打开画布后保存')
  const checkpoints = [...pending.values(), ...await checkpointCanvasDirectorScenes(
    owner, workspace.scopeKey, unpublished.filter(scene => !pending.has(scene.id)), document.scenes, current
  )]
  return { recovered, write: { document, expectedVersion: snapshot.sceneVersion, checkpoints } }
}
