'use client'

import { useEffect, useMemo, useRef, useSyncExternalStore } from 'react'
import type { AssistantScope } from '~/types/assistant-api'
import { createAssistantApi, mainAssistantTransport, type AssistantTransport } from '~/utils/business/assistant'
import { assistantDigest } from '~/utils/assistant/assistantRequestCoordinator'
import { AssistantSessionStore } from '~/utils/assistant/assistantSessionStore'

export function useAssistantSession(scope: AssistantScope, actorId: string, transport: AssistantTransport = mainAssistantTransport, startFresh = false, enabled = true) {
  const scopeKey = assistantDigest(scope)
  const identity = transport.identityKey()
  const store = useMemo(() => new AssistantSessionStore(createAssistantApi(transport, identity), JSON.parse(scopeKey) as AssistantScope, actorId, startFresh),
    [actorId, scopeKey, transport, identity, startFresh])
  const previous = useRef<{ store: AssistantSessionStore; actorId: string } | null>(null)
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot)
  useEffect(() => {
    if (previous.current && previous.current.actorId !== actorId) previous.current.store.clearPrivateRecovery()
    previous.current = { store, actorId }
    if (!actorId || !enabled) return
    const release = store.acquire()
    return () => {
      if (transport.identityKey() !== identity) store.clearPrivateRecovery()
      release()
    }
  }, [actorId, identity, store, transport, enabled])
  return { ...state, store }
}
