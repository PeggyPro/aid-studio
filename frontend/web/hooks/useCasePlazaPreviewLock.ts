'use client'

import { useCallback, useEffect, useSyncExternalStore } from 'react'

let activePreviewId: string | null = null
const listeners = new Set<() => void>()

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function emitChange() {
  for (const listener of listeners) listener()
}

function setActivePreviewId(nextId: string | null) {
  if (activePreviewId === nextId) return
  activePreviewId = nextId
  emitChange()
}

export function useCasePlazaPreviewLock(itemId: string) {
  const isActive = useSyncExternalStore(
    subscribe,
    () => activePreviewId === itemId,
    () => false
  )

  const acquire = useCallback(() => setActivePreviewId(itemId), [itemId])
  const release = useCallback(() => {
    if (activePreviewId === itemId) setActivePreviewId(null)
  }, [itemId])

  useEffect(() => release, [release])

  return { isActive, acquire, release }
}
