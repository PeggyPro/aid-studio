import { useStudioUiStore } from '@/stores/studioUi'
import { studioRemoteMediaUrlProblem } from '@/utils/mediaUrlPolicy'
import { patchStudioNodeAndReferences } from './studioNodeReferenceSync'
import { patchStudioGeneratingEdges } from './studioGeneratingEdges'
import { createStudioEdge, createStudioNode } from './studioGraph'
import { placeStudioNodeWithoutOverlap, toStudioNodeRect } from './studioNodePlacement'
import { studioNodeDimensions } from './studioNodeLod'

export interface StudioImageResult {
  imageUrl?: string
  recordId?: number
  imageId?: number
}

/** 同一任务的所有输出原子落图；确定性节点 ID 让 SSE 与恢复共用且不会重复派生。 */
export function applyStudioImageResults(
  targetId: string,
  items: StudioImageResult[],
  summary: string
): boolean {
  let results = items
    .filter(
      (item, index, all) =>
        item.imageUrl &&
        !studioRemoteMediaUrlProblem(item.imageUrl) &&
        all.findIndex((other) => other.imageUrl === item.imageUrl) === index
    )
    .slice(0, 4)
  if (!results.length) return false
  const state = useStudioUiStore.getState()
  // 流程画布维持原有单目标最后一张回写语义；多结果节点仅属于自由画布。
  if (state.studioMode !== 'free') results = results.slice(-1)
  const target = state.nodes.find((node) => node.id === targetId)
  if (!target) return false
  const sourceId = state.edges.find(
    (edge) => edge.target === targetId && edge.data?.kind === 'reference'
  )?.source
  let nodes = [...state.nodes]
  const edges = [...state.edges]
  const occupied = nodes.map(toStudioNodeRect)
  results.forEach((item, index) => {
    const id = index === 0 ? targetId : `${targetId}-output-${index + 1}`
    const existingIndex = nodes.findIndex((node) => node.id === id)
    const recordId = item.recordId ?? item.imageId
    const data = {
      ...target.data,
      status: 'success' as const,
      mediaKind: 'image' as const,
      mediaUrl: item.imageUrl,
      operationRunId: undefined,
      errorMessage: undefined,
      resultSummary: summary,
      ...(index ? { title: `${target.data.title} · ${index + 1}` } : {}),
      genRecordId:
        recordId && Number.isSafeInteger(Number(recordId)) && Number(recordId) > 0
          ? Number(recordId)
          : undefined,
      updatedAt: new Date().toISOString()
    }
    if (existingIndex >= 0) nodes = patchStudioNodeAndReferences(nodes, id, data)
    else {
      const position = placeStudioNodeWithoutOverlap(
        { x: target.position.x, y: target.position.y + index * 240 },
        studioNodeDimensions(target),
        occupied
      )
      const created = { ...createStudioNode('image', position, data), id, selected: false }
      nodes.push(created)
      occupied.push(toStudioNodeRect(created))
      if (sourceId) edges.push(createStudioEdge(sourceId, id, 'reference'))
    }
  })
  useStudioUiStore.setState({
    nodes,
    edges: patchStudioGeneratingEdges(edges, nodes),
    dirtyRevision: state.dirtyRevision + 1
  })
  return true
}
