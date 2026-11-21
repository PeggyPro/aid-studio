'use client'

import dynamic from 'next/dynamic'
import { useCallback, useEffect, useState } from 'react'
import { Button, Drawer, Skeleton } from 'antd'
import { useCreationStore } from '@/stores/creation'
import type { CreationStep } from '@/types'
import type { PreviewExportBridge, CreateFlowGlobalSettingContext, CreateFlowShellContext } from '@/utils/createFlowInjection'
import { createFlowShellContext } from '@/utils/createFlowInjection'
import { CREATE_FLOW_STEP_ORDER } from '@/utils/createFlowRoutes'
import { EmbeddedRouteLikeProvider } from '@/hooks/useRouteLike'
import { useCreateFlowStoryboardSync } from '@/hooks/useCreateFlowStoryboardSync'
import { useCreateFlowExtractAgents } from '@/hooks/useCreateFlowExtractAgents'
import { useCreateFlowGlobalSettingModal } from '@/hooks/useCreateFlowGlobalSettingModal'
import { useScriptChangeExtractGate } from '@/hooks/useScriptChangeExtractGate'
import {
  hasImmediateProjectConfigAssetLock,
  hasProjectSceneCharacterPropAssets
} from '@/utils/projectConfigAssetGuard'
import { requestCreateFlowStepModal } from '@/utils/createFlowStepModalIntent'
import { studioFlowEmbeddedRoutePath } from '@/utils/studio/studioFlowEditorRoute'
import { ExtractAgentModal } from '@/components/steps/ExtractAgentModal'
import { ProjectGenConfigModal } from '@/components/steps/ProjectGenConfigModal'
import { useStudioFlowRuntime } from './StudioFlowRuntimeProvider'
import './studio-flow-editor.css'

const stepLoading = () => <div className="studio-flow-editor__loading"><Skeleton active paragraph={{ rows: 8 }} /></div>
const GlobalSettingStep = dynamic(() => import('@/app/create/global-setting/page'), { ssr: false, loading: stepLoading })
const StoryScriptStep = dynamic(() => import('@/app/create/story-script/page'), { ssr: false, loading: stepLoading })
const SceneCharacterStep = dynamic(() => import('@/app/create/scene-character/page'), { ssr: false, loading: stepLoading })
const StoryboardScriptStep = dynamic(() => import('@/app/create/storyboard-script/page'), { ssr: false, loading: stepLoading })
const StoryboardVideoStep = dynamic(() => import('@/app/create/storyboard-video/page'), { ssr: false, loading: stepLoading })
const DubbingStep = dynamic(() => import('@/app/create/dubbing/page'), { ssr: false, loading: stepLoading })
const PreviewStep = dynamic(() => import('@/app/create/preview/page'), { ssr: false, loading: stepLoading })

const STEP_TITLES: Record<CreationStep, string> = {
  'global-setting': '项目配置',
  'story-script': '剧本创作',
  'scene-character': '素材准备',
  'storyboard-script': '分镜设计',
  'storyboard-video': '视频生成',
  dubbing: '音画同步',
  preview: '成品预览'
}

export function StudioFlowEditorDrawer() {
  const runtime = useStudioFlowRuntime()
  const activeStep = runtime?.activeEditorStep ?? null
  if (!runtime) return null
  const routePath = studioFlowEmbeddedRoutePath(runtime.editorOpen, activeStep)
  return (
    <EmbeddedRouteLikeProvider path={routePath}>
      <StudioFlowEditorDrawerHost />
    </EmbeddedRouteLikeProvider>
  )
}

