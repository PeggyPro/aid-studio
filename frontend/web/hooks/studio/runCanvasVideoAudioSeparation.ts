import { message } from 'antd'
import type { StudioCanvasNode } from '@/types/studio'
import type { CanvasProjectWorkspaceRuntime } from './useCanvasProjectWorkspace'
import { createStudioTask, useStudioUiStore } from '@/stores/studioUi'
import { placeStudioNodeWithoutOverlap, toStudioNodeRect } from '@/utils/studio/studioNodePlacement'
import { exportStudioVideo } from '@/utils/studio/studioVideoExport'

/** 免费的确定性拆轨：只处理含音轨的 MP4，两个上传结果一起写入画布。 */
export async function runCanvasVideoAudioSeparation(
  source: StudioCanvasNode,
  runtime: CanvasProjectWorkspaceRuntime
): Promise<boolean> {
  if (!runtime.canEdit || !source.data.mediaUrl) {
    message.error('当前视频不可编辑或没有可用源文件')
    return false
  }
  const store = useStudioUiStore.getState()
  const scope = store.scopeKey
  const sourceUrl = source.data.mediaUrl
  const occupied = store.nodes.map(toStudioNodeRect)
  const videoPosition = placeStudioNodeWithoutOverlap(
    { x: source.position.x + 350, y: source.position.y }, { width: 320, height: 180 }, occupied)
  occupied.push({ x: videoPosition.x, y: videoPosition.y, width: 320, height: 180 })
  const audioPosition = placeStudioNodeWithoutOverlap(
    { x: videoPosition.x + 350, y: videoPosition.y }, { width: 320, height: 180 }, occupied)
  const common = { zoneId: source.data.zoneId, storyboardId: source.data.storyboardId,
    status: 'generating' as const, composerHidden: true, canvasTransient: true,
    videoToolOptions: { sourceNodeId: source.id }, resultSummary: '正在分离音视频' }
  const videoNodeId = store.addNode('video', videoPosition, {
    ...common, title: `${source.data.title}-无声视频`, mediaKind: 'video',
    videoPurpose: 'audio_separation', mediaAspectRatio: source.data.mediaAspectRatio
  })
  const audioNodeId = store.addNode('voice', audioPosition, {
    ...common, title: `${source.data.title}-原音轨`, mediaKind: 'audio'
  })
  runtime.reserveGenerationNode(videoNodeId)
  runtime.reserveGenerationNode(audioNodeId)
  store.addConnection({ source: source.id, target: videoNodeId, sourceHandle: null, targetHandle: null }, 'reference')
  store.addConnection({ source: source.id, target: audioNodeId, sourceHandle: null, targetHandle: null }, 'reference')
  store.selectNodes([videoNodeId, audioNodeId])
  window.requestAnimationFrame(() => window.dispatchEvent(
    new CustomEvent('studio:focus-node', { detail: { nodeId: videoNodeId } })))
  const task = createStudioTask(`${source.data.title} · 音视频分离`, {
    nodeId: videoNodeId, targetNodeIds: [videoNodeId, audioNodeId], resultKind: 'video_audio',
    status: 'running', stage: '本地拆分音轨', progress: 0
  })
  store.upsertTask(task)
  const controller = new AbortController()
  const live = () => {
    const current = useStudioUiStore.getState()
    return current.scopeKey === scope
      && current.nodes.some((node) => node.id === videoNodeId)
      && current.nodes.some((node) => node.id === audioNodeId)
      && current.nodes.find((node) => node.id === source.id)?.data.mediaUrl === sourceUrl
  }
  let outputsRegistered = false
  try {
    const result = await exportStudioVideo({
      url: sourceUrl, name: `${source.data.title}-音视频分离`, splitAudio: true,
      signal: controller.signal,
      onProgress: (percent) => {
        if (live()) useStudioUiStore.getState().patchTask(task.id, {
          progress: Math.round(percent * 0.8), stage: '本地拆分音轨'
        })
      }
    })
    if (!result.audioFile) throw new Error('源视频没有可提取的音轨')
    if (!live()) return false
    const video = await runtime.uploadResource(result.file, 'VIDEO')
    if (!live()) return false
    useStudioUiStore.getState().patchTask(task.id, { progress: 90, stage: '上传原音轨' })
    const audio = await runtime.uploadResource(result.audioFile, 'AUDIO')
    if (!live()) return false
    const current = useStudioUiStore.getState()
    current.updateNodeData(videoNodeId, {
      status: 'success', canvasTransient: false, mediaUrl: video.resourceUrl, mediaKind: 'video',
      canvasPrimaryResourceId: String(video.resourceId), canvasResourceIds: [String(video.resourceId)],
      errorMessage: undefined, resultSummary: '无声视频已保存'
    })
    current.updateNodeData(audioNodeId, {
      status: 'success', canvasTransient: false, mediaUrl: audio.resourceUrl, mediaKind: 'audio',
      canvasPrimaryResourceId: String(audio.resourceId), canvasResourceIds: [String(audio.resourceId)],
      errorMessage: undefined, resultSummary: '原音轨已保存'
    })
    outputsRegistered = true
    await runtime.saveNow()
    if (!live()) return false
    useStudioUiStore.getState().patchTask(task.id, {
      status: 'succeeded', progress: 100, stage: '已完成',
      completedNodeIds: [videoNodeId, audioNodeId]
    })
    message.success('音视频分离完成')
    return true
  } catch (error) {
    const reason = error instanceof Error ? error.message : '音视频分离失败'
    if (outputsRegistered) {
      if (live()) useStudioUiStore.getState().patchTask(task.id, {
        status: 'waiting', stage: '资源已上传，画布保存待重试', errorMessage: reason
      })
      message.warning(`音视频已分离并上传，画布保存未确认：${reason}`)
      return false
    }
    if (live()) {
      const current = useStudioUiStore.getState()
      current.updateNodeData(videoNodeId, { status: 'failed', errorMessage: reason, resultSummary: reason })
      current.updateNodeData(audioNodeId, { status: 'failed', errorMessage: reason, resultSummary: reason })
      current.patchTask(task.id, { status: 'failed', stage: '处理失败', errorMessage: reason })
    }
    message.error(reason)
    return false
  } finally {
    controller.abort()
    runtime.releaseGenerationNode(videoNodeId)
    runtime.releaseGenerationNode(audioNodeId)
  }
}
