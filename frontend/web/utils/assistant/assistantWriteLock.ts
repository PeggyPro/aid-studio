type WriteGrant = { lost?: AbortSignal }
type WriteLockCallback<T> = (grant: WriteGrant | null) => T | Promise<T>

/** HTTP cannot expose Web Locks. A same-origin shared worker keeps the same exclusive queue. */
function requestWorkerLock<T>(name: string, options: LockOptions, callback: WriteLockCallback<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    const signal = options.signal
    if (signal?.aborted) { reject(signal.reason); return }
    const worker = new SharedWorker('/assistant-write-lock.js', { name: 'aid-assistant-write-lock' })
    const port = worker.port
    const ownership = new AbortController()
    let finished = false
    let granted = false
    const startup = window.setTimeout(() => fail(new Error('助手本地写入协调未启动，请刷新后重试')), 5000)

    function finish() {
      if (finished) return
      finished = true
      window.clearTimeout(startup)
      window.removeEventListener('pagehide', releaseOnExit)
      signal?.removeEventListener('abort', abort)
      try { port.postMessage('release') } finally { port.close() }
    }

    function fail(error: unknown) {
      if (finished) return
      if (granted) ownership.abort(error)
      finish()
      reject(error)
    }

    function abort() { fail(signal?.reason) }
    function releaseOnExit() { fail(new DOMException('页面会话已退出', 'AbortError')) }

    window.addEventListener('pagehide', releaseOnExit, { once: true })
    signal?.addEventListener('abort', abort, { once: true })
    const unavailable = () => fail(new Error('无法启动助手本地写入协调，请刷新后重试'))
    worker.onerror = unavailable
    port.onmessageerror = unavailable
    port.addEventListener('close', () => fail(new Error('助手本地写入协调已断开，请重新打开会话')), { once: true })
    port.onmessage = ({ data }) => {
      window.clearTimeout(startup)
      if (data === 'waiting' || finished) return
      signal?.removeEventListener('abort', abort)
      granted = data === 'granted'
      Promise.resolve().then(() => {
        if (finished) return
        return callback(granted ? { lost: ownership.signal } : null)
      }).then((result) => {
        if (finished) return
        finish()
        resolve(result as T)
      }, fail)
    }
    port.postMessage({ name, ifAvailable: options.ifAvailable === true })
  })
}

export function assistantLockManager() {
  if (typeof navigator !== 'undefined' && navigator.locks) {
    return {
      request<T>(name: string, options: LockOptions, callback: WriteLockCallback<T>) {
        return navigator.locks.request(name, options, (lock) => callback(lock ? {} : null))
      }
    }
  }
  if (typeof SharedWorker !== 'undefined') return { request: requestWorkerLock }
  throw new Error('当前浏览器无法协调跨标签页写入，请使用支持共享工作线程的浏览器或通过 HTTPS 打开')
}
