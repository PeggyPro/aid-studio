import type { StudioVideoClipRange } from '@/types/studio'
import { formatStudioVideoClipRange } from './studioVideoClip'

export function studioVideoOperationPromptLead(
  purpose: 'reshoot' | 'continuation',
  sourceTitle: string,
  range?: StudioVideoClipRange
): string {
  const source = `@${sourceTitle}`
  if (purpose === 'continuation') return `从 ${source} 的结尾续写新片段；`
  return range
    ? `把 ${source} 的 ${formatStudioVideoClipRange(range)} 片段进行重拍；`
    : `把 ${source} 的所选片段进行重拍；`
}

export function replaceStudioVideoOperationPromptLead(
  prompt: string,
  previous: string | undefined,
  next: string
): string {
  if (previous && prompt.startsWith(previous)) return next + prompt.slice(previous.length)
  if (previous) return prompt
  return prompt.trim() ? `${next} ${prompt}` : next
}

export function studioVideoOperationUserPrompt(prompt: string, lead?: string): string {
  return lead && prompt.startsWith(lead) ? prompt.slice(lead.length).trim() : prompt.trim()
}
