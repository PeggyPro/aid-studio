'use client'

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { AssistantSessionSnapshot } from '@/types/assistant-api'
import type { AssistantSessionStore } from '@/utils/assistant/assistantSessionStore'
import { assistantCommandId } from '@/utils/assistant/assistantRequestCoordinator'

/** Only stale saved content is renewable. Access denials, new targets and unknown tasks are not. */
export function canvasAssistantContextSyncKey(snapshot: AssistantSessionSnapshot | null, conflict?: { sessionId: string; runId: string } | null): string | null {
  const run = snapshot?.activeRun
  if (!snapshot || !run || run.stopRequested || !['PAUSED', 'WAITING_APPROVAL'].includes(run.status)) return null
  const analysis = [...snapshot.steps].reverse().find(step => step.stepKind === 'MAIN_AGENT' || step.operationKind === 'ASSISTANT_DECISION')
  const latest = snapshot.steps.at(-1)
  if (snapshot.steps.some(step => step.providerAcceptanceState === 'UNKNOWN' || step.status === 'RECONCILING')
    || ['ASSISTANT_SCOPE_DENIED', 'CREATIVE_SOURCE_SCOPE_REQUIRED', 'CREATIVE_CONTEXT_INVALID'].includes(latest?.errorCode || '')) return null
  const stale = conflict?.sessionId === snapshot.sessionId && conflict.runId === run.runId
    || analysis === latest && run.nextAction === 'REPLANNING_REQUIRED' && analysis?.status === 'READY' && !analysis.mediaTaskId && analysis.errorCode === 'ASSISTANT_CONTEXT_STALE'
    || run.pauseReason === 'OPERATION_PREPARATION_REJECTED' && snapshot.plan?.status === 'APPROVED'
      && latest?.planRevision === snapshot.plan.revision
      && latest?.status === 'FAILED_PRETASK' && latest.providerAcceptanceState === 'NOT_SUBMITTED'
      && latest.errorCode === 'TOOL_PREPARATION_REFERENCE_CONTEXT_INVALID'
  return stale ? JSON.stringify([snapshot.sessionId, run.runId, run.contextDigest || '', snapshot.inputRequest?.requestId || '', latest?.stepId || '']) : null
}

/** One refresh per stale context, using the existing command journal and original target identities. */
export function useCanvasAssistantContextSync({ enabled, store, prepare, blocked }: {
  enabled: boolean; store: AssistantSessionStore; prepare?: () => Promise<void>; blocked: boolean
}) {
  const state = store.getSnapshot()
  const key = enabled && prepare ? canvasAssistantContextSyncKey(state.snapshot, state.contextConflict) : null
  const attempted = useRef(new Set<string>())
  const flight = useRef(false)
  const lifetime = useRef({ active: false })
  const latest = useRef({ enabled, prepare, blocked })
  useLayoutEffect(() => { latest.current = { enabled, prepare, blocked } })
  const [failure, setFailure] = useState<{ key: string; message: string } | null>(null)
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const token = { active: true }
    lifetime.current = token; flight.current = false; attempted.current.clear()
    return () => { token.active = false }
  }, [store, enabled])
  useEffect(() => {
    if (!key || blocked || !state.writable || state.busy || state.pendingCommand || flight.current || attempted.current.has(key)) return
    const generation = lifetime.current
    const current = () => {
      const view = store.getSnapshot()
      return generation.active && generation === lifetime.current && latest.current.enabled && !latest.current.blocked && view.writable && !view.busy && !view.pendingCommand
        && canvasAssistantContextSyncKey(view.snapshot, view.contextConflict) === key
    }
    attempted.current.add(key)
    flight.current = true
    setFailure(null)
    void (async () => {
      let issued = false, failed = false
      try {
        // A delayed projection may already contain the repair. Read before invalidating anything.
        await store.refresh()
        if (!current()) return
        if (!await store.prepareContextReplacement()) throw new Error('回答尚未可靠保留，请先处理当前问题')
        if (!current()) return
        await latest.current.prepare!()
        if (!current()) return
        const snapshot = store.getSnapshot().snapshot!
        issued = true
        const accepted = await store.execute('refreshContext', { runId: snapshot.activeRun!.runId,
          expectedRunRevision: snapshot.activeRun!.stateRevision, clientRequestId: assistantCommandId(), targetRefs: [], preserveExistingTargets: true })
        if (!accepted) throw new Error(store.getSnapshot().error || '当前素材暂时无法同步')
      } catch (error) {
        failed = true
        if (generation.active && generation === lifetime.current) setFailure({ key, message: error instanceof Error ? error.message : '当前素材暂时无法同步' })
      } finally {
        if (generation.active && generation === lifetime.current) {
          flight.current = false
          if (!issued && !failed) attempted.current.delete(key)
          setRetry(value => value + 1)
        }
      }
    })()
  }, [key, blocked, state.writable, state.busy, state.pendingCommand, store, retry])
  return { automatic: Boolean(key), error: failure?.key === key ? failure.message : '',
    retry: () => { if (key && !flight.current) { attempted.current.delete(key); setRetry(value => value + 1) } } }
}
