import type { ApiEnvelope } from '@/types/business-api'
import type { StudioVideoSubjectDetectData, StudioVideoSubjectDetectRequest, SubjectCandidate } from '@/types/studio-video-subject'
import { request } from '@/utils/api'
import { canvasApiPost } from '@/utils/canvas/canvasApiClient'
import { unwrap } from '@/utils/business/shared'
import { runStudioRequestSingleFlight } from '@/utils/studio/studioRequestSingleFlight'
import { fetchUserTaskDetailOnce, waitUserTaskSseTerminal, TASK_SSE_TIMEOUT_MS } from '@/hooks/useTaskSseFollow'

function candidatesFrom(raw: unknown): SubjectCandidate[] {
  if (typeof raw === 'string') { try { return candidatesFrom(JSON.parse(raw)) } catch { return [] } }
  if (!raw || typeof raw !== 'object') return []
  const obj = raw as Record<string, unknown>
  if (!Array.isArray(obj.candidates)) return obj.data ? candidatesFrom(obj.data) : []
  return obj.candidates.filter((candidate): candidate is SubjectCandidate => {
    if (!candidate || typeof candidate !== 'object') return false
    const c = candidate as SubjectCandidate
    return typeof c.id === 'string' && Boolean(c.id) && typeof c.label === 'string' && Boolean(c.label.trim())
      && typeof c.thumbnailUrl === 'string' && /^(https?:\/\/|\/)/.test(c.thumbnailUrl)
      && c.rect && [c.rect.x, c.rect.y, c.rect.w, c.rect.h].every(Number.isFinite)
      && c.rect.x >= 0 && c.rect.y >= 0 && c.rect.w > 0 && c.rect.h > 0
      && c.rect.x + c.rect.w <= 1.000001 && c.rect.y + c.rect.h <= 1.000001
  })
}
export async function detectStudioVideoSubject(body: StudioVideoSubjectDetectRequest): Promise<SubjectCandidate[]> {
  return runStudioRequestSingleFlight(`subject-detect:${JSON.stringify(body)}`, async () => {
    const response = unwrap(await request.post<ApiEnvelope<StudioVideoSubjectDetectData>>('/api/user/studio/video/subject/detect', body))
    let candidates = candidatesFrom(response)
    if (!candidates.length && Number(response.taskId) > 0) {
      const taskId = Number(response.taskId)
      const terminal = await waitUserTaskSseTerminal({ taskId, timeoutMs: TASK_SSE_TIMEOUT_MS })
      if (terminal.kind === 'sse' && terminal.event.type === 'complete') candidates = candidatesFrom(terminal.event.data)
      if (!candidates.length) {
        const detail = await fetchUserTaskDetailOnce(taskId)
        if (detail?.status === 'SUCCEEDED' || detail?.status === 'SUCCESS') candidates = candidatesFrom(detail.resultData)
      }
    }
    if (!candidates.length) throw new Error('未识别到可用主体，请调整选区后重试')
    return candidates
  })
}

/** Canvas detection uses an uploaded frame resource, never a browser-supplied media URL. */
export async function detectCanvasVideoSubject(workspaceId: string, frameResourceId: string): Promise<SubjectCandidate[]> {
  return runStudioRequestSingleFlight(`canvas-subject:${workspaceId}:${frameResourceId}`, async () => {
    const response = unwrap(await canvasApiPost<StudioVideoSubjectDetectData>(
      '/api/user/canvas/video/subject/detect', { workspaceId, frameResourceId }))
    return candidatesFrom(response)
  })
}
