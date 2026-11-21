import type { ApiEnvelope } from '~/types/business-api'
import { request } from '~/utils/api'
import { runListDedupe, type ListBurstSlot, unwrap } from '~/utils/business/shared'

export interface StyleAnalysisResult {
  assetName: string
  personalityDesc: string
  promptText: string
  remark: string
}

interface StyleAnalysisStatus {
  taskId: string
  status: 'PENDING' | 'SUCCEEDED' | 'FAILED'
  result?: StyleAnalysisResult
  message?: string
}

export interface ActiveStyleAnalysis {
  taskId: string
  imageUrl?: string | null
}

const activeReads = new Map<string, Promise<ActiveStyleAnalysis | null>>()
const activeBurst: ListBurstSlot<ActiveStyleAnalysis | null> = { current: null }
const statusReads = new Map<string, Promise<StyleAnalysisStatus>>()
const statusBurst: ListBurstSlot<StyleAnalysisStatus> = { current: null }

function authKey() {
  return typeof window === 'undefined' ? '' : window.localStorage.getItem('token') || ''
}

export async function submitStyleAnalysis(imageUrl: string, requestId: string) {
  return unwrap(await request.post<ApiEnvelope<StyleAnalysisStatus>>(
    '/api/user/asset/style/analysis/submit', { imageUrl, requestId }, { timeout: 15_000 }
  ))
}

export function getActiveStyleAnalysis() {
  return runListDedupe(authKey(), activeReads, activeBurst, async () =>
    unwrap(await request.post<ApiEnvelope<ActiveStyleAnalysis | null>>('/api/user/asset/style/analysis/active', {}, { timeout: 10_000 })), 0)
}

export function getStyleAnalysisStatus(taskId: string) {
  return runListDedupe(`${authKey()}:${taskId}`, statusReads, statusBurst, async () =>
    unwrap(await request.post<ApiEnvelope<StyleAnalysisStatus>>('/api/user/asset/style/analysis/status', { taskId }, { timeout: 10_000 })), 0)
}
