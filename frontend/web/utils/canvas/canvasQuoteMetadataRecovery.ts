import type { BillingQuoteWire } from '@/types/business-api'
import type { CanvasResource } from '@/types/canvas-api'
import { canvasApiPost } from './canvasApiClient'
import { CANVAS_QUOTE_TIMEOUT_MS, canvasQuoteConnected, canvasQuoteIdentity } from './canvasQuote'

type RecordValue = Record<string, unknown>
const record = (value: unknown): RecordValue | null => value && typeof value === 'object' && !Array.isArray(value) ? value as RecordValue : null

export function canvasQuoteErrorData(reason: unknown): RecordValue | null {
  const error = record(reason), payload = record(error?.payload) ?? error
  return record(payload?.data) ?? payload
}

function current(identity: string, signal: AbortSignal) {
  signal.throwIfAborted()
  if (!canvasQuoteConnected() || canvasQuoteIdentity() !== identity) throw new Error('画布账号或连接已变化，请重新报价')
}

type PreparedMediaType = 'IMAGE' | 'VIDEO' | 'AUDIO'
type MissingMetadata = { workspaceId: string; resourceIds: string[]; expectedMediaType: PreparedMediaType }

/** 只接受服务端指出、且仍在本次正式报价输入中的媒体引用。 */
function missingMetadataReferences(body: unknown, reason: unknown): MissingMetadata | null {
  const error = canvasQuoteErrorData(reason)
  const input = record(body)
  if (typeof input?.workspaceId !== 'string' || !input.workspaceId.trim()) return null

  const missingAudio = error?.errorCode === 'CANVAS_QUOTE_REFERENCE_AUDIO_METADATA_UNAVAILABLE'
  if (missingAudio || error?.errorCode === 'CANVAS_QUOTE_REFERENCE_METADATA_UNAVAILABLE') {
    if (error.field !== 'references' || error.retryable !== true) return null
    const allowed = new Set<string>()
    for (const reference of Array.isArray(input.references) ? input.references : []) {
      const id = record(reference)?.resourceId
      if (typeof id === 'string' && id.trim()) allowed.add(id)
    }
    for (const id of Array.isArray(input.resourceIds) ? input.resourceIds : []) {
      if (typeof id === 'string' && id.trim()) allowed.add(id)
    }
    const ids = error.resourceIds
    if (!Array.isArray(ids) || ids.length === 0) return null
    if (ids.some(id => typeof id !== 'string' || !id.trim() || !allowed.has(id))) {
      throw new Error('服务端素材准备响应与当前引用不一致，请重新报价')
    }
    return { workspaceId: input.workspaceId, resourceIds: [...new Set(ids as string[])], expectedMediaType: missingAudio ? 'AUDIO' : 'VIDEO' }
  }

  if (error?.errorCode === 'MEDIA_INPUT_METADATA_REQUIRED' && error.field === 'references' && error.retryable === true) {
    const allowed = new Set<string>()
    for (const reference of Array.isArray(input.references) ? input.references : []) {
      const id = record(reference)?.resourceId
      if (typeof id === 'string' && id.trim()) allowed.add(id)
    }
    const ids = error.resourceIds
    if (!Array.isArray(ids) || ids.length === 0) return null
    if (ids.some(id => typeof id !== 'string' || !id.trim() || !allowed.has(id))) {
      throw new Error('服务端素材准备响应与当前图片输入不一致，请重新报价')
    }
    return { workspaceId: input.workspaceId, resourceIds: [...new Set(ids as string[])], expectedMediaType: 'IMAGE' }
  }

  if (error?.errorCode !== 'MEDIA_INPUT_METADATA_REQUIRED' || error.retryable !== true
    || typeof error.field !== 'string'
    || !['imageEdit.sourceResourceId', 'imageEdit.maskResourceId'].includes(error.field)) return null
  const imageEdit = record(input.imageEdit)
  const expectedId = error.field === 'imageEdit.sourceResourceId' ? imageEdit?.sourceResourceId : imageEdit?.maskResourceId
  const ids = error.resourceIds
  if (typeof expectedId !== 'string' || !expectedId.trim() || !Array.isArray(ids) || ids.length !== 1
    || typeof ids[0] !== 'string' || ids[0] !== expectedId) {
    throw new Error('服务端素材准备响应与当前图片输入不一致，请重新报价')
  }
  return { workspaceId: input.workspaceId, resourceIds: [expectedId], expectedMediaType: 'IMAGE' }
}

