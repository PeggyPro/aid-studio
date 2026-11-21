import type { CanvasObjectRecord, CanvasOperation, CanvasWorkspaceLoad } from '@/types/canvas-api'

const OBJECT_OPERATIONS = new Set(['MUTATE', 'COPY', 'UNDO', 'IMPORT'])
const PROJECTION_OPERATIONS = new Set([
  'TASK_ACCEPTED', 'TASK_TERMINAL', 'RESULT_SELECTED', 'RESULT_AUTO_SELECTED', 'ASSET_TRANSFER'
])

export class CanvasDeltaGapError extends Error {
  constructor() {
    super('画布增量不连续，需要重新载入快照')
  }
}

function changedObjects(operation: CanvasOperation): CanvasObjectRecord[] {
  const values = operation.data?.objects
  if (!Array.isArray(values)) {
    if (!PROJECTION_OPERATIONS.has(operation.operationType)) throw new CanvasDeltaGapError()
    return []
  }
  if (!OBJECT_OPERATIONS.has(operation.operationType)) throw new CanvasDeltaGapError()
  return values as CanvasObjectRecord[]
}

function applyTextResult(objects: Map<string, CanvasObjectRecord>, operation: CanvasOperation): void {
  const objectId = String(operation.data?.objectId || '')
  const objectVersion = Number(operation.data?.objectVersion)
  const textContent = operation.data?.textContent
  const current = objects.get(objectId)
  if (!current || current.objectType !== 'NODE' || current.content?.nodeType !== 'TEXT'
    || !Number.isSafeInteger(objectVersion) || objectVersion !== current.objectVersion + 1
    || typeof textContent !== 'string') throw new CanvasDeltaGapError()
  const content: Record<string, unknown> = { ...current.content, textContent }
  delete content.textFormat
  delete content.content
  objects.set(objectId, { ...current, objectVersion, content })
}

/** Apply only a complete, contiguous event range to the last accepted snapshot. */
export function mergeCanvasWorkspaceDelta(
  previous: CanvasWorkspaceLoad,
  latest: CanvasWorkspaceLoad,
  events: CanvasOperation[]
): CanvasWorkspaceLoad {
  if (previous.mode !== 'SNAPSHOT' || !previous.objects || latest.mode !== 'CHANGES') {
    throw new CanvasDeltaGapError()
  }
  let cursor = Number(previous.workspace.eventSeq) || 0
  const objects = new Map(previous.objects.map((object) => [String(object.objectId), object]))
  for (const event of events) {
    const seq = Number(event.eventSeq)
    if (!Number.isSafeInteger(seq) || seq !== cursor + 1) throw new CanvasDeltaGapError()
    if (event.operationType === 'TEXT_AUTO_APPLIED') {
      applyTextResult(objects, event)
    } else {
      for (const object of changedObjects(event)) {
        if (!object?.objectId || !object.objectType) throw new CanvasDeltaGapError()
        if (object.deleted) objects.delete(String(object.objectId))
        else objects.set(String(object.objectId), object)
      }
    }
    cursor = seq
  }
  if (cursor !== Number(latest.currentSeq ?? latest.workspace.eventSeq)) throw new CanvasDeltaGapError()
  return {
    ...latest,
    mode: 'SNAPSHOT',
    objects: [...objects.values()],
    changes: undefined,
    workspace: { ...latest.workspace, eventSeq: cursor },
    lastSeq: cursor,
    currentSeq: cursor,
    hasMore: false
  }
}
