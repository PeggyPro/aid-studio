'use client'

import { ReloadOutlined } from '@ant-design/icons'
import { Button, Result } from 'antd'
import { usePathname, useRouter, useSearchParams } from 'next/navigation'
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode
} from 'react'
import HomeNewSidebar from '~/components/layout/HomeNewSidebar'
import { useCreationStore, type CreationStoreState } from '~/stores/creation'
import { createInitialCreationState } from '~/stores/creation/state'
import type { ProjectSnapshotPreviewData } from '~/types/business-api'
import {
  invalidateStoryboardListCache,
  invalidateUserAssetRpsFormListCache,
  invalidateUserTaskDetailCache,
  invalidateUserTaskListCache,
  publicProjectSnapshotPreview
} from '~/utils/businessApi'
import { invalidateCreationStepStatusCache } from '~/utils/creationStepStatusOnce'
import { clearProjectGenConfigCache } from '~/utils/projectGenConfig'
import { invalidateUserStoryboardDetailCache } from '~/utils/storyboardDetailOnce'
import { clearProjectStoryboardRecordCache } from '~/utils/storyboardRecordBatch'
import { invalidateUserProjectDetailCache } from '~/utils/userProjectDetailOnce'
import { invalidateFlowUserTaskListCache } from '~/utils/userTaskListFlowOnce'
import {
  parsePublicProjectPreviewId,
  PUBLIC_PROJECT_PREVIEW_ID_QUERY_KEY
} from '~/utils/publicProjectPreviewNavigation'
import { activatePublicProjectPreviewRuntime } from '~/utils/publicProjectPreviewRuntime'
import { CreateFlowShellSkeleton } from '../create-flow-shell/CreateFlowShellSkeleton'
import './public-project-preview-readonly.css'

interface PreviewLoadState {
  projectId: number
  loading: boolean
  snapshot: ProjectSnapshotPreviewData | null
  error: string
}

function resetProjectReadCaches() {
  invalidateUserProjectDetailCache()
  invalidateFlowUserTaskListCache()
  invalidateUserTaskListCache()
  invalidateUserTaskDetailCache()
  invalidateCreationStepStatusCache()
  clearProjectGenConfigCache()
  invalidateStoryboardListCache()
  invalidateUserStoryboardDetailCache()
  clearProjectStoryboardRecordCache()
  invalidateUserAssetRpsFormListCache()
}

const MUTATION_LABEL_PATTERN = /生成|重新生成|保存|删除|清空|新增|添加|上传|导入|导出|发布|提取|合成|提交|确认|应用|替换|设置为|绑定|解绑|开始|停止|继续创作|重试任务|复制工程/
const VIEW_LABEL_PATTERN = /返回|关闭|取消|查看|详情|编辑|播放|暂停|全屏|放大|缩小|上一|下一|切换|列表|卡片|历史版本|加载更多|展开|收起/
const VIEW_CLASS_PATTERN = /(?:back|close|cancel|preview|viewer|lightbox|play|media|detail|edit|history|tab|pagination|view-mode|switch-view|flow-step)/i

function stopEvent(event: Event) {
  if (event.cancelable) event.preventDefault()
  event.stopPropagation()
  event.stopImmediatePropagation()
}

function inPreviewScope(element: Element): boolean {
  return Boolean(
    element.closest(
      '.create-page--public-readonly, .studio-workspace--public-readonly, .ant-modal-root, .ant-drawer, .ant-dropdown, .ant-popover'
    )
  )
}

function isEditableTarget(element: Element): boolean {
  return Boolean(element.closest('input, textarea, select, [contenteditable="true"], [role="slider"]'))
}

function shouldBlockControl(element: Element): boolean {
  const control = element.closest<HTMLElement>(
    'button, input, select, [role="button"], [role="menuitem"], [role="switch"], [role="slider"]'
  )
  if (!control) return false
  if (control.matches('input[type="file"], input[type="checkbox"], input[type="radio"], select')) {
    return true
  }
  if (control.getAttribute('role') === 'tab') return false
  const className = String(control.className || '')
  const label = [
    control.textContent,
    control.getAttribute('title'),
    control.getAttribute('aria-label')
  ].filter(Boolean).join(' ').trim()
  if (MUTATION_LABEL_PATTERN.test(label)) return true
  if (VIEW_CLASS_PATTERN.test(className) || VIEW_LABEL_PATTERN.test(label)) return false
  // 无明确查看语义的按钮默认视为操作入口；数据读取按钮需显式使用查看/详情/编辑语义。
  return control.matches('button, [role="button"], [role="menuitem"], [role="switch"], [role="slider"]')
}

