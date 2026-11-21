'use client'

import { HtmlShellClass } from '@/components/app/HtmlShellClass'
import { PageLoadingOverlay } from '~/components/common/PageLoadingOverlay'
import { useRouteNavigation } from '~/hooks/useRouteNavigation'
import dynamic from 'next/dynamic'
import { usePathname } from 'next/navigation'
import { useEffect, useState, type ReactNode } from 'react'
import { AppSidebar } from './AppSidebar'
import { useHomeShellCreateModal } from '~/composables/useHomeShellCreateModal'
import { HomeThemeProvider } from '~/components/home/theme/HomeThemeProvider'
import { useV10Theme } from '~/utils/v10ThemePreference'
import '~/components/home/theme/home-theme.css'
import './HomeNewShell.css'

const CreateFirstStepModal = dynamic(() => import('~/components/steps/CreateFirstStepModal'), { ssr: false })

/**
 * home-new 壳层（原 layouts/home-new.vue）：
 * 左侧固定侧栏 + 右侧滚动主区，承载充值弹窗 / 用户菜单 / 创建作品弹窗。
 */
export function HomeNewShell({ children }: { children: ReactNode }) {
  const pathname = usePathname() ?? ''
  const { navigate, isPending: navigationPending } = useRouteNavigation()
  const homeCreateModal = useHomeShellCreateModal()
  const createOpen = homeCreateModal.showCreateFirstStepModal
  const [createReady, setCreateReady] = useState(false)
  useEffect(() => {
    if (createOpen) setCreateReady(true)
  }, [createOpen])
  const homeTheme = useV10Theme()
  const isGalleryRoute = pathname === '/' || pathname === ''

  return (
    <div
      className={`home-layout-root${isGalleryRoute ? ' home-ui-v4-shell' : ''}`}
      data-home-theme={isGalleryRoute ? homeTheme : undefined}
    >
      {navigationPending && <PageLoadingOverlay label="页面切换中…" />}
      <HtmlShellClass classes="home-page-shell app-shell-create layout-home-new" />
      <div className="home-new-page">
        <AppSidebar navigate={navigate} />

        <div className="home-new-main-wrap">
          <main className="home-new-main">
            <div className="home-route-transition-host">
              {/*
                原 Vue 版此处用 Suspense 规避 layout Transition + NuxtPage 叠层导致页面 onMounted
                执行两次（案例广场 / 我的作品列表接口打成双份，Nuxt #32371 同类问题）。
                React 无该问题；过渡改为页面子树自然换页 + CSS 进场动画（参数照抄）。
              */}
              <HomeThemeProvider theme={homeTheme}>
                <div key={pathname} className="home-main-route">{children}</div>
              </HomeThemeProvider>
            </div>
          </main>
        </div>
      </div>

      {createReady ? <CreateFirstStepModal
        open={createOpen}
        confirmLoading={homeCreateModal.createConfirmLoading}
        title={homeCreateModal.creationTitleDraft}
        projectType={homeCreateModal.creationProjectTypeDraft}
        syncProjectTypeFromParent={homeCreateModal.syncProjectTypeFromParent}
        hideProjectType={homeCreateModal.hideProjectType}
        aspectRatio={homeCreateModal.creationGlobalSettingDraft.aspectRatio}
        scriptType={homeCreateModal.creationGlobalSettingDraft.scriptType}
        modelStrategy={homeCreateModal.creationGlobalSettingDraft.modelStrategy}
        creationMode={homeCreateModal.creationGlobalSettingDraft.creationMode}
        modelValue={homeCreateModal.creationGlobalSettingDraft}
        onOpenChange={homeCreateModal.setShowCreateFirstStepModal}
        onTitleChange={homeCreateModal.setCreationTitleDraft}
        onProjectTypeChange={homeCreateModal.setCreationProjectTypeDraft}
        onAspectRatioChange={(v) => homeCreateModal.updateGlobalSettingDraftField('aspectRatio', v)}
        onScriptTypeChange={(v) => homeCreateModal.updateGlobalSettingDraftField('scriptType', v)}
        onModelStrategyChange={(v) => homeCreateModal.updateGlobalSettingDraftField('modelStrategy', v)}
        onCreationModeChange={(v) => homeCreateModal.updateGlobalSettingDraftField('creationMode', v)}
        onModelValueChange={homeCreateModal.patchGlobalSettingDraftStyle}
        onConfirm={() => homeCreateModal.handleCreateConfirm()}
      /> : null}
    </div>
  )
}

export default HomeNewShell