type Preparation = {
  controller: AbortController
  owners: Set<symbol>
  settled: boolean
  promise: Promise<void>
}
const preparations = new Map<string, Preparation>()

/** 同身份同资源单飞；一个消费者离开不取消其他仍在等待的报价。 */
function prepareMedia(workspaceId: string, resourceId: string, expectedMediaType: PreparedMediaType, identity: string, ownerSignal: AbortSignal): Promise<void> {
  current(identity, ownerSignal)
  const key = JSON.stringify([identity, workspaceId, resourceId, expectedMediaType])
  let preparation = preparations.get(key)
  if (!preparation) {
    const controller = new AbortController(), owners = new Set<symbol>()
    const entry: Preparation = { controller, owners, settled: false, promise: Promise.resolve() }
    let timer: ReturnType<typeof setTimeout>
    const interrupted = new Promise<never>((_, reject) => {
      controller.signal.addEventListener('abort', () => reject(controller.signal.reason), { once: true })
      timer = setTimeout(() => controller.abort(new Error('准备素材媒体信息超时，请重新报价')), CANVAS_QUOTE_TIMEOUT_MS)
    })
    const work = Promise.resolve().then(async () => {
      current(identity, controller.signal)
      const response = await canvasApiPost<CanvasResource & { status: string }>(
        '/api/user/canvas/resource/metadata/prepare', { workspaceId, resourceId },
        { signal: controller.signal, requestTimeoutMs: CANVAS_QUOTE_TIMEOUT_MS }
      )
      current(identity, controller.signal)
      const resource = response.data
      if (!resource || resource.resourceId !== resourceId || resource.workspaceId !== workspaceId
        || resource.mediaType !== expectedMediaType || resource.status !== 'ACTIVE') {
        throw new Error('服务端素材准备响应与当前引用不一致，请重新报价')
      }
    })
    entry.promise = Promise.race([work, interrupted]).finally(() => {
      entry.settled = true
      clearTimeout(timer)
      if (preparations.get(key) === entry) preparations.delete(key)
    })
    preparations.set(key, entry)
    preparation = entry
  }
  const entry = preparation, owner = Symbol('metadata-quote-owner')
  entry.owners.add(owner)
  return new Promise<void>((resolve, reject) => {
    let finished = false
    const release = () => {
      ownerSignal.removeEventListener('abort', aborted)
      entry.owners.delete(owner)
      if (!entry.owners.size && !entry.settled) {
        entry.controller.abort(new Error('当前报价已取消'))
        if (preparations.get(key) === entry) preparations.delete(key)
      }
    }
    const aborted = () => {
      if (finished) return
      finished = true; release(); reject(ownerSignal.reason)
    }
    ownerSignal.addEventListener('abort', aborted, { once: true })
    entry.promise.then(() => {
      if (finished) return
      finished = true; release()
      try { current(identity, ownerSignal); resolve() } catch (reason) { reject(reason) }
    }, reason => {
      if (finished) return
      finished = true; release(); reject(reason)
    })
    if (ownerSignal.aborted) aborted()
  })
}

/** 报价仍是纯读；只有特定缺信息响应才进行独立准备，最多一次重报。 */
export async function quoteWithCanvasMetadataRecovery(body: unknown,
  execute: (signal: AbortSignal) => Promise<BillingQuoteWire>, signal: AbortSignal): Promise<BillingQuoteWire> {
  const identity = canvasQuoteIdentity()
  current(identity, signal)
  try {
    const quote = await execute(signal)
    current(identity, signal)
    return quote
  } catch (reason) {
    current(identity, signal)
    const missing = missingMetadataReferences(body, reason)
    if (!missing) throw reason
    let cursor = 0
    const batch = new AbortController()
    const preparationSignal = AbortSignal.any([signal, batch.signal])
    const workers = Array.from({ length: Math.min(2, missing.resourceIds.length) }, async () => {
      while (cursor < missing.resourceIds.length) {
        current(identity, preparationSignal)
        const id = missing.resourceIds[cursor++]
        await prepareMedia(missing.workspaceId, id, missing.expectedMediaType, identity, preparationSignal)
      }
    })
    try { await Promise.all(workers) } catch (error) { batch.abort(error); throw error }
    current(identity, signal)
    const quote = await execute(signal)
    current(identity, signal)
    return quote
  }
}
