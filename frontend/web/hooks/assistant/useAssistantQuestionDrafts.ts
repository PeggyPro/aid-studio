'use client'

import { useEffect, useRef, useState } from 'react'
import type { AssistantAnswerDraft, AssistantInputRequest } from '~/types/assistant-api'
import type { AssistantSessionStore } from '~/utils/assistant/assistantSessionStore'
import { assistantAnswerError, assistantQuestionEditable, emptyAssistantAnswer } from '~/utils/assistant/assistantQuestions'
import { assistantCommandId, assistantDigest, assistantErrorCode, assistantErrorMessage, assistantErrorEnvelope, assistantCommandWasRejected } from '~/utils/assistant/assistantRequestCoordinator'
import { removeAssistantDurable } from '~/utils/assistant/assistantDurableStorage'
import { assistantAnswerDraft } from '~/utils/assistant/assistantWire'
import { readAssistantQuestionBackup, saveAssistantQuestionBackup, type PendingAssistantAnswer } from '~/utils/assistant/assistantLocalDrafts'
import { AssistantQuestionViewWriter } from '~/utils/assistant/assistantQuestionViewWriter'

function sameValue(a: AssistantAnswerDraft, b: AssistantAnswerDraft) {
  return a.freeText === b.freeText && assistantDigest(a.selectedOptionIds) === assistantDigest(b.selectedOptionIds)
}

