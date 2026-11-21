import type { AssistantAdoptionAdapter, AssistantAdoptionTarget } from '~/types/assistant-artifact'
import { useCreationStore } from '~/stores/creation'
import { mainAssistantTransport } from '~/utils/business/assistant'
import { userScriptDetailByProject } from '~/utils/businessApi'
import { resolveStoryScriptEditorHtmlAfterApiLoad, storyScriptOriginalTextForApi } from '~/utils/htmlPlain'
import { flushStoryScriptAutoSave, getStoryScriptAutoSaveCoordinator, getStoryScriptServerBaseline, setStoryScriptServerBaseline } from '~/utils/storyScriptPersistence'

/** 仅适配当前已打开的剧本。服务端采用后先核验编辑器及基线，绝不把新手改当成旧快照覆盖。 */
export function createAssistantScriptAdoption(projectId: number, episodeId: number, afterRefresh?: () => Promise<void>): AssistantAdoptionAdapter {
  const context = { projectId, episodeId }
  const sameScope = () => {
    const state = useCreationStore.getState()
    return state.currentProjectId === projectId && (state.currentProjectType === 'movie' ? 0 : state.currentEpisodeId) === episodeId
  }
  const supports = (target: AssistantAdoptionTarget) => target.targetKind === 'SCRIPT'
    && target.targetObjectId === 'project:' + projectId + ':episode:' + episodeId
    && String(target.requiredContext.projectId) === String(projectId) && String(target.requiredContext.episodeId) === String(episodeId)
  return {
    supports,
    prepare: async (target) => {
      if (!supports(target) || !sameScope()) throw new Error('采用目标不属于当前打开的剧本')
      const content = useCreationStore.getState().formData.storyScript.content
      const assertUnchanged = () => {
        if (!sameScope() || useCreationStore.getState().formData.storyScript.content !== content) throw new Error('当前剧本已改变，请重新检查后采用')
      }
      const result = await flushStoryScriptAutoSave(context)
      assertUnchanged()
      if (result === 'error' || result === 'conflict') throw new Error('当前剧本尚未保存成功，请先处理编辑器提示')
      const baseline = getStoryScriptServerBaseline(context)
      if (storyScriptOriginalTextForApi(content) !== baseline.originalText) throw new Error('当前编辑尚未保存，请稍后重试')
      return { context: {}, targetRevision: 'script:' + baseline.scriptId + ':v' + baseline.comicVersion + ':' + baseline.contentHash, assertUnchanged }
    },
    applied: async (result, command) => {
      if (result.status !== 'SUCCEEDED' || result.targetKind !== 'SCRIPT'
        || result.targetObjectId !== 'project:' + projectId + ':episode:' + episodeId) return
      if (!sameScope()) return '剧本已在服务端采用；当前页面已切换，请从目标作品查看。'
      const content = useCreationStore.getState().formData.storyScript.content
      const baseline = getStoryScriptServerBaseline(context)
      const baselineRevision = 'script:' + baseline.scriptId + ':v' + baseline.comicVersion + ':' + baseline.contentHash
      if (storyScriptOriginalTextForApi(content) !== baseline.originalText
        || (baselineRevision !== command.expectedTargetRevision && baselineRevision !== result.targetRevision)) {
        return '服务端已采用；当前新修改已保留，请在编辑器核对服务器版本后继续。'
      }
      const identity = mainAssistantTransport.identityKey()
      const row = await userScriptDetailByProject(context, { force: true })
      if (identity !== mainAssistantTransport.identityKey()) return
      if (!sameScope() || useCreationStore.getState().formData.storyScript.content !== content) return '服务端已采用；读取期间的新修改已保留。'
      const revision = row ? 'script:' + row.id + ':v' + row.comicVersion + ':' + row.contentHash : ''
      if (!row || revision !== result.targetRevision) return '剧本已采用，目标又有更新；请从编辑器历史版本核对。'
      getStoryScriptAutoSaveCoordinator(context).updateContent(String(row.originalText ?? ''), false)
      setStoryScriptServerBaseline(context, row)
      useCreationStore.getState().updateFormData({ storyScript: { content: resolveStoryScriptEditorHtmlAfterApiLoad(String(row.originalText ?? ''), content) } })
      await afterRefresh?.()
      return '已采用到当前剧本。'
    }
  }
}
