import type { AssistantTransport } from '~/utils/business/assistant'
import type { SeriesCapabilityRequest, SeriesCommit } from '../../types/assistant-series'
import { assistantDigest, coordinateAssistantRequest } from '~/utils/assistant/assistantRequestCoordinator'
import { validateSeriesRequest } from './assistantSeriesAdoption'

/** Contract-bound. Uses the supplied existing encrypted transport; contains no auth/token storage. */
export function createAssistantSeriesApi(transport: AssistantTransport) {
  const identity = transport.identityKey(), root = '/api/user/assistant/artifact/adoption/series'
  const post = async (path: string, body: unknown): Promise<unknown> => {
    if (identity !== transport.identityKey()) throw new Error('登录身份已变化')
    const result = await transport.post<unknown>(path, body)
    if (identity !== transport.identityKey()) throw new Error('登录身份已变化')
    if ((result.code !== 200 && result.code !== 0) || result.data == null) throw result
    return result.data
  }
  return {
    capabilities: (request: SeriesCapabilityRequest): Promise<unknown> => {
      validateSeriesRequest(request)
      const body = structuredClone(request), path = root + '/capabilities'
      return coordinateAssistantRequest({ key: transport.kind + ':' + identity + ':' + path + ':' + assistantDigest(body), body, cacheMs: 0, execute: () => post(path, body) })
    },
    commit: (request: SeriesCommit): Promise<unknown> => {
      if (typeof request.clientRequestId !== 'string' || !request.clientRequestId.trim() || request.clientRequestId.length > 64) return Promise.reject(new Error('缺少原整批采用操作标识'))
      const body = structuredClone(request)
      return coordinateAssistantRequest({ key: transport.kind + ':' + identity + ':' + root + ':' + request.clientRequestId, body, cacheMs: 0, execute: () => post(root, body) })
    }
  }
}
