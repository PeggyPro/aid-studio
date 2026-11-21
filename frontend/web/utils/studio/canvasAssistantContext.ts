import type { AssistantContextRef, AssistantSessionSnapshot } from '@/types/assistant-api'
import type { CanvasProjectWorkspaceRuntime } from '@/hooks/studio/useCanvasProjectWorkspace'
import { getStudioWorkspaceData, useStudioUiStore } from '@/stores/studioUi'
import { serializeCanvasWorkspace, stableCanvasJson } from '@/utils/canvas/canvasWorkspaceAdapter'

export function canvasAssistantSelectionFingerprint(ids: string[]): string {
  const selected = new Set(ids)
  const objects = serializeCanvasWorkspace(getStudioWorkspaceData()).filter((item) => item.objectType === 'NODE' && selected.has(item.objectId))
  return stableCanvasJson(objects.sort((a, b) => a.objectId.localeCompare(b.objectId)))
}

/** Same persisted content boundary as CanvasAssistantPresentationMutation; never drop unknown content fields. */
export function canvasAssistantReferenceContent(payload: Record<string, unknown>): Record<string, unknown> {
  const value = structuredClone(payload)
  delete value.position; delete value.size
  const metadata = value.metadata as Record<string, unknown> | undefined
  if (!metadata || typeof metadata !== 'object' || Array.isArray(metadata)) return value
  delete metadata.hidden; delete metadata.draggable
  const data = metadata.studioData as Record<string, unknown> | undefined
  if (data && typeof data === 'object' && !Array.isArray(data)) {
    for (const key of ['displayWidth', 'displayHeight', 'modelName', 'canvasCurrentTaskId', 'canvasCurrentResultId', 'canvasLatestTaskStatus']) delete data[key]
    if (value.nodeType === 'TEXT') delete data.model
    if (data.canvasTransient === false) delete data.canvasTransient
    if (Array.isArray(data.canvasResourceIds) && !data.canvasResourceIds.length) delete data.canvasResourceIds
    if (!Object.keys(data).length) delete metadata.studioData
  }
  if (!Object.keys(metadata).length) delete value.metadata
  return value
}

function referenceFingerprint(ids: string[]): string {
  const selected = new Set(ids)
  return stableCanvasJson(serializeCanvasWorkspace(getStudioWorkspaceData())
    .filter(item => item.objectType === 'NODE' && selected.has(item.objectId))
    .map(item => ({ ...item, payload: canvasAssistantReferenceContent(item.payload) }))
    .sort((a, b) => a.objectId.localeCompare(b.objectId)))
}

/** Explicit workspace refresh saves through existing CAS; server preserves the original target identities. */
export async function prepareCanvasAssistantContextRefresh(runtime: CanvasProjectWorkspaceRuntime, scopeKey: string): Promise<void> {
  const workspaceId = runtime.project?.workspace.workspaceId
  if (!workspaceId || !runtime.canEdit || !runtime.canMutate || useStudioUiStore.getState().scopeKey !== scopeKey) throw new Error('当前画布不可更新引用，请确认连接和编辑权限')
  await runtime.saveNow()
  if (useStudioUiStore.getState().scopeKey !== scopeKey || String(runtime.project?.workspace.workspaceId) !== String(workspaceId)
    || useStudioUiStore.getState().dirtyRevision > 0) throw new Error('画布已切换或尚未保存，请核对后重试')
}

/** Capture saved versions for the local queue without saving, resolving, or sending. */
export function captureCanvasAssistantContext(runtime: CanvasProjectWorkspaceRuntime, scopeKey: string, nodeIds: string[]): AssistantContextRef[] {
  const workspaceId = runtime.project?.workspace.workspaceId
  const state = useStudioUiStore.getState()
  if (!workspaceId || !runtime.canEdit || state.scopeKey !== scopeKey) throw new Error('当前画布不可引用，请确认连接和编辑权限')
  if (state.dirtyRevision > 0) throw new Error('引用内容尚未保存，请先保存画布后加入队列')
  return [...new Set(nodeIds)].map((id): AssistantContextRef => {
    const node = state.nodes.find(item => item.id === id), version = runtime.getNodeVersion(id)
    if (!node || node.data.flowBinding || node.data.canvasTransient || runtime.isNodeBusy(id)
      || !Number.isSafeInteger(version) || !version || version < 1) throw new Error('引用节点尚无可用的已保存版本，请完成保存后加入队列')
    return { kind: 'FREE_CANVAS', workspaceId: String(workspaceId), objectId: id, objectVersion: version, label: String(node.data.title || node.data.label || id) }
  })
}

