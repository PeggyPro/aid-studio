import type { WorkData, StoryboardPanel } from '@/types'
import type { StudioCanvasNode, StudioFlowEdge, StudioImagePurpose } from '@/types/studio'
import { createStudioEdge, createStudioNode } from './studioGraph'

export interface StudioFlowBridgeGraph {
  nodes: StudioCanvasNode[]
  edges: StudioFlowEdge[]
}

export function importCreationFlowToStudio(formData: WorkData): StudioFlowBridgeGraph {
  const nodes: StudioCanvasNode[] = []
  const edges: StudioFlowEdge[] = []
  const script = createStudioNode('storyboard_script', { x: 38, y: 84 }, {
    title: '故事剧本',
    prompt: formData.storyScript.content,
    resultSummary: formData.storyScript.content.slice(0, 180),
    status: formData.storyScript.content.trim() ? 'success' : 'empty',
    zoneId: 'story-script',
    bindingKey: 'storyScript.content'
  })
  nodes.push(script)

  const assets = [
    ...formData.sceneCharacter.characters.map((title, index) => ({ imagePurpose: 'character' as const, title, bindingKey: `sceneCharacter.characters.${index}` })),
    ...formData.sceneCharacter.scenes.map((title, index) => ({ imagePurpose: 'scene' as const, title, bindingKey: `sceneCharacter.scenes.${index}` })),
    ...formData.sceneCharacter.props.map((title, index) => ({ imagePurpose: 'prop' as const, title, bindingKey: `sceneCharacter.props.${index}` }))
  ]
  assets.forEach((asset, index) => {
    const node = createStudioNode('image', { x: 405 + (index % 3) * 255, y: 84 + Math.floor(index / 3) * 132 }, {
      title: asset.title || `图片 ${index + 1}`,
      imagePurpose: asset.imagePurpose,
      prompt: asset.title,
      status: asset.title ? 'success' : 'empty',
      zoneId: 'scene-character',
      bindingKey: asset.bindingKey
    })
    nodes.push(node)
    edges.push(createStudioEdge(script.id, node.id, 'reference'))
  })

  formData.storyboardScript.panels.forEach((panel, index) => {
    const videoUrl = panel.finalComposeVideoUrl || panel.finalVideoUrl
    const unit = createStudioNode('image', { x: 1438 + (index % 4) * 255, y: 84 + Math.floor(index / 4) * 132 }, {
      title: panel.title || `镜头 ${index + 1}`,
      prompt: panel.description || panel.scriptContent || '',
      resultSummary: panel.description,
      status: 'success',
      imagePurpose: 'storyboard',
      mediaUrl: panel.finalImageUrl || undefined,
      zoneId: 'storyboard-script',
      storyboardId: panel.id,
      bindingKey: `storyboardScript.panels.${panel.id}`
    })
    nodes.push(unit)
    edges.push(createStudioEdge(script.id, unit.id, 'flow'))
    if (!videoUrl) return
    const result = createStudioNode('video', { x: unit.position.x + 235, y: unit.position.y }, {
      title: `${unit.data.title} · 视频`,
      status: 'success',
      mediaUrl: videoUrl,
      zoneId: 'storyboard-video',
      storyboardId: panel.id,
      bindingKey: `storyboardResult.${panel.id}`
    })
    nodes.push(result)
    edges.push(createStudioEdge(unit.id, result.id, 'flow'))
  })
  return { nodes, edges }
}

export function exportStudioToCreationFlow(nodes: StudioCanvasNode[], current: WorkData): Partial<WorkData> {
  const script = nodes.find((node) => node.data.bindingKey === 'storyScript.content')
    ?? nodes.find((node) => node.data.kind === 'storyboard_script')
  const valueFor = (imagePurpose: StudioImagePurpose) => nodes
    .filter((node) => node.data.kind === 'image' && node.data.imagePurpose === imagePurpose)
    .sort(bindingOrder)
    .map((node) => node.data.title.trim())
    .filter(Boolean)
  const panels: StoryboardPanel[] = nodes
    .filter((node) => node.data.kind === 'image' && node.data.imagePurpose === 'storyboard')
    .sort(bindingOrder)
    .map((node, index) => {
      const existing = current.storyboardScript.panels.find((panel) => panel.id === node.data.storyboardId)
      return {
        ...existing,
        id: node.data.storyboardId || existing?.id || `studio-${index + 1}`,
        title: node.data.title,
        description: node.data.prompt || node.data.resultSummary || ''
      }
    })
  return {
    storyScript: { content: script?.data.prompt || script?.data.resultSummary || current.storyScript.content },
    sceneCharacter: {
      characters: valueFor('character'),
      scenes: valueFor('scene'),
      props: valueFor('prop')
    },
    storyboardScript: { panels }
  }
}

function bindingOrder(a: StudioCanvasNode, b: StudioCanvasNode): number {
  return (a.data.bindingKey || a.id).localeCompare(b.data.bindingKey || b.id, undefined, { numeric: true })
}
