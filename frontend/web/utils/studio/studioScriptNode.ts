import { STUDIO_NODE_META } from '@/config/studioMeta'
import type { StudioNodeData } from '@/types/studio'
import { isStudioStoryboardImageNode } from '@/utils/studio/studioImageNode'

export function resolveStudioScriptNodeText(data: StudioNodeData): string {
  if (typeof data.documentContent === 'string') return data.documentContent.trim()
  const meta = STUDIO_NODE_META[data.kind]
  const fromPrompt = data.prompt !== meta.defaultPrompt ? data.prompt : ''
  const body = (fromPrompt || data.resultSummary || '').trim()
  if (body || data.kind === 'storyboard_script') return body
  return String(data.subtitle || '').trim()
}

export function isStudioScriptContentNode(data: StudioNodeData): boolean {
  if (isStudioStoryboardImageNode(data)) return false
  if (data.kind === 'storyboard_script') {
    if (data.flowBinding?.role === 'step') return false
    return Boolean(resolveStudioScriptNodeText(data))
  }
  if (data.kind !== 'text') return false
  if (!resolveStudioScriptNodeText(data)) return false
  if (data.flowBinding?.entityType === 'rps_asset') return false
  if (data.flowBinding) {
    return data.flowBinding.entityType === 'story_script' || data.flowBinding.step === 'story-script'
  }
  return true
}

export function resolveStudioScriptScopeFromBinding(binding?: {
  projectId?: number
  episodeId?: number | null
}): { projectId: number | null; episodeId: number | null } {
  if (binding?.projectId) {
    return { projectId: binding.projectId, episodeId: binding.episodeId ?? 0 }
  }
  return { projectId: null, episodeId: null }
}

export function resolveStudioScriptNodeScope(data: StudioNodeData): {
  projectId: number | null
  episodeId: number | null
} {
  return resolveStudioScriptScopeFromBinding(data.flowBinding)
}
