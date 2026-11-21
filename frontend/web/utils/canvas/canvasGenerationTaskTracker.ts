import type { CanvasActiveTasks, CanvasTaskProjection } from '@/types/canvas-api'
import { apiErrorMessage } from '@/utils/apiError'
import { isCanvasTaskTerminal } from './canvasTaskState'

/** A revoked session or an invalid task identity cannot recover by polling. */
export function isUnrecoverableCanvasTaskQueryError(error: unknown): boolean {
  const detail = error && typeof error === 'object' ? error as {
    code?: unknown; status?: unknown; response?: { status?: unknown }
  } : null
  const code = Number(detail?.response?.status ?? detail?.status ?? detail?.code)
  if ([401, 403, 404].includes(code)) return true
  return /^(画布服务登录已失效|任务不属于当前工作区|媒体任务不存在|项目不存在或无权访问|工作区不存在|当前项目角色无权执行该操作)/.test(apiErrorMessage(error))
}

type TaskEntry = {
  workspaceId: string
  nodeId: string
  inputVersion?: number
  terminalSignalled: boolean
  timer: ReturnType<typeof setTimeout> | null
  checking: boolean
  wakePending: boolean
  unavailable: boolean
  lastState: string
  terminalTask: CanvasTaskProjection | null
  terminalAttempts: number
  resolve: (task: CanvasTaskProjection | null) => void
  promise: Promise<CanvasTaskProjection | null>
}

type RunObserver = {
  workspaceId: string
  nodeId: string
  inputVersion?: number
  onHint: () => void
}

class TaskIdentityMismatchError extends Error { }

type TrackerOptions = {
  inputVersion?: (nodeId: string) => number | undefined
  query: (workspaceId: string, taskId: string) => Promise<CanvasTaskProjection>
  onUpdate: (taskId: string, nodeId: string, task: CanvasTaskProjection) => void
  onTerminal: (taskId: string, nodeId: string, task: CanvasTaskProjection) => Promise<void> | void
  onTerminalStalled?: (taskId: string, nodeId: string, task: CanvasTaskProjection) => void
  onUnavailable?: (taskId: string, nodeId: string) => void
  onQueryStopped?: (taskId: string, nodeId: string, error: unknown) => void
} & (
  { workspaceId: () => string; queryActive: (workspaceId: string) => Promise<CanvasActiveTasks> }
  | { workspaceId?: never; queryActive?: never }
)

