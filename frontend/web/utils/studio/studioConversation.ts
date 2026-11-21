import type { StudioConversationPlanStep, StudioConversationState } from '@/types/studio'

export const DEFAULT_STUDIO_CONVERSATION: StudioConversationState = {
  phase: 'idle',
  goal: '',
  parameters: {},
  planSteps: [],
  updatedAt: new Date(0).toISOString()
}

const STANDARD_PLAN: StudioConversationPlanStep[] = [
  { id: 'screenplay', label: '剧本结构与场次', status: 'pending', credits: 2 },
  { id: 'assets', label: '角色、场景与道具', status: 'pending', credits: 18 },
  { id: 'storyboard', label: '分镜图与导演调度', status: 'pending', credits: 12 },
  { id: 'video', label: '镜头视频', status: 'pending', credits: 40 }
]

export function beginStudioConversation(goal: string): StudioConversationState {
  return {
    phase: 'clarifying',
    goal: goal.trim(),
    parameters: {},
    pendingField: 'durationSeconds',
    planSteps: [],
    updatedAt: new Date().toISOString()
  }
}

export function parseStudioDuration(value: string): number | null {
  const match = value.match(/(\d+(?:\.\d+)?)\s*(分钟|分|秒)/)
  if (!match) return null
  const amount = Number(match[1])
  if (!Number.isFinite(amount) || amount <= 0) return null
  return Math.round(amount * (match[2] === '秒' ? 1 : 60))
}

export function answerStudioConversation(
  current: StudioConversationState,
  answer: string
): StudioConversationState {
  if (current.pendingField === 'durationSeconds') {
    const durationSeconds = parseStudioDuration(answer)
    if (!durationSeconds) return current
    return {
      ...current,
      parameters: { ...current.parameters, durationSeconds },
      pendingField: 'style',
      updatedAt: new Date().toISOString()
    }
  }
  if (current.pendingField === 'style' && answer.trim()) {
    return {
      ...current,
      phase: 'awaiting_confirmation',
      parameters: { ...current.parameters, style: answer.trim() },
      pendingField: undefined,
      planSteps: STANDARD_PLAN.map((step) => ({ ...step })),
      updatedAt: new Date().toISOString()
    }
  }
  return current
}
