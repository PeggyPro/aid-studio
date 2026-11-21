'use client'

import { useCallback, useState } from 'react'
import type { AssistantComposerDraftStore } from '@/components/assistant/AssistantSessionPanel'

/** Panel-lifetime drafts isolated by canvas, account, assistant mode and session. */
export function useCanvasAssistantComposerDraft(binding: string) {
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const key = (sessionId: string) => JSON.stringify([binding, sessionId])
  const store: AssistantComposerDraftStore = {
    read: sessionId => drafts[key(sessionId)] || '',
    update: (sessionId, value) => {
      const draftKey = key(sessionId)
      setDrafts(current => {
        const next = typeof value === 'function' ? value(current[draftKey] || '') : value
        if (current[draftKey] === next) return current
        return { ...current, [draftKey]: next }
      })
    }
  }
  const bindSession = useCallback((sessionId: string) => {
    setDrafts(current => {
      const pending = JSON.stringify([binding, '']), target = JSON.stringify([binding, sessionId])
      if (!(pending in current)) return current
      const next = { ...current, [target]: current[target] ?? current[pending] }
      delete next[pending]
      return next
    })
  }, [binding])
  return { store, bindSession }
}
