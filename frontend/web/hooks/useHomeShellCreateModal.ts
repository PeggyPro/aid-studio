import { message } from 'antd'
import { useRouter } from 'next/navigation'
import { create } from 'zustand'
import { useCreationStore } from '~/stores/creation'
import type { GlobalSettingData } from '~/types'
import { CREATE_FIRST_STEP_DEFAULTS } from '~/utils/createFirstStepDefaults'
import { CREATE_SERIES_EPISODE_LIST_PATH, CREATE_SERIES_SCRIPT_UPLOAD_PATH, creationStepToRoutePath } from '~/utils/createFlowRoutes'
import { storeHomeScreenplayRequest, type HomeScreenplayInput } from '~/utils/homeComposerScreenplay'
import { creationStepAdvance, userScriptSave, userScriptSplitConfirm, userScriptSplitPreview } from '~/utils/businessApi'
import { apiErrorMessage } from '~/utils/apiError'
import {
  createStudioFlowProject,
  StudioFlowProjectValidationError
} from '~/utils/studio/studioFlowProjectCreate'

interface HomeShellCreateModalState {
  showCreateFirstStepModal: boolean
  createConfirmLoading: boolean
  /** 为 true 时弹窗左侧作品类型跟随父级（我的作品页按 tab）；首页我要创作为 false，固定默认剧集 */
  syncProjectTypeFromParent: boolean
  /** 首页「制作大片 / 制作连续剧」入口：隐藏作品类型，仅按入口默认电影或剧集 */
  hideProjectType: boolean
  postCreateDestination: 'steps' | 'choose'
  creationTitleDraft: string
  creationProjectTypeDraft: 'movie' | 'series'
  creationGlobalSettingDraft: GlobalSettingData
}

function cloneStoreGlobalSetting(): GlobalSettingData {
  return JSON.parse(
    JSON.stringify(useCreationStore.getState().formData.globalSetting)
  ) as GlobalSettingData
}

/** 首页壳层（案例广场 / 我的作品 / 资产库）共用的「创建作品第一步」弹窗状态（原 Vue 模块级单例） */
const useHomeShellCreateModalStore = create<HomeShellCreateModalState>(() => ({
  showCreateFirstStepModal: false,
  createConfirmLoading: false,
  syncProjectTypeFromParent: false,
  hideProjectType: false,
  postCreateDestination: 'steps',
  creationTitleDraft: '未命名作品',
  creationProjectTypeDraft: 'series',
  creationGlobalSettingDraft: cloneStoreGlobalSetting()
}))

function setShowCreateFirstStepModal(open: boolean) {
  useHomeShellCreateModalStore.setState({ showCreateFirstStepModal: open })
}

function setCreationTitleDraft(title: string) {
  useHomeShellCreateModalStore.setState({ creationTitleDraft: title })
}

function setCreationProjectTypeDraft(type: 'movie' | 'series') {
  useHomeShellCreateModalStore.setState({ creationProjectTypeDraft: type })
}

function syncCreateDraftFromStore() {
  useHomeShellCreateModalStore.setState({
    creationTitleDraft: useCreationStore.getState().workTitle || '未命名作品',
    creationGlobalSettingDraft: cloneStoreGlobalSetting(),
    creationProjectTypeDraft: CREATE_FIRST_STEP_DEFAULTS.projectType
  })
}

function updateGlobalSettingDraft(value: GlobalSettingData) {
  useHomeShellCreateModalStore.setState({ creationGlobalSettingDraft: value })
}

/** 仅更新风格库字段，避免冲掉左侧基本信息草稿 */
function patchGlobalSettingDraftStyle(
  patch: Pick<
    GlobalSettingData,
    'selectedStyle' | 'myStyles' | 'style' | 'styleSelectionTouched' | 'styleLocked'
  >
) {
  const draft = useHomeShellCreateModalStore.getState().creationGlobalSettingDraft
  useHomeShellCreateModalStore.setState({
    creationGlobalSettingDraft: {
      ...draft,
      selectedStyle: patch.selectedStyle,
      myStyles: patch.myStyles,
      style: patch.style,
      styleSelectionTouched: patch.styleSelectionTouched,
      styleLocked: patch.styleLocked
    }
  })
}

