'use client'

import { useCallback, useEffect, useRef } from 'react'
import { message as antdMessage } from 'antd'
import { getStudioWorkspaceData, useStudioUiStore } from '@/stores/studioUi'
import {
  getStudioWorkspaceStorageKeys,
  mockStudioApi,
  StudioWorkspaceConflictError
} from '@/utils/studio/mockStudioApi'
import { cancelAllStudioMockTasks } from './useStudioCommands'
import { createStudioPerfFixture } from '@/utils/studio/studioPerfFixture'
import type { StudioMode } from '@/types/studio'
import { migrateStudioWorkspaceData } from '@/utils/studio/studioLegacyNodeMap'
import { serializeStudioFlowLayoutWorkspace } from '@/utils/studio/studioFlowWorkspace'
import { useCanvasProjectWorkspace } from './useCanvasProjectWorkspace'
import { useCanvasPublicationWorkspace, type CanvasPublicationSource } from './useCanvasPublicationWorkspace'

export function useStudioWorkspace(scopeKey: string, options?: {
  perfNodeCount?: number
  studioMode?: StudioMode
  readOnly?: boolean
  canvasProjectId?: string | null
  publication?: CanvasPublicationSource
}) {
  const perfNodeCount = options?.perfNodeCount
  const studioMode = options?.studioMode
  const publication = options?.publication
  const readOnly = options?.readOnly === true || Boolean(publication)
  const canvasProjectId = publication ? null : options?.canvasProjectId || null
  const publicationLoading = useCanvasPublicationWorkspace(scopeKey, publication)
  const remote = useCanvasProjectWorkspace(scopeKey, canvasProjectId, Boolean(canvasProjectId))
  const dirtyRevision = useStudioUiStore((state) => state.dirtyRevision)
  const hydrated = useStudioUiStore((state) => state.hydrated)
  const hydrate = useStudioUiStore((state) => state.hydrate)
  const beginLoad = useStudioUiStore((state) => state.beginLoad)
  const setLoadError = useStudioUiStore((state) => state.setLoadError)
  const markSaving = useStudioUiStore((state) => state.markSaving)
  const markSaved = useStudioUiStore((state) => state.markSaved)
  const markSaveError = useStudioUiStore((state) => state.markSaveError)
  const loadedScopeRef = useRef('')
  const storageRevisionRef = useRef(0)
  const writerIdRef = useRef('')
  if (!writerIdRef.current) {
    writerIdRef.current = `studio-tab-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
  }
  const skipFirstSaveRef = useRef(true)
  const saveTransactionsRef = useRef(new Map<string, {
    promise: Promise<void>
    queued: boolean
  }>())

  useEffect(() => {
    if (canvasProjectId || publication) return
    let cancelled = false
    cancelAllStudioMockTasks()
    beginLoad(scopeKey, { readOnly })
    loadedScopeRef.current = ''
    skipFirstSaveRef.current = true
    const loadPromise = perfNodeCount
      ? Promise.resolve(createStudioPerfFixture(scopeKey, perfNodeCount))
      : mockStudioApi.loadWorkspace(scopeKey, studioMode)
    void loadPromise
      .then((workspace) => {
        if (cancelled) return
        storageRevisionRef.current = mockStudioApi.readWorkspaceRevision(scopeKey).revision
        loadedScopeRef.current = scopeKey
        hydrate(migrateStudioWorkspaceData(workspace), { readOnly })
      })
      .catch((error: unknown) => {
        if (cancelled) return
        const message = error instanceof Error ? error.message : 'Studio 工作区加载失败'
        setLoadError(message)
      })
    return () => {
      cancelled = true
      cancelAllStudioMockTasks()
    }
  }, [beginLoad, canvasProjectId, hydrate, perfNodeCount, scopeKey, setLoadError, studioMode, publication, readOnly])

  const saveNow = useCallback(async () => {
    if (readOnly || useStudioUiStore.getState().readOnly) return
    if (canvasProjectId) return remote.saveNow()
    if (readOnly || perfNodeCount || !hydrated || loadedScopeRef.current !== scopeKey) return
    const existing = saveTransactionsRef.current.get(scopeKey)
    if (existing) {
      existing.queued = true
      return existing.promise
    }
    const transaction = { promise: Promise.resolve(), queued: false }
    const savePromise = (async () => {
      do {
        transaction.queued = false
        if (loadedScopeRef.current !== scopeKey) break
        // 每轮固定本次 scope 与布局快照。旧 scope 请求允许完成，但不能读取或
        // 标记随后 hydrate 的新 scope。
        const workspace = serializeStudioFlowLayoutWorkspace(getStudioWorkspaceData())
        if (workspace.scopeKey !== scopeKey) break
        const revision = useStudioUiStore.getState().dirtyRevision
        if (loadedScopeRef.current === scopeKey) markSaving()
        try {
          const { savedAt, revision: storageRevision } = await mockStudioApi.saveWorkspace(workspace, {
            expectedRevision: storageRevisionRef.current,
            writerId: writerIdRef.current
          })
          storageRevisionRef.current = storageRevision
          if (loadedScopeRef.current === scopeKey) markSaved(savedAt, revision)
        } catch (error: unknown) {
          const saveMessage = error instanceof StudioWorkspaceConflictError
            ? error.message
            : error instanceof Error ? error.message : '布局保存失败，请重试'
          if (loadedScopeRef.current === scopeKey) {
            markSaveError(saveMessage)
            if (error instanceof StudioWorkspaceConflictError) antdMessage.warning(saveMessage)
          }
          break
        }
      } while (transaction.queued && loadedScopeRef.current === scopeKey)
    })().finally(() => {
      if (saveTransactionsRef.current.get(scopeKey) === transaction) {
        saveTransactionsRef.current.delete(scopeKey)
      }
    })
    transaction.promise = savePromise
    saveTransactionsRef.current.set(scopeKey, transaction)
    return savePromise
  }, [canvasProjectId, hydrated, markSaveError, markSaved, markSaving, perfNodeCount, readOnly, remote, scopeKey])

  useEffect(() => {
    if (canvasProjectId || publication || readOnly || perfNodeCount) return
    const revisionStorageKey = getStudioWorkspaceStorageKeys(scopeKey).revision
    const handleExternalWorkspaceChange = (event: StorageEvent) => {
      if (event.key !== revisionStorageKey || !event.newValue || loadedScopeRef.current !== scopeKey) return
      let nextRevision = 0
      let writerId = ''
      try {
        const parsed = JSON.parse(event.newValue) as { revision?: number; writerId?: string }
        nextRevision = Number(parsed.revision)
        writerId = String(parsed.writerId || '')
      } catch {
        return
      }
      if (!Number.isSafeInteger(nextRevision) || nextRevision <= storageRevisionRef.current) return
      if (writerId && writerId === writerIdRef.current) return
      if (useStudioUiStore.getState().dirtyRevision > 0) {
        const conflictMessage = '其他标签页已更新当前画布；本页未保存内容不会覆盖它，请刷新后重新应用修改'
        markSaveError(conflictMessage)
        antdMessage.warning(conflictMessage)
        return
      }
      void mockStudioApi.loadWorkspace(scopeKey, studioMode).then((workspace) => {
        if (loadedScopeRef.current !== scopeKey || useStudioUiStore.getState().dirtyRevision > 0) return
        storageRevisionRef.current = mockStudioApi.readWorkspaceRevision(scopeKey).revision
        hydrate(migrateStudioWorkspaceData(workspace))
      }).catch(() => {
        markSaveError('检测到其他标签页更新，但重新载入失败，请刷新页面')
      })
    }
    window.addEventListener('storage', handleExternalWorkspaceChange)
    return () => window.removeEventListener('storage', handleExternalWorkspaceChange)
  }, [canvasProjectId, hydrate, markSaveError, perfNodeCount, publication, readOnly, scopeKey, studioMode])

  useEffect(() => {
    if (canvasProjectId || readOnly || !hydrated || loadedScopeRef.current !== scopeKey) return
    if (skipFirstSaveRef.current) {
      skipFirstSaveRef.current = false
      return
    }
    if (!dirtyRevision) return
    const timer = window.setTimeout(() => void saveNow(), 650)
    return () => window.clearTimeout(timer)
  }, [canvasProjectId, dirtyRevision, hydrated, readOnly, saveNow, scopeKey])

  useEffect(() => {
    if (canvasProjectId || readOnly) return
    const handleBeforeUnload = () => {
      if (perfNodeCount || !useStudioUiStore.getState().hydrated) return
      try {
        const result = mockStudioApi.saveWorkspaceSync(
          serializeStudioFlowLayoutWorkspace(getStudioWorkspaceData()),
          { expectedRevision: storageRevisionRef.current, writerId: writerIdRef.current }
        )
        storageRevisionRef.current = result.revision
      } catch {
        // beforeunload 不能安全展示冲突 UI；乐观 revision 会保证旧标签页不会覆盖新内容。
      }
    }
    window.addEventListener('beforeunload', handleBeforeUnload)
    return () => {
      window.removeEventListener('beforeunload', handleBeforeUnload)
      if (!perfNodeCount && useStudioUiStore.getState().hydrated) void saveNow()
    }
  }, [canvasProjectId, perfNodeCount, readOnly, saveNow])

  return { saveNow, canvasRuntime: canvasProjectId ? remote : null, publicationLoading }
}
