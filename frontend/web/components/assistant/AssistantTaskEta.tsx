'use client'

import { useEffect, useState } from 'react'
import type { AssistantTask } from '~/types/assistant-api'
import { formatTaskEtaText } from '~/utils/taskSseProgressText'

/** Uses server ETA and the existing formatter; the local clock never polls or changes task state. */
export function AssistantTaskEta({ task, hidden = false }: { task: AssistantTask; hidden?: boolean }) {
  const [now, setNow] = useState(() => Date.now())
  const active = !hidden && !['SUCCEEDED', 'FAILED', 'CANCELLED', 'CANCELED', 'COMPLETED'].includes(task.status)
  useEffect(() => {
    if (!active || !task.eta) return
    setNow(Date.now())
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [active, task.eta])
  if (!active) return null
  const estimate = formatTaskEtaText(task.eta, now)
  const queue = task.status === 'QUEUED' && typeof task.queuePosition === 'number' && task.queuePosition > 0
    ? `排队中 · 第 ${task.queuePosition} 位` : ''
  if (!estimate && !queue) return null
  return <p className="assistant-task-eta text-xs" aria-live="off">{[queue, estimate].filter(Boolean).join(' · ')}</p>
}