export function useAssistantQuestionDrafts(input: AssistantInputRequest, store: AssistantSessionStore) {
  const legacyKey = `aid:assistant:questions:${store.api.transport.kind}:${store.storageNamespace || store.api.transport.kind}:${store.actorId}:${input.requestId}:${input.schemaDigest}`
  const key = `${store.recoveryScopeKey}:questions:${store.getSnapshot().snapshot?.sessionId}:${input.runId}:${input.requestId}:${input.schemaDigest}`
  const writable = store.getSnapshot().writable
  const [durableReady, setDurableReady] = useState(false)
  const backupReady = useRef(false)
  const server = useRef(new Map(input.drafts.map((answer) => [answer.questionId, answer])))
  const pending = useRef<Record<string, PendingAssistantAnswer>>(Object.create(null))
  const cursorRef = useRef(input.cursorQuestionId || input.questions[0]?.id || '')
  const currentRequest = useRef(input)
  const flushPromise = useRef<Promise<boolean> | null>(null)
  const mounted = useRef(true)
  const action = useRef(false)
  const contextStale = useRef(false)
  const [stale, setStale] = useState(false)
  const viewWriter = useRef<AssistantQuestionViewWriter | null>(null)
  viewWriter.current ??= new AssistantQuestionViewWriter(input.requestId, (view) => store.api.saveView(view),
    () => mounted.current && backupReady.current && !contextStale.current && store.getSnapshot().writable && assistantQuestionEditable(currentRequest.current), input.cursorQuestionId)
  const [acting, setActing] = useState(false)
  const suspendCommand = useRef<Record<string, unknown> | undefined>(undefined)
  const [cursor, setCursor] = useState(input.cursorQuestionId || input.questions[0]?.id || '')
  const [answers, setAnswers] = useState<Record<string, AssistantAnswerDraft>>(() => Object.fromEntries(input.questions.map((question) => [question.id, input.drafts.find((answer) => answer.questionId === question.id) || emptyAssistantAnswer(question.id)])))
  const [failure, setFailure] = useState<{ source: 'ANSWER' | 'VIEW'; message: string } | null>(null)
  const error = failure?.message ?? null
  const setError = (message: string | null) => setFailure(message === null ? null : { source: 'ANSWER', message })
  const [saving, setSaving] = useState(false)
  const [conflicts, setConflicts] = useState<string[]>([])
  const [dirtyCount, setDirtyCount] = useState(0)
  const [suspendPending, setSuspendPending] = useState(false)
  const [suspendRetryReady, setSuspendRetryReady] = useState(false)

  const persist = () => {
    if (!mounted.current || !store.getSnapshot().writable || !backupReady.current) return false
    if (['SUBMITTED', 'CONSUMED'].includes(currentRequest.current.status)) {
      try { removeAssistantDurable(key); sessionStorage.removeItem(legacyKey); return true } catch { return false }
    }
    const saved = saveAssistantQuestionBackup(key, { savedAt: Date.now(), cursorQuestionId: cursorRef.current, pending: pending.current, suspendCommand: suspendCommand.current,
      ...(contextStale.current ? { contextErrorCode: 'ASSISTANT_CONTEXT_STALE', archivedAnswers: currentRequest.current.questions.map(question => pending.current[question.id]?.answer || server.current.get(question.id) || emptyAssistantAnswer(question.id)) } : {}) })
    if (!saved && mounted.current) setError('本地草稿未能持久保存，未发送新的操作；请保持页面打开并释放存储空间')
    return saved
  }
  const canWrite = () => store.getSnapshot().writable && durableReady
  const publish = () => {
    if (!mounted.current) return
    setAnswers(Object.fromEntries(currentRequest.current.questions.map((question) => [question.id, pending.current[question.id]?.answer || server.current.get(question.id) || emptyAssistantAnswer(question.id)])))
    setConflicts(Object.keys(pending.current).filter((id) => pending.current[id].conflict))
    setDirtyCount(Object.keys(pending.current).length)
    setSuspendPending(Boolean(suspendCommand.current))
    const pendingCommand = store.getSnapshot().pendingCommand
    setSuspendRetryReady(Boolean(suspendCommand.current) && (!pendingCommand
      || pendingCommand.action === 'suspendInput' && assistantDigest(pendingCommand.body) === assistantDigest(suspendCommand.current)))
  }

  const reconcileSuspendCommand = (authoritativeSuspended = false) => {
    const command = suspendCommand.current
    if (!command) return false
    const state = store.getSnapshot()
    const accepted = state.lastCommandResult?.action === 'suspendInput'
      && assistantDigest(state.lastCommandResult.body) === assistantDigest(command)
    const rejected = state.lastRejectedCommand?.action === 'suspendInput'
      && state.lastRejectedCommand.clientRequestId === command.clientRequestId
    if (!accepted && !rejected && !(authoritativeSuspended && !state.pendingCommand)) return false
    if (accepted) pending.current = Object.create(null)
    suspendCommand.current = undefined
    persist(); publish()
    return true
  }

  const contextConflict = store.getSnapshot().contextConflict
  useEffect(() => {
    if (contextConflict?.runId !== input.runId || contextConflict.requestId !== input.requestId) return
    contextStale.current = true; setStale(true)
    setError('引用内容已更新，请更新引用后继续。你的回答已保留。')
    persist()
    // 保存/提交均可发现冲突；以同一运行的冲突状态停止旧问题的自动保存。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [contextConflict, input.runId])

  useEffect(() => {
    mounted.current = true
    const restore = () => {
    let backup
    try { backup = readAssistantQuestionBackup(key, legacyKey, store.getSnapshot().writable); backupReady.current = true; setDurableReady(true) }
    catch (cause) { backupReady.current = false; setError(assistantErrorMessage(cause)); setDurableReady(false); return }
    if (backup) {
      contextStale.current = backup.contextErrorCode === 'ASSISTANT_CONTEXT_STALE'; setStale(contextStale.current)
      if (contextStale.current) store.reportContextConflict({ errorCode: 'ASSISTANT_CONTEXT_STALE' }, input)
      pending.current = Object.create(null)
      for (const [id, value] of Object.entries(backup.pending)) {
        if (!input.questions.some((question) => question.id === id)) continue
        const authoritative = server.current.get(id) || emptyAssistantAnswer(id)
        if (!value.sent && authoritative.revision > value.answer.revision && sameValue(authoritative, value.answer)) continue
        pending.current[id] = { ...value, conflict: !value.sent && authoritative.revision !== value.answer.revision }
      }
      if (input.questions.some((question) => question.id === backup.cursorQuestionId)) {
        cursorRef.current = backup.cursorQuestionId
        // Restore the external persisted view on mount.
        setCursor(backup.cursorQuestionId)
      }
      suspendCommand.current = backup.suspendCommand
      publish()
    }
    }
    restore()
    const changed = (event: StorageEvent) => { if (event.key === key && !store.getSnapshot().writable) restore() }
    window.addEventListener('storage', changed)
    return () => { persist(); mounted.current = false; viewWriter.current?.invalidate(); window.removeEventListener('storage', changed) }
    // 身份/request/schema变化由QuestionDeck的key重新挂载，不能把旧答案迁到新问题。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, writable])

  useEffect(() => {
    currentRequest.current = input
    for (const answer of input.drafts) {
      const previous = server.current.get(answer.questionId)
      if (previous && previous.revision > answer.revision) continue
      server.current.set(answer.questionId, answer)
      const local = pending.current[answer.questionId]
      if (local && !local.sent && !flushPromise.current && answer.revision !== local.answer.revision) {
        if (sameValue(answer, local.answer)) delete pending.current[answer.questionId]
        else local.conflict = true
      }
    }
    publish()
    // Request identity remounts the deck; local draft refs are reconciled as one unit.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [input])

  const commandState = store.getSnapshot()
  useEffect(() => {
    if (!reconcileSuspendCommand(input.status === 'SUSPENDED')) publish()
    // Store changes rerender the owning session panel; these identities are the exact
    // accepted/rejected/pending evidence for the persisted suspend command.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [input.status, commandState.pendingCommand, commandState.lastCommandResult, commandState.lastRejectedCommand])

  const flush = (): Promise<boolean> => {
    if (flushPromise.current) return flushPromise.current
    if (!canWrite() || contextStale.current || !assistantQuestionEditable(currentRequest.current) || suspendCommand.current) return Promise.resolve(false)
    setSaving(true)
    setError(null)
    flushPromise.current = (async () => {
      try {
        for (const question of currentRequest.current.questions) {
          if (!mounted.current || !canWrite()) return false
          const entry = pending.current[question.id]
          if (!entry) continue
          if (entry.conflict) return false
          entry.sent ??= { answer: { ...entry.answer, selectedOptionIds: [...entry.answer.selectedOptionIds] }, operationId: entry.operationId }
          const sent = entry.sent
          if (!persist()) return false
          const result = await store.api.saveDraft({ requestId: input.requestId, questionId: question.id,
            expectedDraftRevision: sent.answer.revision, selectedOptionIds: sent.answer.selectedOptionIds,
            freeText: sent.answer.freeText, clientOperationId: sent.operationId })
          if (!mounted.current || !canWrite()) return false
          const saved: AssistantAnswerDraft = { ...sent.answer, revision: result.draftRevision, state: result.answerState }
          server.current.set(question.id, saved)
          const latest = pending.current[question.id]
          if (latest && sameValue(latest.answer, sent.answer)) delete pending.current[question.id]
          else if (latest) { latest.answer = { ...latest.answer, revision: result.draftRevision }; latest.sent = undefined }
          if (!persist()) { if (latest) { latest.sent = sent; pending.current[question.id] = latest }; return false }
          publish()
        }
        if (!mounted.current || !canWrite()) return false
        if (!await viewWriter.current!.save(cursorRef.current)) return false
        if (!mounted.current || !canWrite()) return false
        await store.refresh()
        return Object.keys(pending.current).length === 0
      } catch (cause) {
        if (assistantCommandWasRejected(cause)) for (const entry of Object.values(pending.current)) entry.sent = undefined
        if (assistantErrorCode(cause) === 'ASSISTANT_CONTEXT_STALE' && assistantCommandWasRejected(cause)) {
          contextStale.current = true; setStale(true)
          store.reportContextConflict(cause, input)
          await store.refresh()
        }
        if (assistantErrorCode(cause) === 'INPUT_DRAFT_CONFLICT') {
          const envelope = assistantErrorEnvelope(cause)
          const data = envelope.data as { details?: { currentDraft?: unknown } } | undefined
          if (data?.details?.currentDraft) {
            const authoritative = assistantAnswerDraft(data.details.currentDraft)
            server.current.set(authoritative.questionId, authoritative)
            if (pending.current[authoritative.questionId]) pending.current[authoritative.questionId].conflict = true
          } else for (const entry of Object.values(pending.current)) entry.conflict = true
          await store.refresh()
        }
        if (mounted.current) setError(contextStale.current ? '引用内容已更新，请更新引用后继续。你的回答已保留。' : assistantErrorMessage(cause))
        return false
      } finally {
        flushPromise.current = null
        persist()
        publish()
        if (mounted.current) setSaving(false)
      }
    })()
    return flushPromise.current
  }

  useEffect(() => {
    if (!writable || !durableReady || error || acting || !dirtyCount || conflicts.length || suspendPending || !assistantQuestionEditable(input)) return
    const timer = setTimeout(() => { if (navigator.onLine) void flush() }, 700)
    return () => clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [answers, dirtyCount, conflicts.length, suspendPending, input.status, error, acting, writable, durableReady])

  const edit = (answer: AssistantAnswerDraft) => {
    if (!canWrite() || contextStale.current || !assistantQuestionEditable(input) || suspendCommand.current) return
    setError(null)
    const current = pending.current[answer.questionId]
    pending.current[answer.questionId] = { answer: { ...answer, revision: current?.answer.revision ?? server.current.get(answer.questionId)?.revision ?? 0 }, operationId: assistantCommandId(), conflict: current?.conflict, sent: current?.sent }
    persist(); publish()
  }

  const move = async (questionId: string) => {
    cursorRef.current = questionId; setCursor(questionId)
    if (!canWrite() || !persist() || contextStale.current) return
    try {
      if (await viewWriter.current!.save(questionId) && mounted.current) {
        setFailure((current) => current?.source === 'VIEW' ? null : current)
      }
    }
    catch (cause) {
      if (mounted.current && assistantErrorCode(cause) === 'ASSISTANT_CONTEXT_STALE' && assistantCommandWasRejected(cause)) { contextStale.current = true; setStale(true); store.reportContextConflict(cause, input); persist() }
      if (mounted.current) {
        if (contextStale.current) setError('引用内容已更新，请更新引用后继续。你的回答已保留。')
        else setFailure({ source: 'VIEW', message: assistantErrorMessage(cause) })
      }
    }
  }

  const resolveConflict = (questionId: string, keepLocal: boolean) => {
    if (!canWrite() || pending.current[questionId]?.sent) return
    setError(null)
    const entry = pending.current[questionId]
    if (entry && keepLocal) pending.current[questionId] = { answer: { ...entry.answer, revision: server.current.get(questionId)?.revision ?? 0 }, operationId: assistantCommandId() }
    else delete pending.current[questionId]
    persist(); publish()
  }

  const submit = async () => {
    if (!canWrite() || store.getSnapshot().busy || action.current) return
    action.current = true; setActing(true)
    try {
    if (flushPromise.current && !await flushPromise.current) return
    for (const question of input.questions) {
      const answer = pending.current[question.id]?.answer || server.current.get(question.id) || emptyAssistantAnswer(question.id)
      const problem = assistantAnswerError(question, answer)
      if (problem) { setError(problem); await move(question.id); return }
    }
    // 完整答案集必须绑定真实持久版本；跳过可选题也先保存空草稿。
    for (const question of input.questions) {
      if (!question.required && !pending.current[question.id] && !(server.current.get(question.id)?.revision)) {
        pending.current[question.id] = { answer: server.current.get(question.id) || emptyAssistantAnswer(question.id), operationId: assistantCommandId() }
      }
    }
    persist(); publish()
    if (!await flush()) return
    const run = store.getSnapshot().snapshot?.activeRun
    if (!mounted.current || !run || run.runId !== input.runId) return
    await store.execute('submitInput', { requestId: input.requestId, responseKey: assistantCommandId(), expectedRunRevision: run.stateRevision,
      schemaDigest: input.schemaDigest, contextDigest: input.contextDigest,
      answers: input.questions.map((question) => ({ questionId: question.id, draftRevision: server.current.get(question.id)?.revision ?? 0 })) })
    } finally { action.current = false; if (mounted.current) setActing(false) }
  }

  const suspend = async () => {
    if (!canWrite() || store.getSnapshot().busy || action.current) return
    action.current = true; setActing(true)
    try {
    if (flushPromise.current) await flushPromise.current
    if (Object.values(pending.current).some((entry) => entry.sent) && !await flush()) return
    const run = store.getSnapshot().snapshot?.activeRun
    if (!run) return
    suspendCommand.current ??= { requestId: input.requestId, clientRequestId: assistantCommandId(), expectedRunRevision: run.stateRevision,
      dirtyDrafts: Object.values(pending.current).map((entry) => ({ questionId: entry.answer.questionId, expectedDraftRevision: entry.answer.revision,
        selectedOptionIds: entry.answer.selectedOptionIds, freeText: entry.answer.freeText, clientOperationId: entry.operationId })) }
    if (!persist()) return
    publish()
    const accepted = await store.execute('suspendInput', suspendCommand.current)
    if (accepted) { pending.current = Object.create(null); suspendCommand.current = undefined; persist(); publish() }
    else if (!store.getSnapshot().pendingCommand) { suspendCommand.current = undefined; persist(); publish() }
    } finally { action.current = false; if (mounted.current) setActing(false) }
  }

  const retrySuspend = async () => {
    const command = suspendCommand.current
    if (!command) { await suspend(); return }
    if (!canWrite() || store.getSnapshot().busy || action.current) return
    const pendingCommand = store.getSnapshot().pendingCommand
    if (pendingCommand && (pendingCommand.action !== 'suspendInput' || assistantDigest(pendingCommand.body) !== assistantDigest(command))) {
      setError('另一个操作尚待核实，请先处理原操作')
      return
    }
    if (!pendingCommand) { await suspend(); return }
    action.current = true; setActing(true)
    try {
      await store.retryPending()
      reconcileSuspendCommand(currentRequest.current.status === 'SUSPENDED')
    } finally { action.current = false; if (mounted.current) setActing(false) }
  }

  useEffect(() => store.registerQuestionDraftGuard(async (mode) => {
    if (action.current || suspendCommand.current) return false
    if (flushPromise.current) await flushPromise.current
    if (action.current || suspendCommand.current || !mounted.current || !backupReady.current || Object.values(pending.current).some((entry) => entry.conflict)) return false
    // The old request cannot save against changed content. Explicit replacement preserves
    // its draft instead; unknown writes still have to be reconciled with their original keys.
    if (mode === 'PRESERVE_STALE' && contextStale.current && store.getSnapshot().contextConflict?.runId === input.runId && store.getSnapshot().contextConflict?.requestId === input.requestId) {
      return !Object.values(pending.current).some(entry => entry.sent) && persist()
    }
    // Opening/history/role switches do not change an untouched answer set. Cursor persistence is
    // best-effort view state and must not force an expired historical context through saveView.
    // Unknown draft writes, local edits and conflicts remain in pending and retain their CAS guard.
    if (Object.keys(pending.current).length === 0) return true
    if (assistantQuestionEditable(currentRequest.current) && await flush()) {
      // A submit/suspend or another edit may start while the write is in flight.
      return mounted.current && backupReady.current && !action.current && !suspendCommand.current && Object.keys(pending.current).length === 0
    }
    return false
  }))

  return { answers, serverAnswers: Object.fromEntries(server.current), cursor, error, saving, acting, conflicts, dirtyCount, suspendPending, suspendRetryReady,
    writable: writable && durableReady && !stale, edit, move, resolveConflict, flush, submit, suspend, retrySuspend }
}
