'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { StudioSpeechInputSession, studioSpeechInputUnavailableReason } from '@/utils/studio/studioSpeechInput'

export function useStudioSpeechInput(options: {
  scopeKey: string
  enabled: boolean
  isCurrent: () => boolean
  onText: (text: string, confirmed: string) => void
  onFinish: () => void
}) {
  const [state, setState] = useState<'idle' | 'requesting' | 'connecting' | 'listening'>('idle')
  const [level, setLevel] = useState(0)
  const [error, setError] = useState('')
  const [unavailable, setUnavailable] = useState('')
  const latest = useRef(options)
  const session = useRef<StudioSpeechInputSession | null>(null)
  useEffect(() => { latest.current = options })

  useEffect(() => {
    // Detection runs after hydration; no browser API is accessed during SSR.
    const timer = window.setTimeout(() => setUnavailable(studioSpeechInputUnavailableReason()), 0)
    return () => window.clearTimeout(timer)
  }, [])

  useEffect(() => () => {
    session.current?.dispose()
    session.current = null
  }, [options.scopeKey, options.enabled])

  const stop = useCallback(() => {
    session.current?.stop()
    session.current = null
  }, [])

  const start = useCallback(() => {
    if (session.current || !latest.current.enabled) return
    setError('')
    const currentScope = latest.current.scopeKey
    const current = new StudioSpeechInputSession({
      isCurrent: () => latest.current.enabled && latest.current.scopeKey === currentScope && latest.current.isCurrent(),
      onState: (next) => {
        setState(next)
        if (next === 'idle') session.current = null
      },
      onLevel: setLevel,
      onText: (text, confirmed) => latest.current.onText(text, confirmed),
      onError: setError,
      onFinish: () => latest.current.onFinish()
    })
    session.current = current
    void current.start()
  }, [])

  return { state, active: state !== 'idle', level, error, unavailable, start, stop }
}
