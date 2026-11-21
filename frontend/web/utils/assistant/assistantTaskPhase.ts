import type { AssistantTask } from '~/types/assistant-api'

/** ETA phase describes local work, not a provider acceptance or terminal outcome. */
export function assistantTaskPhase(task: AssistantTask): string | undefined {
  if (['SUCCEEDED', 'FAILED', 'CANCELLED', 'CANCELED', 'COMPLETED'].includes(task.status)) return undefined
  return ({ QUEUED: '排队中', STARTING: '正在准备生成', GENERATING: '正在生成', RECONCILING: '状态待核实' } as Record<string, string>)[task.eta?.phase || '']
}
