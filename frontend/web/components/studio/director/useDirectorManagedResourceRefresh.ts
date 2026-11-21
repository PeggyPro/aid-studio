'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import type { DirectorSceneData } from '@/types/studio'
import { useStudioUiStore } from '@/stores/studioUi'
import { canvasRegisteredModelResourcesRead, type CanvasRegisteredModelResource } from '@/utils/canvas/canvasRegisteredModelResources'
import { directorManagedResourceIds, refreshDirectorManagedResourceLinks } from './directorManagedResourceRefresh'

type RefreshState = { key: string; status: 'loading' | 'ready' | 'error'; error: string }

export function useDirectorManagedResourceRefresh(input: {
  scene: DirectorSceneData
  nodeId: string
  scopeKey: string
  workspaceId?: string
  getScene: () => DirectorSceneData
  applyScene: (scene: DirectorSceneData) => void
}) {
  const { scene, nodeId, scopeKey, workspaceId } = input
  const key = JSON.stringify([scopeKey, nodeId, scene.id, workspaceId])
  const current = useRef(input)
  useEffect(() => { current.current = input }, [input])
  const running = useRef<Promise<void> | null>(null)
  const generation = useRef(0)
  const [state, setState] = useState<RefreshState>(() => ({ key, status: 'loading', error: '' }))
  const refresh = useCallback((): Promise<void> => {
    if (running.current) return running.current
    const epoch = ++generation.current
    const snapshot = current.current
    const sceneId = snapshot.getScene().id
    const isCurrent = () => {
      const editor = useStudioUiStore.getState()
      return generation.current === epoch && editor.scopeKey === scopeKey && editor.directorOpenNodeId === nodeId
        && current.current.scopeKey === scopeKey && current.current.nodeId === nodeId && current.current.workspaceId === workspaceId
        && editor.nodes.some(node => node.id === nodeId) && current.current.getScene().id === sceneId
    }
    const promise = (async () => {
      try {
        if (!isCurrent()) return
        const ids = directorManagedResourceIds(snapshot.getScene())
        if (!ids.length) { if (isCurrent()) setState({ key, status: 'ready', error: '' }); return }
        setState({ key, status: 'loading', error: '' })
        if (!workspaceId) throw new Error('请在原画布项目中打开模型，场景已保留')
        const rows: CanvasRegisteredModelResource[] = []
        for (let offset = 0; offset < ids.length; offset += 100) {
          rows.push(...await canvasRegisteredModelResourcesRead(workspaceId, ids.slice(offset, offset + 100)))
          if (!isCurrent()) return
        }
        if (!isCurrent()) return
        snapshot.applyScene(refreshDirectorManagedResourceLinks(current.current.getScene(), rows, ids))
        setState({ key, status: 'ready', error: '' })
      } catch (error) {
        if (isCurrent()) setState({ key, status: 'error', error: error instanceof Error ? error.message : '模型资源读取失败，场景已保留，请重试' })
      }
    })()
    running.current = promise
    void promise.finally(() => { if (running.current === promise) running.current = null })
    return promise
  }, [key, nodeId, scopeKey, workspaceId])
  const invalidate = useCallback(() => { generation.current++; running.current = null }, [])
  useEffect(() => {
    void refresh()
    return invalidate
  }, [refresh, invalidate])
  return { ...(state.key === key ? state : { key, status: 'loading' as const, error: '' }), refresh }
}
