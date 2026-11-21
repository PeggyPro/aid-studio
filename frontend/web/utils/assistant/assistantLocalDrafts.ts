import type { AssistantAnswerDraft } from '~/types/assistant-api'
import { readAssistantDurable, writeAssistantDurable, removeAssistantDurable } from './assistantDurableStorage'

export interface PendingAssistantAnswer {
  answer: AssistantAnswerDraft
  operationId: string
  conflict?: boolean
  sent?: { answer: AssistantAnswerDraft; operationId: string }
}

export interface AssistantQuestionBackup {
  savedAt: number
  cursorQuestionId: string
  pending: Record<string, PendingAssistantAnswer>
  suspendCommand?: Record<string, unknown>
  contextErrorCode?: 'ASSISTANT_CONTEXT_STALE'
  /** Read-only archive of this request's answers; never migrate to a replacement schema. */
  archivedAnswers?: AssistantAnswerDraft[]
}

const MAX_AGE = 7 * 24 * 60 * 60_000

function validAnswer(answer: AssistantAnswerDraft | undefined, id: string): boolean {
  return !!answer && answer.questionId === id && Number.isSafeInteger(answer.revision) && answer.revision >= 0
    && ['ANSWERED', 'PARTIAL', 'UNANSWERED', 'CLEARED'].includes(answer.state)
    && typeof answer.freeText === 'string' && Array.isArray(answer.selectedOptionIds)
    && answer.selectedOptionIds.every((option) => typeof option === 'string')
}

/** 仅保存当前身份、会话和schema的未同步副本，跨端权威始终来自服务器。 */
export function readAssistantQuestionBackup(key: string, legacyKey?: string, migrate = true): AssistantQuestionBackup | null {
  if (typeof window === 'undefined') return null
  try {
    let value = readAssistantDurable<AssistantQuestionBackup>(key)
    const legacy = !value && legacyKey ? sessionStorage.getItem(legacyKey) : null
    if (legacy) {
      if (legacy.length > 256_000) throw new Error('旧问题草稿过大，已保留原记录')
      value = JSON.parse(legacy) as AssistantQuestionBackup
    }
    if (!value) return null
    if (!value || !value.pending || typeof value.pending !== 'object' || Array.isArray(value.pending)
      || !Number.isFinite(value.savedAt) || typeof value.cursorQuestionId !== 'string') throw new Error('问题草稿恢复记录不完整')
    for (const [id, entry] of Object.entries(value.pending)) {
      if (!entry || typeof entry.operationId !== 'string' || !entry.operationId || !validAnswer(entry.answer, id)
        || (entry.sent && (typeof entry.sent.operationId !== 'string' || !entry.sent.operationId || !validAnswer(entry.sent.answer, id)))) throw new Error('问题草稿恢复记录不完整')
    }
    if (value.contextErrorCode !== undefined && value.contextErrorCode !== 'ASSISTANT_CONTEXT_STALE'
      || value.archivedAnswers !== undefined && (!Array.isArray(value.archivedAnswers) || value.archivedAnswers.some(answer => !validAnswer(answer, answer?.questionId)))) throw new Error('问题草稿恢复记录不完整')
    if (!Object.keys(value.pending).length && !value.suspendCommand && Date.now() - value.savedAt > MAX_AGE) {
      if (migrate) { removeAssistantDurable(key); if (legacyKey) sessionStorage.removeItem(legacyKey) }
      return null
    }
    if (legacy && migrate) { writeAssistantDurable(key, value); sessionStorage.removeItem(legacyKey!) }
    return value
  } catch (error) { throw error instanceof Error ? error : new Error('问题草稿无法恢复，原记录已保留') }
}

export function saveAssistantQuestionBackup(key: string, value: AssistantQuestionBackup): boolean {
  try {
    const serialized = JSON.stringify(value)
    if (serialized.length > 256_000) return false
    writeAssistantDurable(key, value)
    return true
  } catch { return false }
}
