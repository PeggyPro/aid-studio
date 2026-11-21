import type { AssistantContextRef, AssistantContextIdentity } from '~/types/assistant-api'
import type { EditorTextSelection } from '~/utils/quill/editorTextSelection'
import { flushStoryScriptAutoSave, getStoryScriptServerBaseline } from '~/utils/storyScriptPersistence'
import { assistantSha256 } from './assistantSha256'

export function assistantContextWire(ref: AssistantContextRef): Record<string, unknown> {
  if ((ref.kind === 'PREVIZ_IMAGE' || ref.kind === 'PREVIZ_EDITOR')) return { kind: ref.kind, previzProjectId: ref.previzProjectId, objectId: ref.objectId, contentHash: ref.contentHash, reason: ref.label }
  if (ref.kind === 'PREVIZ_PROJECT') return { kind: ref.kind, previzProjectId: ref.previzProjectId, sourceVersion: ref.sourceVersion, reason: ref.label }
  if (ref.kind === 'CANVAS_RESOURCE') return { kind: ref.kind, workspaceId: ref.workspaceId, resourceId: ref.resourceId, reason: ref.label }
  if (ref.kind === 'DOCUMENT') return { kind: ref.kind, documentId: ref.documentId, version: ref.version, contentHash: ref.contentHash, reason: ref.label }
  if (ref.kind === 'FREE_CANVAS') return { kind: ref.kind, workspaceId: ref.workspaceId, objectId: ref.objectId, version: String(ref.objectVersion), reason: ref.label }
  if (ref.kind === 'FLOW_ENTITY') {
    if (typeof ref.revision !== 'string' || !ref.revision.trim() || ref.revision.length > 64) throw new Error('流程对象缺少有效的已保存版本，请重新读取后引用')
    return { kind: ref.kind, projectId: ref.projectId, episodeId: ref.episodeId, entityType: ref.entityType, entityId: ref.entityId, version: ref.revision, reason: ref.label }
  }
  return { kind: ref.kind, scriptId: ref.scriptId, contentHash: ref.contentHash, selection: ref.selection, reason: ref.label }
}

/** 编辑器先完成既有保存；没有权威版本或选段变化时拒绝发送引用。 */
export async function resolveAssistantScriptContext(projectId: number, episodeId: number, selections: EditorTextSelection[] = []): Promise<AssistantContextRef[]> {
  const context = { projectId, episodeId }
  const saved = await flushStoryScriptAutoSave(context)
  if (saved === 'conflict' || saved === 'error') throw new Error('剧本尚未保存成功，请处理编辑器提示后重试')
  const baseline = getStoryScriptServerBaseline(context)
  if (!baseline.scriptId || !baseline.contentHash) throw new Error('当前剧本尚无可引用的已保存版本')
  const base: AssistantContextRef = { kind: 'SCRIPT', projectId: String(projectId), episodeId: String(episodeId), scriptId: String(baseline.scriptId), contentHash: baseline.contentHash, label: '当前已保存剧本' }
  if (!selections.length) return [base]
  return Promise.all(selections.map(async (selection) => {
    const start = selection.index, end = start + selection.length
    if (baseline.originalText.slice(start, end) !== selection.text) throw new Error('剧本选段与已保存原文不一致，请重新选取后发送')
    return { ...base, label: '剧本选段', selection: { start, end, selectedTextHash: assistantSha256(new TextEncoder().encode(selection.text)) } }
  }))
}

export function assistantContextIdentity(ref: AssistantContextRef): AssistantContextIdentity {
  if ((ref.kind === 'PREVIZ_IMAGE' || ref.kind === 'PREVIZ_EDITOR')) return { kind: ref.kind, previzProjectId: ref.previzProjectId, objectId: ref.objectId, contentHash: ref.contentHash }
  if (ref.kind === 'PREVIZ_PROJECT') return { kind: ref.kind, previzProjectId: ref.previzProjectId }
  if (ref.kind === 'CANVAS_RESOURCE') return { kind: ref.kind, workspaceId: ref.workspaceId, resourceId: ref.resourceId }
  if (ref.kind === 'DOCUMENT') return {kind:ref.kind,documentId:ref.documentId}
  if (ref.kind === 'FREE_CANVAS') return { kind: ref.kind, workspaceId: ref.workspaceId, objectId: ref.objectId }
  if (ref.kind === 'FLOW_ENTITY') return { kind: ref.kind, projectId: ref.projectId, entityType: ref.entityType, entityId: ref.entityId }
  return { kind: ref.kind, scriptId: ref.scriptId, ...(ref.selection ? { selection: { ...ref.selection } } : {}) }
}
