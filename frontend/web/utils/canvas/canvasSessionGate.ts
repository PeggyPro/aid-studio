/** A canvas write needs a live WebSocket session, even when the owner works alone. */
export function createCanvasSessionGate(timeoutMs = 12_000) {
  let sessionId = ''
  let terminalError: Error | null = null
  const pending = new Set<{
    resolve: (sessionId: string) => void
    reject: (error: Error) => void
    timer: ReturnType<typeof setTimeout>
  }>()

  return {
    wait(requestConnection: () => void): Promise<string> {
      if (terminalError) return Promise.reject(terminalError)
      if (sessionId) return Promise.resolve(sessionId)
      return new Promise((resolve, reject) => {
        const startConnection = pending.size === 0
        const waiter = {
          resolve,
          reject,
          timer: setTimeout(() => {
            pending.delete(waiter)
            reject(new Error('实时协作连接未建立，请检查网络后重试'))
          }, timeoutMs)
        }
        pending.add(waiter)
        if (startConnection) {
          try {
            requestConnection()
          } catch (error) {
            this.fail(error instanceof Error ? error : new Error('画布协作连接失败'))
          }
        }
      })
    },
    ready(value: string) {
      sessionId = value
      terminalError = null
      for (const waiter of pending) {
        clearTimeout(waiter.timer)
        waiter.resolve(value)
      }
      pending.clear()
    },
    disconnect() {
      sessionId = ''
    },
    fail(error: Error) {
      sessionId = ''
      terminalError = error
      for (const waiter of pending) {
        clearTimeout(waiter.timer)
        waiter.reject(error)
      }
      pending.clear()
    }
  }
}
