'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { message } from 'antd'
import { createStudioTask, useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode, StudioVideoClipSession } from '@/types/studio'
import { placeStudioNodeWithoutOverlap, toStudioNodeRect } from '@/utils/studio/studioNodePlacement'
import { exportStudioVideo, StudioVideoExportCompatibilityError } from '@/utils/studio/studioVideoExport'
import { uploadVideoToOssWithToast } from '@/utils/ossUpload'
import { runStudioVideoOperation } from './useStudioVideoOperation'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'

export function useStudioVideoTransform(node: StudioCanvasNode, session: StudioVideoClipSession) {
  const canvasRuntime = useCanvasProjectRuntime()
  const [busy, setBusy] = useState(false)
  const [progress, setProgress] = useState(0)
  const controller = useRef<AbortController | null>(null)
  useEffect(() => () => controller.current?.abort(), [])
  const confirm = useCallback(async () => {
    if (controller.current || !node.data.mediaUrl) return
    const abort = new AbortController()
    controller.current = abort
    setBusy(true)
    setProgress(0)
    const store = useStudioUiStore.getState()
    const scope = store.scopeKey
    const sourceUrl = node.data.mediaUrl
    const mode = session.mode === 'crop' ? 'crop' : 'trim'
    const label = mode === 'crop' ? '画面裁剪' : '视频截取'
    const position = placeStudioNodeWithoutOverlap(
      { x: node.position.x + 350, y: node.position.y }, { width: 320, height: 180 },
      store.nodes.map(toStudioNodeRect))
    const targetId = store.addNode('video', position, {
      title: `${node.data.title}-${label}`, zoneId: node.data.zoneId, storyboardId: node.data.storyboardId,
      status: 'generating', mediaKind: 'video', videoPurpose: mode, composerHidden: true,
      canvasTransient: Boolean(canvasRuntime),
      mediaAspectRatio: node.data.mediaAspectRatio,
      videoToolOptions: { sourceNodeId: node.id, ...(mode === 'trim' ? { clipRange: session.range } : { cropRect: session.cropRect }) },
      resultSummary: `${label}处理中`
    })
    canvasRuntime?.reserveGenerationNode(targetId)
    store.addConnection({ source: node.id, target: targetId, sourceHandle: null, targetHandle: null }, 'reference')
    store.selectNodes([node.id])
    const task = createStudioTask(`${node.data.title} · ${label}`, { nodeId: targetId, stage: '本地导出中', status: 'running', progress: 0 })
    store.upsertTask(task)
    const current = () => {
      const state = useStudioUiStore.getState()
      return state.scopeKey === scope && state.nodes.some((item) => item.id === targetId) ? state : null
    }
    const requireSource = () => {
      abort.signal.throwIfAborted()
      const state = current()
      if (!state) throw new Error('结果节点或画布已变化')
      if (state.nodes.find(item => item.id === node.id)?.data.mediaUrl !== sourceUrl) {
        throw new Error('源视频已变化，请重新处理')
      }
      return state
    }
    let cloudSubmitted = false
    const markCancelled = () => {
      if (cloudSubmitted) return
      current()?.updateNodeData(targetId, { status: 'failed', errorMessage: '视频处理已取消', resultSummary: '视频处理已取消' })
      current()?.patchTask(task.id, { status: 'failed', stage: '已取消', errorMessage: '视频处理已取消' })
    }
    abort.signal.addEventListener('abort', markCancelled, { once: true })
    const report = (value: number, stage: string) => {
      if (abort.signal.aborted) return
      setProgress(Math.round(value))
      current()?.patchTask(task.id, { progress: Math.round(value), stage })
    }
    try {
      let complete = false
      try {
        const result = await exportStudioVideo({ url: sourceUrl, name: `${node.data.title}-${label}`,
          ...(mode === 'trim' ? { range: session.range } : { cropRect: session.cropRect }),
          signal: abort.signal, onProgress: (value) => report(value * 0.85, '本地导出中') })
        requireSource()
        report(85, '上传视频中')
        const resource = canvasRuntime ? await canvasRuntime.uploadResource(result.file, 'VIDEO') : null
        const url = resource?.resourceUrl ?? await uploadVideoToOssWithToast(result.file, (value) => report(85 + value * 0.15, '上传视频中'))
        requireSource()
        if (!url) throw new Error('视频上传失败，请重试')
        current()?.updateNodeData(targetId, { status: 'success', mediaUrl: url, mediaKind: 'video', canvasTransient: false,
          ...(resource ? { canvasPrimaryResourceId: String(resource.resourceId),
            canvasResourceIds: [String(resource.resourceId)] } : {}),
          mediaAspectRatio: result.width / result.height, errorMessage: undefined, resultSummary: `${label}完成` })
        current()?.patchTask(task.id, { status: 'succeeded', stage: '已完成', progress: 100 })
        complete = true
        if (canvasRuntime) {
          try { await canvasRuntime.saveNow() }
          catch { message.error('视频已完成，画布保存失败，请重试保存') }
        }
      } catch (error) {
        abort.signal.throwIfAborted()
        if (!(error instanceof StudioVideoExportCompatibilityError)) throw error
        if (canvasRuntime) {
          throw new Error('当前浏览器无法处理此视频，请换用兼容格式后重试')
        }
        requireSource()
        current()?.removeTask(task.id)
        cloudSubmitted = true
        setProgress(0)
        message.info('本地无法处理此视频，正在尝试云端处理')
        complete = await runStudioVideoOperation({ source: node, targetNodeId: targetId,
          label, operation: mode === 'trim' ? 'TRIM' : 'CROP',
          onProgress: (value) => report(value, '云端处理中'),
          ...(mode === 'trim' ? { clipRange: session.range } : { cropRect: session.cropRect }) })
      }
      if (complete && !abort.signal.aborted) {
        const state = current()
        if (state?.videoClipSession === session) {
          state.closeVideoClipSession(session)
          state.selectNodes([targetId])
          window.dispatchEvent(new CustomEvent('studio:focus-node', { detail: { nodeId: targetId } }))
        }
      }
    } catch (error) {
      const errorMessage = abort.signal.aborted ? '视频处理已取消' : error instanceof Error ? error.message : `${label}失败`
      current()?.updateNodeData(targetId, { status: 'failed', errorMessage, resultSummary: errorMessage })
      current()?.patchTask(task.id, { status: 'failed', stage: '处理未完成', errorMessage })
      if (!abort.signal.aborted) message.error(errorMessage)
    } finally {
      canvasRuntime?.releaseGenerationNode(targetId)
      abort.signal.removeEventListener('abort', markCancelled)
      controller.current = null
      if (!abort.signal.aborted) setBusy(false)
    }
  }, [canvasRuntime, node, session])
  return { busy, progress, confirm }
}