function updateGlobalSettingDraftField<K extends keyof GlobalSettingData>(
  key: K,
  value: GlobalSettingData[K]
) {
  const draft = useHomeShellCreateModalStore.getState().creationGlobalSettingDraft
  useHomeShellCreateModalStore.setState({
    creationGlobalSettingDraft: {
      ...draft,
      [key]: value
    }
  })
}

function openCreateModal(options?: {
  worksTab?: 'film' | 'series'
  hideProjectType?: boolean
  postCreateDestination?: 'steps' | 'choose'
}) {
  const base = cloneStoreGlobalSetting()
  const d = CREATE_FIRST_STEP_DEFAULTS
  // 新建作品不带入上次/当前作品的风格；selectedStyle 置空后由风格库默认选中精选第一项
  const next: Partial<HomeShellCreateModalState> = {
    creationTitleDraft: '未命名作品',
    hideProjectType: Boolean(options?.hideProjectType),
    postCreateDestination: options?.postCreateDestination ?? 'steps',
    creationGlobalSettingDraft: {
      ...base,
      scriptType: d.scriptType,
      modelStrategy: d.modelStrategy,
      creationMode: d.creationMode,
      selectedStyle: null,
      style: '',
      styleSelectionTouched: false,
      styleLocked: false
    }
  }
  if (options?.worksTab) {
    next.syncProjectTypeFromParent = true
    next.creationProjectTypeDraft = options.worksTab === 'series' ? 'series' : 'movie'
  } else {
    next.syncProjectTypeFromParent = false
    next.creationProjectTypeDraft = d.projectType
  }
  next.showCreateFirstStepModal = true
  useHomeShellCreateModalStore.setState(next)
}

