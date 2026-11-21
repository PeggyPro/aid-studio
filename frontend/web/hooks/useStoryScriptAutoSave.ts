'use client'

import { message, Modal } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import { getRouteLikeSnapshot, useRouteLike } from '~/hooks/useRouteLike'
import { useCreationStore } from '~/stores/creation'
import {
  resolveStoryScriptEditorHtmlAfterApiLoad,
  storyScriptOriginalTextForApi
} from '~/utils/htmlPlain'
import {
  getStoryScriptAutoSaveCoordinator,
  type StoryScriptAutoSaveCoordinator,
  type StoryScriptFlushResult,
  type StoryScriptPersistenceState
} from '~/utils/storyScriptPersistence'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'

const INITIAL_STATE: StoryScriptPersistenceState = {
  status: 'saved',
  lastSavedAt: null,
  errorMessage: null,
  conflict: null
}

/**
 * 剧本自动保存：停止输入5秒保存，持续编辑最多等待20秒，并统一处理重试与内容冲突。
 */
export function useStoryScriptAutoSave(
  applyEditorContent?: (html: string) => void
) {
  const route = useRouteLike()
  const projectId = useCreationStore((state) => state.currentProjectId)
  const episodeId = useCreationStore((state) => state.currentEpisodeId)
  const projectType = useCreationStore((state) => state.currentProjectType)
  const [saveState, setSaveState] = useState<StoryScriptPersistenceState>(INITIAL_STATE)
  const coordinatorRef = useRef<StoryScriptAutoSaveCoordinator | null>(null)
  const pendingEdits = useRef<Promise<StoryScriptAutoSaveCoordinator | null>>(Promise.resolve(null))
  const dialogOpenRef = useRef(false)
  const applyEditorContentRef = useRef(applyEditorContent)

  useEffect(() => {
    applyEditorContentRef.current = applyEditorContent
  }, [applyEditorContent])

  useEffect(() => {
    let active = true
    let unsubscribe: (() => void) | undefined

    void resolveStoryScriptSaveContext(useCreationStore.getState(), route).then((ctx) => {
      if (!ctx || !active) return

      const coordinator = getStoryScriptAutoSaveCoordinator(ctx)
      coordinatorRef.current = coordinator
      unsubscribe = coordinator.subscribe(() => setSaveState(coordinator.getState()))
      setSaveState(coordinator.getState())
    })

    return () => {
      active = false
      unsubscribe?.()
      coordinatorRef.current = null
    }
  }, [route, projectId, episodeId, projectType])

  const queueEdit = useCallback((html: string) => {
    const scope = resolveStoryScriptSaveContext(useCreationStore.getState(), getRouteLikeSnapshot())
    const text = storyScriptOriginalTextForApi(html)
    pendingEdits.current = pendingEdits.current.then(async (previous) => {
      const ctx = await scope
      if (!ctx) return previous

      const coordinator = getStoryScriptAutoSaveCoordinator(ctx)
      if (previous && previous !== coordinator) void previous.flush()
      coordinator.updateContent(text)
      return coordinator
    })
  }, [])

  const flushAutoSave = useCallback(async (): Promise<StoryScriptFlushResult> => {
    const displayed = coordinatorRef.current
    const edited = await pendingEdits.current
    if (edited && displayed && edited !== displayed) void edited.flush()
    return (displayed ?? edited)?.flush() ?? 'clean'
  }, [])

  useEffect(() => {
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'hidden') void flushAutoSave()
    }
    const handlePageHide = () => {
      void flushAutoSave()
    }
    const handleOnline = () => {
      void flushAutoSave()
    }
    document.addEventListener('visibilitychange', handleVisibilityChange)
    window.addEventListener('pagehide', handlePageHide)
    window.addEventListener('online', handleOnline)
    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange)
      window.removeEventListener('pagehide', handlePageHide)
      window.removeEventListener('online', handleOnline)
      void flushAutoSave()
    }
  }, [flushAutoSave])

  useEffect(() => {
    const coordinator = coordinatorRef.current
    if (!saveState.conflict || !coordinator || dialogOpenRef.current) return
    dialogOpenRef.current = true
    Modal.confirm({
      title: '剧本内容冲突',
      content: '服务器上的剧本已在其他页面更新，请选择保留当前内容重新保存，或加载服务器内容。',
      okText: '保留本地并重试',
      cancelText: '加载服务器内容',
      closable: false,
      maskClosable: false,
      onOk: async () => {
        const result = await coordinator.keepLocalAfterConflict()
        if (result === 'error') {
          message.error(coordinator.getState().errorMessage || '重新保存失败')
          return Promise.reject()
        }
        if (result === 'conflict') {
          message.warning('服务器内容再次更新，请重新选择')
          return Promise.reject()
        }
      },
      onCancel: () => {
        const server = coordinator.useServerAfterConflict()
        const html = resolveStoryScriptEditorHtmlAfterApiLoad(
          String(server?.originalText ?? ''),
          ''
        )
        applyEditorContentRef.current?.(html)
      },
      afterClose: () => {
        dialogOpenRef.current = false
      }
    })
  }, [saveState.conflict])

  return { queueEdit, flushAutoSave, saveState }
}
