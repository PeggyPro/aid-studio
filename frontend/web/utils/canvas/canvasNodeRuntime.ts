import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioNodeData } from '@/types/studio'
import { patchStudioGeneratingEdges } from '@/utils/studio/studioGeneratingEdges'

/** Server task state is not a node edit: never autosave it or advance the input version. */
export function updateCanvasNodeRuntime(scopeKey: string, nodeId: string, patch: Pick<Partial<StudioNodeData>, 'status' | 'errorMessage' | 'operationRunId' | 'canvasCurrentTaskId'>): void {
  useStudioUiStore.setState((state) => {
    if (state.scopeKey !== scopeKey) return state
    const nodes = state.nodes.map((node) => node.id === nodeId ? { ...node, data: { ...node.data, ...patch } } : node)
    return { nodes, edges: patchStudioGeneratingEdges(state.edges, nodes) }
  })
}
