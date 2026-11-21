'use client'

import { useEffect, useLayoutEffect, useRef, useState, type SetStateAction } from 'react'

export interface AssistantComposerDraftStore {
  read: (sessionId: string) => string
  update: (sessionId: string, value: SetStateAction<string>) => void
}

/** A switch owns a draft before its first await, independently of the old snapshot. */
export function useAssistantComposerDraft(sessionId: string, store?: AssistantComposerDraftStore) {
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [transition, setTransition] = useState<{ token: object; target?: string; text: string } | null>(null)
  const current = useRef(transition)
  const unopened = useRef<string | null>(null)
  const initialInput = useRef<{ text: string } | null>(null)
  const host = useRef(store)
  useLayoutEffect(() => { host.current = store }, [store])
  const read = (id: string) => store ? store.read(id) : drafts[id] || ''
  const write = (id: string, value: SetStateAction<string>) => {
    if (host.current) host.current.update(id, value)
    else setDrafts(previous => ({ ...previous, [id]: typeof value === 'function' ? value(previous[id] || '') : value }))
  }
  const text = transition?.text ?? read(sessionId)
  useEffect(() => {
    if (!sessionId || current.current || !initialInput.current) return
    const input = initialInput.current
    initialInput.current = null
    write(sessionId, input.text)
    // A first restoration binds only text actually entered while the session was unknown.
  }, [sessionId, transition])
  const setText = (value: SetStateAction<string>) => {
    const pending = current.current
    if (!pending) {
      if (!sessionId) {
        const next = typeof value === 'function' ? value(read('')) : value
        initialInput.current = { text: next }
        write('', next)
      } else write(sessionId, value)
      return
    }
    const next = { ...pending, text: typeof value === 'function' ? value(pending.text) : value }
    current.current = next
    setTransition(next)
    // Known history targets retain their edits even if reading that session fails.
    if (next.target) write(next.target, next.text)
  }
  const begin = (target?: string) => {
    if (current.current) return null
    const token = {}
    const pending = { token, target, text: target ? read(target) : unopened.current ?? (!sessionId ? read('') : '') }
    current.current = pending
    setTransition(pending)
    return token
  }
  const finish = (token: object, destination?: string) => {
    const pending = current.current
    if (pending?.token !== token) return
    if (destination) {
      write(destination, pending.text)
      initialInput.current = null
      if (!pending.target) unopened.current = null
    } else if (!pending.target) {
      // A failed open has no destination yet; keep its new draft for an explicit retry.
      unopened.current = pending.text
    }
    current.current = null
    setTransition(null)
  }
  return { text, setText, begin, finish, clear: (id: string) => write(id, ''), transitioning: !!transition }
}
