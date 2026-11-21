import type { CreationStep } from '~/types'
import { creationStepToRoutePath } from '~/utils/createFlowRoutes'

export const PUBLIC_PROJECT_PREVIEW_MODE = 'readonly' as const
export const PUBLIC_PROJECT_PREVIEW_QUERY_KEY = 'projectPreview' as const
export const PUBLIC_PROJECT_PREVIEW_ID_QUERY_KEY = 'previewProjectId' as const
export const CREATE_FLOW_ENTRY_GUIDE_QUERY_KEY = 'flowGuide' as const
export const CREATE_FLOW_ENTRY_GUIDE_VALUE = 'project-entry' as const

export function parsePublicProjectPreviewId(value: unknown): number | null {
  const raw = Array.isArray(value) ? value[0] : value
  const id = Number(raw)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

export function isPublicProjectPreviewQuery(
  query: { get: (key: string) => string | null } | Record<string, unknown>
): boolean {
  const reader = hasQueryReader(query) ? query : null
  const record = query as Record<string, unknown>
  const mode = reader
    ? reader.get(PUBLIC_PROJECT_PREVIEW_QUERY_KEY)
    : record[PUBLIC_PROJECT_PREVIEW_QUERY_KEY]
  const projectId = reader
    ? reader.get(PUBLIC_PROJECT_PREVIEW_ID_QUERY_KEY)
    : record[PUBLIC_PROJECT_PREVIEW_ID_QUERY_KEY]
  return String(mode ?? '') === PUBLIC_PROJECT_PREVIEW_MODE && parsePublicProjectPreviewId(projectId) != null
}

function hasQueryReader(
  query: { get: (key: string) => string | null } | Record<string, unknown>
): query is { get: (key: string) => string | null } {
  return typeof (query as { get?: unknown }).get === 'function'
}

export function buildPublicProjectPreviewFlowHref(
  projectId: number,
  step: CreationStep = 'global-setting'
): string {
  const query = new URLSearchParams({
    [PUBLIC_PROJECT_PREVIEW_QUERY_KEY]: PUBLIC_PROJECT_PREVIEW_MODE,
    [PUBLIC_PROJECT_PREVIEW_ID_QUERY_KEY]: String(projectId),
    [CREATE_FLOW_ENTRY_GUIDE_QUERY_KEY]: CREATE_FLOW_ENTRY_GUIDE_VALUE,
    from: 'case-preview'
  })
  return `${creationStepToRoutePath(step)}?${query.toString()}`
}

export function buildPublicProjectPreviewCanvasHref(input: {
  projectId: number
  episodeId?: number | null
  previewStep?: CreationStep
}): string {
  const query = new URLSearchParams({
    studioMode: 'flow',
    [PUBLIC_PROJECT_PREVIEW_QUERY_KEY]: PUBLIC_PROJECT_PREVIEW_MODE,
    [PUBLIC_PROJECT_PREVIEW_ID_QUERY_KEY]: String(input.projectId),
    from: 'case-preview'
  })
  if (input.episodeId != null && input.episodeId >= 0) {
    query.set('episodeId', String(input.episodeId))
  }
  if (input.previewStep) query.set('previewStep', input.previewStep)
  return `/create/studio?${query.toString()}`
}

export function buildCopiedProjectPreviewHref(projectId: number): string {
  const query = new URLSearchParams({
    projectId: String(projectId),
    id: String(projectId),
    from: 'case-copy',
    [CREATE_FLOW_ENTRY_GUIDE_QUERY_KEY]: CREATE_FLOW_ENTRY_GUIDE_VALUE
  })
  return `${creationStepToRoutePath('preview')}?${query.toString()}`
}
