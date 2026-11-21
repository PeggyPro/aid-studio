'use client'

import { useCallback, useEffect, useRef } from 'react'
import { useStudioUiStore } from '@/stores/studioUi'
import { notifySmartEdit, useStudioSmartEdit } from '@/stores/studioSmartEdit'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { exportSmartEditTimeline, smartEditOutputSize, validateSmartEditExport } from '@/utils/studio/smartEditExport'
import { buildSmartEditFcpxml } from '@/utils/studio/smartEditFcpxml'
import { smartEditExportFingerprint } from '@/utils/studio/studioSmartEdit'
import { readSmartEditSourceSize, routeSmartEditCompose, smartEditDecodedClips } from '@/utils/studio/smartEditComposeRoute'
import { cancelSmartEditCompose, placeSmartEditOutput, submitSmartEditCompose, syncSmartEditComposeJobs } from '@/utils/studio/smartEditComposeJob'
import { downloadMediaFileWithToast } from '@/utils/mediaFileDownload'

function download(file: File) {
  const url = URL.createObjectURL(file)
  const link = document.createElement('a'); link.href = url; link.download = file.name
  document.body.appendChild(link); link.click(); link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 30000)
}
const COMPOSE_POLL_MS = 3000
const flights = new Map<string, AbortController>()

/** 服务端成片只随画布轮询：离开画布停止查询，任务本身不取消，回来后按票据继续。 */
export function useSmartEditComposeWatcher(scopeKey: string) {
  const runtime = useCanvasProjectRuntime()
  const hasTicket = useStudioUiStore((state) => state.scopeKey === scopeKey && state.nodes.some((node) => node.data.smartEdit?.compose))
  useEffect(() => {
    if (!runtime || !hasTicket) return
    let stopped = false, timer: ReturnType<typeof setTimeout> | undefined
    const tick = async () => {
      await syncSmartEditComposeJobs(runtime, scopeKey).catch(() => undefined)
      if (!stopped) timer = setTimeout(tick, COMPOSE_POLL_MS)
    }
    void tick()
    return () => { stopped = true; clearTimeout(timer) }
  }, [hasTicket, runtime, scopeKey])
}

