import type { AssistantAnswerDraft, AssistantDraftPatch, AssistantInputRequest, AssistantQuestion } from '~/types/assistant-api'

export function emptyAssistantAnswer(questionId: string): AssistantAnswerDraft {
  return { questionId, revision: 0, selectedOptionIds: [], freeText: '', state: 'UNANSWERED' }
}

export function assistantAnswerValue(question: AssistantQuestion, previous: AssistantAnswerDraft, patch: Partial<AssistantAnswerDraft>): AssistantAnswerDraft {
  const next = { ...previous, ...patch, questionId: question.id }
  next.selectedOptionIds = [...new Set(next.selectedOptionIds)]
  next.state = next.selectedOptionIds.length || next.freeText.trim() ? (assistantAnswerComplete(question, next) ? 'ANSWERED' : 'PARTIAL') : 'UNANSWERED'
  return next
}

export function toggleAssistantOption(question: AssistantQuestion, answer: AssistantAnswerDraft, optionId: string): AssistantAnswerDraft {
  if (question.kind !== 'multi') return assistantAnswerValue(question, answer, { selectedOptionIds: [optionId] })
  const selected = answer.selectedOptionIds.includes(optionId)
  const option = question.options?.find((item) => item.id === optionId)
  const ids = selected ? answer.selectedOptionIds.filter((id) => id !== optionId)
    : option?.exclusive ? [optionId]
      : [...answer.selectedOptionIds.filter((id) => !question.options?.find((item) => item.id === id)?.exclusive), optionId]
  return assistantAnswerValue(question, answer, { selectedOptionIds: ids })
}

export function assistantAnswerError(question: AssistantQuestion, answer: AssistantAnswerDraft): string | null {
  const count = answer.selectedOptionIds.length
  const text = answer.freeText.trim()
  const choiceQuestion = question.kind === 'single' || question.kind === 'multi'
  if (question.required && !count && !text) return '请回答此问题'
  if (choiceQuestion) {
    if (text && !question.allowFreeText) return '此问题不允许自由补充'
    const minimum = question.minSelected ?? (question.required ? 1 : 0)
    if ((question.required || count > 0 || !!text) && count < minimum) return `至少选择 ${minimum} 项`
    if (question.maxSelected != null && count > question.maxSelected) return `最多选择 ${question.maxSelected} 项`
  }
  if (answer.selectedOptionIds.some((id) => !question.options?.some((option) => option.id === id))) return '选项已更新，请重新选择'
  if (question.kind === 'single' && count > 1) return '只能选择一项'
  if (question.kind === 'multi') {
    if (count > 1 && answer.selectedOptionIds.some((id) => question.options?.find((option) => option.id === id)?.exclusive)) return '该选项不能与其他项同时选择'
  }
  if (question.maxLength != null && answer.freeText.length > question.maxLength) return `最多输入 ${question.maxLength} 个字符`
  if (text && question.kind === 'number') {
    const value = Number(text)
    if (!Number.isFinite(value)) return '请输入有效数字'
    if (question.min != null && value < question.min) return `不能小于 ${question.min}`
    if (question.max != null && value > question.max) return `不能大于 ${question.max}`
  }
  return null
}

/** 只认服务端持久化在当前问题选项上的控制动作，不从可变展示文案推断。 */
export function assistantAnswerRequestsPause(question: AssistantQuestion, answer: AssistantAnswerDraft): boolean {
  const selected = new Set(answer.selectedOptionIds)
  return Boolean(question.options?.some((option) => selected.has(option.id) && option.controlAction === 'PAUSE_RUN'))
}

export function assistantDraftPatch(answer: AssistantAnswerDraft): AssistantDraftPatch {
  return {
    questionId: answer.questionId, expectedDraftRevision: answer.revision,
    selectedOptionIds: answer.selectedOptionIds, freeText: answer.freeText, state: answer.state
  }
}

export function assistantQuestionEditable(request: AssistantInputRequest): boolean {
  return request.status === 'OPEN' || request.status === 'DRAFTING'
}

/** 草稿有值不代表答完：选项下限、互斥和文本限制同样影响未答计数。 */
export function assistantAnswerComplete(question: AssistantQuestion, answer: AssistantAnswerDraft): boolean {
  return Boolean(answer.selectedOptionIds.length || answer.freeText.trim()) && assistantAnswerError(question, answer) === null
}
