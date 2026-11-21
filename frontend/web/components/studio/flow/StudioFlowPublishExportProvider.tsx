'use client'

import {
  createContext,
  lazy,
  Suspense,
  useContext,
  useMemo,
  type ReactNode
} from 'react'
import dynamic from 'next/dynamic'
import type { CreateFlowGlobalSettingContext, CreateFlowShellContext } from '@/utils/createFlowInjection'
import { createFlowShellContext } from '@/utils/createFlowInjection'
import { EmbeddedRouteLikeProvider } from '@/hooks/useRouteLike'
import { useStudioFlowPublishExport } from '@/hooks/studio/useStudioFlowPublishExport'
import { useCreationStore } from '@/stores/creation'
import { useStudioFlowRuntime } from './StudioFlowRuntimeProvider'

const VideoPreview = dynamic(
  () => import('@/components/steps/VideoPreview').then((module) => ({
    default: module.VideoPreview
  })),
  { ssr: false }
)

const PublishCasePlazaModal = lazy(() => import('@/components/common/PublishCasePlazaModal'))

export type StudioFlowPublishExportValue = ReturnType<typeof useStudioFlowPublishExport>

const StudioFlowPublishExportContext = createContext<StudioFlowPublishExportValue | null>(null)

export function useStudioFlowPublishExportContext(): StudioFlowPublishExportValue | null {
  return useContext(StudioFlowPublishExportContext)
}

const noopGlobalSettingContext = {
  confirmLoading: false,
  titleDraft: '',
  projectTypeDraft: 'movie' as const,
  draft: {} as CreateFlowGlobalSettingContext['draft'],
  projectTypeLocked: true,
  showModal: false,
  syncFromStore: () => {},
  openModal: () => {},
  setTitleDraft: () => {},
  setProjectTypeDraft: () => {},
  updateField: () => {},
  patchStyle: () => {},
  save: async () => false,
  contentConfigLocked: false
} satisfies CreateFlowGlobalSettingContext

function StudioFlowPreviewExportHost({
  publishExport
}: {
  publishExport: StudioFlowPublishExportValue
}) {
  const runtime = useStudioFlowRuntime()
  const currentProjectId = useCreationStore((state) => state.currentProjectId)
  const currentEpisodeId = useCreationStore((state) => state.currentEpisodeId)
  const storyboardVideoPanels = useCreationStore((state) => state.formData.storyboardVideo.panels)
  const dubbingPanels = useCreationStore((state) => state.formData.dubbing.panels)
  const bgm = useCreationStore((state) => state.formData.dubbing.bgm)

  const previewScopeKey = `${currentProjectId ?? 'na'}:${currentEpisodeId ?? 'na'}`
  const previewRoutePath = useMemo(() => {
    const params = new URLSearchParams()
    if (currentProjectId) params.set('projectId', String(currentProjectId))
    if (currentEpisodeId != null) params.set('episodeId', String(currentEpisodeId))
    const query = params.toString()
    return query ? `/create/preview?${query}` : '/create/preview'
  }, [currentEpisodeId, currentProjectId])

  const shellContext = useMemo<CreateFlowShellContext>(() => ({
    goToStep: () => {},
    stopExtractAssets: () => {},
    openExtractModalFromScp: () => {},
    openContinueExtractModal: () => {},
    dismissScriptChangeLightBanner: () => {},
    jumpToStoryboardScriptFromVideo: () => {},
    clearStoryboardScriptJumpTooltip: () => {},
    storyboardScriptTooltipTargetIndex: null,
    storyboardScriptTooltipKey: 0,
    syncVideoAndDubbingFromScriptPanels: () => {},
    setDubbingGenerating: () => {},
    storyboardListLoading: false,
    storyboardListSyncReady: true,
    globalSetting: noopGlobalSettingContext,
    openProjectGenConfig: () => {},
    registerPreviewExportBridge: publishExport.registerPreviewExportBridge,
    notifyPreviewExportSuccess: publishExport.handlePreviewExportSuccess,
    embeddedEpisodeId: runtime?.episodeId ?? null
  }), [
    publishExport.handlePreviewExportSuccess,
    publishExport.registerPreviewExportBridge,
    runtime?.episodeId
  ])

  return (
    <EmbeddedRouteLikeProvider path={previewRoutePath}>
      <createFlowShellContext.Provider value={shellContext}>
        <div className="studio-flow-preview-export-host" aria-hidden="true">
          <VideoPreview
            key={previewScopeKey}
            storyboardVideoPanels={storyboardVideoPanels}
            dubbingPanels={dubbingPanels}
            bgm={bgm}
          />
        </div>
      </createFlowShellContext.Provider>
    </EmbeddedRouteLikeProvider>
  )
}

export function StudioFlowPublishExportProvider({
  children,
  pageReady
}: {
  children: ReactNode
  pageReady: boolean
}) {
  const publishExport = useStudioFlowPublishExport(pageReady)
  const currentProjectId = useCreationStore((state) => state.currentProjectId)

  return (
    <StudioFlowPublishExportContext.Provider value={publishExport}>
      {children}
      <StudioFlowPreviewExportHost publishExport={publishExport} />
      <Suspense fallback={null}>
        <PublishCasePlazaModal
          open={publishExport.publishCasePlazaModalOpen}
          projectId={currentProjectId}
          initialProjectDesc={publishExport.publishInitialProjectDesc}
          onOpenChange={publishExport.setPublishModalOpen}
          onSuccess={(payload) => void publishExport.onPublishCasePlazaMetaSuccess(payload)}
        />
      </Suspense>
    </StudioFlowPublishExportContext.Provider>
  )
}
