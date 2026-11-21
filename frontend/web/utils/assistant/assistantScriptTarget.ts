import type { AssistantTargetRef } from '~/types/assistant-effects'
import { useCreationStore } from '~/stores/creation'
import { getStoryScriptServerBaseline } from '~/utils/storyScriptPersistence'
import { mainAssistantTransport } from '~/utils/business/assistant'

interface OpenScript {
  actorKey: string
  projectId: string
  episodeId: string
  scriptId: string
  /** ScriptRuntimeScopeAccess.resolved uses comicVersion decimal, not capability revision/contentRef. */
  comicVersion: string
  contentHash: string
}
export interface MainScriptLocatePorts {
  opened: () => OpenScript | null
  /** A read-only StoryScriptHandle.focusEditor or existing Flow selectStudioFlowStepNode bridge. */
  focusCurrentEditor: () => boolean
}

/** Consume only an authenticated SCRIPT effect; never infer or construct an effect. */
export function createMainScriptEffectLocator(ports: MainScriptLocatePorts, scope: { actorKey: string; projectId: string; episodeId: string }) {
  return (target: AssistantTargetRef, isCurrent: () => boolean): void => {
    const opened = ports.opened()
    if (!isCurrent() || !opened || target.kind !== 'SCRIPT' || opened.actorKey !== scope.actorKey || opened.projectId !== scope.projectId || opened.episodeId !== scope.episodeId
      || target.projectId !== scope.projectId || target.episodeId !== scope.episodeId || target.scriptId !== opened.scriptId) throw new Error('此目标不是当前打开的剧本，请从对应作品或集数查看')
    if (target.version !== opened.comicVersion || target.contentHash !== opened.contentHash) throw new Error('编辑器当前版本尚未同步该目标，已保留本地内容，请核对版本后重试')
    // Dirty text is deliberately untouched. No save, version switch, range replacement or navigation.
    if (!ports.focusCurrentEditor()) throw new Error('当前剧本编辑器尚未就绪')
  }
}

/** Concrete bridge for both existing Main entries; baseline stays read-only and no API is invoked. */
export function createAssistantScriptTarget(projectId: number, episodeId: number, focusCurrentEditor: () => boolean) {
  return (target: AssistantTargetRef, isCurrent: () => boolean): void => {
    if (!Number.isSafeInteger(projectId) || projectId < 1 || !Number.isSafeInteger(episodeId) || episodeId < 0) throw new Error('当前剧本范围无效')
    const actorKey = mainAssistantTransport.identityKey()
    createMainScriptEffectLocator({ focusCurrentEditor, opened: () => {
      const state = useCreationStore.getState(), baseline = getStoryScriptServerBaseline({ projectId, episodeId })
      if (!Number.isSafeInteger(baseline.scriptId) || baseline.scriptId < 1 || !Number.isSafeInteger(baseline.comicVersion) || baseline.comicVersion < 1) return null
      return { actorKey: mainAssistantTransport.identityKey(), projectId: String(state.currentProjectId), episodeId: String(state.currentProjectType === 'movie' ? 0 : state.currentEpisodeId),
        scriptId: String(baseline.scriptId), comicVersion: String(baseline.comicVersion), contentHash: baseline.contentHash }
    } }, { actorKey, projectId: String(projectId), episodeId: String(episodeId) })(target, isCurrent)
  }
}
