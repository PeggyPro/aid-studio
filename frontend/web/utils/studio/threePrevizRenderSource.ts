import type { DirectorSceneData } from '@/types/studio'

const observationFields = new Set(['updatedAt', 'revision', 'viewMode', 'directorCamera'])

/** Cursor and observation changes are not authored animation edits. Unknown fields remain significant. */
export function sameThreePrevizRenderSource(previous: DirectorSceneData, next: DirectorSceneData) {
  if (previous === next) return true
  for (const field of new Set([...Object.keys(previous), ...Object.keys(next)])) {
    if (observationFields.has(field)) continue
    if (field === 'timeline') {
      const a = previous.timeline, b = next.timeline
      if (a === b) continue
      if (!a || !b) return false
      for (const key of new Set([...Object.keys(a), ...Object.keys(b)])) {
        if (key !== 'currentTime' && !Object.is(a[key as keyof typeof a], b[key as keyof typeof b])) return false
      }
    } else if (field === 'modeling') {
      const a = previous.modeling, b = next.modeling
      if (a === b) continue
      if (!a || !b || a.schemaVersion !== b.schemaVersion || a.project.id !== b.project.id || a.project.remoteWorkspaceId !== b.project.remoteWorkspaceId || a.project.scene !== b.project.scene || a.directorBindings !== b.directorBindings) return false
    } else if (!Object.is(previous[field as keyof DirectorSceneData], next[field as keyof DirectorSceneData])) return false
  }
  return true
}
