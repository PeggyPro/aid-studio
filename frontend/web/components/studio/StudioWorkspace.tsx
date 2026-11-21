'use client'

import { useEffect } from 'react'
import { readCanvasHomeAssistantDraft, readCanvasHomeAssistantLaunch } from '@/utils/canvas/canvasHomeAssistantDraft'
import dynamic from 'next/dynamic'
import { Button, Result, Skeleton } from 'antd'
import { useStudioWorkspace } from '@/hooks/studio/useStudioWorkspace'
import { useStudioUiStore } from '@/stores/studioUi'
import { StudioCanvas } from './canvas/StudioCanvas'
import { StudioLeftPanel } from './shell/StudioLeftPanel'
import { StudioRightPanel } from './shell/StudioRightPanel'
import { StudioTopbar } from './shell/StudioTopbar'
import { StudioRechargeHost } from './shell/StudioRechargeHost'
import type { StudioMode } from '@/types/studio'
import { StudioFirstVisitGuide } from './StudioFirstVisitGuide'
import { DirectorStageBoundary, DirectorStageLoading } from './director/DirectorStageTransition'
import { StudioFlowRuntimeProvider } from './flow/StudioFlowRuntimeProvider'
import { useStudioPanelPresence } from '@/hooks/studio/useStudioPanelPresence'
import { StudioStoryScriptAgentProvider } from './StudioStoryScriptAgentProvider'
import { StudioSkillWorkspaceProvider } from './skills/StudioSkillWorkspaceProvider'
import { StudioFreeThemeProvider } from './theme/StudioFreeThemeProvider'
import { StudioChromeProvider } from './shell/StudioChromeSlot'
import { StudioCanvasLiquidOrb } from './canvas/StudioCanvasLiquidOrb'
import { StudioCanvasEmptyOrb } from './shell/StudioCanvasEmptyOrb'
import { useStudioRemoteTaskRecovery } from '@/hooks/studio/useStudioRemoteTaskRecovery'
import { CanvasProjectRuntimeProvider } from './free/CanvasProjectRuntimeContext'
import { StudioReadOnlyTopbar } from './shell/StudioReadOnlyTopbar'
import { canvasPublicationHref } from '@/utils/canvas/canvasPublicationNavigation'
import type { CanvasPublicationSource } from '@/hooks/studio/useCanvasPublicationWorkspace'

const DirectorStage = dynamic(() => import('./director/DirectorStage'), {
  ssr: false,
  loading: DirectorStageLoading
})
const StudioSmartEditEditor = dynamic(() => import('./canvas/StudioSmartEditEditor'), { ssr: false })

export function StudioWorkspace(props: Parameters<typeof StudioWorkspaceContent>[0]) {
  return (
    <StudioFreeThemeProvider enabled={props.studioMode === 'free'} syncWithSiteTheme={props.studioMode === 'free'}>
      <StudioChromeProvider><StudioWorkspaceContent {...props} /></StudioChromeProvider>
    </StudioFreeThemeProvider>
  )
}

