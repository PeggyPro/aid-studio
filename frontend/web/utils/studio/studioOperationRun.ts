import { useStudioUiStore } from '@/stores/studioUi'
import { studioId } from './studioGraph'

const VOLATILE_NODE_DATA_KEYS = new Set([
  'status', 'errorMessage', 'resultSummary', 'updatedAt', 'operationRunId', 'audioRunId',
  'canvasCurrentTaskId', 'highlightRefNodeId'
])

function stableValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(stableValue)
  if (!value || typeof value !== 'object') return value
  return Object.fromEntries(Object.entries(value as Record<string, unknown>)
    .filter(([key]) => !VOLATILE_NODE_DATA_KEYS.has(key))
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([key, item]) => [key, stableValue(item)]))
}

function fnv1a(value: string): string {
  let hash = 0x811c9dc5
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index)
    hash = Math.imul(hash, 0x01000193)
  }
  return `v1-${(hash >>> 0).toString(36)}`
}

export function createStudioOperationInputHash(scopeKey: string, nodeIds: string[]): string {
  const store = useStudioUiStore.getState()
  const nodes = [...new Set(nodeIds)].map((id) => {
    const node = store.nodes.find((item) => item.id === id)
    return node ? { id: node.id, data: stableValue(node.data) } : { id, missing: true }
  })
  return fnv1a(JSON.stringify({ scopeKey, nodes }))
}

/** 为一组异步写回目标建立持久化 compare-and-set 令牌。 */
export function beginStudioOperationRun(scopeKey: string, nodeIds: string[]): string | null {
  const store = useStudioUiStore.getState()
  const uniqueIds = [...new Set(nodeIds)]
  if (store.scopeKey !== scopeKey || uniqueIds.some((id) => !store.nodes.some((node) => node.id === id))) {
    return null
  }
  const runId = studioId('operation-run')
  for (const nodeId of uniqueIds) store.updateNodeData(nodeId, { operationRunId: runId }, false)
  return runId
}

export function isStudioOperationRunCurrent(scopeKey: string, runId: string, nodeIds: string[]): boolean {
  const store = useStudioUiStore.getState()
  return store.scopeKey === scopeKey && [...new Set(nodeIds)].every((id) =>
    store.nodes.some((node) => node.id === id && node.data.operationRunId === runId)
  )
}

export function clearStudioOperationRun(scopeKey: string, runId: string, nodeIds: string[]): void {
  const store = useStudioUiStore.getState()
  if (store.scopeKey !== scopeKey) return
  for (const nodeId of [...new Set(nodeIds)]) {
    const node = store.nodes.find((item) => item.id === nodeId)
    if (node?.data.operationRunId === runId) store.updateNodeData(nodeId, { operationRunId: undefined }, false)
  }
}

export function settleStudioOperationRun(scopeKey: string, runId: string, nodeIds: string[]): void {
  const store = useStudioUiStore.getState()
  if (store.scopeKey !== scopeKey) return
  for (const nodeId of [...new Set(nodeIds)]) {
    const node = store.nodes.find((item) => item.id === nodeId)
    if (node?.data.operationRunId !== runId && node?.data.audioRunId !== runId) continue
    const pending = node.data.status === 'generating' || node.data.status === 'queued'
    store.updateNodeData(nodeId, {
      operationRunId: undefined,
      audioRunId: node.data.audioRunId === runId ? undefined : node.data.audioRunId,
      ...(pending ? {
        status: node.data.mediaUrl ? 'success' : 'empty',
        resultSummary: '输入已变更，旧任务结果未覆盖当前内容'
      } : {})
    }, false)
  }
}

export function isStudioOperationInputCurrent(
  scopeKey: string,
  inputSnapshotHash: string,
  nodeIds: string[]
): boolean {
  return createStudioOperationInputHash(scopeKey, nodeIds) === inputSnapshotHash
}
