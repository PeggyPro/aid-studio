'use client'

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { AssistantSessionSnapshot } from '~/types/assistant-api'
import type { AssistantRunBudgetPolicy } from '~/types/assistant-budget'
import type { AssistantSessionStore } from '~/utils/assistant/assistantSessionStore'
import { assistantErrorMessage } from '~/utils/assistant/assistantRequestCoordinator'

// All budget reads in this host share an in-flight request, including explicit recovery.
// There is no result cache and no mutation, command replay or execution intent here.
const flights = new WeakMap<AssistantSessionStore, Map<string, Promise<AssistantRunBudgetPolicy>>>()
const received = new WeakMap<AssistantRunBudgetPolicy, number>()
type ExpiryAttempt = { first: Promise<AssistantRunBudgetPolicy>; postExpiry?: Promise<AssistantRunBudgetPolicy> }
const expiryAttempts = new WeakMap<AssistantSessionStore, Map<string, ExpiryAttempt>>()
const started = new WeakMap<Promise<AssistantRunBudgetPolicy>, number>()
const readOrders = new WeakMap<Promise<AssistantRunBudgetPolicy>, number>()
const receivedOrders = new WeakMap<AssistantRunBudgetPolicy, number>()
let nextReadOrder = 0
function readBudget(store: AssistantSessionStore, runId: string): Promise<AssistantRunBudgetPolicy> {
  let reads = flights.get(store)
  if (!reads) { reads = new Map(); flights.set(store, reads) }
  const pending = reads.get(runId)
  if (pending) return pending
  const startedAt = Date.now(), readOrder = ++nextReadOrder
  const request = Promise.resolve().then(() => store.api.budgetDetail(runId)).then(value => {
    received.set(value, startedAt)
    receivedOrders.set(value, readOrder)
    return value
  })
  started.set(request, startedAt)
  readOrders.set(request, readOrder)
  reads.set(runId, request)
  void request.finally(() => { if (reads.get(runId) === request) reads.delete(runId) }).catch(() => undefined)
  return request
}
function scope(snapshot: AssistantSessionSnapshot): string {
  return JSON.stringify([snapshot.sessionId, snapshot.revision, snapshot.lastEventSeq,
    snapshot.activeRun?.runId, snapshot.activeRun?.stateRevision])
}
function budgetKey(sessionId: string, budget: AssistantRunBudgetPolicy): string {
  return JSON.stringify([sessionId, budget.runId, budget.policyRevision,
    budget.authorizationId, budget.authorizationRevision, budget.expiresAt])
}

