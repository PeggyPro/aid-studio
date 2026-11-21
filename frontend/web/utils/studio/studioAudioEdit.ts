import type { StudioAudioEdit, StudioAudioOperation, StudioAudioOutput } from '@/types/studio-audio'

export const AUDIO_OPERATION_LABELS: Record<StudioAudioOperation, string> = { TRIM: '裁剪', SPEED: '变速', CUSTOM_SPLIT: '自定义切分' }
export function audioEditProblem(edit: StudioAudioEdit): string | null {
  if (!edit.sourceAudioUrl) return '源音频不可用'
  if (edit.operation === 'CUSTOM_SPLIT') return edit.instruction.trim() && edit.instruction.length <= 2000 ? null : '请输入切分要求（最多 2000 字）'
  if (edit.operation === 'SPEED') return Number.isFinite(edit.speed) && edit.speed >= .2 && edit.speed <= 3 ? null : '变速范围为 0.2x–3x'
  if (![edit.durationSeconds, edit.startSeconds, edit.endSeconds].every(Number.isFinite)
    || edit.durationSeconds <= 0 || edit.startSeconds < 0 || edit.endSeconds > edit.durationSeconds
    || edit.endSeconds - edit.startSeconds < .05) return '请选择至少 0.05 秒的有效区间'
  return null
}
export function audioDerivedTitle(edit: StudioAudioEdit): string {
  return `${edit.sourceTitle}-${edit.operation === 'SPEED' ? `${edit.speed.toFixed(2)}x变速` : AUDIO_OPERATION_LABELS[edit.operation]}`
}
export function audioTime(seconds: number): string {
  const value = Number.isFinite(seconds) ? Math.max(0, seconds) : 0
  return `${Math.floor(value / 60).toString().padStart(2, '0')}:${(value % 60).toFixed(2).padStart(5, '0')}`
}
/** Only explicitly typed, persisted audio outputs can mark a task successful. */
export function parseAudioOutputs(raw: unknown): StudioAudioOutput[] {
  if (typeof raw === 'string') { try { return parseAudioOutputs(JSON.parse(raw)) } catch { return [] } }
  if (!raw || typeof raw !== 'object') return []
  const value = raw as Record<string, unknown>
  if (Array.isArray(value.outputs)) {
    if (!value.outputs.length || value.outputs.length > 16) return []
    const outputs = value.outputs.map(parseAudioOutputs)
    return outputs.every((items) => items.length === 1) ? outputs.flat() : []
  }
  if (typeof value.audioUrl === 'string' && /^(https?:\/\/|\/)/.test(value.audioUrl)) {
    return [{ audioUrl: value.audioUrl,
      ...(Number.isFinite(Number(value.durationSeconds)) && Number(value.durationSeconds) > 0 ? { durationSeconds: Number(value.durationSeconds) } : {}),
      ...(Number.isFinite(Number(value.recordId)) && Number(value.recordId) > 0 ? { recordId: Number(value.recordId) } : {}) }]
  }
  return parseAudioOutputs(value.data ?? value.result ?? value.output)
}
