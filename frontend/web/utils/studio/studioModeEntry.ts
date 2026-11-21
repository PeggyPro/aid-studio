import type { StudioMode } from '@/types/studio'
import type { UserProjectType } from '@/types/business-api'

export function parseStudioMode(value: string | null | undefined): StudioMode | null {
  return value === 'flow' || value === 'free' ? value : null
}

export function safeStudioScopePart(value: string | null | undefined, fallback = 'default'): string {
  return String(value ?? '').replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 64) || fallback
}

export function buildStudioModeHref(search: string, mode: StudioMode): string {
  const next = new URLSearchParams(search)
  next.set('studioMode', mode)
  return `/create/studio?${next.toString()}`
}

/**
 * 自由画布继续使用历史 key，保证旧数据无损；流程画布使用独立布局 key。
 */
export function studioWorkspaceScopeKey(
  projectId: string | null | undefined,
  episodeId: string | null | undefined,
  mode: StudioMode
): string {
  const base = `project-${safeStudioScopePart(projectId, 'preview')}:episode-${safeStudioScopePart(episodeId)}`
  return mode === 'free' ? base : `${base}:mode-flow`
}

export function parsePositiveStudioId(value: string | null | undefined): number | null {
  if (!value || !/^\d+$/.test(value)) return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
}

/** 电影流程的 episodeId=0 是有效 scope；剧集仍使用正整数 ID。 */
export function parseStudioEpisodeId(value: string | null | undefined): number | null {
  if (!value || !/^\d+$/.test(value)) return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : null
}

export function canonicalStudioFlowEpisodeId(
  projectType: UserProjectType,
  requestedEpisodeId: number | null
): number | null {
  return projectType === 'movie' ? 0 : requestedEpisodeId
}
