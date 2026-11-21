import type { StudioCanvasNode, StudioMediaKind, StudioNodeData } from '@/types/studio'

function resolvedMediaKind(data: StudioNodeData): StudioMediaKind {
  return data.mediaKind
    ?? (data.kind === 'voice' ? 'audio' : data.kind === 'video' ? 'video' : data.kind === 'text' || data.kind === 'storyboard_script' ? 'text' : 'image')
}

/**
 * 节点上传、生成或改名后同步所有 node attachment 引用，并维护视频首尾帧 URL。
 * 这是引用数据的统一更新边界，避免预设源节点后续上传后下游仍保留空缩略图。
 */
export function patchStudioNodeAndReferences(
  nodes: StudioCanvasNode[],
  nodeId: string,
  patch: Partial<StudioNodeData>
): StudioCanvasNode[] {
  const currentSource = nodes.find((node) => node.id === nodeId)
  if (!currentSource) return nodes
  const now = new Date().toISOString()
  const nextSource: StudioCanvasNode = {
    ...currentSource,
    data: { ...currentSource.data, ...patch, updatedAt: now }
  }
  const previousUrl = currentSource.data.mediaUrl
  const nextUrl = nextSource.data.mediaUrl
  const styleNode = nextSource.data.kind === 'style_library'
  const nextKind = styleNode ? 'text' : resolvedMediaKind(nextSource.data)

  return nodes.map((node) => {
    if (node.id === nodeId) return nextSource
    const refs = node.data.composerRefs
    if (!refs?.some((ref) => ref.nodeId === nodeId)) return node

    const composerRefs = refs.map((ref) => ref.nodeId === nodeId
      ? { ...ref, title: nextSource.data.title, mediaKind: nextKind, previewUrl: nextUrl }
      : ref)
    let generationOptions = node.data.generationOptions
    const oldStyleId = currentSource.data.generationOptions?.styleId
    if (styleNode && node.data.kind === 'image' && generationOptions?.styleId === oldStyleId) {
      const style = nextSource.data.generationOptions
      generationOptions = {
        ...generationOptions,
        styleId: style?.styleId,
        styleName: style?.styleName ?? nextSource.data.title,
        stylePreviewUrl: style?.stylePreviewUrl ?? nextUrl,
        stylePrompt: style?.stylePrompt
      }
    }
    if (node.data.kind === 'video') {
      generationOptions = { ...generationOptions }
      if (previousUrl && generationOptions.startFrameUrl === previousUrl) generationOptions.startFrameUrl = nextUrl
      if (previousUrl && generationOptions.endFrameUrl === previousUrl) generationOptions.endFrameUrl = nextUrl
      if (!previousUrl && nextUrl && nextKind === 'image') {
        const frameRefs = composerRefs.filter((ref) => ref.channel !== 'mention' && ref.mediaKind === 'image')
        const frameIndex = frameRefs.findIndex((ref) => ref.nodeId === nodeId)
        if (frameIndex === 0 && !generationOptions.startFrameUrl) generationOptions.startFrameUrl = nextUrl
        if (frameIndex === 1 && generationOptions.endFrameEnabled && !generationOptions.endFrameUrl) {
          generationOptions.endFrameUrl = nextUrl
        }
      }
    }
    return {
      ...node,
      data: { ...node.data, composerRefs, generationOptions, updatedAt: now }
    }
  })
}
