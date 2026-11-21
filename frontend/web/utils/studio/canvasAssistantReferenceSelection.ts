export const CANVAS_ASSISTANT_REFERENCE_EVENT = 'studio:assistant-reference-node'

export interface CanvasAssistantReferenceSelectionDetail {
  scopeKey: string
  nodeIds: string[]
}

/**
 * Pointer selection publishes the resulting ids, including [] when the pane is cleared.
 * Effect navigation and other programmatic selection do not publish, so they cannot change the draft.
 */
export function publishCanvasAssistantReferenceSelection(scopeKey: string, nodeIds: readonly string[]) {
  if (typeof window === 'undefined' || !scopeKey) return
  const detail: CanvasAssistantReferenceSelectionDetail = { scopeKey, nodeIds: [...nodeIds] }
  window.dispatchEvent(new CustomEvent(CANVAS_ASSISTANT_REFERENCE_EVENT, { detail }))
}

/** The ids selectNodes will apply for this pointer gesture. */
export function canvasAssistantPointerSelection(current: readonly string[], id: string, additive: boolean): string[] {
  if (!additive) return [id]
  return current.includes(id) ? current.filter(item => item !== id) : [...current, id]
}

export function canvasAssistantMarqueeSelection(current: readonly string[], hitIds: readonly string[], additive: boolean): string[] {
  return additive ? [...new Set([...current, ...hitIds])] : [...hitIds]
}

export function appendCanvasAssistantReferenceIds(current: readonly string[], added: readonly string[]): string[] {
  return [...new Set([...current, ...added].filter(id => typeof id === 'string' && !!id))]
}

export interface CanvasAssistantReferenceDraftState {
  ids: string[]
  selectionIds: string[]
}

/**
 * Selection-owned chips follow the pointer selection.
 * Pins added by @, attachments or restored messages stay until removed explicitly.
 */
export function syncCanvasAssistantReferenceDraft<T extends CanvasAssistantReferenceDraftState>(draft: T, nextSelection: readonly string[]): T {
  const selectionIds = [...new Set(nextSelection.filter(id => typeof id === 'string' && !!id))]
  const owned = new Set(draft.selectionIds)
  const pinned = draft.ids.filter(id => !owned.has(id))
  const ids = [...new Set([...pinned, ...selectionIds])]
  const sameSelection = selectionIds.length === draft.selectionIds.length && selectionIds.every((id, index) => id === draft.selectionIds[index])
  const sameIds = ids.length === draft.ids.length && ids.every((id, index) => id === draft.ids[index])
  if (sameSelection && sameIds) return draft
  return { ...draft, ids, selectionIds }
}

/** Remove deleted node IDs from every mode's draft in this canvas, preserving other scopes. */
export function pruneCanvasAssistantReferenceDrafts<T extends { binding: string; ids: string[]; selectionIds?: string[] }>(
  drafts: Record<string, T>, scopeKey: string, removed: ReadonlySet<string>
): Record<string, T> {
  if (!removed.size) return drafts
  const scopePrefix = JSON.stringify([scopeKey]).slice(0, -1) + ','
  let next = drafts
  for (const [key, draft] of Object.entries(drafts)) {
    if (!draft.binding.startsWith(scopePrefix)) continue
    const ids = draft.ids.filter(id => !removed.has(id))
    const selectionIds = draft.selectionIds?.filter(id => !removed.has(id))
    const idsChanged = ids.length !== draft.ids.length
    const selectionChanged = !!draft.selectionIds && selectionIds!.length !== draft.selectionIds.length
    if (!idsChanged && !selectionChanged) continue
    if (next === drafts) next = { ...drafts }
    next[key] = { ...draft, ids, ...(draft.selectionIds ? { selectionIds } : {}) }
  }
  return next
}
