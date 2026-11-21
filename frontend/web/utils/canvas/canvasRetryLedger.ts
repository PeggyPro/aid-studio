type RetryRecord = { fingerprint: string; operationId: string }
type RetryStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

/** Keep an uncertain write's idempotency key across a reload of the same tab. */
export function createCanvasRetryLedger(storage?: RetryStorage | null) {
  const memory = new Map<string, RetryRecord>()
  const keyFor = (scope: string) => `aid:canvas:retry:${scope}`
  const activeStorage = () => storage === undefined
    ? (typeof window === 'undefined' ? null : window.sessionStorage)
    : storage

  const read = (scope: string): RetryRecord | null => {
    const key = keyFor(scope)
    try {
      const backend = activeStorage()
      const raw = backend?.getItem(key)
      if (backend && !raw) return null
      if (raw) {
        const parsed = JSON.parse(raw) as Partial<RetryRecord>
        if (typeof parsed.fingerprint === 'string' && typeof parsed.operationId === 'string') {
          return parsed as RetryRecord
        }
      }
    } catch { /* session storage may be unavailable */ }
    return memory.get(key) || null
  }

  return {
    getOrCreate(scope: string, fingerprint: string, createId: () => string): string {
      const existing = read(scope)
      if (existing?.fingerprint === fingerprint) return existing.operationId
      const record = { fingerprint, operationId: createId() }
      const key = keyFor(scope)
      memory.set(key, record)
      try { activeStorage()?.setItem(key, JSON.stringify(record)) } catch { /* keep memory fallback */ }
      return record.operationId
    },
    clear(scope: string, fingerprint: string): void {
      if (read(scope)?.fingerprint !== fingerprint) return
      const key = keyFor(scope)
      memory.delete(key)
      try { activeStorage()?.removeItem(key) } catch { /* keep memory fallback */ }
    }
  }
}

export const canvasRetryLedger = createCanvasRetryLedger()