export function useSmartEditExport(scopeKey: string) {
  const runtime = useCanvasProjectRuntime()
  const owned = useRef(new Set<string>())
  useEffect(() => {
    const keys = owned.current
    return () => { for (const key of keys) flights.get(key)?.abort(); keys.clear() }
  }, [scopeKey])
  const start = useCallback(async (nodeId: string, mode: 'local' | 'canvas' | 'fcpxml') => {
    const notify = (type: 'info' | 'success' | 'warning' | 'error', text: string) => notifySmartEdit(nodeId, type, text, scopeKey)
    const store = useStudioUiStore.getState()
    const node = store.nodes.find((item) => item.id === nodeId)
    if (!node?.data.smartEdit || store.scopeKey !== scopeKey) return
    const edit = node.data.smartEdit
    if (edit.jobId) { notify('warning', '正在剪辑，请稍后导出'); return }
    if (runtime && !runtime.canMutate) { notify('warning', '当前画布不可导出并保存'); return }
    const key = `${scopeKey}:${nodeId}`
    if (flights.has(key)) { notify('warning', '正在导出'); return }
    const size = smartEditOutputSize(node.data.generationOptions?.aspectRatio)
    if (mode === 'fcpxml') {
      try {
        const xml = buildSmartEditFcpxml(edit.timeline, { projectName: node.data.title, ...size, fps: 30, hiddenTracks: edit.hiddenTracks, baseUrl: window.location.href })
        download(new File([xml], `${node.data.title.replace(/[<>:"/\\|?*]/g, '_')}.fcpxml`, { type: 'application/xml' }))
        notify('info', '工程不打包素材；转场、文字动画及导入差异已写入 XML 注释，请在达芬奇核对。')
      } catch (error) { notify('error', error instanceof Error ? error.message : '导出失败') }
      return
    }
    if (!runtime) { notify('error', '当前环境未接入画布资源上传，请在已保存的自由画布项目中导出'); return }
    try { validateSmartEditExport(edit.timeline) } catch (error) { notify('error', error instanceof Error ? error.message : '导出失败'); return }
    const controller = new AbortController()
    flights.set(key, controller); owned.current.add(key)
    const progress = (value?: number) => useStudioSmartEdit.setState((state) => {
      const next = { ...state.exportProgress }
      if (value === undefined) delete next[key]; else next[key] = value
      return { exportProgress: next }
    })
    progress(0)
    let release: (() => Promise<void>) | undefined
    let renewal: ReturnType<typeof setInterval> | undefined
    try {
      const fingerprint = smartEditExportFingerprint(edit, node.data.generationOptions?.aspectRatio)
      const current = () => {
        controller.signal.throwIfAborted()
        const latest = useStudioUiStore.getState()
        const target = latest.nodes.find((item) => item.id === nodeId)
        if (latest.scopeKey !== scopeKey || !target?.data.smartEdit) throw new Error('画布或剪辑节点已切换，导出未写回')
        if (smartEditExportFingerprint(target.data.smartEdit, target.data.generationOptions?.aspectRatio) !== fingerprint) throw new Error('时间轴已变化，请重新导出；原成片未被覆盖')
        return target
      }
      const clips = smartEditDecodedClips(edit.timeline, edit.hiddenTracks)
      const sizes = new Map<string, number | null | undefined>()
      const missing = new Set<string>()
      for (const clip of clips) {
        const resourceId = clip.sourceResourceId
        const bytes = resourceId ? runtime.resourceById(resourceId)?.fileSize : null
        if (resourceId && typeof bytes === 'number' && Number.isSafeInteger(bytes) && bytes > 0) sizes.set(resourceId, bytes)
        else missing.add(clip.sourceUrl)
      }
      const sourceSizes = new Map(await Promise.all([...missing].map(async (url) =>
        [url, await readSmartEditSourceSize(url, controller.signal)] as const)))
      current()
      const route = routeSmartEditCompose({ timeline: edit.timeline, hiddenTracks: edit.hiddenTracks,
        fileSizeByResourceId: sizes, fileSizeBySourceUrl: sourceSizes })
      if (route.route === 'server') {
        if (edit.compose) throw new Error('该节点已有未完成的成片任务')
        try {
          await submitSmartEditCompose(runtime, scopeKey, nodeId, mode)
          notify('info', '素材较大，已交给服务端合成；关闭剪辑台或离开画布不会中断，回来后可查看进度')
        } catch (error) {
          const detail = error instanceof Error ? error.message : ''
          if (!/接口不存在|404/.test(detail)) throw error
          throw new Error(route.reason === 'over-limit'
            ? '素材总大小超过 500 MB，当前环境暂不支持服务端合成，可导出 FCPXML'
            : '无法确认部分素材大小，当前环境暂不支持服务端合成，可重新选择素材或导出 FCPXML')
        }
        return
      }
      const lease = await runtime.acquireNodeEditLease(nodeId)
      release = lease?.release
      let renewing = false
      if (lease) renewal = setInterval(() => {
        if (renewing) return
        renewing = true
        void lease.renew().catch(() => controller.abort()).finally(() => { renewing = false })
      }, 5000)
      current()
      const file = await exportSmartEditTimeline(edit.timeline, { signal: controller.signal, onProgress: (value) => progress(Math.round(value * 0.9)), name: node.data.title, ...size, hiddenTracks: edit.hiddenTracks })
      current(); progress(92)
      const resource = await runtime.uploadResource(file, 'VIDEO')
      current()
      placeSmartEditOutput(nodeId, mode, { resourceId: String(resource.resourceId), resourceUrl: resource.resourceUrl }, edit)
      progress(100)
      if (mode === 'local') download(file)
      await runtime.saveNow()
      notify('success', mode === 'canvas' ? '成片已添加到画布' : '成片已导出并保存到剪辑节点')
    } catch (error) {
      if (controller.signal.aborted) notify('info', '已取消导出')
      else notify('error', error instanceof Error ? error.message : '导出失败，原成片未被覆盖')
    } finally { clearInterval(renewal); await release?.().catch(() => undefined); flights.delete(key); owned.current.delete(key); progress() }
  }, [runtime, scopeKey])
  const cancel = useCallback((nodeId: string) => {
    const local = flights.get(`${scopeKey}:${nodeId}`)
    if (local) { local.abort(); return }
    if (runtime) void cancelSmartEditCompose(runtime, scopeKey, nodeId).catch((error: unknown) => notifySmartEdit(nodeId, 'error', error instanceof Error ? error.message : '取消失败，请重试', scopeKey))
  }, [runtime, scopeKey])
  const downloadLast = useCallback((nodeId: string) => {
    const node = useStudioUiStore.getState().nodes.find((item) => item.id === nodeId)
    const result = node?.data.smartEdit?.composeDownload
    if (result) void downloadMediaFileWithToast(result.resourceUrl, `${node.data.title}.mp4`, '成片')
  }, [])
  return { start, cancel, downloadLast }
}
