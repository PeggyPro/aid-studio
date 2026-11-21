type AccessLink = { id: string | number; status: string }

export type CanvasAccessCreationResult<T, R extends AccessLink> =
  | { status: 'created'; value: T }
  | { status: 'failed'; error: unknown }
  | { status: 'uncertain'; error: unknown; beforeIds: string[]; newActiveIds: string[]; rows: R[] | null }

function definiteRejection(error: unknown): boolean {
  const detail = error as { code?: number; response?: { status?: number } }
  const code = Number(detail?.response?.status ?? detail?.code)
  return code >= 400 && code < 500 && code !== 408 && code !== 429
}

/** A lost create response must not be blindly retried: compare authoritative link records first. */
export async function createCanvasAccessLinkSafely<T, R extends AccessLink>(
  list: () => Promise<R[]>,
  create: () => Promise<T>
): Promise<CanvasAccessCreationResult<T, R>> {
  let before: R[]
  try {
    before = await list()
  } catch (error) {
    return { status: 'failed', error }
  }
  try {
    return { status: 'created', value: await create() }
  } catch (error) {
    const beforeIds = before.map((item) => String(item.id))
    const rows = await list().catch(() => null)
    const known = new Set(beforeIds)
    const newActiveIds = (rows || []).filter((item) => !known.has(String(item.id)) && item.status === 'ACTIVE')
      .map((item) => String(item.id))
    if (rows && !newActiveIds.length && definiteRejection(error)) return { status: 'failed', error }
    return { status: 'uncertain', error, beforeIds, newActiveIds, rows }
  }
}

export type CanvasAccessUncertainty = { beforeIds: string[]; newActiveIds: string[] }

export function createCanvasAccessUncertaintyStore(storage?: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> | null) {
  const keyFor = (projectId: string, kind: 'invite' | 'share') => `aid:canvas:uncertain:${projectId}:${kind}`
  const activeStorage = () => storage === undefined
    ? (typeof window === 'undefined' ? null : window.sessionStorage)
    : storage
  return {
    read(projectId: string, kind: 'invite' | 'share'): CanvasAccessUncertainty | null {
      if (!projectId) return null
      try {
        const raw = activeStorage()?.getItem(keyFor(projectId, kind))
        if (!raw) return null
        const parsed = JSON.parse(raw) as Partial<CanvasAccessUncertainty>
        if (Array.isArray(parsed.beforeIds) && Array.isArray(parsed.newActiveIds)) {
          return { beforeIds: parsed.beforeIds.map(String), newActiveIds: parsed.newActiveIds.map(String) }
        }
      } catch { /* storage may be unavailable */ }
      return null
    },
    write(projectId: string, kind: 'invite' | 'share', value: CanvasAccessUncertainty): void {
      if (!projectId) return
      try { activeStorage()?.setItem(keyFor(projectId, kind), JSON.stringify(value)) } catch { /* UI state remains active */ }
    },
    clear(projectId: string, kind: 'invite' | 'share'): void {
      if (!projectId) return
      try { activeStorage()?.removeItem(keyFor(projectId, kind)) } catch { /* UI state remains active */ }
    }
  }
}

export const canvasAccessUncertaintyStore = createCanvasAccessUncertaintyStore()