function useReadonlyDocumentGuard(active: boolean) {
  useEffect(() => {
    if (!active || typeof document === 'undefined') return
    const root = document.documentElement
    root.dataset.publicProjectPreviewReadonly = 'true'

    const markReadonlyControls = (scope: ParentNode) => {
      const controls = scope.querySelectorAll?.<HTMLElement>(
        'button, input, textarea, select, [contenteditable="true"], [role="button"], [role="menuitem"], [role="switch"], [role="slider"]'
      ) ?? []
      controls.forEach((control) => {
        if (!inPreviewScope(control)) return
        if (control.matches('input, textarea, select, [contenteditable="true"]')) {
          control.setAttribute('aria-readonly', 'true')
          control.dataset.publicPreviewReadonlyField = 'true'
        }
        if (shouldBlockControl(control)) {
          control.setAttribute('aria-disabled', 'true')
          control.dataset.publicPreviewDisabled = 'true'
        }
      })
    }
    markReadonlyControls(document)
    const observer = new MutationObserver((records) => {
      records.forEach((record) => {
        record.addedNodes.forEach((node) => {
          if (node instanceof Element) {
            if (node.matches('button, input, textarea, select, [contenteditable="true"], [role]')) {
              markReadonlyControls(node.parentElement ?? node)
            } else {
              markReadonlyControls(node)
            }
          }
        })
      })
    })
    observer.observe(document.body, { childList: true, subtree: true })

    const onClick = (event: Event) => {
      const target = event.target
      if (!(target instanceof Element) || !inPreviewScope(target)) return
      if (shouldBlockControl(target)) stopEvent(event)
    }
    const onPointerDown = (event: Event) => {
      const target = event.target
      if (!(target instanceof Element) || !inPreviewScope(target)) return
      if (target.closest('input[type="file"], select, [role="slider"], [role="switch"], [draggable="true"]')) {
        stopEvent(event)
      }
    }
    const onEdit = (event: Event) => {
      const target = event.target
      if (!(target instanceof Element) || !inPreviewScope(target) || !isEditableTarget(target)) return
      stopEvent(event)
    }
    const onSubmit = (event: Event) => {
      const target = event.target
      if (target instanceof Element && inPreviewScope(target)) stopEvent(event)
    }
    const onDragMutation = (event: Event) => {
      const target = event.target
      if (target instanceof Element && inPreviewScope(target)) stopEvent(event)
    }
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target
      if (!(target instanceof Element)) return
      const globalPreviewTarget = target === document.body || target === document.documentElement
      if (!globalPreviewTarget && !inPreviewScope(target)) return
      const mutationShortcut =
        /^(?:Delete|Backspace)$/i.test(event.key) ||
        ((event.ctrlKey || event.metaKey) && /^(?:s|x|v|z|y|d)$/i.test(event.key))
      if (mutationShortcut) {
        stopEvent(event)
        return
      }
      if (!isEditableTarget(target)) return
      const navigationKey = /^(?:Tab|Escape|ArrowLeft|ArrowRight|ArrowUp|ArrowDown|Home|End|PageUp|PageDown)$/
      const readonlyShortcut = (event.ctrlKey || event.metaKey) && /^(?:a|c|f)$/i.test(event.key)
      if (!navigationKey.test(event.key) && !readonlyShortcut) stopEvent(event)
    }

    document.addEventListener('click', onClick, true)
    document.addEventListener('pointerdown', onPointerDown, true)
    document.addEventListener('beforeinput', onEdit, true)
    document.addEventListener('input', onEdit, true)
    document.addEventListener('change', onEdit, true)
    document.addEventListener('submit', onSubmit, true)
    document.addEventListener('paste', onEdit, true)
    document.addEventListener('dragstart', onDragMutation, true)
    document.addEventListener('drop', onDragMutation, true)
    document.addEventListener('keydown', onKeyDown, true)
    return () => {
      observer.disconnect()
      delete root.dataset.publicProjectPreviewReadonly
      document.querySelectorAll<HTMLElement>('[data-public-preview-disabled]').forEach((control) => {
        delete control.dataset.publicPreviewDisabled
        if (!control.hasAttribute('disabled')) control.removeAttribute('aria-disabled')
      })
      document.querySelectorAll<HTMLElement>('[data-public-preview-readonly-field]').forEach((control) => {
        delete control.dataset.publicPreviewReadonlyField
        control.removeAttribute('aria-readonly')
      })
      document.removeEventListener('click', onClick, true)
      document.removeEventListener('pointerdown', onPointerDown, true)
      document.removeEventListener('beforeinput', onEdit, true)
      document.removeEventListener('input', onEdit, true)
      document.removeEventListener('change', onEdit, true)
      document.removeEventListener('submit', onSubmit, true)
      document.removeEventListener('paste', onEdit, true)
      document.removeEventListener('dragstart', onDragMutation, true)
      document.removeEventListener('drop', onDragMutation, true)
      document.removeEventListener('keydown', onKeyDown, true)
    }
  }, [active])
}

function PreviewLoading() {
  return (
    <div className="create-page create-page--public-readonly">
      <CreateFlowShellSkeleton
        seriesChrome={false}
        seriesScriptUpload={false}
        seriesEpisodeList={false}
        stepCount={7}
      />
    </div>
  )
}

