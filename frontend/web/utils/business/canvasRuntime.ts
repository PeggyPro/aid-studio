import type { CanvasId } from '@/types/canvas-api'
import type {
  CanvasRuntimeGroup,
  CanvasRuntimeInvokeRequest,
  CanvasRuntimeMember,
  CanvasRuntimeMemberType,
  CanvasRuntimePage,
  CanvasRuntimeRun,
  CanvasRuntimeRunHistory
} from '@/types/canvas-runtime-api'
import { canvasApiPost } from '@/utils/canvas/canvasApiClient'
import { normalizeCanvasPayload } from './canvas'
import { runListDedupe, stableRequestKey, type ListBurstSlot, unwrap } from './shared'

const ROOT = '/api/user/runtime'

function normalizeRun(run: CanvasRuntimeRun & { id?: CanvasId }): CanvasRuntimeRun {
  // GenericRuntimeService.runView exposes the persistent Run ID as `id`.
  const runId = run.runId ?? run.id
  if (!runId) throw new Error('运行结果缺少 Run ID，请刷新后重试')
  return { ...run, runId }
}

function post<T>(path: string, data: unknown = {}): Promise<T> {
  return canvasApiPost<T>(`${ROOT}${path}`, data).then(unwrap).then(normalizeCanvasPayload)
}

let runtimeMainToken: string | undefined
let runtimeSessionGeneration = 0

function runtimeReadKey(path: string, data: unknown): string {
  const mainToken = typeof window === 'undefined' ? '' : window.localStorage.getItem('token') || ''
  if (mainToken !== runtimeMainToken) {
    runtimeMainToken = mainToken
    runtimeSessionGeneration += 1
  }
  return `${runtimeSessionGeneration}:${path}:${stableRequestKey(data)}`
}

/** Runtime POST reads can be mounted by more than one consumer; merge identical bursts without caching mutations. */
function createRuntimeRead<T>(path: string, burstMs = 450) {
  const inflight = new Map<string, Promise<T>>()
  const burst: ListBurstSlot<T> = { current: null }
  return (data: unknown = {}): Promise<T> => {
    const key = runtimeReadKey(path, data)
    return runListDedupe(key, inflight, burst, () => post<T>(path, data), burstMs)
  }
}

const readRuntimeGroups = createRuntimeRead<CanvasRuntimePage<CanvasRuntimeGroup>>('/catalog/groups')
const readRuntimeMembers = createRuntimeRead<CanvasRuntimePage<CanvasRuntimeMember>>('/catalog/members')
const readRuntimeModels = createRuntimeRead<CanvasRuntimePage<{
  model: CanvasRuntimeMember['models'][number]
  memberCodes: string[]
}>>('/catalog/models')
const readRuntimeRunDetail = createRuntimeRead<CanvasRuntimeRun>('/run/detail', 0)
const readRuntimeRunHistory = createRuntimeRead<CanvasRuntimeRunHistory>('/run/history', 0)

export function canvasRuntimeGroups(data: { parentCode?: string | null; pageNum?: number; pageSize?: number } = {}) {
  return readRuntimeGroups(data)
}

export function canvasRuntimeMembers(data: { groupCode: string; pageNum?: number; pageSize?: number }) {
  return readRuntimeMembers(data)
}

export function canvasRuntimeModels(data: { groupCode: string; pageNum?: number; pageSize?: number }) {
  return readRuntimeModels(data)
}

export function canvasRuntimeInvoke(data: CanvasRuntimeInvokeRequest) {
  return post<CanvasRuntimeRun>('/invoke', data).then(normalizeRun)
}

export function canvasRuntimeRunDetail(runId: CanvasId) {
  return readRuntimeRunDetail({ runId }).then(normalizeRun)
}

export function canvasRuntimeRunHistory(data: {
  memberType?: CanvasRuntimeMemberType
  memberCode?: string
  workspaceId?: CanvasId
  nodeObjectId?: string
  pageNum?: number
  pageSize?: number
}) {
  return readRuntimeRunHistory(data).then((page) => ({
    ...page, items: page.items.map(normalizeRun)
  }))
}

export function canvasRuntimeRespond(runId: CanvasId, answer: string, responseKey: string) {
  return post<CanvasRuntimeRun>('/input/respond', { runId, answer, responseKey }).then(normalizeRun)
}

export function canvasRuntimeCancel(runId: CanvasId) {
  return post<CanvasRuntimeRun>('/run/cancel', { runId }).then(normalizeRun)
}

export type CanvasCreatorKind = 'skill' | 'agent'

export function canvasCreatorPost<T>(kind: CanvasCreatorKind, action: string, data: Record<string, unknown> = {}): Promise<T> {
  return post<T>(`/creator/${kind}/${action}`, data)
}

export function canvasCreatorList<T>(kind: CanvasCreatorKind, data: { keyword?: string; status?: '0' | '1'; pageNum?: number; pageSize?: number } = {}) {
  return canvasCreatorPost<{ items: T[]; total: number; pageNum: number; pageSize: number }>(kind, 'list', data)
}

export function canvasCreatorVersionList<T>(kind: CanvasCreatorKind, id: CanvasId, pageNum = 1) {
  return canvasCreatorPost<{ items: T[]; total: number; currentVersionId?: CanvasId }>(kind, 'version/list', {
    [`${kind}Id`]: id, pageNum, pageSize: 20
  })
}
