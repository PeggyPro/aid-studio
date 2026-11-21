import type { StudioComposerRef } from '@/types/studio'

export interface StudioMentionState {
  start: number
  query: string
}

export function detectStudioMention(value: string, caret: number): StudioMentionState | null {
  const before = value.slice(0, caret)
  const atIndex = before.lastIndexOf('@')
  if (atIndex < 0) return null
  const query = before.slice(atIndex + 1)
  // Inline references occupy one \0 in the editor's text projection. A search
  // can follow any text, but must never cross an existing reference or a break.
  if (/[\s@\0]/.test(query)) return null
  return { start: atIndex, query }
}

export function removeStudioMentionToken(value: string, mention: StudioMentionState): string {
  return `${value.slice(0, mention.start)}${value.slice(mention.start + 1 + mention.query.length)}`
}

export function insertStudioMentionToken(value: string, mention: StudioMentionState, title: string): string {
  const token = `@${title} `
  return `${value.slice(0, mention.start)}${token}${value.slice(mention.start + 1 + mention.query.length)}`
}

export function formatStudioMentionToken(ref: Pick<StudioComposerRef, 'title'>): string {
  return `@${ref.title}`
}
