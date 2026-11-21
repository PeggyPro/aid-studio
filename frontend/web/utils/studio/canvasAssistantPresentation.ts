import type { AssistantSessionSnapshot } from '@/types/assistant-api'

const TERMINAL = new Set(['COMPLETED', 'SUCCEEDED', 'FAILED', 'STOPPED', 'CANCELLED'])
const ACTIVE_STEP = new Set(['PREPARING', 'READY', 'SUBMITTING', 'INFLIGHT', 'RUNNING', 'WAITING_TASK', 'WAITING_SKILL'])

/** 只投影服务端真实状态，不构造模型思考内容或已完成操作。 */
export function canvasAssistantActivity(snapshot: AssistantSessionSnapshot) {
  const run = snapshot.activeRun
  if (!run) return null
  if (run.stopRequested && !TERMINAL.has(run.status)) return { kind: 'notice', label: '正在停止后续执行', detail: '已受理的任务仍在核实，请稍候。' }
  if (run.status === 'PLANNING') return { kind: 'thinking', label: '正在思考…', detail: '助手正在理解你的需求，完成后会在这里回复。' }
  if (run.status === 'RUNNING') {
    const step = snapshot.steps.find((item) => ACTIVE_STEP.has(item.status))
    if (step?.stepKind === 'MAIN_AGENT' || step?.operationKind === 'ASSISTANT_DECISION') {
      const task = snapshot.tasks.find((item) => item.stepId === step.stepId || item.taskId === step.mediaTaskId)
      if (task?.status === 'QUEUED' || task?.status === 'PENDING') return { kind: 'working', label: '助手排队中…', detail: '任务已受理，开始后会继续回复。' }
      return { kind: 'thinking', label: '正在思考…', detail: '助手正在整理回复。' }
    }
    return { kind: 'working', label: run.hasActiveTasks ? '正在生成…' : '正在处理…', detail: '进度会随任务状态更新。' }
  }
  if (run.status === 'READY') return { kind: 'working', label: '准备开始…', detail: '正在等待已授权任务开始。' }
  if (run.status === 'WAITING_INPUT') return { kind: 'notice', label: '需要你补充一点信息', detail: '回答下方问题后，助手会继续。' }
  if (run.status === 'WAITING_APPROVAL') return { kind: 'notice', label: '等待你确认', detail: snapshot.plan?.status === 'PROPOSED' ? '请核对下方创作计划，确认后继续。' : '请核对下方费用和授权范围，确认后继续。' }
  if (run.status === 'STOPPING') return { kind: 'notice', label: '正在停止后续执行', detail: '已受理的任务仍在核实，请稍候。' }
  if (run.status === 'PAUSING' || run.status === 'RECONCILING') return { kind: 'notice', label: '正在核实任务结果', detail: '已受理操作的结果和费用尚待确认。' }
  if (run.status === 'PAUSED') return { kind: 'notice', label: '对话已暂停', detail: '可以补充要求，或选择继续。' }
  if (run.status === 'FAILED') return { kind: 'error', label: '本轮未能完成', detail: snapshot.steps.find((item) => item.status === 'FAILED' && item.safeMessage)?.safeMessage || '请查看任务记录中的原因，或调整需求后重新发送。' }
  if (run.status === 'STOPPED' || run.status === 'CANCELLED') return { kind: 'notice', label: '已停止后续执行', detail: '已受理的任务和费用以实际结果为准。' }
  return null
}
