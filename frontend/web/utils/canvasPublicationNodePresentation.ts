type PublicationMediaKind = 'image' | 'video' | 'audio' | 'text'

// React Flow disables pointer events on non-selectable readonly nodes; media controls still need input.
export const canvasPublicationMediaStyle = { pointerEvents: 'auto' as const }

/** Readonly presentation falls back to the frozen resource type and original node text. */
export function canvasPublicationNodePresentation(
  data: { mediaKind?: PublicationMediaKind; documentContent?: string; resultSummary?: string; prompt?: string },
  content: Record<string, unknown> | null | undefined,
  resourceMediaType: string | undefined,
) {
  const resourceKind = resourceMediaType?.toLowerCase()
  const mediaKind = data.mediaKind ?? (
    resourceKind === 'image' || resourceKind === 'video' || resourceKind === 'audio'
      ? resourceKind : undefined
  )
  const originalText = typeof content?.textContent === 'string' ? content.textContent : undefined
  return { mediaKind, text: data.documentContent || originalText || data.resultSummary || data.prompt }
}
