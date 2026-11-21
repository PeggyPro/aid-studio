import type { AssistantSeriesEditorAdapter } from '../../types/assistant-series'
import { useCreationStore } from '~/stores/creation'
import { mainAssistantTransport } from '~/utils/business/assistant'
import { createAssistantScriptAdoption } from '~/utils/assistant/assistantScriptAdoption'
import { storyScriptOriginalTextForApi } from '~/utils/htmlPlain'
import { flushStoryScriptAutoSave, getStoryScriptServerBaseline } from '~/utils/storyScriptPersistence'
import { userEpisodePage } from '~/utils/business/project'
import { coordinateAssistantRequest } from '~/utils/assistant/assistantRequestCoordinator'

/** Project-scope batch adoption only flushes/refreshes an actually opened, explicitly selected target. */
export function createAssistantMainSeries(projectId: number): AssistantSeriesEditorAdapter {
  if (!Number.isSafeInteger(projectId) || projectId < 1) throw new Error('项目身份无效')
  return {
    projectId: String(projectId),
    episodes: async (page) => {
      if (!Number.isSafeInteger(page) || page < 1) throw new Error('剧集分页无效')
      const identity = mainAssistantTransport.identityKey(), body = { projectId, pageNum: page, pageSize: 50 }
      return coordinateAssistantRequest({ key: 'MAIN:' + identity + ':series-episodes:' + projectId + ':' + page, body, cacheMs: 0, execute: async () => {
        const result = await userEpisodePage(body)
        if (identity !== mainAssistantTransport.identityKey()) throw new Error('登录身份已变化')
        const items = result.rows.map(row => {
          // Existing episode API uses JSON numbers; unsafe integers cannot be repaired client-side.
          if (!Number.isSafeInteger(row.id) || row.id < 1 || row.projectId !== projectId || !Number.isSafeInteger(row.episodeNo) || Number(row.episodeNo) < 1) throw new Error('剧集身份或顺序不可可靠读取')
          return { episodeId: String(row.id), episodeNo: row.episodeNo!, title: String(row.comicTitle || '') }
        })
        if (result.hasMore && !items.length) throw new Error('剧集分页未前进')
        return { items, nextPage: result.hasMore ? page + 1 : null }
      } })
    },
    prepare: async (request) => {
      const initial = useCreationStore.getState(), identity = mainAssistantTransport.identityKey()
      if (initial.currentProjectId !== projectId || initial.currentProjectType !== 'series') throw new Error('请先打开对应的剧集项目')
      const episodeId = initial.currentEpisodeId, content = initial.formData.storyScript.content
      const assertUnchanged = () => {
        const current = useCreationStore.getState()
        if (identity !== mainAssistantTransport.identityKey() || current.currentProjectId !== projectId || current.currentProjectType !== 'series'
          || current.currentEpisodeId !== episodeId || current.formData.storyScript.content !== content) throw new Error('编辑内容或当前剧集已改变，请重新预览')
      }
      const targetRevisions: Record<string, string> = {}
      if (request.mode === 'UPDATE_EXISTING' && episodeId && request.items.some(item => item.episodeId === String(episodeId))) {
        const context = { projectId, episodeId }
        const saved = await flushStoryScriptAutoSave(context)
        assertUnchanged()
        if (saved === 'error' || saved === 'conflict') throw new Error('当前编辑尚未保存成功，请先处理冲突')
        const baseline = getStoryScriptServerBaseline(context)
        if (storyScriptOriginalTextForApi(content) !== baseline.originalText || !Number.isSafeInteger(baseline.scriptId) || !Number.isSafeInteger(baseline.comicVersion)) throw new Error('当前剧本缺少可靠保存版本')
        targetRevisions[String(episodeId)] = 'script:' + baseline.scriptId + ':v' + baseline.comicVersion + ':' + baseline.contentHash
      }
      return { assertUnchanged, targetRevisions }
    },
    applied: async (receipt) => {
      const current = useCreationStore.getState()
      if (receipt.projectId !== String(projectId) || current.currentProjectId !== projectId || current.currentProjectType !== 'series') return '整批采用已保存，请在对应项目查看。'
      const target = receipt.items.find(item => item.episodeId === String(current.currentEpisodeId))
      if (!target || !current.currentEpisodeId) return '整批采用已保存；新增或更新剧集可从项目剧集列表查看。'
      return createAssistantScriptAdoption(projectId, current.currentEpisodeId).applied({
        adoptionId: target.adoptionId, artifactId: target.artifactId, targetKind: 'SCRIPT', targetObjectId: target.targetObjectId,
        targetRevision: target.targetRevision, status: 'SUCCEEDED', receipt: { ...target }
      }, { expectedTargetRevision: target.previousTargetRevision })
    }
  }
}
