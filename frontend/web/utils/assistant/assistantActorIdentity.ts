import type { AssistantTransport } from '~/utils/business/assistant'
import { coordinateAssistantRequest } from './assistantRequestCoordinator'

const actorIdentityCacheMillis = 30_000

/** Share only the verified actor ID; balances, permissions and approvals are never cached here. */
export async function readAssistantActorIdentity(transport: AssistantTransport, identity = transport.identityKey(), force = false): Promise<string> {
  const kind = transport.kind
  const namespace = transport.storageNamespace?.() || kind
  const requireCurrentIdentity = () => {
    if (identity !== transport.identityKey() || kind !== transport.kind || namespace !== (transport.storageNamespace?.() || transport.kind)) throw new Error('登录状态已变化，请重新打开助手')
  }
  requireCurrentIdentity()
  const actorId = await coordinateAssistantRequest<string>({
    key: JSON.stringify([kind, namespace, identity, 'actor-identity']), body: {}, cacheMs: actorIdentityCacheMillis, force,
    execute: async () => {
      requireCurrentIdentity()
      const result = await transport.post<{ userId: unknown }>('/api/user/profile', {})
      requireCurrentIdentity()
      if ((result.code !== 0 && result.code !== 200) || result.data == null) throw result
      const id = result.data.userId
      if (typeof id !== 'string' && !(typeof id === 'number' && Number.isSafeInteger(id))) throw new Error('账号标识不可读取，请重新核对身份')
      if (!/^[1-9]\d*$/.test(String(id))) throw new Error('账号标识不可读取，请重新核对身份')
      return String(id)
    }
  })
  requireCurrentIdentity()
  return actorId
}
