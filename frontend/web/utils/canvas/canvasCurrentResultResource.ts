import type { CanvasCurrentResult, CanvasMediaType, CanvasResource } from '@/types/canvas-api'

export type CanvasCurrentResultProjection = {
  result: CanvasCurrentResult
  mediaUrl?: string
  resource?: CanvasResource
  resourceId?: string
}

function positiveId(value: unknown): string | undefined {
  const id = String(value ?? '')
  return /^\d+$/.test(id) && id !== '0' ? id : undefined
}

function nonBlank(value: unknown): string | undefined {
  const text = typeof value === 'string' ? value.trim() : ''
  return text || undefined
}

/** A normalized display URL and a storage URL may differ; a selected result has an exact resource identity. */
export function canvasResourceMatchesNodeMedia(resource: CanvasResource | undefined, node: { mediaUrl?: string; canvasCurrentResultId?: string }): boolean {
  if (!resource || !nonBlank(node.mediaUrl)) return false
  if (nonBlank(resource.resourceUrl) === node.mediaUrl || nonBlank(resource.displayUrl) === node.mediaUrl) return true
  const resultId = positiveId(node.canvasCurrentResultId)
  return !!resultId && !!positiveId(resource.resourceId)
    && String(resource.status || '').toUpperCase() === 'ACTIVE'
    && String(resource.sourceType || '').toUpperCase() === 'MEDIA_RESULT'
    && positiveId(resource.sourceId) === resultId
}

/**
 * Projects the server-selected media result without treating an unrelated or
 * incomplete resource row as proof that the result is already registered.
 */
export function projectCanvasCurrentResult(
  result: CanvasCurrentResult | undefined,
  resources: ReadonlyMap<string, CanvasResource>,
  expectedMediaType: CanvasMediaType | null
): CanvasCurrentResultProjection | undefined {
  if (!result || !expectedMediaType || !['IMAGE', 'VIDEO', 'AUDIO'].includes(expectedMediaType)) return undefined
  if (String(result.mediaType || '').toUpperCase() !== expectedMediaType) return undefined

  const resultUrl = nonBlank(result.resultUrl)
  const fallback: CanvasCurrentResultProjection = {
    result,
    ...(resultUrl ? { mediaUrl: resultUrl } : {})
  }
  const resultId = positiveId(result.resultId)
  const resourceId = positiveId(result.resourceId)
  if (!resultId || !resourceId) return fallback

  const resource = resources.get(resourceId)
  if (!resource
    || String(resource.mediaType).toUpperCase() !== expectedMediaType
    || String(resource.status || '').toUpperCase() !== 'ACTIVE'
    || String(resource.sourceType || '').toUpperCase() !== 'MEDIA_RESULT'
    || positiveId(resource.sourceId) !== resultId
    || !nonBlank(resource.resourceUrl)) {
    return fallback
  }
  // The selected result URL is normalized by the server; the resource may still contain a storage path.
  // Resource identity proves registration, independently from the browser-facing display address.
  return { result, resource, resourceId, mediaUrl: resource.displayUrl || resultUrl || resource.resourceUrl }
}