/** Freeze the selection, save through existing leases/CAS, then bind the real object versions. */
export async function resolveCanvasAssistantContext(runtime: CanvasProjectWorkspaceRuntime, scopeKey: string, nodeIds: string[], selectionPolicy: 'CURRENT' | 'FIXED' = 'CURRENT'): Promise<AssistantContextRef[]> {
  const workspaceId = runtime.project?.workspace.workspaceId
  if (!workspaceId || !runtime.canEdit || !runtime.canMutate) throw new Error('当前画布不可引用，请确认连接和编辑权限')
  const ids = [...new Set(nodeIds)]
  if (!ids.length) throw new Error('请先选择需要引用的画布节点')
  const before = useStudioUiStore.getState()
  if (before.scopeKey !== scopeKey) throw new Error('画布已切换，请重新选择')
  for (const id of ids) {
    const node = before.nodes.find((item) => item.id === id)
    if (!node || node.data.flowBinding || node.data.canvasTransient || runtime.isNodeBusy(id)) throw new Error('选中节点仍在处理或不可引用，请等待完成后重新选择')
  }
  const fingerprint = referenceFingerprint(ids)
  await runtime.saveNow()
  const after = useStudioUiStore.getState()
  if (after.scopeKey !== scopeKey || (selectionPolicy === 'CURRENT' && stableCanvasJson([...after.chatContextNodeIds].sort()) !== stableCanvasJson([...ids].sort()))) throw new Error('画布或选区已变化，请重新发送')
  if (fingerprint !== referenceFingerprint(ids)) throw new Error('引用节点在保存期间发生修改，请核对后重新发送')
  return ids.map((id): AssistantContextRef => {
    const node = after.nodes.find((item) => item.id === id)
    const version = runtime.getNodeVersion(id)
    if (!node || !version || !Number.isSafeInteger(version) || version < 1 || node.data.canvasTransient || runtime.isNodeBusy(id)) throw new Error('节点尚无可引用的已保存版本')
    return { kind: 'FREE_CANVAS', workspaceId: String(workspaceId), objectId: id, objectVersion: version, label: String(node.data.title || node.data.label || id) }
  })
}

export function locateCanvasAssistantTarget(runtime: CanvasProjectWorkspaceRuntime, scopeKey: string, ref: AssistantContextRef): void {
  const state = useStudioUiStore.getState()
  if (ref.kind !== 'FREE_CANVAS' || ref.workspaceId !== String(runtime.project?.workspace.workspaceId) || state.scopeKey !== scopeKey) throw new Error('该产物属于其他画布，无法在此定位')
  if (!state.nodes.some((node) => node.id === ref.objectId)) throw new Error('关联节点已删除或尚未同步，请先保存并刷新画布')
  state.selectNodes([ref.objectId])
  window.requestAnimationFrame(() => {
    if (useStudioUiStore.getState().scopeKey === scopeKey) window.dispatchEvent(new CustomEvent('studio:focus-node', { detail: { nodeId: ref.objectId } }))
  })
}

/** 基于工作区内真实运行投影；普通消息/事件水位不触发画布回读。 */
export function canvasAssistantSnapshotSignature(snapshot: AssistantSessionSnapshot, workspaceId: string): string | null {
  if (snapshot.workspaceId !== workspaceId) return null
  const terminal = new Set(['SUCCEEDED', 'COMPLETED', 'FAILED', 'CANCELLED', 'CANCELED'])
  const changes = [
    ...snapshot.artifacts.map((item) => 'artifact:' + item.artifactId + ':' + item.revision + ':' + item.status),
    ...snapshot.tasks.filter((item) => terminal.has(item.status)).map((item) => 'task:' + item.taskId + ':' + item.status),
    ...(snapshot.steps || []).filter((item) => terminal.has(item.status)).map((item) => 'step:' + item.stepId + ':' + item.status)
  ]
  return changes.length ? snapshot.sessionId + ':' + changes.sort().join('|') : null
}