/** 路由敏感的原流程 Shell hooks 必须在 embedded route Provider 内执行。 */
function StudioFlowEditorDrawerHost() {
  const runtime = useStudioFlowRuntime()
  const activeStep = runtime?.activeEditorStep ?? null
  const currentProjectId = useCreationStore((state) => state.currentProjectId)
  const currentEpisodeId = useCreationStore((state) => state.currentEpisodeId)
  const currentProjectType = useCreationStore((state) => state.currentProjectType)
  const showExtractAgentModal = useCreationStore((state) => state.showExtractAgentModal)
  const extractAgents = useCreationStore((state) => state.extractAgents)
  const extractModelCodes = useCreationStore((state) => state.extractModelCodes)
  const extractModalActionMode = useCreationStore((state) => state.extractModalActionMode)
  const [showProjectGenConfig, setShowProjectGenConfig] = useState(false)
  const [dubbingGenerating, setDubbingGenerating] = useState(false)
  const [previewBridge, setPreviewBridge] = useState<PreviewExportBridge | null>(null)
  const storyboardSync = useCreateFlowStoryboardSync()
  const extract = useCreateFlowExtractAgents()
  const scriptChangeGate = useScriptChangeExtractGate()
  const globalSetting = useCreateFlowGlobalSettingModal()
  const syncGlobalSettingDraftFromStore = globalSetting.syncGlobalSettingDraftFromStore
  const [assetConfigLocked, setAssetConfigLocked] = useState(false)

  useEffect(() => {
    if (activeStep === 'global-setting') syncGlobalSettingDraftFromStore()
  }, [activeStep, syncGlobalSettingDraftFromStore])

  useEffect(() => {
    const pid = currentProjectId
    if (!pid) {
      setAssetConfigLocked(false)
      return
    }
    const creation = useCreationStore.getState()
    setAssetConfigLocked(hasImmediateProjectConfigAssetLock({
      styleLocked: creation.formData.globalSetting.styleLocked === true,
      sceneCharacter: creation.formData.sceneCharacter
    }))
    let cancelled = false
    void hasProjectSceneCharacterPropAssets(pid, currentEpisodeId ?? 0, {
      styleLocked: creation.formData.globalSetting.styleLocked === true,
      sceneCharacter: creation.formData.sceneCharacter
    }).then((hasAssets) => {
      if (!cancelled) setAssetConfigLocked(hasAssets)
    })
    return () => {
      cancelled = true
    }
  }, [activeStep, currentEpisodeId, currentProjectId])

  const goToStep = useCallback((stepIndex: number) => {
    const step = CREATE_FLOW_STEP_ORDER[stepIndex]
    if (step) runtime?.openEditor(step)
  }, [runtime])

  const globalSettingContext: CreateFlowGlobalSettingContext = {
    confirmLoading: globalSetting.globalSettingConfirmLoading,
    titleDraft: globalSetting.creationTitleDraft,
    projectTypeDraft: globalSetting.globalSettingProjectTypeDraft,
    draft: globalSetting.creationGlobalSettingDraft,
    projectTypeLocked: currentProjectType === 'movie' || currentProjectType === 'series',
    showModal: activeStep === 'global-setting',
    syncFromStore: globalSetting.syncGlobalSettingDraftFromStore,
    openModal: () => runtime?.openEditor('global-setting'),
    setTitleDraft: globalSetting.setCreationTitleDraft,
    setProjectTypeDraft: globalSetting.setGlobalSettingProjectTypeDraft,
    updateField: globalSetting.updateGlobalSettingDraftField,
    patchStyle: globalSetting.patchGlobalSettingDraftStyle,
    save: async () => {
      const saved = await globalSetting.handleGlobalSettingConfirm({
        navigateAfterSave: false,
        contentConfigLocked: assetConfigLocked
      })
      if (saved) await runtime?.refresh()
      return saved
    },
    contentConfigLocked: assetConfigLocked,
    contentConfigLockReason: assetConfigLocked ? 'assets' : undefined
  }

  const contextValue: CreateFlowShellContext = {
    goToStep,
    stopExtractAssets: extract.stopExtractAssets,
    openExtractModalFromScp: extract.handleOpenExtractModalFromScp,
    openContinueExtractModal: () => {
      extract.setExtractModalScope('all')
      scriptChangeGate.openContinueExtractModal()
    },
    dismissScriptChangeLightBanner: scriptChangeGate.dismissLightBanner,
    jumpToStoryboardScriptFromVideo: (panelIndex) => {
      requestCreateFlowStepModal('storyboard-image', panelIndex)
      runtime?.openEditor('storyboard-script')
    },
    clearStoryboardScriptJumpTooltip: storyboardSync.clearStoryboardScriptJumpTooltip,
    storyboardScriptTooltipTargetIndex: storyboardSync.storyboardScriptTooltipTargetIndex,
    storyboardScriptTooltipKey: storyboardSync.storyboardScriptTooltipKey,
    syncVideoAndDubbingFromScriptPanels: storyboardSync.syncVideoAndDubbingFromScriptPanels,
    setDubbingGenerating,
    storyboardListLoading: false,
    storyboardListSyncReady: true,
    globalSetting: globalSettingContext,
    openProjectGenConfig: () => setShowProjectGenConfig(true),
    registerPreviewExportBridge: (bridge) => {
      setPreviewBridge(bridge)
    },
    notifyPreviewExportSuccess: () => {
      void runtime?.refresh()
    },
    registerStoryScriptFlush: runtime?.registerStoryScriptFlush,
    embeddedEpisodeId: runtime?.episodeId
  }

  if (!runtime) return null
  const footer = activeStep === 'global-setting' ? (
    <Button
      type="primary"
      loading={globalSetting.globalSettingConfirmLoading}
      onClick={() => void globalSettingContext.save()}
    >
      保存项目配置
    </Button>
  ) : activeStep === 'preview' && previewBridge ? (
    <Button
      type="primary"
      loading={previewBridge.exporting}
      onClick={() => void previewBridge.exportFullVideo()}
    >
      导出完整视频
    </Button>
  ) : null

  return (
    <>
      <Drawer
        open={runtime.editorOpen}
        title={activeStep ? STEP_TITLES[activeStep] : ''}
        placement="right"
        width="min(1480px, calc(100vw - 72px))"
        className="studio-flow-editor-drawer"
        rootClassName="studio-flow-editor-drawer-root"
        footer={footer ? <div className="studio-flow-editor__footer">{footer}</div> : null}
        onClose={() => { void runtime.closeEditor() }}
      >
        {activeStep ? (
          <createFlowShellContext.Provider value={contextValue}>
            <div
              className={`studio-flow-editor__content preview-content create-step-route step-${activeStep}`}
              data-dubbing-generating={dubbingGenerating ? 'true' : 'false'}
            >
              <StepContent key={`${activeStep}:${runtime.editorMountRevision}`} step={activeStep} />
            </div>
          </createFlowShellContext.Provider>
        ) : null}
      </Drawer>
      <ExtractAgentModal
        open={showExtractAgentModal}
        agents={extractAgents}
        modelCodes={extractModelCodes}
        scope={extract.extractModalScope}
        actionMode={extractModalActionMode}
        onOpenChange={(open) => useCreationStore.setState({ showExtractAgentModal: open })}
        onAgentsChange={extract.updateExtractAgents}
        onModelCodesChange={extract.updateExtractModelCodes}
        onStart={extract.startExtractAssets}
      />
      <ProjectGenConfigModal
        open={showProjectGenConfig}
        projectId={currentProjectId}
        episodeId={currentEpisodeId}
        onOpenChange={setShowProjectGenConfig}
        onSaved={() => void runtime.refresh()}
      />
    </>
  )
}

function StepContent({ step }: { step: CreationStep }) {
  if (step === 'global-setting') return <GlobalSettingStep />
  if (step === 'story-script') return <StoryScriptStep />
  if (step === 'scene-character') return <SceneCharacterStep />
  if (step === 'storyboard-script') return <StoryboardScriptStep />
  if (step === 'storyboard-video') return <StoryboardVideoStep />
  if (step === 'dubbing') return <DubbingStep />
  return <PreviewStep />
}
