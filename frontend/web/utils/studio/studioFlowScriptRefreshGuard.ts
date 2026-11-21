/** 网络读取只可刷新同一目标、同一请求且未被本地编辑/新权威版本推进的剧本。 */
export interface StudioFlowScriptRefreshGuard {
  projectId: number
  episodeId: number | null
  requestRevision: number
  editorContent: string
  baselineRevision: string
  preserveUnsaved: boolean
}
export function canApplyStudioFlowScriptRefresh(start: StudioFlowScriptRefreshGuard, current: Omit<StudioFlowScriptRefreshGuard, 'preserveUnsaved'>): boolean {
  return !start.preserveUnsaved && start.projectId === current.projectId && start.episodeId === current.episodeId
    && start.requestRevision === current.requestRevision && start.editorContent === current.editorContent
    && start.baselineRevision === current.baselineRevision
}
