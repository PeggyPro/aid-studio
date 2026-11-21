import { canvasLockAcquire, canvasLockRelease, canvasLockRenew } from '@/utils/business/canvas'
import type { CanvasLockItem } from '@/types/canvas-api'

/** 同一会话内自动保存和长时精修共享租约，最后一个持有者关闭才释放。 */
export function createCanvasEditLeaseManager(api = { acquire: canvasLockAcquire, release: canvasLockRelease, renew: canvasLockRenew }) {
  const held = new Map<string, { lock: CanvasLockItem; count: number; releaseUncertain: boolean }>()
  let queue: Promise<unknown> = Promise.resolve()
  const serial = <T>(fn: () => Promise<T>): Promise<T> => {
    const next = queue.then(fn, fn)
    queue = next.catch(() => undefined)
    return next
  }
  return {
    acquire(workspaceId: string, sessionId: string, ids: string[]) {
      return serial(async () => {
        const unique = [...new Set(ids)]
        const key = (id: string) => `${workspaceId}:${sessionId}:${id}`
        // A release response can be lost after the server has processed it.
        // Reconcile that ambiguity before handing its old token to a new writer.
        const uncertain = unique.filter((id) => held.get(key(id))?.releaseUncertain)
        if (uncertain.length) {
          const previous = uncertain.map((id) => held.get(key(id))!.lock)
          let stillHeld = false
          try {
            const response = await api.renew(workspaceId, sessionId, previous)
            stillHeld = previous.every((lock) => response.locks.some((item) => String(item.objectId) === lock.objectId && item.lockToken === lock.lockToken))
          } catch {
            // The release may have succeeded. Ask acquire for the authoritative
            // token; the server reuses a lock owned by this exact session.
          }
          if (!stillHeld) {
            const response = await api.acquire(workspaceId, sessionId, uncertain)
            const grants = response.locks.map((lock) => ({ objectId: String(lock.objectId), lockToken: String(lock.lockToken || '') }))
            if (uncertain.some((id) => !grants.some((lock) => lock.objectId === id && lock.lockToken))) {
              await api.release(workspaceId, sessionId, grants).catch(() => undefined)
              throw new Error('未取得完整节点编辑租约')
            }
            for (const lock of grants) {
              const entry = held.get(key(lock.objectId))
              if (entry) entry.lock = lock
            }
          }
          for (const id of uncertain) held.get(key(id))!.releaseUncertain = false
        }
        const missing = unique.filter((id) => !held.has(key(id)))
        if (missing.length) {
          const response = await api.acquire(workspaceId, sessionId, missing)
          const locks = response.locks.map((lock) => ({ objectId: String(lock.objectId), lockToken: String(lock.lockToken || '') }))
          if (missing.some((id) => !locks.some((lock) => lock.objectId === id && lock.lockToken))) {
            await api.release(workspaceId, sessionId, locks).catch(() => undefined)
            throw new Error('未取得完整节点编辑租约')
          }
          for (const lock of locks) held.set(key(lock.objectId), { lock, count: 0, releaseUncertain: false })
        }
        const locks = unique.map((id) => { const entry = held.get(key(id))!; entry.count += 1; return entry.lock })
        let released = false
        return {
          locks,
          renew: async () => {
            if (released) throw new Error('节点编辑租约已释放')
            const response = await api.renew(workspaceId, sessionId, locks)
            if (locks.some((lock) => !response.locks.some((renewed) => String(renewed.objectId) === lock.objectId && renewed.lockToken === lock.lockToken))) throw new Error('节点编辑租约已失效')
          },
          release: () => serial(async () => {
            if (released) return
            released = true
            const unused = locks.filter((lock) => { const entry = held.get(key(lock.objectId)); if (!entry) return false; entry.count -= 1; return entry.count === 0 })
            if (!unused.length) return
            try {
              await api.release(workspaceId, sessionId, unused)
              for (const lock of unused) held.delete(key(lock.objectId))
            } catch (error) {
              for (const lock of unused) {
                const entry = held.get(key(lock.objectId))
                if (entry) entry.releaseUncertain = true
              }
              throw error
            }
          }),
          /** The server promoted these edit locks to task locks. Drop all local holders without releasing them. */
          handoff: () => serial(async () => {
            if (released) return
            released = true
            for (const lock of locks) held.delete(key(lock.objectId))
          })
        }
      })
    }
  }
}
export type CanvasNodeEditLease = Awaited<ReturnType<ReturnType<typeof createCanvasEditLeaseManager>['acquire']>>
