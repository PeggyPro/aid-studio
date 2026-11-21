const studioRequestInflight = new Map<string, Promise<unknown>>()

function orderedReadParameters(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(orderedReadParameters)
  if (value && typeof value === 'object') return Object.fromEntries(
    Object.entries(value).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0)
      .map(([key, item]) => [key, orderedReadParameters(item)])
  )
  return value
}

/** Only for reads: share physical requests by current identity, operation and complete JSON parameters. */
export function runStudioReadSingleFlight<T>(
  getIdentity: () => string,
  operation: string,
  parameters: unknown,
  request: (snapshot: unknown) => Promise<T>
): Promise<T> {
  try {
    const identity = getIdentity()
    if (!identity.trim() || !operation.trim()) throw new Error('只读请求缺少身份或操作标识')
    // Keep the request body consistent with its key even if a caller later mutates its input.
    const serialized = JSON.stringify(parameters)
    const snapshot: unknown = serialized === undefined ? undefined : JSON.parse(serialized)
    const parameterKey = serialized === undefined ? 'undefined' : JSON.stringify(orderedReadParameters(snapshot))
    const key = JSON.stringify(['studio-read', identity, operation, parameterKey])
    const assertIdentity = () => {
      if (getIdentity() !== identity) throw Object.assign(new Error('请求身份已变化，请重新发起读取'), { code: 'STUDIO_READ_IDENTITY_CHANGED' })
    }
    assertIdentity()
    const existing = studioRequestInflight.get(key)
    if (existing) return existing as Promise<T>
    let resolve!: (value: T) => void
    let reject!: (error: unknown) => void
    const pending = new Promise<T>((yes, no) => { resolve = yes; reject = no })
    const promise = pending.finally(() => {
      if (studioRequestInflight.get(key) === promise) studioRequestInflight.delete(key)
    })
    // Register before dispatch, and hold the key until the physical request settles.
    studioRequestInflight.set(key, promise)
    try {
      Promise.resolve(request(snapshot)).then(value => { assertIdentity(); return value }).then(resolve, reject)
    } catch (error) { reject(error) }
    return promise
  } catch (error) { return Promise.reject(error) }
}

/**
 * 同一画布作用域、节点与动作在请求结束前只允许一个执行者。
 * 重复入口复用首个 Promise，避免按钮、快捷键或重复挂载再次提交真实任务。
 */
export function runStudioRequestSingleFlight<T>(
  key: string,
  request: () => Promise<T>
): Promise<T> {
  const normalizedKey = String(key || '').trim()
  if (!normalizedKey) return request()

  const existing = studioRequestInflight.get(normalizedKey)
  if (existing) return existing as Promise<T>

  const promise: Promise<T> = Promise.resolve()
    .then(request)
    .finally(() => {
      if (studioRequestInflight.get(normalizedKey) === promise) {
        studioRequestInflight.delete(normalizedKey)
      }
    })
  studioRequestInflight.set(normalizedKey, promise)
  return promise
}