async function runCreateConfirm(
  router: { push: (href: string) => void },
  options?: {
    navigateDelayMs?: number
    input?: HomeScreenplayInput
  }
) {
  const state = useHomeShellCreateModalStore.getState()
  if (state.createConfirmLoading) return
  useHomeShellCreateModalStore.setState({ createConfirmLoading: true })
  try {
    const finalTitle = (state.creationTitleDraft || '').trim() || '未命名作品'
    const created = await createStudioFlowProject({
      title: finalTitle,
      projectType: state.creationProjectTypeDraft,
      globalSetting: state.creationGlobalSettingDraft
    })
    useCreationStore.getState().setCurrentStepIndex(1)
    const pid = String(created.projectId)
    const createQuery = `projectId=${pid}&id=${pid}`
    const input = options?.input
    if (input) {
      let destination = input.kind === 'series-script'
        ? `${CREATE_SERIES_SCRIPT_UPLOAD_PATH}?${createQuery}`
        : `${creationStepToRoutePath('story-script')}?${createQuery}&stepInitAdvance=1`
      try {
        if (input.kind === 'request') {
          storeHomeScreenplayRequest(created.projectId, input.text)
        } else if (input.kind === 'single-script') {
          await userScriptSave({ projectId: created.projectId, episodeId: 0, originalText: input.text })
          void message.success('剧本已导入')
        } else {
          const request = { projectId: created.projectId, scriptText: input.text, episodeKeyword: '第一集' }
          const preview = await userScriptSplitPreview(request)
          if (preview.totalEpisodes < 2 || preview.items.length !== preview.totalEpisodes || !preview.firstEpisodeNo) {
            throw new Error('未能确认分集，请在项目内重新解析')
          }
          const result = await userScriptSplitConfirm({ ...request, firstEpisodeNo: preview.firstEpisodeNo })
          const firstEpisodeId = result.episodes[0]?.episodeId
          if (!firstEpisodeId) throw new Error('未取得分集结果，请在项目内确认')
          destination = `${CREATE_SERIES_EPISODE_LIST_PATH}?${createQuery}&episodeId=${firstEpisodeId}`
          const store = useCreationStore.getState()
          store.setCurrentProjectContext({ projectId: created.projectId, episodeId: firstEpisodeId })
          store.setSeriesFlowEnteredStoryScript(true)
          // The import has committed even if the optional progress update fails.
          await creationStepAdvance({ projectId: created.projectId, episodeId: firstEpisodeId, completedStep: 2 }).catch(() => {})
          void message.success(`已导入 ${result.totalEpisodes} 集剧本`)
        }
      } catch (error) {
        const operation = input.kind === 'request' ? '需求交接失败' : '剧本导入失败'
        void message.error(apiErrorMessage(error, operation) + '。项目已创建，可在项目内继续。')
      }
      router.push(destination)
      useHomeShellCreateModalStore.setState({ createConfirmLoading: false, showCreateFirstStepModal: false })
      return
    }
    message.success(created.message || '项目创建成功')
    const navigateToSteps = () => {
      if (state.creationProjectTypeDraft === 'series') {
        router.push(`${CREATE_SERIES_SCRIPT_UPLOAD_PATH}?${createQuery}`)
      } else {
        router.push(`/create?${createQuery}&stepInitAdvance=1`)
      }
    }
    const delay = options?.navigateDelayMs ?? (state.postCreateDestination === 'choose' ? 250 : 2000)
    setTimeout(() => {
      useHomeShellCreateModalStore.setState({
        createConfirmLoading: false,
        showCreateFirstStepModal: false
      })
      if (state.postCreateDestination !== 'choose') {
        navigateToSteps()
        return
      }
      navigateToSteps()
    }, delay)
  } catch (error: unknown) {
    useHomeShellCreateModalStore.setState({ createConfirmLoading: false })
    if (error instanceof StudioFlowProjectValidationError) {
      message.warning(error.message)
      return
    }
    const backendMsg =
      error && typeof error === 'object' && 'msg' in error
        ? String((error as { msg?: string }).msg)
        : error instanceof Error
          ? error.message
          : ''
    message.error(backendMsg || '创建项目失败，请稍后重试')
  }
}

function applyComposerDraft(payload: {
  input: HomeScreenplayInput
  globalSetting: GlobalSettingData
}) {
  const d = CREATE_FIRST_STEP_DEFAULTS
  useHomeShellCreateModalStore.setState({
    syncProjectTypeFromParent: false,
    postCreateDestination: 'steps',
    creationProjectTypeDraft: payload.input.kind === 'series-script' ? 'series' : 'movie',
    creationTitleDraft: '未命名作品',
    creationGlobalSettingDraft: {
      ...payload.globalSetting,
      description: '',
      scriptType: d.scriptType,
      modelStrategy: d.modelStrategy,
      creationMode: d.creationMode
    }
  })
}

export type HomeShellCreateModalApi = ReturnType<typeof useHomeShellCreateModal>

/**
 * 首页壳层共用的「创建作品第一步」弹窗状态（单例）：
 * 组件内调用响应式读取；原 Vue 直接赋值的字段改为对应 setXxx 动作。
 */
export function useHomeShellCreateModal() {
  const router = useRouter()
  const state = useHomeShellCreateModalStore()

  return {
    ...state,
    setShowCreateFirstStepModal,
    setCreationTitleDraft,
    setCreationProjectTypeDraft,
    syncCreateDraftFromStore,
    updateGlobalSettingDraft,
    patchGlobalSettingDraftStyle,
    updateGlobalSettingDraftField,
    openCreateModal,
    handleCreateConfirm: () => runCreateConfirm(router),
    createFromComposer: (payload: {
      input: HomeScreenplayInput
      globalSetting: GlobalSettingData
    }) => {
      if (useHomeShellCreateModalStore.getState().createConfirmLoading) return
      applyComposerDraft(payload)
      return runCreateConfirm(router, { input: payload.input })
    }
  }
}
