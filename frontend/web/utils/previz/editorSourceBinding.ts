import type { PrevizProject } from '@/types/previz'
import { captureEditorSnapshot } from './editorSnapshot'

/** Source HEAD and the visible scene are independent observations after an unfinished build.
 * Only a matching scene may be saved/bound as the current source's full document. */
export function previzEditorSourceBinding(project: Pick<PrevizProject, 'remoteWorkspaceId' | 'remoteSourceVersion'>, projectId: string, sourceVersion: string | null) {
  if (project.remoteWorkspaceId && project.remoteWorkspaceId !== projectId) throw new Error('三维项目已切换，请重新打开当前项目对话')
  const bindDocument = project.remoteSourceVersion === sourceVersion || project.remoteSourceVersion === undefined && sourceVersion === null
  return { bindDocument, sourceDiverged: !bindDocument }
}

export async function preparePrevizEditorSnapshot(options: {
  projectId: string; sourceVersion: string | null; getProject: () => PrevizProject; assertCurrent: () => void
  flushDocument: () => Promise<void>; flushLocal: () => Promise<void>; currentEditor: () => Promise<{ editorVersion: string | null }>
  selected: string[]; frame: number; selectedObjectId?: string
}) {
  const { projectId, sourceVersion, getProject, assertCurrent } = options
  assertCurrent()
  const binding = previzEditorSourceBinding(getProject(), projectId, sourceVersion)
  // A source-only failure must not force the old visible scene to overwrite/bind a newer source HEAD.
  await (binding.bindDocument ? options.flushDocument() : options.flushLocal())
  assertCurrent()
  const observed = getProject(), scene = observed.scene
  previzEditorSourceBinding(observed, projectId, sourceVersion)
  const expectedVersion = observed.editorVersion ?? (await options.currentEditor()).editorVersion ?? null
  assertCurrent()
  if (getProject().scene !== scene) throw new Error('读取编辑版本期间场景已变化，请重新发送')
  const state = await captureEditorSnapshot(scene, options.selected, options.frame, options.selectedObjectId)
  assertCurrent()
  const current = getProject(), finalBinding = previzEditorSourceBinding(current, projectId, sourceVersion)
  if (current.scene !== scene) throw new Error('读取场景快照期间场景已变化，请重新发送')
  if (binding.bindDocument && finalBinding.bindDocument && observed.remoteDocumentVersion === current.remoteDocumentVersion && current.remoteDocumentVersion) state.documentVersion = current.remoteDocumentVersion
  return { state, expectedVersion }
}
