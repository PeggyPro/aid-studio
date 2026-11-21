'use client'

import { StudioChromeSlot } from './StudioChromeSlot'
import { StudioChromeIcon } from '../theme/StudioChromeIcon'

import { useCallback, useEffect, useRef, useState } from 'react'
import { Button, Dropdown, Input, Modal, message } from 'antd'
import type { MenuProps } from 'antd'
import {
  DeleteOutlined,
  DownOutlined,
  HomeOutlined,
  SaveOutlined,
  UnorderedListOutlined,
  WarningOutlined
} from '@ant-design/icons'
import agentIconRaw from '~/assets/img/home/agent-icon.svg'
import { useRouter } from 'next/navigation'
import { useStudioUiStore } from '@/stores/studioUi'
import { useCreationStore } from '@/stores/creation'
import { CREATE_FLOW_STEP_ORDER } from '@/utils/createFlowRoutes'
import { useStudioFlowRuntime } from '@/components/studio/flow/StudioFlowRuntimeProvider'
import { buildStudioStepsFlowHref } from '@/utils/studio/studioFlowNavigation'
import { StudioFlowDeliveryMenu } from '@/components/studio/flow/StudioFlowDeliveryMenu'
import { filterStudioFlowCanvasVisibleNodes } from '@/utils/studio/studioFlowCanvasProjection'
import {
  hasStudioFlowPreviewStepSelected,
  isStudioFlowDeliveryReady
} from '@/utils/studio/studioFlowDelivery'
import { userProjectDelete, userProjectUpdate } from '@/utils/business/project'
import { canvasProjectUpdate } from '@/utils/business/canvas'
import type { CanvasProjectWorkspaceRuntime } from '@/hooks/studio/useCanvasProjectWorkspace'
import { isProjectPublicLockError, projectPublicLockUserHint } from '@/utils/projectAudit'
import { assetUrl } from '@/utils/assetUrl'
import AidHoverLogo from '@/components/atoms/AidHoverLogo'
import { PublicProjectReadonlyBadge } from '@/components/create/project-preview/PublicProjectReadonlyBadge'
import { buildPublicProjectPreviewFlowHref } from '@/utils/publicProjectPreviewNavigation'
import '@/components/home/composer/home-composer-flow.css'
import { CanvasProjectPanel } from '@/components/canvas/CanvasProjectPanel'
import { deleteCanvasProjectAfterCancellingQueuedTasks } from '@/utils/canvas/canvasProjectDeletion'
import { apiErrorMessage } from '@/utils/apiError'
import { StudioCanvasPublishShare } from './StudioCanvasPublishShare'
import { StudioUserMenu } from './StudioUserMenu'

const agentIconUrl = assetUrl(agentIconRaw)
const dropdownClassNames = { root: 'studio-canvas-menu-overlay studio-canvas-menu-overlay--canvas' }

