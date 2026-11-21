import { useStudioUiStore } from '@/stores/studioUi'
import type { DirectorSceneData } from '@/types/studio'
import { createDefaultDirectorScene } from './directorScene'
import { studioNodeToComposerRef } from './studioReferencePicker'

export const DIRECTOR_FINISH_PROMPT =
  '素材职责：白模视频只提供机位运动、切点、人物走位与速度；灰白几何与色块人形不保留。\n参考图定义美术、材质、光影与服饰细节。\n保持：同一主体、切点对齐、硬切无转场。'
/** 仅创建3D预演节点（及可选连线），不自动打开舞台；用户从节点「打开3D预演」进入。 */
export function createPrevizFromImage(sourceNodeId?: string, position?: { x: number; y: number }) {
  const store = useStudioUiStore.getState()
  const source = store.nodes.find((n) => n.id === sourceNodeId)
  store.beginGraphTransaction()
  const scene = createDefaultDirectorScene()
  scene.name = '主场景'
  const id = store.addNode(
    'director',
    position ?? (source ? { x: source.position.x + 360, y: source.position.y } : undefined),
    { title: '3D预演', directorSceneId: scene.id },
  )
  store.setDirectorScene(scene)
  if (source)
    store.addConnection({ source: source.id, target: id, sourceHandle: null, targetHandle: null }, 'reference')
  store.commitGraphTransaction()
  return id
}
export function addDirectorPrevizVideo(
  nodeId: string,
  sceneId: string,
  media: {
    url: string
    mediaId: string
    width: number
    height: number
    duration: number
    recordMode: 'active_camera' | 'director'
    burnedNameplates: boolean
  },
) {
  const store = useStudioUiStore.getState()
  const source = store.nodes.find((n) => n.id === nodeId)
  if (!source) throw new Error('3D预演节点已删除')
  store.beginGraphTransaction()
  const id = store.addNode(
    'video',
    { x: source.position.x + 360, y: source.position.y + 100 },
    {
      title: `白模预演 · ${media.duration}s`,
      status: 'success',
      mediaKind: 'video',
      mediaUrl: media.url,
      mediaId: media.mediaId,
      previzOfSceneId: sceneId,
      canvasResourceIds: [media.mediaId],
      canvasPrimaryResourceId: media.mediaId,
      operationOrigin: 'director_recording',
      previzOfNodeId: nodeId,
      previzRecordMode: media.recordMode,
      previzFps: 30,
      previzWidth: media.width,
      previzHeight: media.height,
      previzBurnedNameplates: media.burnedNameplates,
    },
  )
  store.addConnection({ source: nodeId, target: id, sourceHandle: null, targetHandle: null }, 'reference')
  store.commitGraphTransaction()
  store.selectNodes([id])
  return id
}

export function beginDirectorPrevizVideo(nodeId: string, sceneId: string) {
  const store = useStudioUiStore.getState()
  const source = store.nodes.find(node => node.id === nodeId)
  if (!source) throw new Error('3D预演节点已删除')
  store.beginGraphTransaction()
  const id = store.addNode('video', { x: source.position.x + 360, y: source.position.y + 100 }, {
    title: '白模预演 · 正在录制', status: 'generating', mediaKind: 'video',
    canvasTransient: true, operationOrigin: 'director_recording', previzOfSceneId: sceneId, previzOfNodeId: nodeId
  })
  store.addConnection({ source: nodeId, target: id, sourceHandle: null, targetHandle: null }, 'reference')
  store.commitGraphTransaction(); store.selectNodes([id]); return id
}
export function findDirectorPrevizVideo(nodeId: string, sceneId: string) {
  const { nodes, edges } = useStudioUiStore.getState()
  const outputs = new Set(edges.filter(edge => edge.source === nodeId && edge.data?.kind === 'reference').map(edge => edge.target))
  return nodes.findLast(node => {
    const data = node.data
    if (data.kind !== 'video' || data.status !== 'success' || !data.mediaUrl || data.canvasTransient) return false
    if (data.previzOfSceneId || data.previzOfNodeId) {
      return data.previzOfSceneId === sceneId && data.previzOfNodeId === nodeId
    }
    // Older timeline exports only recorded provenance on the reference edge.
    return data.operationOrigin === 'director_recording' && outputs.has(node.id)
  })
}

export function openDirectorFinishComposer(nodeId: string, scene: DirectorSceneData) {
  const store = useStudioUiStore.getState()
  const video = findDirectorPrevizVideo(nodeId, scene.id)
  if (!video) throw new Error('没有可用的白模视频，请重新录制或上传')
  const image = store.nodes.find(node => node.data.kind === 'image' && node.data.mediaUrl
    && scene.scenePackage?.sourceImageMediaIds.includes(node.data.mediaId ?? ''))
    ?? store.nodes.find(node => node.data.kind === 'image' && node.data.mediaUrl && scene.sourceNodeIds?.includes(node.id))
  const references = image ? [video, image] : [video]
  store.beginGraphTransaction()
  const id = store.addNode(
    'video',
    { x: video.position.x + 360, y: video.position.y },
    {
      title: '预演成片',
      operationOrigin: 'director_reference',
      status: 'empty',
      prompt: DIRECTOR_FINISH_PROMPT,
      generationOptions: { videoMode: 'multiParam' },
      composerRefs: references.map(studioNodeToComposerRef),
    },
  )
  for (const source of references) {
    store.addConnection({ source: source.id, target: id, sourceHandle: null, targetHandle: null }, 'reference')
  }
  store.commitGraphTransaction()
  store.selectNodes([id])
  return { hasReferenceImage: Boolean(image) }
}