function PreviewError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="create-page create-page--public-readonly">
      <div className="main-layout">
        <HomeNewSidebar useRouterLinks galleryActive />
        <main className="create-main public-project-preview-error">
          <Result
            status="warning"
            title="暂时无法查看工程"
            subTitle={message}
            extra={(
              <Button type="primary" icon={<ReloadOutlined />} onClick={onRetry}>
                重新加载
              </Button>
            )}
          />
        </main>
      </div>
    </div>
  )
}

export function PublicProjectPreviewProvider({ children }: { children: ReactNode }) {
  const pathname = usePathname() ?? ''
  const router = useRouter()
  const searchParams = useSearchParams()
  const projectId = parsePublicProjectPreviewId(
    searchParams.get(PUBLIC_PROJECT_PREVIEW_ID_QUERY_KEY)
  ) ?? 0
  const generationRef = useRef(0)
  const previousStoreRef = useRef<CreationStoreState | null>(null)
  const deactivateRuntimeRef = useRef<(() => void) | null>(null)
  const [request, setRequest] = useState<PreviewLoadState>({
    projectId: 0,
    loading: true,
    snapshot: null,
    error: ''
  })

  const stopPreviewRuntime = useCallback(() => {
    const previousStore = previousStoreRef.current
    if (previousStore) useCreationStore.setState(previousStore, true)
    deactivateRuntimeRef.current?.()
    if (deactivateRuntimeRef.current) resetProjectReadCaches()
    previousStoreRef.current = null
    deactivateRuntimeRef.current = null
  }, [])

  const startPreviewRuntime = useCallback((snapshot: ProjectSnapshotPreviewData) => {
    stopPreviewRuntime()
    resetProjectReadCaches()
    previousStoreRef.current = useCreationStore.getState()
    deactivateRuntimeRef.current = activatePublicProjectPreviewRuntime(snapshot)
    useCreationStore.setState({
      ...createInitialCreationState(),
      isHydrated: true
    })
  }, [stopPreviewRuntime])

  const load = useCallback((force = false) => {
    if (!projectId) return
    const generation = ++generationRef.current
    if (force) {
      stopPreviewRuntime()
      setRequest({ projectId, loading: true, snapshot: null, error: '' })
    }
    void publicProjectSnapshotPreview(projectId, { force })
      .then((snapshot) => {
        if (generation !== generationRef.current) return
        if (!snapshot.allowPreview) {
          setRequest({
            projectId,
            loading: false,
            snapshot: null,
            error: '当前作品暂不支持查看工程'
          })
          return
        }
        startPreviewRuntime(snapshot)
        setRequest({ projectId, loading: false, snapshot, error: '' })
      })
      .catch((error: unknown) => {
        if (generation !== generationRef.current) return
        const detail = error as { msg?: string; message?: string }
        setRequest({
          projectId,
          loading: false,
          snapshot: null,
          error: detail?.msg || detail?.message || '工程预览加载失败'
        })
      })
  }, [projectId, startPreviewRuntime, stopPreviewRuntime])

  useEffect(() => {
    stopPreviewRuntime()
    const timer = window.setTimeout(() => load(false), 0)
    return () => {
      window.clearTimeout(timer)
      generationRef.current += 1
      stopPreviewRuntime()
    }
  }, [load, stopPreviewRuntime])

  const snapshot = request.projectId === projectId ? request.snapshot : null
  const firstEpisodeId = useMemo(() => {
    if (!snapshot || String(snapshot.projectType).toLowerCase() !== 'series') return null
    const episode = snapshot.nodes
      .filter((node) => node.type === 'episode')
      .sort((left, right) => Number(left.order) - Number(right.order))[0]
    const id = Number(episode?.data.id)
    return Number.isFinite(id) && id > 0 ? id : null
  }, [snapshot])
  const needsEpisodeQuery = Boolean(
    firstEpisodeId && !Number(searchParams.get('episodeId'))
  )
  useReadonlyDocumentGuard(Boolean(snapshot))

  useEffect(() => {
    if (!needsEpisodeQuery || !firstEpisodeId) return
    const next = new URLSearchParams(searchParams.toString())
    next.set('episodeId', String(firstEpisodeId))
    router.replace(`${pathname}?${next.toString()}`, { scroll: false })
  }, [firstEpisodeId, needsEpisodeQuery, pathname, router, searchParams])

  if (!projectId) {
    return <PreviewError message="缺少要查看的作品信息" onRetry={() => router.push('/case')} />
  }
  if (request.projectId !== projectId || request.loading || needsEpisodeQuery) return <PreviewLoading />
  if (request.error || !snapshot) {
    return <PreviewError message={request.error || '工程预览加载失败'} onRetry={() => load(true)} />
  }
  return <>{children}</>
}
