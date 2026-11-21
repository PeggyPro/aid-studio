/** Own lock/presence notifications confirm the HTTP operation we just performed.
 * Other participants' changes require an authoritative state read. */
export function canvasCollaborationNeedsState(
  event: Record<string, unknown>,
  ownSessionId: string,
  ownUserId: string | null
): boolean {
  if (event.type === 'PRESENCE_STATE') {
    return !ownSessionId || String(event.sessionId || '') !== ownSessionId
  }
  if (event.type !== 'LOCK_CHANGED') return false
  const locks = event.locks
  if (!Array.isArray(locks) || locks.length === 0) return true
  return locks.some((item: unknown) => {
    if (!item || typeof item !== 'object') return true
    const lock = item as Record<string, unknown>
    const holderSession = String(lock.sessionId || '')
    if (holderSession) return holderSession !== ownSessionId
    return !ownUserId || String(lock.userId || '') !== ownUserId
  })
}
