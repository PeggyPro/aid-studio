import type { StudioCanvasNode, StudioFlowEdge, StudioNodeKind, StudioWorkspaceData } from '@/types/studio'
import { createEmptyStudioWorkspace } from './mockStudioApi'
import { STUDIO_NODE_KIND_ORDER } from './studioNodeKind'

const KINDS: StudioNodeKind[] = STUDIO_NODE_KIND_ORDER

export function createStudioPerfFixture(scopeKey: string, count = 3000): StudioWorkspaceData {
  const workspace = createEmptyStudioWorkspace(scopeKey, 'free')
  const now = new Date(0).toISOString()
  const columns = Math.ceil(Math.sqrt(count * 1.7))
  const nodes: StudioCanvasNode[] = Array.from({ length: count }, (_, index) => {
    const kind = KINDS[index % KINDS.length]
    return {
      id: `perf-node-${index}`,
      type: 'studioNode',
      position: { x: (index % columns) * 250, y: Math.floor(index / columns) * 192 },
      data: {
        kind,
        title: `性能节点 ${index + 1}`,
        subtitle: index % 11 === 0 ? '含预览媒体' : 'Canvas LOD 验收',
        status: index % 97 === 0 ? 'failed' : index % 13 === 0 ? 'success' : 'empty',
        prompt: `性能夹具节点 ${index + 1}`,
        mediaKind: index % 11 === 0 ? 'image' : undefined,
        errorMessage: index % 97 === 0 ? '性能夹具失败态' : undefined,
        createdAt: now,
        updatedAt: now
      }
    }
  })
  const edges: StudioFlowEdge[] = nodes.slice(1).map((node, index) => ({
    id: `perf-edge-${index}`,
    source: nodes[index].id,
    target: node.id,
    type: 'smoothstep',
    data: { kind: 'flow', label: '流程' }
  }))

  return {
    ...workspace,
    title: `AID Studio · ${count} 节点性能夹具`,
    nodes,
    edges,
    viewport: { x: 24, y: 64, zoom: 0.16 }
  }
}