/** One mounted canvas coordinates media tasks and Runtime Runs over its collaboration socket. */
export function createCanvasGenerationTaskTracker(options: TrackerOptions) {
  const entries = new Map<string, TaskEntry>()
  const runObservers = new Map<string, Set<RunObserver>>()
  let closed = false
  let realtimeConnected = false
  let sweepTimer: ReturnType<typeof setTimeout> | null = null
  let activeRequest: Promise<void> | null = null
  let refreshAfterActiveRequest = false
  const currentWorkspaceId = () => options.workspaceId?.() ?? '__legacy__'

  const valid = (taskId: string, entry: TaskEntry) =>
    !closed && entries.get(taskId) === entry && currentWorkspaceId() === entry.workspaceId

  const matches = (taskId: string, entry: TaskEntry, task: CanvasTaskProjection) =>
    task.taskId != null && String(task.taskId) === taskId
    && (options.queryActive
      ? task.objectId != null && String(task.objectId) === entry.nodeId
        && task.inputObjectVersion != null
        && (entry.inputVersion == null || Number(task.inputObjectVersion) === entry.inputVersion)
      : (task.objectId == null || String(task.objectId) === entry.nodeId)
        && (task.inputObjectVersion == null || entry.inputVersion == null
          || Number(task.inputObjectVersion) === entry.inputVersion))
    && (task.workspaceId == null || String(task.workspaceId) === entry.workspaceId)

  const schedule = (taskId: string, entry: TaskEntry, delay: number) => {
    if (!valid(taskId, entry)) return
    if (entry.timer) clearTimeout(entry.timer)
    entry.timer = setTimeout(() => void check(taskId, entry), delay)
  }

  const check = async (taskId: string, entry: TaskEntry) => {
    if (!valid(taskId, entry)) return
    if (entry.checking) { entry.wakePending = true; return }
    entry.timer = null
    entry.checking = true
    try {
      const scopedProjection = entry.terminalTask != null
      const task = entry.terminalTask ?? await options.query(entry.workspaceId, taskId)
      if (!valid(taskId, entry)) return
      if ((options.queryActive && !scopedProjection && task.workspaceId == null)
        || !matches(taskId, entry, task)) {
        throw new TaskIdentityMismatchError('任务与工作区、节点或输入版本不匹配')
      }
      if (entry.inputVersion == null && task.inputObjectVersion != null) {
        entry.inputVersion = Number(task.inputObjectVersion)
      }
      entry.unavailable = false
      const status = String(task.status || '').toUpperCase()
      const state = JSON.stringify([status, task.progress, task.eta?.displayProgress, task.eta?.waitReasonCode, task.eta, task.executionPhase, task.queueWaitReason, task.queuePosition, task.errorMessage])
      if (isCanvasTaskTerminal(status)) {
        if (!entry.terminalTask) entry.terminalAttempts = 0
        entry.terminalTask = task
        // Keep the terminal response while workspace results catch up. A result
        // read must never turn into another request for this completed task.
        await options.onTerminal(taskId, entry.nodeId, task)
        if (!valid(taskId, entry)) return
        options.onUpdate(taskId, entry.nodeId, task)
        entries.delete(taskId)
        entry.resolve(task)
        return
      }
      if (entry.terminalSignalled) {
        entry.terminalAttempts += 1
        if (entry.terminalAttempts < 6) schedule(taskId, entry, Math.min(15000, 1000 * 2 ** entry.terminalAttempts))
        else entry.terminalSignalled = false
        return
      }
      if (entry.lastState !== state) {
        entry.lastState = state
        options.onUpdate(taskId, entry.nodeId, task)
      }
      // Nonterminal tasks are calibrated together by one workspace read.
    } catch (error) {
      if (valid(taskId, entry)) {
        if (!entry.terminalTask && (error instanceof TaskIdentityMismatchError
          || isUnrecoverableCanvasTaskQueryError(error))) {
          entries.delete(taskId)
          entry.resolve(null)
          options.onQueryStopped?.(taskId, entry.nodeId, error)
        } else if (entry.terminalTask) {
          entry.terminalAttempts += 1
          if (entry.terminalAttempts >= 6) {
            entries.delete(taskId)
            options.onTerminalStalled?.(taskId, entry.nodeId, entry.terminalTask)
            entry.resolve(entry.terminalTask)
          } else {
            schedule(taskId, entry, Math.min(15000, 2000 * 2 ** (entry.terminalAttempts - 1)))
          }
        } else {
          if (!entry.unavailable) options.onUnavailable?.(taskId, entry.nodeId)
          entry.unavailable = true
          if (entry.terminalSignalled) schedule(taskId, entry, 5000)
        }
      }
    } finally {
      entry.checking = false
      if (entry.wakePending && valid(taskId, entry)) {
        entry.wakePending = false
        schedule(taskId, entry, 0)
      }
      scheduleSweep()
    }
  }

  const scheduleSweep = (delay?: number) => {
    if (sweepTimer && delay == null && !closed && (entries.size > 0 || runObservers.size > 0)) return
    if (sweepTimer) clearTimeout(sweepTimer)
    sweepTimer = null
    if (closed || (entries.size === 0 && runObservers.size === 0)) return
    if (!options.queryActive) {
      for (const [taskId, entry] of entries) {
        if (!entry.terminalTask && !entry.timer) schedule(taskId, entry, delay ?? 2000)
      }
      return
    }
    sweepTimer = setTimeout(() => {
      // Release the fired timer before refresh schedules the next audit.
      sweepTimer = null
      void refresh()
    }, delay ?? (realtimeConnected ? 45000 : 10000))
  }

  const refresh = (): Promise<void> => {
    if (closed || (entries.size === 0 && runObservers.size === 0)) return Promise.resolve()
    const queryActive = options.queryActive
    if (!queryActive) return Promise.resolve()
    if (activeRequest) {
      refreshAfterActiveRequest = true
      return activeRequest
    }
    const workspaceId = currentWorkspaceId()
    if (!workspaceId) return Promise.resolve()
    activeRequest = (async () => {
      try {
        const active = await queryActive(workspaceId)
        if (closed || currentWorkspaceId() !== workspaceId
          || String(active.workspaceId) !== workspaceId) return
        const byTask = new Map(active.items.filter((task) => task.taskId != null)
          .map((task) => [String(task.taskId), task]))
        for (const [taskId, entry] of entries) {
          if (!valid(taskId, entry) || entry.terminalTask || entry.terminalSignalled) continue
          const task = byTask.get(taskId)
          if (task && entry.inputVersion == null && task.inputObjectVersion != null) {
            entry.inputVersion = Number(task.inputObjectVersion)
          }
          if (task && !matches(taskId, entry, task)) {
            entries.delete(taskId)
            entry.resolve(null)
            options.onQueryStopped?.(taskId, entry.nodeId,
              new TaskIdentityMismatchError('任务与工作区、节点或输入版本不匹配'))
          } else if (task) {
            const state = JSON.stringify([task.status, task.progress, task.eta?.displayProgress, task.eta?.waitReasonCode,
              task.queuePosition, task.errorMessage])
            if (state !== entry.lastState) {
              entry.lastState = state
              entry.unavailable = false
              try { options.onUpdate(taskId, entry.nodeId, task) }
              catch { options.onUnavailable?.(taskId, entry.nodeId) }
            }
          } else schedule(taskId, entry, 0)
        }
        const activeRuns = new Map(active.items.filter((item) => item.sourceType === 'RUNTIME_RUN' && item.runId != null)
          .map((item) => [String(item.runId), item]))
        for (const [runId, observers] of runObservers) {
          const run = activeRuns.get(runId)
          for (const observer of observers) {
            if (observer.workspaceId !== workspaceId) continue
            if (run && String(run.objectId) !== observer.nodeId) continue
            if (run && observer.inputVersion == null && run.inputObjectVersion != null) {
              observer.inputVersion = Number(run.inputObjectVersion)
            }
            if (run && observer.inputVersion != null && run.inputObjectVersion != null
              && Number(run.inputObjectVersion) !== observer.inputVersion) continue
            // The shared audit also repairs missed progress events whose Run
            // status stays RUNNING between two reads.
            try { observer.onHint() } catch { /* One subscriber cannot block other tasks. */ }
          }
        }
      } catch (error) {
        if (closed || currentWorkspaceId() !== workspaceId) return
        for (const [taskId, entry] of entries) {
          if (!valid(taskId, entry) || entry.terminalTask) continue
          if (isUnrecoverableCanvasTaskQueryError(error)) {
            entries.delete(taskId)
            entry.resolve(null)
            options.onQueryStopped?.(taskId, entry.nodeId, error)
          } else if (!entry.unavailable) {
            entry.unavailable = true
            options.onUnavailable?.(taskId, entry.nodeId)
          }
        }
      } finally {
        activeRequest = null
        if (refreshAfterActiveRequest) {
          refreshAfterActiveRequest = false
          void refresh()
        } else scheduleSweep()
      }
    })()
    return activeRequest
  }

  const wake = (taskId?: string) => {
    for (const [id, entry] of entries) {
      if (!taskId || id === taskId) {
        if (entry.checking) entry.wakePending = true
        else schedule(id, entry, 0)
      }
    }
  }
  const onOnline = () => { scheduleSweep(0) }
  const onVisible = () => { if (document.visibilityState === 'visible') scheduleSweep(0) }
  if (typeof window !== 'undefined') {
    window.addEventListener('online', onOnline)
    document.addEventListener('visibilitychange', onVisible)
  }

  return {
    follow(taskId: string, nodeId: string, initial?: CanvasTaskProjection, streamActive = false): Promise<CanvasTaskProjection | null> {
      const existing = entries.get(taskId)
      if (existing) return existing.nodeId === nodeId ? existing.promise : Promise.resolve(null)
      const workspaceId = currentWorkspaceId()
      if (closed || !workspaceId) return Promise.resolve(null)
      let resolve!: TaskEntry['resolve']
      const promise = new Promise<CanvasTaskProjection | null>((done) => { resolve = done })
      const entry: TaskEntry = {
        workspaceId, nodeId, inputVersion: initial?.inputObjectVersion ?? options.inputVersion?.(nodeId),
        terminalSignalled: false, timer: null, checking: false, wakePending: false,
        unavailable: false, lastState: '', terminalTask: null, terminalAttempts: 0, resolve, promise
      }
      entries.set(taskId, entry)
      if (initial) {
        if (!matches(taskId, entry, initial)) {
          entries.delete(taskId)
          resolve(null)
          return promise
        }
        const status = String(initial.status || '').toUpperCase()
        if (isCanvasTaskTerminal(status)) {
          entry.terminalTask = initial
          schedule(taskId, entry, 0)
        } else {
          options.onUpdate(taskId, nodeId, initial)
          // The supplied projection is already authoritative; audit it at the regular interval.
          if (!streamActive) scheduleSweep(options.queryActive ? undefined : 0)
        }
      } else scheduleSweep(0)
      return promise
    },
    has(taskId: string) { return entries.has(taskId) },
    setRealtimeConnected(connected: boolean) {
      realtimeConnected = connected
      scheduleSweep(0)
    },
    acceptEvent(event: Record<string, unknown>) {
      const taskId = String(event.taskId || '')
      const entry = entries.get(taskId)
      if (!entry || !valid(taskId, entry)
        || String(event.workspaceId || '') !== entry.workspaceId
        || String(event.objectId || '') !== entry.nodeId
        || entry.inputVersion == null
        || Number(event.inputObjectVersion) !== entry.inputVersion) return false
      if (String(event.type) === 'TASK_TERMINAL') {
        entry.terminalSignalled = true
        wake(taskId)
      }
      else scheduleSweep(0)
      return true
    },
    observeRun(runId: string, nodeId: string, onHint: () => void) {
      const workspaceId = currentWorkspaceId()
      if (closed || !workspaceId) return () => undefined
      const observer: RunObserver = {
        workspaceId, nodeId, inputVersion: options.inputVersion?.(nodeId), onHint
      }
      const observers = runObservers.get(runId) ?? new Set<RunObserver>()
      observers.add(observer)
      runObservers.set(runId, observers)
      scheduleSweep(0)
      return () => {
        observers.delete(observer)
        if (observers.size === 0) runObservers.delete(runId)
        scheduleSweep()
      }
    },
    acceptRunEvent(event: Record<string, unknown>) {
      const runId = String(event.runId || '')
      const observers = runObservers.get(runId)
      if (!observers) return false
      let accepted = false
      for (const observer of observers) {
        if (observer.workspaceId !== currentWorkspaceId()
          || String(event.workspaceId || '') !== observer.workspaceId
          || String(event.objectId || '') !== observer.nodeId
          || observer.inputVersion == null
          || Number(event.inputObjectVersion) !== observer.inputVersion) continue
        try { observer.onHint() } catch { /* A subscriber cannot break the socket dispatcher. */ }
        accepted = true
      }
      return accepted
    },
    refresh,
    wake,
    dispose() {
      if (closed) return
      closed = true
      if (sweepTimer) clearTimeout(sweepTimer)
      if (typeof window !== 'undefined') {
        window.removeEventListener('online', onOnline)
        document.removeEventListener('visibilitychange', onVisible)
      }
      for (const entry of entries.values()) {
        if (entry.timer) clearTimeout(entry.timer)
        entry.resolve(null)
      }
      entries.clear()
      runObservers.clear()
    }
  }
}