/** A clock crossing requests current authority once; time never grants renewal permission. */
export function useAssistantBudgetExpiryRefresh(store: AssistantSessionStore, snapshot: AssistantSessionSnapshot,
  budget: AssistantRunBudgetPolicy | null, clock: number, busy: boolean,
  onBudget: (value: AssistantRunBudgetPolicy) => void) {
  const [visible, setVisible] = useState(() => typeof document === 'undefined' || document.visibilityState === 'visible')
  const [outcome, setOutcome] = useState<{ scope: string; budget: AssistantRunBudgetPolicy; readOrder: number; pending: boolean; error: string | null } | null>(null)
  const current = useRef({ store, snapshot, budget, busy, visible, onBudget })
  useLayoutEffect(() => { current.current = { store, snapshot, budget, busy, visible, onBudget } }, [store, snapshot, budget, busy, visible, onBudget])
  const lifetime = useRef(0)
  const joined = useRef(new Map<string, number>())
  useEffect(() => {
    lifetime.current += 1
    const update = () => setVisible(document.visibilityState === 'visible')
    document.addEventListener('visibilitychange', update)
    return () => { lifetime.current += 1; document.removeEventListener('visibilitychange', update) }
  }, [store])
  const read = useCallback((runId: string) => readBudget(store, runId), [store])
  const renderedScope = scope(snapshot)
  useEffect(() => {
    const run = snapshot.activeRun, expiresAt = budget ? Date.parse(budget.expiresAt) : NaN
    if (!visible || busy || !run || !budget || budget.runId !== run.runId || budget.runRevision !== run.stateRevision
      || !Number.isFinite(expiresAt) || expiresAt > clock || budget.status === 'EXPIRED'
      || (received.get(budget) ?? -Infinity) >= expiresAt) return
    const latest = store.getSnapshot().snapshot
    if (!latest || scope(latest) !== renderedScope) return
    const key = budgetKey(snapshot.sessionId, budget)
    const generation = lifetime.current
    const joinKey = JSON.stringify([key, renderedScope])
    if (joined.current.get(joinKey) === generation) return
    joined.current.set(joinKey, generation)
    let attempted = expiryAttempts.get(store)
    if (!attempted) { attempted = new Map(); expiryAttempts.set(store, attempted) }
    let attempt = attempted.get(key)
    if (!attempt) { attempt = { first: read(run.runId) }; attempted.set(key, attempt) }
    const request = attempt.postExpiry ?? attempt.first
    setOutcome({ scope: renderedScope, budget, readOrder: readOrders.get(attempt.postExpiry ?? request)!, pending: true, error: null })
    const stillCurrent = () => {
      const value = current.current, authoritative = store.getSnapshot().snapshot
      return lifetime.current === generation && value.store === store
        && scope(value.snapshot) === renderedScope && !!authoritative && scope(authoritative) === renderedScope
        && !!value.budget && budgetKey(value.snapshot.sessionId, value.budget) === key
    }
    void request.then(async value => {
      if (!stillCurrent()) return
      // Joining an earlier read must not consume the one real post-deadline check.
      // The read coordinator has removed its settled flight before this continuation.
      if ((started.get(request) ?? -Infinity) < expiresAt && value.status !== 'EXPIRED'
        && value.renewBlockReason === 'NOT_EXPIRED') {
        if (value.runId !== run.runId || value.runRevision !== run.stateRevision || budgetKey(snapshot.sessionId, value) !== key)
          throw new Error('费用状态已变化，请重新同步当前对话。')
        const live = current.current
        if (!live.visible || live.busy || store.getSnapshot().busy || Date.now() < expiresAt) {
          joined.current.delete(joinKey)
          return
        }
        if (!attempt.postExpiry) attempt.postExpiry = read(run.runId)
        value = await attempt.postExpiry
        if (!stillCurrent()) return
      }
      if (value.runId !== run.runId || value.runRevision !== run.stateRevision || value.policyRevision < budget.policyRevision)
        throw new Error('费用状态已变化，请重新同步当前对话。')
      current.current.onBudget(value)
      setOutcome({ scope: renderedScope, budget, readOrder: readOrders.get(attempt.postExpiry ?? request)!, pending: false, error: null })
    }).catch(cause => {
      if (stillCurrent()) setOutcome({ scope: renderedScope, budget: current.current.budget!, readOrder: readOrders.get(attempt.postExpiry ?? request)!, pending: false, error: assistantErrorMessage(cause) })
    }).finally(() => {
      // A switched or hidden host must not retain a loading state after its old read settles.
      if (lifetime.current === generation) setOutcome(value => value?.scope === renderedScope && value.pending
        ? { ...value, pending: false } : value)
    })
  }, [store, snapshot, budget, clock, busy, visible, read, renderedScope])
  // A new authoritative object or scope supersedes any failed TTL attempt.
  const loading = outcome?.scope === renderedScope && !!budget
    && budgetKey(snapshot.sessionId, outcome.budget) === budgetKey(snapshot.sessionId, budget) && outcome.pending
  const unverified = !!budget && budget.runId === snapshot.activeRun?.runId && budget.renewBlockReason === 'NOT_EXPIRED'
    && Number.isFinite(Date.parse(budget.expiresAt)) && Date.parse(budget.expiresAt) <= clock
  return { readBudget: read, loading, error: (outcome?.scope === renderedScope && budget && budgetKey(snapshot.sessionId, outcome.budget) === budgetKey(snapshot.sessionId, budget)
    && (receivedOrders.get(budget) ?? 0) <= outcome.readOrder ? outcome.error : null)
    || (!loading && unverified ? '费用确认期限已到，请重新同步核实当前状态；对话与历史仍保留。' : null) }
}
