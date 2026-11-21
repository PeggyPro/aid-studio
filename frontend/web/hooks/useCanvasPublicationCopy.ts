'use client'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { CanvasCopyJob } from '@/types/canvas-api'
import {
  canvasPublicationCopy,
  canvasPublicationCopyAction,
  canvasPublicationCopyStatus,
} from '@/utils/canvas/canvasPublicationApi'
import { canvasOperationId } from '@/utils/canvas/canvasId'
import { apiErrorMessage } from '@/utils/apiError'
import { useUserStore } from '@/stores/user'

const terminal = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED'])
export function useCanvasPublicationCopy(publicationId: string) {
  const userId = useUserStore((s) => s.user?.id || ''),
    token = useUserStore((s) => s.token)
  const [job, setJob] = useState<CanvasCopyJob | null>(null),
    [error, setError] = useState(''),
    [busy, setBusy] = useState(false)
  const request = useRef(false),
    key = useRef(''),
    active = useRef(false),
    actionVersion = useRef(0)
  const storageKey = `aid:canvas:copy:${userId}:${publicationId}`
  const identity = useRef(storageKey + token)
  useLayoutEffect(() => {
    identity.current = storageKey + token
  }, [storageKey, token])
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    active.current = true
    let cancelled = false,
      timer: ReturnType<typeof setTimeout> | undefined
    let saved = ''
    try {
      saved = localStorage.getItem(storageKey) || ''
    } catch {}
    key.current = saved || key.current
    if (!token || !userId || !key.current)
      return () => {
        active.current = false
      }
    const operationId = key.current
    async function poll() {
      if (cancelled) return
      const version = actionVersion.current
      try {
        const next = await canvasPublicationCopyStatus(operationId)
        if (cancelled) return
        if (version === actionVersion.current && !request.current) {
          setJob(next)
          setError('')
          if (terminal.has(next.status)) return
        }
      } catch (e) {
        if (!cancelled && version === actionVersion.current)
          setError(apiErrorMessage(e, '复制状态暂时不可用，正在核实原请求'))
      }
      if (!cancelled)
        timer = setTimeout(() => void poll(), document.hidden ? 5000 : 1500)
    }
    void poll()
    return () => {
      cancelled = true
      active.current = false
      if (timer) clearTimeout(timer)
    }
  }, [publicationId, storageKey, token, userId, revision])
  async function act(action: 'start' | 'cancel' | 'retry') {
    if (request.current || !token || !userId) return
    request.current = true
    actionVersion.current += 1
    setBusy(true)
    setError('')
    const expectedIdentity = storageKey + token
    if (!key.current) {
      key.current = canvasOperationId('publication-copy')
      try {
        localStorage.setItem(storageKey, key.current)
      } catch {}
    }
    try {
      const next =
        action === 'start'
          ? await canvasPublicationCopy(publicationId, key.current)
          : await canvasPublicationCopyAction(key.current, action)
      if (!active.current || expectedIdentity !== identity.current) return
      setJob(next)
    } catch (e) {
      if (active.current && expectedIdentity === identity.current)
        setError(apiErrorMessage(e, '复制结果暂未确认，正在核实原请求'))
    } finally {
      request.current = false
      if (active.current && expectedIdentity === identity.current) {
        setBusy(false)
        setRevision((v) => v + 1)
      }
    }
  }
  return {
    job,
    error,
    busy,
    start: () => act('start'),
    cancel: () => act('cancel'),
    retry: () => act('retry'),
  }
}
