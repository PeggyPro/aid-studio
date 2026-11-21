import type { BillingQuoteVO, BillingQuoteWire } from '@/types/business-api'
import { canvasAssistantTransport } from './canvasAssistantTransport'
import { hasCanvasGatewaySession } from './canvasApiClient'

/** 只读报价总截止，包括身份、公钥与素材准备；不改变生成请求的截止。 */
export const CANVAS_QUOTE_TIMEOUT_MS = 30_000

export function canvasQuoteIdentity(): string { return canvasAssistantTransport.identityKey() }

export function canvasQuoteConnected(): boolean {
  return typeof window !== 'undefined' && hasCanvasGatewaySession(localStorage.getItem('token') || '')
}

function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical)
  if (!value || typeof value !== 'object') return value
  return Object.fromEntries(Object.entries(value).sort(([left], [right]) => left.localeCompare(right))
    .map(([key, item]) => [key, canonical(item)]))
}

export function canvasQuoteRequestKey(value: unknown): string {
  return value == null ? '' : JSON.stringify(canonical(value))
}

function quoteAmount(value: string | number | null | undefined): number | null | undefined {
  if (value == null) return value
  let nonzeroDecimal = false
  if (typeof value === 'string') {
    const decimal = /^(0|[1-9]\d*)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(value)
    if (!decimal || decimal[0] !== value) throw new Error('服务端报价金额无效，请重新报价')
    const fraction = decimal[2] ?? '', exponent = Number(decimal[3] ?? 0)
    if (!Number.isSafeInteger(exponent)) throw new Error('服务端报价金额无效，请重新报价')
    const digits = (decimal[1] + fraction).replace(/^0+/, '')
    nonzeroDecimal = digits.length > 0
    // 比较精确十进制整数部分，避免 MAX_SAFE_INTEGER + 小数被 Number 舍回边界。
    const integerLength = digits.length - fraction.length + exponent
    const max = String(Number.MAX_SAFE_INTEGER)
    if (nonzeroDecimal && integerLength > max.length) throw new Error('服务端报价金额超出展示范围，请重新报价')
    if (nonzeroDecimal && integerLength === max.length) {
      const integer = digits.padEnd(integerLength, '0').slice(0, integerLength)
      if (integer > max || (integer === max && /[1-9]/.test(digits.slice(integerLength)))) {
        throw new Error('服务端报价金额超出展示范围，请重新报价')
      }
    }
  } else if (typeof value !== 'number') throw new Error('服务端报价金额无效，请重新报价')
  const amount = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(amount) || amount < 0 || Object.is(amount, -0) || amount > Number.MAX_SAFE_INTEGER
    || (nonzeroDecimal && amount === 0)) {
    throw new Error('服务端报价金额无效，请重新报价')
  }
  return amount
}

/** 将权威 wire 金额归一至展示域；不据免费标记猜价格，也不重新计算结算金额。 */
export function assertCanvasQuote(value: BillingQuoteWire | null | undefined): BillingQuoteVO {
  const visited = new WeakSet<object>()
  function normalize(item: BillingQuoteWire | null | undefined): BillingQuoteVO {
    if (!item || item.matched !== true || typeof item.isFree !== 'boolean' || visited.has(item)) {
      throw new Error(item?.matched === false ? '当前参数没有可用计费规则' : '服务端报价响应不完整，请重新报价')
    }
    visited.add(item)
    const amountValue = quoteAmount(item.amount), preHoldValue = quoteAmount(item.preHoldAmount)
    const amount = preHoldValue ?? amountValue
    const amounts = [amountValue, preHoldValue].filter(value => value != null)
    if (typeof amount !== 'number' || (item.isFree && amounts.some(value => value !== 0))) {
      throw new Error('服务端报价金额无效，请重新报价')
    }
    if (item.items != null && !Array.isArray(item.items)) throw new Error('服务端报价明细无效，请重新报价')
    const items = item.items == null ? item.items : item.items.map(child => normalize(child))
    if (item.isFree && items?.some(child => !child.isFree)) throw new Error('服务端报价免费标记与明细不一致，请重新报价')
    return { ...item, amount: amountValue, preHoldAmount: preHoldValue, items }
  }
  return normalize(value)
}

type RunningQuote = {
  identity: string
  controller: AbortController
  owners: Set<symbol>
  settled: boolean
  promise: Promise<BillingQuoteVO>
}
const running = new Map<string, RunningQuote>()

export function invalidateStaleCanvasQuotes(): void {
  const identity = canvasQuoteIdentity()
  const connected = canvasQuoteConnected()
  for (const [key, entry] of running) {
    if (!connected || entry.identity !== identity) {
      entry.controller.abort(new Error('画布账号或连接已变化，请重新报价'))
      running.delete(key)
    }
  }
}

/** 同身份同请求并发共享；失败不缓存，身份变化后的结果不能作为有效报价。 */
export function requestCanvasQuote(scope: string, body: unknown, execute: (signal: AbortSignal) => Promise<BillingQuoteWire>, ownerSignal?: AbortSignal): Promise<BillingQuoteVO> {
  if (ownerSignal?.aborted) return Promise.reject(ownerSignal.reason)
  if (!canvasQuoteConnected()) return Promise.reject(new Error('画布连接尚未就绪，连接完成后将重新报价'))
  const identity = canvasQuoteIdentity()
  invalidateStaleCanvasQuotes()
  const key = JSON.stringify([identity, scope, canvasQuoteRequestKey(body)])
  let entry = running.get(key)
  if (!entry) {
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    const interrupted = new Promise<never>((_, reject) => {
      controller.signal.addEventListener('abort', () => reject(controller.signal.reason), { once: true })
      timer = setTimeout(() => controller.abort(Object.assign(new Error('报价请求超时'), { code: 'CANVAS_QUOTE_TIMEOUT' })), CANVAS_QUOTE_TIMEOUT_MS)
    })
    const work = Promise.resolve().then(() => {
      controller.signal.throwIfAborted()
      return execute(controller.signal)
    })
    const promise = Promise.race([work, interrupted]).then(value => {
      controller.signal.throwIfAborted()
      if (!canvasQuoteConnected() || canvasQuoteIdentity() !== identity) throw new Error('画布账号或连接已变化，请重新报价')
      return assertCanvasQuote(value)
    })
    const created: RunningQuote = { identity, controller, owners: new Set(), settled: false, promise }
    created.promise = promise.finally(() => {
      created.settled = true
      clearTimeout(timer)
      if (running.get(key) === created) running.delete(key)
    })
    running.set(key, created)
    entry = created
  }
  const shared = entry, owner = Symbol('quote-owner')
  shared.owners.add(owner)
  return new Promise<BillingQuoteVO>((resolve, reject) => {
    let finished = false
    const release = () => {
      ownerSignal?.removeEventListener('abort', aborted)
      shared.owners.delete(owner)
      if (!shared.owners.size && !shared.settled) {
        shared.controller.abort(new Error('当前报价已取消'))
        if (running.get(key) === shared) running.delete(key)
      }
    }
    const aborted = () => {
      if (finished) return
      finished = true; release(); reject(ownerSignal?.reason)
    }
    ownerSignal?.addEventListener('abort', aborted, { once: true })
    shared.promise.then(value => {
      if (finished) return
      finished = true; release()
      try { resolve(structuredClone(value)) } catch (reason) { reject(reason) }
    }, reason => {
      if (finished) return
      finished = true; release(); reject(reason)
    })
    if (ownerSignal?.aborted) aborted()
  })
}
