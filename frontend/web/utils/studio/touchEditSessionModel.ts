import type {
  StudioTouchEditRect,
  StudioTouchEditRegion,
  StudioTouchEditRegionKind,
  StudioTouchEditSession,
  StudioTouchEditTool
} from '@/types/studio'

function snapshotHistoryEntry(session: StudioTouchEditSession): StudioTouchEditSession['history'][number] {
  return {
    draft: session.draft ? structuredClone(session.draft) : null,
    edits: structuredClone(session.edits),
    menuPhase: session.menuPhase,
    activeRegionId: session.activeRegionId
  }
}

export function createEmptyTouchEditSession(nodeId: string): StudioTouchEditSession {
  return {
    nodeId,
    tool: 'point',
    brushSize: 0.04,
    draft: null,
    menuPhase: 'action',
    activeRegionId: null,
    edits: [],
    history: [],
    draftInstruction: ''
  }
}

export function pushTouchEditHistory(session: StudioTouchEditSession): StudioTouchEditSession {
  return {
    ...session,
    history: [...session.history, snapshotHistoryEntry(session)]
  }
}

export function undoTouchEditSession(session: StudioTouchEditSession): StudioTouchEditSession {
  if (session.history.length === 0) return session
  const history = session.history.slice(0, -1)
  const prev = session.history[session.history.length - 1]!
  return {
    ...session,
    draft: prev.draft ? structuredClone(prev.draft) : null,
    edits: structuredClone(prev.edits),
    menuPhase: prev.menuPhase,
    activeRegionId: prev.activeRegionId,
    draftInstruction: '',
    history
  }
}

export function commitDraftToEdits(
  session: StudioTouchEditSession,
  instruction: string
): StudioTouchEditSession {
  if (!session.draft) return session
  const text = instruction.trim()
  const committed: StudioTouchEditRegion = {
    ...session.draft,
    instruction: text
  }
  return {
    ...session,
    edits: [...session.edits, committed],
    draft: null,
    menuPhase: 'action',
    activeRegionId: committed.id,
    draftInstruction: ''
  }
}

export function buildTouchEditGeneratePayload(
  image: string,
  edits: StudioTouchEditRegion[],
  projectId?: number
): {
  image: string
  projectId?: number
  edits: Array<{
    id: string
    source: StudioTouchEditTool
    kind: StudioTouchEditRegionKind
    rect: StudioTouchEditRect
    fromRect?: StudioTouchEditRect
    brushPath?: { x: number; y: number }[]
    brushSize?: number
    detectLabel?: string
    instruction: string
  }>
} {
  return {
    image,
    ...(projectId !== undefined ? { projectId } : {}),
    edits: edits.map((item) => ({
      id: item.id,
      source: item.source,
      kind: item.kind,
      rect: { ...item.rect },
      ...(item.fromRect ? { fromRect: { ...item.fromRect } } : {}),
      ...(item.brushPath ? { brushPath: item.brushPath.map((p) => ({ ...p })) } : {}),
      ...(item.brushSize !== undefined ? { brushSize: item.brushSize } : {}),
      ...(item.detectLabel ? { detectLabel: item.detectLabel } : {}),
      instruction: item.instruction
    }))
  }
}

export function canSubmitTouchEdit(edits: StudioTouchEditRegion[]): boolean {
  if (edits.length === 0) return false
  return edits.every((item) => Boolean(item.instruction.trim()))
}