function StudioWorkspaceContent({
  scopeKey,
  displayTitle,
  perfNodeCount,
  studioMode,
  projectId,
  canvasProjectId,
  publication,
  episodeId,
  readOnly = false
}: {
  scopeKey: string
  displayTitle: string
  perfNodeCount?: number
  studioMode: StudioMode
  projectId: number | null
  canvasProjectId?: string | null
  publication?: CanvasPublicationSource
  episodeId: number | null
  readOnly?: boolean
}) {
  const loading = useStudioUiStore((state) => state.loading)
  const hydrated = useStudioUiStore((state) => state.hydrated)
  const hydratedScope = useStudioUiStore((state) => state.scopeKey)
  const loadError = useStudioUiStore((state) => state.loadError)
  const title = useStudioUiStore((state) => state.title)
  const setTitle = useStudioUiStore((state) => state.setTitle)
  const directorOpenNodeId = useStudioUiStore((state) => state.directorOpenNodeId)
  const closeDirector = useStudioUiStore((state) => state.closeDirector)
  const rightCollapsed = useStudioUiStore((state) => state.rightCollapsed)
  const { saveNow, canvasRuntime, publicationLoading } = useStudioWorkspace(scopeKey, {
    perfNodeCount,
    studioMode,
    readOnly,
    canvasProjectId,
    publication
  })
  const effectiveReadOnly = readOnly || Boolean(publication) || Boolean(canvasRuntime && !canvasRuntime.canEdit)
  useStudioRemoteTaskRecovery(!effectiveReadOnly)
  const { rendered: rightPanelRendered } = useStudioPanelPresence(!rightCollapsed)
  const rightOverlayOpen = studioMode === 'flow' && rightPanelRendered
  const loadedCanvasProjectId = canvasRuntime?.project?.projectId

  useEffect(() => {
    if (studioMode === 'free' && hydrated && !loading && hydratedScope === scopeKey
      && canvasProjectId && String(loadedCanvasProjectId) === canvasProjectId
      && (readCanvasHomeAssistantLaunch(canvasProjectId) || readCanvasHomeAssistantDraft(canvasProjectId))) {
      useStudioUiStore.getState().setRightCollapsed(false)
    }
  }, [studioMode, hydrated, loading, hydratedScope, scopeKey, canvasProjectId, loadedCanvasProjectId])

  useEffect(() => {
    if (!shouldApplyStudioWorkspaceDisplayTitle(studioMode, hydrated, title, displayTitle)) return
    setTitle(displayTitle)
  }, [displayTitle, hydrated, setTitle, studioMode, title])

  // Topbar / 侧栏在 React Flow 画布之外；此处若不拦截，Ctrl/⌘+滚轮会触发浏览器整页缩放。
  // 画布内缩放仍由 React Flow（zoomActivationKeyCode=Control）处理，preventDefault 只取消浏览器默认行为。
  useEffect(() => {
    const preventBrowserPageZoom = (event: WheelEvent) => {
      if (event.ctrlKey || event.metaKey) event.preventDefault()
    }
    window.addEventListener('wheel', preventBrowserPageZoom, { capture: true, passive: false })
    return () => window.removeEventListener('wheel', preventBrowserPageZoom, { capture: true })
  }, [])

  if (loading || publicationLoading || hydratedScope !== scopeKey) {
    return (
      <div className="studio-loading-screen">
        <div className="studio-loading-screen__brand">AI·D <span>STUDIO</span></div>
        <Skeleton active paragraph={{ rows: 5 }} title={{ width: 260 }} />
      </div>
    )
  }

  if (loadError) {
    return (
      <div className="studio-loading-screen">
        <Result
          status="error"
          title="Studio 工作区加载失败"
          subTitle={loadError}
          extra={<Button type="primary" onClick={() => window.location.reload()}>重新加载</Button>}
        />
      </div>
    )
  }

  const workspace = (
    <div
      className={`studio-workspace studio-workspace--${studioMode}${rightOverlayOpen ? ' studio-workspace--right-panel-open' : ''}${effectiveReadOnly ? ' studio-workspace--public-readonly' : ''} h-dvh w-full max-w-full overflow-hidden`}
      data-readonly-project-preview={readOnly ? 'true' : undefined}
    >
      {effectiveReadOnly ? <StudioReadOnlyTopbar title={title} backHref={publication
        ? canvasPublicationHref(publication.publicationId, { history: publication.history }) : '/canvas'} />
        : <StudioTopbar onSave={saveNow} canvasRuntime={canvasRuntime} />}
      <div className="studio-workspace__body">
        {!effectiveReadOnly ? <StudioLeftPanel /> : null}
        <StudioCanvas onSave={saveNow} readOnly={effectiveReadOnly} />
        {!effectiveReadOnly ? <StudioRightPanel canvasProjectId={canvasProjectId} /> : null}
      </div>
      {studioMode === 'free' && !effectiveReadOnly ? (
        <StudioCanvasLiquidOrb key={scopeKey} scopeKey={scopeKey} panelPresent={rightPanelRendered} />
      ) : null}
      {studioMode === 'free' && !effectiveReadOnly ? <StudioCanvasEmptyOrb /> : null}
      {!effectiveReadOnly ? <StudioFirstVisitGuide scopeKey={scopeKey} studioMode={studioMode} /> : null}
      {!effectiveReadOnly ? <StudioRechargeHost /> : null}
      {studioMode === 'free' && !effectiveReadOnly ? <StudioSmartEditEditor scopeKey={scopeKey} /> : null}
      {!effectiveReadOnly && directorOpenNodeId ? (
        <DirectorStageBoundary key={directorOpenNodeId} onClose={closeDirector}>
          <DirectorStage nodeId={directorOpenNodeId} />
        </DirectorStageBoundary>
      ) : null}
    </div>
  )
  const scopedWorkspace = studioMode === 'free' && !effectiveReadOnly ? <StudioSkillWorkspaceProvider>{workspace}</StudioSkillWorkspaceProvider> : workspace
  const runtimeWorkspace = canvasRuntime ? (
    <CanvasProjectRuntimeProvider value={canvasRuntime}>{scopedWorkspace}</CanvasProjectRuntimeProvider>
  ) : scopedWorkspace
  return studioMode === 'flow' && projectId ? (
    <StudioFlowRuntimeProvider
      projectId={projectId}
      episodeId={episodeId}
      workspaceScopeKey={scopeKey}
      readOnly={readOnly}
    >
      <StudioStoryScriptAgentProvider>
        {runtimeWorkspace}
      </StudioStoryScriptAgentProvider>
    </StudioFlowRuntimeProvider>
  ) : runtimeWorkspace
}

export function shouldApplyStudioWorkspaceDisplayTitle(
  studioMode: StudioMode,
  hydrated: boolean,
  title: string,
  displayTitle: string
): boolean {
  // 流程标题是服务端项目字段，由 Runtime 投影；本地默认标题不能制造布局脏状态。
  return studioMode === 'free' && hydrated && title === 'AID Studio · 未命名作品' && Boolean(displayTitle)
}