export function StudioTopbar({
  onSave,
  readOnly = false,
  canvasRuntime = null
}: {
  onSave: () => void | Promise<void>
  readOnly?: boolean
  canvasRuntime?: CanvasProjectWorkspaceRuntime | null
}) {
  const router = useRouter()
  const flowRuntime = useStudioFlowRuntime()
  const title = useStudioUiStore((state) => state.title)
  const setTitle = useStudioUiStore((state) => state.setTitle)
  const studioMode = useStudioUiStore((state) => state.studioMode)
  const setRightCollapsed = useStudioUiStore((state) => state.setRightCollapsed)
  const nodes = useStudioUiStore((state) => state.nodes)
  const visibleFlowNodes = studioMode === 'flow' ? filterStudioFlowCanvasVisibleNodes(nodes) : nodes
  const selectedNodeIds = useStudioUiStore((state) => state.selectedNodeIds)
  const currentExportStatus = useCreationStore((state) => state.currentExportStatus)
  const currentFinalVideoUrl = useCreationStore((state) => state.currentFinalVideoUrl)
  const currentPendingVideoUrl = useCreationStore((state) => state.currentPendingVideoUrl)
  const workTitle = useCreationStore((state) => state.workTitle)
  const projectId = useCreationStore((state) => state.currentProjectId)
  const canvasProjectId = canvasRuntime?.project ? String(canvasRuntime.project.projectId) : ''
  const titleReadOnly = canvasRuntime?.project
    ? canvasRuntime.role !== 'OWNER'
    : readOnly
  const titleValue = studioMode === 'flow' ? workTitle : title
  const titleMeasureRef = useRef<HTMLSpanElement | null>(null)
  const avatarTriggerRef = useRef<HTMLButtonElement | null>(null)
  const [titleInputWidthPx, setTitleInputWidthPx] = useState(160)
  const [collaborationOpen, setCollaborationOpen] = useState(false)
  const workTitleSaveBaselineRef = useRef((workTitle || '').trim() || '未命名作品')
  const canvasTitleSaveBaselineRef = useRef('')
  const titleFocusedRef = useRef(false)
  const trimmedTitle = titleValue?.trim()
  const titleMeasureText = trimmedTitle && trimmedTitle.length > 0 ? trimmedTitle : '作品名称'

  const syncTitleInputWidth = useCallback(() => {
    window.setTimeout(() => {
      const el = titleMeasureRef.current
      if (!el) return
      setTitleInputWidthPx(Math.min(Math.max(el.offsetWidth + 28, 96), 560))
    }, 0)
  }, [])

  useEffect(() => {
    syncTitleInputWidth()
  }, [syncTitleInputWidth, titleValue])

  useEffect(() => {
    if (titleFocusedRef.current) return
    workTitleSaveBaselineRef.current = (workTitle || '').trim() || '未命名作品'
  }, [projectId, workTitle])

  useEffect(() => {
    const projectName = canvasRuntime?.project?.projectName?.trim()
    if (projectName) canvasTitleSaveBaselineRef.current = projectName
  }, [canvasRuntime?.project?.projectName])
  const deliveryReady = studioMode === 'flow' && isStudioFlowDeliveryReady(nodes, {
    exportStatus: currentExportStatus,
    finalVideoUrl: currentFinalVideoUrl,
    pendingVideoUrl: currentPendingVideoUrl
  })
  const hasExportedVideo =
    currentExportStatus === 2 ||
    Boolean(String(currentFinalVideoUrl || '').trim()) ||
    Boolean(String(currentPendingVideoUrl || '').trim())
  const deliveryHighlighted = deliveryReady && (
    hasStudioFlowPreviewStepSelected(nodes, selectedNodeIds) || hasExportedVideo
  )
  const dirtyRevision = useStudioUiStore((state) => state.dirtyRevision)
  const tasks = useStudioUiStore((state) => state.tasks)
  const running = tasks.filter((task) => task.status === 'running' || task.status === 'waiting').length
  const failed = nodes.filter((node) => node.data.status === 'failed').length

  const saveFromUi = useCallback(async () => {
    try {
      await onSave()
      return true
    } catch (error: unknown) {
      const state = useStudioUiStore.getState()
      message.error(state.saveError || apiErrorMessage(error, '画布保存失败，请稍后重试'))
      return false
    }
  }, [onSave])

  const deleteWork = () => {
    if (studioMode === 'free' && canvasRuntime?.project) {
      if (canvasRuntime.role !== 'OWNER') {
        message.warning('只有画布所有者可以删除项目')
        return
      }
      Modal.confirm({
        title: '删除自由画布',
        content: `确认删除《${canvasRuntime.project.projectName}》吗？可取消的排队任务会先停止，删除后无法恢复。`,
        okText: '删除',
        cancelText: '取消',
        okType: 'danger',
        className: 'create-flow-modal',
        async onOk() {
          try {
            const current = canvasRuntime.project
            if (!current) throw new Error('画布项目已切换，请重新删除')
            const result = await deleteCanvasProjectAfterCancellingQueuedTasks(
              String(current.projectId),
              String(current.workspace.workspaceId)
            )
            message.success(result.cancelledTaskCount > 0
              ? `已停止 ${result.cancelledTaskCount} 个排队任务并删除项目`
              : '删除成功')
            router.push('/works?tab=canvas&from=studio')
          } catch (error: unknown) {
            message.error(apiErrorMessage(error, '删除自由画布失败'))
          }
        }
      })
      return
    }
    if (!projectId) {
      message.warning('当前预览作品无法删除')
      return
    }
    Modal.confirm({
      title: '删除作品',
      content: `确认删除《${title}》吗？`,
      okText: '删除',
      cancelText: '取消',
      okType: 'danger',
      className: 'create-flow-modal',
      async onOk() {
        try {
          await userProjectDelete(projectId)
          message.success('删除成功')
          router.push('/works?from=studio')
        } catch (error: unknown) {
          message.error(apiErrorMessage(error, '删除作品失败'))
        }
      }
    })
  }

  const projectMenu: MenuProps = {
    className: 'studio-toolbar-more-menu studio-toolbar-more-menu--canvas',
    selectable: false,
    items: [
      {
        key: 'home',
        label: '返回主页',
        icon: <HomeOutlined />,
        onClick: () => router.push('/')
      },
      {
        key: 'works',
        label: '我的作品',
        icon: <UnorderedListOutlined />,
        onClick: () => router.push(studioMode === 'free' ? '/works?tab=canvas&from=studio' : '/works?from=studio')
      },
      { type: 'divider' },
      {
        key: 'delete',
        label: '删除作品',
        icon: <DeleteOutlined />,
        danger: true,
        disabled: studioMode === 'free'
          ? !canvasProjectId || canvasRuntime?.role !== 'OWNER'
          : readOnly || !projectId,
        onClick: deleteWork
      }
    ]
  }

  const switchToSteps = () => {
    const proceed = async () => {
      const creation = useCreationStore.getState()
      const index = Math.min(Math.max(creation.currentStepIndex, 0), CREATE_FLOW_STEP_ORDER.length - 1)
      const requestedPreviewStep = typeof window === 'undefined'
        ? ''
        : new URLSearchParams(window.location.search).get('previewStep') || ''
      const targetStep = CREATE_FLOW_STEP_ORDER.find((step) => step === requestedPreviewStep)
        ?? CREATE_FLOW_STEP_ORDER[index]
      if (readOnly) {
        const previewProjectId = flowRuntime?.projectId ?? creation.currentProjectId
        if (previewProjectId) {
          router.push(buildPublicProjectPreviewFlowHref(previewProjectId, targetStep))
        }
        return
      }
      if (flowRuntime && !await flowRuntime.flushDirtyEditors()) {
        Modal.error({
          title: '剧本尚未保存',
          content: '剧本自动保存失败或存在内容冲突，请处理后再切换到步骤流程。',
          okText: '知道了',
          className: 'create-flow-modal'
        })
        return
      }
      if (!await saveFromUi()) return
      if (useStudioUiStore.getState().saveStatus === 'error') {
        Modal.error({
          title: '画布布局保存失败',
          content: useStudioUiStore.getState().saveError || '请重试后再切换到步骤流程。',
          okText: '知道了',
          className: 'create-flow-modal'
        })
        return
      }
      const from = typeof window === 'undefined'
        ? undefined
        : new URLSearchParams(window.location.search).get('from') || undefined
      router.push(buildStudioStepsFlowHref({
        step: CREATE_FLOW_STEP_ORDER[index],
        projectId: creation.currentProjectId,
        episodeId: creation.currentEpisodeId,
        from: from || 'studio'
      }))
    }
    if (!dirtyRevision) {
      void proceed().catch((error: unknown) => {
        message.error(apiErrorMessage(error, '切换步骤流程失败，请稍后重试'))
      })
      return
    }
    Modal.confirm({
      title: '保存画布布局并返回步骤流程？',
      content: '流程业务数据已经实时保存到服务端；这里只保存节点位置、连线和画布专属内容。',
      okText: '保存并返回',
      cancelText: '留在画布',
      className: 'create-flow-modal',
      onOk: proceed
    })
  }

  const handleTitleChange = (value: string) => {
    if (titleReadOnly) return
    if (studioMode === 'flow') {
      useCreationStore.getState().setWorkTitle(value)
      setTitle(`AID Studio · ${(value.trim() || '未命名作品')}`)
      syncTitleInputWidth()
      return
    }
    setTitle(value, !canvasRuntime?.project)
    syncTitleInputWidth()
  }

  const handleTitleBlur = async () => {
    if (titleReadOnly) return
    if (studioMode === 'free') {
      const trimmed = (useStudioUiStore.getState().title || '').trim() || '未命名画布'
      setTitle(trimmed, !canvasRuntime?.project)
      if (!canvasRuntime?.project || canvasRuntime.role !== 'OWNER') {
        void saveFromUi()
        return
      }
      if (trimmed === canvasTitleSaveBaselineRef.current) return
      try {
        const updated = await canvasProjectUpdate({ projectId: String(canvasRuntime.project.projectId), projectName: trimmed })
        canvasTitleSaveBaselineRef.current = updated.projectName
        await canvasRuntime.refreshProject()
      } catch (error: unknown) {
        message.error(apiErrorMessage(error, '保存画布名称失败'))
        setTitle(canvasTitleSaveBaselineRef.current || canvasRuntime.project.projectName, false)
      }
      return
    }
    const store = useCreationStore.getState()
    const trimmed = (store.workTitle || '').trim() || '未命名作品'
    store.setWorkTitle(trimmed)
    setTitle(`AID Studio · ${trimmed}`)
    if (trimmed === workTitleSaveBaselineRef.current) {
      syncTitleInputWidth()
      return
    }
    if (!projectId) {
      workTitleSaveBaselineRef.current = trimmed
      store.updateFormData({
        globalSetting: { ...store.formData.globalSetting, title: trimmed }
      })
      syncTitleInputWidth()
      return
    }
    try {
      await userProjectUpdate({ id: projectId, projectName: trimmed })
      workTitleSaveBaselineRef.current = trimmed
      const storeNow = useCreationStore.getState()
      storeNow.updateFormData({
        globalSetting: { ...storeNow.formData.globalSetting, title: trimmed }
      })
    } catch (error: unknown) {
      if (isProjectPublicLockError(error)) {
        message.error(projectPublicLockUserHint())
        useCreationStore.getState().setWorkTitle(workTitleSaveBaselineRef.current)
        setTitle(`AID Studio · ${workTitleSaveBaselineRef.current}`)
        return
      }
      const err = error as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '保存标题失败')
      useCreationStore.getState().setWorkTitle(workTitleSaveBaselineRef.current)
      setTitle(`AID Studio · ${workTitleSaveBaselineRef.current}`)
    } finally {
      syncTitleInputWidth()
    }
  }

  return (
    <header className="studio-topbar">
      <div className="studio-topbar__left">
        <div className="studio-topbar__project">
          <Dropdown menu={projectMenu} trigger={['click']} placement="bottomLeft" classNames={dropdownClassNames}>
            <button type="button" className="studio-topbar__aid-trigger" aria-label="作品菜单">
              <AidHoverLogo className="studio-topbar__aid-logo" alt="AID" />
              {studioMode === 'free' ? <DownOutlined className="studio-topbar__project-chevron" aria-hidden /> : null}
            </button>
          </Dropdown>
          {studioMode === 'free' ? <span className="studio-topbar__project-divider" aria-hidden /> : null}
          <div className="studio-topbar__title-wrap" style={{ width: `${titleInputWidthPx}px` }}>
            <span ref={titleMeasureRef} className="studio-title-input-measure" aria-hidden="true">
              {titleMeasureText}
            </span>
            <Input
              className="studio-title-input"
              value={titleValue}
              aria-label="Studio 作品名称"
              readOnly={titleReadOnly}
              maxLength={100}
              title={titleReadOnly ? '当前账号不能修改画布名称' : '点击修改作品名称'}
              placeholder="作品名称"
              onFocus={() => { titleFocusedRef.current = true }}
              onChange={(event) => handleTitleChange(event.target.value)}
              onBlur={() => {
                titleFocusedRef.current = false
                void handleTitleBlur()
              }}
            />
          </div>
        </div>
      </div>
      {studioMode === 'flow' ? (
        <div className="studio-topbar__center">
          <span className="studio-topbar__mode-tag">流程创作</span>
        </div>
      ) : null}
      {studioMode === 'free' ? <StudioChromeSlot /> : null}
      <div className="studio-topbar__right">
        {readOnly && !canvasRuntime ? <PublicProjectReadonlyBadge /> : null}
        {studioMode === 'free' && canvasRuntime?.project ? (
          <CanvasProjectPanel
            runtime={canvasRuntime}
            open={collaborationOpen}
            showTrigger={false}
            onOpenChange={setCollaborationOpen}
            returnFocusRef={avatarTriggerRef}
          />
        ) : null}
        {studioMode === 'flow' ? (
          <>
            <span className="studio-topbar__stats">{visibleFlowNodes.length} 节点 · {running} 进行中</span>
            {failed ? <span className="studio-diagnostic-banner"><WarningOutlined /> {failed} 个节点待处理</span> : null}
            <Button
              icon={<StudioChromeIcon name="save" fallback={<SaveOutlined />} />}
              disabled={readOnly || Boolean(canvasRuntime?.project && !canvasRuntime.canMutate)}
              onClick={() => void saveFromUi()}
            >保存</Button>
          </>
        ) : (
          <StudioCanvasPublishShare canvasRuntime={canvasRuntime} />
        )}
        {studioMode === 'flow' ? (
          <span className={deliveryHighlighted ? 'studio-topbar__delivery studio-topbar__delivery--active' : 'studio-topbar__delivery'}>
            <StudioFlowDeliveryMenu compact disabled={readOnly || !deliveryReady} />
          </span>
        ) : null}
        {studioMode === 'flow' ? <Button onClick={switchToSteps}>切换到步骤流程</Button> : null}
        {studioMode === 'flow' ? (
          <button
            type="button"
            className="studio-topbar__ai-create home-composer-flow is-flowing"
            data-studio-guide="conversation"
            aria-label="打开 AI 创作"
            disabled={readOnly}
            onClick={() => setRightCollapsed(false)}
          >
            <StudioChromeIcon name="sparkle" fallback={<img src={agentIconUrl} alt="" className="studio-topbar__ai-create-icon" />} />
            AI创作
          </button>
        ) : null}
        {studioMode === 'free' ? (
          <StudioUserMenu
            canvasRuntime={canvasRuntime}
            triggerRef={avatarTriggerRef}
            onOpenCollaboration={() => setCollaborationOpen(true)}
          />
        ) : null}
      </div>
    </header>
  )
}
