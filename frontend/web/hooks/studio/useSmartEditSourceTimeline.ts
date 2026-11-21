'use client'

import { useEffect, useState } from 'react'
import type { StudioCanvasNode, StudioFlowEdge } from '@/types/studio'
import { dispatchSmartEdit, notifySmartEdit, useStudioSmartEdit } from '@/stores/studioSmartEdit'
import { useStudioUiStore } from '@/stores/studioUi'
import { readSmartEditMediaDuration } from '@/utils/studio/smartEditJob'
import { smartEditSources } from '@/utils/studio/studioSmartEdit'
import { appendSmartEditSources, hasValidSmartEditClip, needsSmartEditSourceTimeline, prepareSmartEditSourceTimeline, smartEditAppendSource, type SmartEditAppendSource } from '@/utils/studio/smartEditSourceTimeline'

const sourceFingerprint = (id: string, nodes: StudioCanvasNode[], edges: StudioFlowEdge[]) => JSON.stringify(smartEditSources(id, nodes, edges)
  .filter((source) => source.data.kind !== 'image').map((source) => [source.id, source.data.mediaUrl, source.data.canvasPrimaryResourceId, source.data.canvasResourceIds, source.data.audioDurationSeconds]))
/** A metadata read result belongs only to the project, editor session and source set it started from. */
function isStale(signal: AbortSignal, nodeId: string, scopeKey: string, session: number, sourceKey: string) {
  const current = useStudioUiStore.getState()
  const editor = useStudioSmartEdit.getState()
  return signal.aborted || current.scopeKey !== scopeKey || editor.session !== session || editor.nodeId !== nodeId || sourceFingerprint(nodeId, current.nodes, current.edges) !== sourceKey
}

export function useSmartEditSourceTimeline(node: StudioCanvasNode, canWrite: boolean) {
  const scopeKey = useStudioUiStore((state) => state.scopeKey)
  const sourceKey = useStudioUiStore((state) => sourceFingerprint(node.id, state.nodes, state.edges))
  const draft = useStudioSmartEdit((state) => state.previewTimeline)
  const session = useStudioSmartEdit((state) => state.session)
  const closing = useStudioSmartEdit((state) => state.closing)
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState({ key: '', error: '', ready: false })
  const [append, setAppend] = useState({ key: '', busy: false, error: '' })
  const needed = needsSmartEditSourceTimeline(node.data.smartEdit!)
  const jobRunning = Boolean(node.data.smartEdit!.jobId)
  const key = `${scopeKey}:${node.id}:${session}:${sourceKey}:${attempt}`
  useEffect(() => {
    if (!needed || closing || sourceKey === '[]') return
    const controller = new AbortController()
    const workspace = useStudioUiStore.getState()
    const baseline = workspace.nodes.find((item) => item.id === node.id)?.data.smartEdit?.timeline
    useStudioSmartEdit.setState({ previewTimeline: null, playing: false })
    // Deferring one microtask lets Strict Mode dispose its probe before any media request starts.
    void Promise.resolve().then(() => {
      controller.signal.throwIfAborted()
      return prepareSmartEditSourceTimeline(smartEditSources(node.id, workspace.nodes, workspace.edges), controller.signal)
    }).then((timeline) => {
      if (isStale(controller.signal, node.id, scopeKey, session, sourceKey) || useStudioUiStore.getState().nodes.find((item) => item.id === node.id)?.data.smartEdit?.timeline !== baseline) return
      useStudioSmartEdit.setState({ previewTimeline: timeline })
      setResult({ key, error: '', ready: true })
    }).catch((error: unknown) => {
      if (!controller.signal.aborted) setResult({ key, ready: false, error: error instanceof Error ? error.message : '素材读取失败' })
    })
    return () => controller.abort()
  }, [closing, key, needed, node.id, scopeKey, session, sourceKey])
  const ready = result.key === key && result.ready
  useEffect(() => {
    if (needed && ready && draft && canWrite && !closing) dispatchSmartEdit(node.id, { type: 'replaceTimeline', timeline: draft }, false)
  }, [canWrite, closing, draft, needed, node.id, ready])
  useEffect(() => {
    if (needed || jobRunning || !canWrite || closing || sourceKey === '[]') return
    const controller = new AbortController()
    void Promise.resolve().then(async () => {
      controller.signal.throwIfAborted()
      const workspace = useStudioUiStore.getState()
      const edit = workspace.nodes.find((item) => item.id === node.id)?.data.smartEdit
      if (!edit || edit.jobId) return
      const candidates = smartEditSources(node.id, workspace.nodes, workspace.edges).filter((source) => source.data.kind !== 'image' && !(edit.dismissedSourceIds ?? []).includes(source.id) && !hasValidSmartEditClip(edit, source.id))
      if (!candidates.length) return
      setAppend({ key, busy: true, error: '' })
      const readyToAppend: SmartEditAppendSource[] = []
      let error = ''
      for (const source of candidates) {
        try { readyToAppend.push(smartEditAppendSource(source, await readSmartEditMediaDuration(source, controller.signal))) }
        catch (reason) { controller.signal.throwIfAborted(); error ||= reason instanceof Error ? reason.message : '素材读取失败' }
      }
      controller.signal.throwIfAborted()
      const latest = useStudioUiStore.getState().nodes.find((item) => item.id === node.id)?.data.smartEdit
      if (!isStale(controller.signal, node.id, scopeKey, session, sourceKey) && !useStudioSmartEdit.getState().closing && latest && !latest.jobId && !needsSmartEditSourceTimeline(latest)) {
        const appended = appendSmartEditSources(latest, readyToAppend)
        const written = appended.state === latest || dispatchSmartEdit(node.id, { type: 'replaceTimeline', timeline: appended.state.timeline }, false)
        if (written && appended.skippedForLimit.length) notifySmartEdit(node.id, 'warning', '智能剪辑最多引用 24 个素材', scopeKey)
      }
      setAppend({ key, busy: false, error })
    }).catch((error: unknown) => {
      if (!controller.signal.aborted) setAppend({ key, busy: false, error: error instanceof Error ? error.message : '素材读取失败' })
    })
    return () => controller.abort()
  }, [canWrite, closing, jobRunning, key, needed, node.id, scopeKey, session, sourceKey])
  return {
    edit: needed && ready && draft ? { ...node.data.smartEdit!, timeline: draft } : node.data.smartEdit!,
    loading: needed && sourceKey !== '[]' && result.key !== key,
    error: needed && result.key === key ? result.error : '',
    /** Appending never replaces the preview or blocks editing; only the notice line reflects it. */
    appending: !needed && append.key === key && append.busy,
    appendError: !needed && append.key === key ? append.error : '',
    retry: () => setAttempt((value) => value + 1)
  }
}
