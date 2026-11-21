export interface MutationFeedback {
  type: 'success' | 'info' | 'warning';
  message: string;
  completed: boolean;
}

function record(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown> : {};
}

/** HTTP success alone does not confirm the requested media terminal state. */
export function failureConfirmationFeedback(response: unknown): MutationFeedback {
  const data = record(record(response).data);
  const result = record(data.result);
  if (result.terminal === true && result.taskStatus === 'FAILED') {
    const pending = record(data.anomaly).compensationPending;
    return pending === true || pending === 1
      ? { type: 'warning', message: '任务已确认失败，账务补偿仍在处理中，请查看详情', completed: true }
      : { type: 'success', message: '任务已确认失败', completed: true };
  }
  if (result.terminal === true && result.taskStatus === 'SUCCEEDED')
    return { type: 'info', message: '任务已成功，未改为失败', completed: false };
  if (result.terminal === true && result.taskStatus === 'CANCELLED')
    return { type: 'info', message: '任务已取消，请查看当前处理结果', completed: false };
  const detail = typeof result.message === 'string' ? result.message.trim() : '';
  return {
    type: 'warning',
    message: detail ? `未确认失败：${detail}` : '未取得确认失败结果，请查看任务详情',
    completed: false
  };
}
