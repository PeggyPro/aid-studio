'use client'

import { useEffect, useState } from 'react'
import type { CanvasTaskHistory } from '@/types/canvas-api'
import type { CanvasProjectWorkspaceRuntime } from './useCanvasProjectWorkspace'
import { canvasImageGalleryResults, type CanvasImageGalleryResult } from '@/utils/canvas/canvasImageGallery'

const inFlight = new Map<string, Promise<CanvasTaskHistory>>()

function loadOnce(key: string, load: () => Promise<CanvasTaskHistory>): Promise<CanvasTaskHistory> {
  const pending = inFlight.get(key)
  if (pending) return pending
  const request = load().finally(() => { inFlight.delete(key) })
  inFlight.set(key, request)
  return request
}

/** The gallery is a view of server history, never part of NODE input metadata. */
export function useCanvasImageGallery(input: {
  runtime: CanvasProjectWorkspaceRuntime | null
  nodeId: string
  taskId?: string
  enabled: boolean
}) {
  const [state, setState] = useState<{ key: string; results: CanvasImageGalleryResult[]; error: boolean } | null>(null)
  const [retryKey, setRetryKey] = useState(0)
  const workspaceId = String(input.runtime?.project?.workspace.workspaceId || '')
  const key = `${workspaceId}:${input.nodeId}:${input.taskId || ''}`
  const loadHistory = input.runtime?.loadNodeHistory

  useEffect(() => {
    if (!input.enabled || !loadHistory || !workspaceId || !input.taskId) return
    let active = true
    let timer: ReturnType<typeof setTimeout> | null = null
    const load = async (attempt: number) => {
      try {
        const history = await loadOnce(key, () => loadHistory(input.nodeId))
        if (!active) return
        const results = canvasImageGalleryResults(history, input.taskId!)
        setState({ key, results, error: false })
        const task = history.items.find((item) => String(item.taskId) === input.taskId)
        const awaitingPersistence = !task || task.results.length === 0
          || task.results.some((result) => result.persisting)
        if (awaitingPersistence && attempt < 3) {
          timer = setTimeout(() => { void load(attempt + 1) }, 2000)
        }
      } catch {
        if (!active) return
        if (attempt < 3) timer = setTimeout(() => { void load(attempt + 1) }, 2000)
        else setState({ key, results: [], error: true })
      }
    }
    void load(0)
    return () => {
      active = false
      if (timer) clearTimeout(timer)
    }
  }, [input.enabled, input.nodeId, input.taskId, key, loadHistory, retryKey, workspaceId])

  return {
    results: input.enabled && state?.key === key ? state.results : [],
    error: input.enabled && state?.key === key && state.error,
    retry: () => setRetryKey((value) => value + 1)
  }
}
