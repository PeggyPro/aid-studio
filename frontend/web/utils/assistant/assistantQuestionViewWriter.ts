type View = { requestId: string; cursorQuestionId: string }

/** One ordered writer for navigation and answer autosave within a mounted question request. */
export class AssistantQuestionViewWriter {
  private tail: Promise<unknown> = Promise.resolve()
  private pending: { cursor: string; epoch: number; promise: Promise<boolean> } | null = null
  private confirmed: string | undefined
  private epoch = 0

  constructor(
    private readonly requestId: string,
    private readonly send: (view: View) => Promise<View>,
    private readonly canWrite: () => boolean,
    initialCursor?: string,
  ) {
    this.confirmed = initialCursor || undefined
  }

  /** Keep the in-flight ordering fence, but discard authority from the previous owner/mount. */
  invalidate() {
    this.epoch++
    this.confirmed = undefined
    this.pending = null
  }

  save(cursor: string): Promise<boolean> {
    if (!cursor || !this.canWrite()) return Promise.resolve(false)
    const epoch = this.epoch
    if (this.pending?.epoch === epoch && this.pending.cursor === cursor) return this.pending.promise
    const promise = this.tail.catch(() => {}).then(async () => {
      if (epoch !== this.epoch || !this.canWrite()) return false
      if (this.confirmed === cursor) return true
      // A failed request can still have reached the server. Do not reuse the old cursor ACK.
      this.confirmed = undefined
      const ack = await this.send({ requestId: this.requestId, cursorQuestionId: cursor })
      if (epoch !== this.epoch || !this.canWrite()) return false
      if (ack.requestId !== this.requestId || ack.cursorQuestionId !== cursor) {
        throw new Error('问题位置保存回执不匹配，请重试')
      }
      this.confirmed = cursor
      return true
    })
    this.tail = promise
    this.pending = { cursor, epoch, promise }
    const clear = () => { if (this.pending?.promise === promise) this.pending = null }
    void promise.then(clear, clear)
    return promise
  }
}
