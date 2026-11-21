import type { StudioFlowStep, StudioNodeKind, StudioTemplate } from '@/types/studio'

export type StudioCanvasEmptyShortcutAction =
  | { type: 'add-node'; kind: StudioNodeKind; title?: string; imagePurpose?: 'character' | 'scene' | 'storyboard' | 'generic' }
  | { type: 'apply-template'; templateId: string }
  | { type: 'open-chat'; hint?: string }
  | { type: 'open-flow-step'; step: StudioFlowStep }

export interface StudioCanvasEmptyShortcut {
  id: string
  label: string
  tone: 'cyan' | 'rose' | 'blue' | 'violet' | 'neutral'
  badge?: string
  action: StudioCanvasEmptyShortcutAction
}

export const STUDIO_CANVAS_FREE_SHORTCUTS: StudioCanvasEmptyShortcut[] = [
  {
    id: 'text-to-video',
    label: '文字生视频',
    tone: 'cyan',
    action: { type: 'add-node', kind: 'video', title: '文字生视频' }
  },
  {
    id: 'image-background',
    label: '图片换背景',
    tone: 'rose',
    action: { type: 'add-node', kind: 'image', title: '图片换背景', imagePurpose: 'scene' }
  },
  {
    id: 'first-frame-video',
    label: '首帧生成视频',
    tone: 'blue',
    action: { type: 'add-node', kind: 'image', title: '首帧画面', imagePurpose: 'storyboard' }
  },
  {
    id: 'audio-to-video',
    label: '音频生视频',
    tone: 'violet',
    action: { type: 'add-node', kind: 'voice', title: '音频生视频' }
  },
  {
    id: 'template',
    label: '模板',
    tone: 'neutral',
    action: { type: 'apply-template', templateId: 'short-drama' }
  }
]

export const STUDIO_CANVAS_FLOW_SHORTCUTS: StudioCanvasEmptyShortcut[] = [
  {
    id: 'story-script',
    label: '故事脚本生成',
    tone: 'cyan',
    action: { type: 'open-chat', hint: 'story-script' }
  },
  {
    id: 'character-sheet',
    label: '角色三视图',
    tone: 'rose',
    action: { type: 'open-chat', hint: 'character-sheet' }
  },
  {
    id: 'reference-video',
    label: '全能参考生视频',
    tone: 'blue',
    badge: '流程',
    action: { type: 'open-chat', hint: 'reference-video' }
  },
  {
    id: 'end-frame-video',
    label: '收尾帧生视频',
    tone: 'violet',
    badge: '流程',
    action: { type: 'open-chat', hint: 'end-frame-video' }
  }
]

export function resolveStudioCanvasTemplate(
  templates: StudioTemplate[],
  templateId: string
): StudioTemplate | undefined {
  return templates.find((item) => item.id === templateId)
}

const FLOW_SHORTCUT_SKILL_PATTERNS: Record<string, RegExp> = {
  'story-script': /剧本|脚本|screenplay|script_writ/i,
  'character-sheet': /角色|三视|character/i,
  'reference-video': /全能参考|参考生视频|reference/i,
  'end-frame-video': /收尾帧|首尾帧|end.?frame|edge.?video|first.?last/i
}

const FLOW_SHORTCUT_EMPTY_HINTS: Record<string, string> = {
  'story-script': '结合当前项目风格生成剧本，确认后一键带入画布，后续素材与分镜将自动推进。',
  'character-sheet': '结合当前项目风格生成角色三视图，确认后可带入画布继续推进素材。',
  'reference-video': '结合当前项目风格与参考素材生成视频，确认后一键带入画布。',
  'end-frame-video': '结合当前项目风格用收尾帧生成视频，确认后一键带入画布。'
}

export function resolveFlowShortcutSkillCode(
  skills: Array<{ skillCode: string; name?: string | null; description?: string | null; capability?: string | null }>,
  hint?: string
): string | undefined {
  const pattern = hint ? FLOW_SHORTCUT_SKILL_PATTERNS[hint] : undefined
  if (!pattern) return undefined
  return skills.find((skill) =>
    pattern.test(`${skill.skillCode} ${skill.name || ''} ${skill.description || ''} ${skill.capability || ''}`)
  )?.skillCode
}

export function resolveFlowShortcutEmptyHint(
  skills: Array<{ skillCode: string; name?: string | null; description?: string | null; capability?: string | null }>,
  selectedSkillCode: string,
  fallback: string
): string {
  const skill = skills.find((item) => item.skillCode === selectedSkillCode)
  if (!skill) return fallback
  const blob = `${skill.skillCode} ${skill.name || ''} ${skill.description || ''} ${skill.capability || ''}`
  const matchedHint = Object.entries(FLOW_SHORTCUT_SKILL_PATTERNS).find(([, pattern]) => pattern.test(blob))?.[0]
  return (matchedHint && FLOW_SHORTCUT_EMPTY_HINTS[matchedHint]) || fallback
}
