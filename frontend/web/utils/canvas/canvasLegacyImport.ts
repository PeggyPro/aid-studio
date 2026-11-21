import type { StudioWorkspaceData } from '@/types/studio'
import { serializeCanvasWorkspace } from './canvasWorkspaceAdapter'
import { createEmptyStudioWorkspace } from '@/utils/studio/mockStudioApi'
import { validateStudioWorkspace } from '@/utils/studio/studioWorkspaceValidation'

const PREFIX = 'aid-studio-mock:v1:'
const MAX_LOCAL_JSON_LENGTH = 20 * 1024 * 1024

export interface LegacyCanvasCandidate {
  scopeKey: string
  title: string
  savedAt: string
  nodeCount: number
  objectCount: number
  omittedMediaCount: number
  repairedEntries: number
  workspace: StudioWorkspaceData
}

/** Only local free canvases are eligible. Reading never mutates or removes old workspaces. */
export function listLegacyCanvasCandidates(storage: Storage): LegacyCanvasCandidate[] {
  const candidates: LegacyCanvasCandidate[] = []
  for (let index = 0; index < storage.length; index += 1) {
    const key = storage.key(index)
    if (!key?.startsWith(PREFIX)) continue
    const scopeKey = key.slice(PREFIX.length)
    const raw = storage.getItem(key)
    if (!raw || raw.length > MAX_LOCAL_JSON_LENGTH) continue
    try {
      const parsed: unknown = JSON.parse(raw)
      if (!parsed || typeof parsed !== 'object' || (parsed as { studioMode?: unknown }).studioMode !== 'free') continue
      const validated = validateStudioWorkspace(parsed, scopeKey, 'free', createEmptyStudioWorkspace(scopeKey, 'free'))
      if (!validated) continue
      const workspace = validated.workspace
      const objectCount = workspace.nodes.length + workspace.edges.length + workspace.groups.length
      if (objectCount < 1 || objectCount > 2000) continue
      const objectIds = [...workspace.nodes, ...workspace.edges, ...workspace.groups].map((item) => item.id)
      if (new Set(objectIds).size !== objectCount) continue
      candidates.push({
        scopeKey,
        title: workspace.title?.trim() || '未命名项目',
        savedAt: workspace.savedAt,
        nodeCount: workspace.nodes.length,
        objectCount,
        omittedMediaCount: workspace.nodes.filter((node) => Boolean(node.data.mediaUrl || node.data.canvasResourceIds?.length || node.data.composerRefs?.length)).length,
        repairedEntries: validated.repairedEntries,
        workspace
      })
    } catch {
      // A malformed local item is not a project and must not block healthy items.
    }
  }
  return candidates.sort((a, b) => b.savedAt.localeCompare(a.savedAt))
}

/** Import only node inputs and topology. Local URLs, task identity and foreign resource IDs are never transferred. */
export function buildLegacyCanvasImport(candidate: LegacyCanvasCandidate, operationId: string) {
  const workspace: StudioWorkspaceData = {
    ...candidate.workspace,
    nodes: candidate.workspace.nodes.map((node) => ({
      ...node,
      data: {
        ...node.data,
        canvasResourceIds: [],
        canvasPrimaryResourceId: undefined,
        composerRefs: []
      }
    }))
  }
  const serialized = serializeCanvasWorkspace(workspace)
  const usedIds = new Set(serialized.map((item) => item.objectId))
  const aliases = new Map<string, string>()
  for (const item of serialized) {
    if (item.objectId.length <= 64) {
      aliases.set(item.objectId, item.objectId)
      continue
    }
    let index = aliases.size + 1
    let alias = `legacy_${index}`
    while (usedIds.has(alias)) alias = `legacy_${++index}`
    aliases.set(item.objectId, alias)
    usedIds.add(alias)
  }
  const rewrite = (value: unknown, key = ''): unknown => {
    if (Array.isArray(value)) return value.map((item) => rewrite(item, key))
    if (value && typeof value === 'object') {
      return Object.fromEntries(Object.entries(value).map(([childKey, child]) => [childKey, rewrite(child, childKey)]))
    }
    if (typeof value === 'string' && !['prompt', 'negativePrompt', 'documentContent', 'title', 'description'].includes(key)) {
      return aliases.get(value) || value
    }
    return value
  }
  return {
    projectName: candidate.title.slice(0, 100),
    projectDesc: '从本地自由画布导入',
    operationId,
    objects: serialized.map(({ objectId, objectType, payload }) => ({
      localObjectId: aliases.get(objectId) || objectId,
      objectType,
      payload: rewrite(payload) as Record<string, unknown>
    }))
  }
}
