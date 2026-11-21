/** A stop/pause intent never replaces an unresolved command or grants permission to execute. */
export type AssistantQueuedControl = {
  action: 'pause' | 'stop'; sessionId: string; runId: string; clientRequestId: string; dispatched: boolean
}
export type AssistantControlQueueContext = {
  sessionId: string | null; runId: string | null; revision: number; writable: boolean
  busy: boolean; pendingCommand: boolean; unavailable: boolean; allowedActions: readonly string[]
}
type Pending = AssistantQueuedControl & { promise: Promise<boolean>; resolve: (accepted: boolean) => void }

export class AssistantControlQueue {
  private pending: Pending | null = null
  private fences = new Set<string>()
  constructor(private readonly changed: (value: AssistantQueuedControl | null) => void) {}
  private key(sessionId: string, runId: string) { return `${sessionId}:${runId}` }
  fenced(sessionId: string, runId: string): boolean { return this.fences.has(this.key(sessionId, runId)) }
  release(sessionId: string, runId: string) {
    if (!this.pending) this.fences.delete(this.key(sessionId, runId))
  }
  current(): AssistantQueuedControl | null {
    if (!this.pending) return null
    const { action, sessionId, runId, clientRequestId, dispatched } = this.pending
    return { action, sessionId, runId, clientRequestId, dispatched }
  }
  request(action: 'pause' | 'stop', sessionId: string, runId: string, clientRequestId: string): Promise<boolean> {
    if (this.pending) return this.pending.action === action && this.pending.sessionId === sessionId && this.pending.runId === runId
      ? this.pending.promise : Promise.resolve(false)
    let resolve!: Pending['resolve']
    const promise = new Promise<boolean>(accepted => { resolve = accepted })
    this.fences.add(this.key(sessionId, runId))
    if (this.fences.size > 64) this.fences.delete(this.fences.values().next().value!)
    this.pending = { action, sessionId, runId, clientRequestId, promise, resolve, dispatched: false }
    this.changed(this.current())
    return promise
  }
  pump(context: AssistantControlQueueContext,
       dispatch: (control: AssistantQueuedControl, revision: number) => Promise<boolean>) {
    const pending = this.pending
    if (!pending || pending.dispatched) return
    if (context.unavailable || context.sessionId !== pending.sessionId || context.runId !== pending.runId) {
      this.finish(pending, false); return
    }
    if (!context.writable || context.busy || context.pendingCommand || !context.allowedActions.includes(pending.action.toUpperCase())) return
    if (!Number.isSafeInteger(context.revision) || context.revision < 0) { this.finish(pending, false); return }
    pending.dispatched = true
    const control = this.current()!
    this.changed(this.current())
    // Freeze the latest observed revision once. Errors never create a new key or rebase the request.
    void Promise.resolve().then(() => dispatch(control, context.revision))
      .then(accepted => this.finish(pending, accepted), () => this.finish(pending, false))
  }
  cancel() { if (this.pending && !this.pending.dispatched) this.finish(this.pending, false) }
  private finish(pending: Pending, accepted: boolean) {
    if (this.pending !== pending) return
    this.pending = null
    pending.resolve(accepted)
    this.changed(null)
  }
}
