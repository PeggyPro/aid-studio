import type { StudioVideoOperation } from '@/types/business-api'
import type { StudioComposerRef } from '@/types/studio'
import type { StudioVideoSubjectEdit, StudioVideoSubjectMark, StudioVideoSubjectMode, SubjectCandidate } from '@/types/studio-video-subject'

export const SUBJECT_MODES: Record<StudioVideoSubjectMode, { label: string; operation: StudioVideoOperation }> = {
  remove: { label: '主体消除', operation: 'SUBJECT_REMOVE' },
  modify: { label: '主体修改', operation: 'SUBJECT_MODIFY' },
  replace: { label: '主体替换', operation: 'SUBJECT_REPLACE' },
  matting: { label: '智能抠图', operation: 'SUBJECT_MATTING' }
}
export const SUBJECT_LIMIT = 4
export function selectedSubject(mark: StudioVideoSubjectMark): SubjectCandidate | undefined {
  return mark.candidates.find((candidate) => candidate.id === mark.candidateId)
}
export function subjectEditProblem(edit: StudioVideoSubjectEdit): string | null {
  if (!edit.marks.length || edit.marks.length > SUBJECT_LIMIT) return '请确认 1–4 个主体'
  if (![edit.range.startSeconds, edit.range.endSeconds, edit.range.durationSeconds].every(Number.isFinite)
    || edit.range.startSeconds < 0 || edit.range.endSeconds > edit.range.durationSeconds
    || edit.range.endSeconds <= edit.range.startSeconds) return '请选择有效的视频片段'
  for (const mark of edit.marks) {
    if (!mark.confirmed || !selectedSubject(mark)) return '请先确认所有主体标记'
    if (!Number.isFinite(mark.frameSeconds) || mark.frameSeconds < edit.range.startSeconds || mark.frameSeconds > edit.range.endSeconds) return '所选片段需要包含所有标记帧'
    if (edit.mode === 'modify' && !mark.instruction.trim()) return '请填写每个主体的修改内容'
    if (edit.mode === 'replace' && !mark.replacementUrl) return '请为每个主体选择替换图片'
  }
  return null
}
/** Shared by the preview and submission: displayed intent cannot drift from saved marks. */
export function subjectEditPrompt(edit: StudioVideoSubjectEdit): string {
  return `${edit.sourceTitle} 的 ${edit.range.startSeconds.toFixed(2)}–${edit.range.endSeconds.toFixed(2)} 秒片段中，` + edit.marks.map((mark, index) => {
    const name = selectedSubject(mark)?.label || '主体'
    if (edit.mode === 'modify') return `将主体 ${index + 1}（${name}）修改为：${mark.instruction.trim()}`
    if (edit.mode === 'replace') return `将主体 ${index + 1}（${name}）替换为对应参考图片`
    return `${edit.mode === 'matting' ? '保留并提取' : '消除'}主体 ${index + 1}（${name}）`
  }).join('；')
}
export function subjectEditRequestPrompt(edit: StudioVideoSubjectEdit, prompt: string): string {
  return (prompt.trim() || subjectEditPrompt(edit)).replace(`@${edit.sourceTitle}`, edit.sourceTitle)
}
export function subjectEditRefs(edit: StudioVideoSubjectEdit): StudioComposerRef[] {
  const refs: StudioComposerRef[] = [{ id: `subject-source-${edit.sourceNodeId}`, kind: 'node', nodeId: edit.sourceNodeId,
    title: edit.sourceTitle, mediaKind: 'video', previewUrl: edit.sourceVideoUrl }]
  edit.marks.forEach((mark, index) => {
    const candidate = selectedSubject(mark)
    if (candidate?.thumbnailUrl) refs.push({ id: `subject-${mark.id}`, kind: 'frame', title: `主体 ${index + 1} · ${candidate.label}`,
      previewUrl: candidate.thumbnailUrl, mediaKind: 'image' })
    if (mark.replacementUrl) refs.push({ id: `replacement-${mark.id}`, kind: 'asset', title: `主体 ${index + 1} 替换图`,
      nodeId: mark.replacementNodeId, previewUrl: mark.replacementUrl, mediaKind: 'image' })
  })
  return refs
}
