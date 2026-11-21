/** Private recovery journal. Writes are serialized by assistantWriteLock. */
export const ASSISTANT_DURABLE_PREFIX = 'aid:assistant:durable:v2:'
const MAX_BYTES = 1_000_000

function rejectSecrets(value: unknown): void {
  if (!value || typeof value !== 'object') return
  for (const [key, item] of Object.entries(value)) {
    if (/^(authorization|password|accessToken|refreshToken|apiKey|aesKey|privateKey|canvasToken|mainToken|token)$/i.test(key)) {
      throw new Error('恢复记录包含不允许保存的鉴权字段')
    }
    rejectSecrets(item)
  }
}

export function readAssistantDurable<T>(key: string): T | null {
  const raw = localStorage.getItem(key)
  if (!raw) return null
  if (raw.length > MAX_BYTES) throw new Error('本地恢复记录过大，已保留原记录并停止发送')
  try {
    const envelope = JSON.parse(raw) as { version?: number; value?: T }
    if (envelope.version !== 2 || !envelope.value || typeof envelope.value !== 'object') throw new Error()
    rejectSecrets(envelope.value)
    return envelope.value
  } catch { throw new Error('本地恢复记录版本或内容异常，已保留原记录并停止发送') }
}

export function writeAssistantDurable(key: string, value: unknown): void {
  rejectSecrets(value)
  const raw = JSON.stringify({ version: 2, value })
  if (raw.length > MAX_BYTES) throw new Error('本地恢复记录过大，请先保存已有内容')
  try {
    localStorage.setItem(key, raw)
    if (localStorage.getItem(key) !== raw) throw new Error()
  } catch { throw new Error('本地恢复记录未能持久保存，未发送新的操作；请释放存储空间后重试') }
}

export function removeAssistantDurable(key: string): void {
  localStorage.removeItem(key)
}

export function clearAssistantActorRecovery(prefix: string): void {
  for (const key of Object.keys(localStorage)) if (key.startsWith(prefix)) localStorage.removeItem(key)
}
