#!/usr/bin/env node

import { gunzipSync } from 'node:zlib'
import { randomUUID } from 'node:crypto'
import { pathToFileURL } from 'node:url'

const DEFAULT_API_BASE = process.env.NEXT_PROXY_TARGET || 'http://127.0.0.1:8080'
const DEFAULT_TIMEOUT_MS = 12_000

function parseArgs(argv) {
  const options = {}
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index]
    if (arg === '--help' || arg === '-h') {
      options.help = true
      continue
    }
    if (!arg.startsWith('--')) throw new Error(`无法识别的参数：${arg}`)
    const key = arg.slice(2)
    const value = argv[index + 1]
    if (!value || value.startsWith('--')) throw new Error(`参数 ${arg} 缺少值`)
    options[key] = value
    index += 1
  }
  return options
}

function usage() {
  return `自由画布 WebSocket 连接 demo

PowerShell 用法：
  # 推荐：使用环境变量传 Token，自动选取最近更新的画布
  $env:CANVAS_ACCESS_TOKEN = '<token>'
  node scripts/canvas-websocket-demo.mjs --api-base http://127.0.0.1:8080

  # 指定工作区
  node scripts/canvas-websocket-demo.mjs --api-base http://127.0.0.1:8080 --workspace-id 92001

  # 已有一次性 ticket URL 时直接测试（推荐用环境变量，避免进入命令历史）
  $env:CANVAS_WEBSOCKET_URL = 'ws://127.0.0.1:8080/ws/canvas?ticket=<ticket>'
  node scripts/canvas-websocket-demo.mjs

可选参数：
  --page-id <id>        页面实例标识；默认自动生成
  --timeout-ms <毫秒>   连接与心跳确认超时；默认 ${DEFAULT_TIMEOUT_MS}
  --url <WebSocket URL> 直接测试一次性 ticket 地址（会进入命令历史）

成功标准：收到 CONNECTED，发送 HEARTBEAT 后收到 HEARTBEAT_ACK。
脚本不会输出 Token 或完整 ticket。`
}

function normalizeBaseUrl(value) {
  const url = new URL(String(value || DEFAULT_API_BASE).trim())
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error('api-base 必须使用 http 或 https')
  }
  return url.origin
}

function maskWebsocketUrl(value) {
  try {
    const url = new URL(value)
    if (url.searchParams.has('ticket')) url.searchParams.set('ticket', '<redacted>')
    return url.toString().replace('%3Credacted%3E', '<redacted>')
  } catch {
    return '<invalid-websocket-url>'
  }
}

function toWebsocketUrl(websocketPath, apiBase) {
  const raw = String(websocketPath || '').trim()
  if (!raw) throw new Error('ticket 响应缺少 websocketPath')
  if (/^wss?:\/\//i.test(raw)) return raw
  const url = new URL(raw.startsWith('/') ? raw : `/${raw}`, apiBase)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  return url.toString()
}

function base64ToBytes(value) {
  return Uint8Array.from(Buffer.from(String(value), 'base64'))
}

function bytesToBase64(value) {
  return Buffer.from(value).toString('base64')
}

function normalizePublicKey(value) {
  return String(value || '')
    .replace(/-----BEGIN PUBLIC KEY-----/g, '')
    .replace(/-----END PUBLIC KEY-----/g, '')
    .replace(/\s+/g, '')
}

async function loadPublicConfig(apiBase, timeoutMs) {
  const clientTime = Date.now()
  const response = await fetch(`${apiBase}/auth/public-config`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest'
    },
    body: '{}',
    signal: AbortSignal.timeout(timeoutMs)
  })
  const payload = await parseJsonResponse(response, '公开配置')
  if (!response.ok || Number(payload?.code) !== 200) {
    throw new Error(`公开配置请求失败：HTTP ${response.status}，${payload?.msg || '未知错误'}`)
  }
  const data = payload.data || {}
  const serverTime = Number(data.serverTime)
  return {
    crypto: data.crypto || {},
    serverTimeOffsetMs: Number.isFinite(serverTime) ? serverTime - clientTime : 0
  }
}

async function prepareEncryptedRequest(body, publicKey, timestampMs) {
  const aesKey = await crypto.subtle.generateKey(
    { name: 'AES-GCM', length: 256 },
    true,
    ['encrypt', 'decrypt']
  )
  const rawAesKey = await crypto.subtle.exportKey('raw', aesKey)
  const rsaKey = await crypto.subtle.importKey(
    'spki',
    base64ToBytes(normalizePublicKey(publicKey)),
    { name: 'RSA-OAEP', hash: 'SHA-256' },
    false,
    ['encrypt']
  )
  const encryptedAesKey = await crypto.subtle.encrypt(
    { name: 'RSA-OAEP' },
    rsaKey,
    rawAesKey
  )
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const encryptedBody = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, tagLength: 128 },
    aesKey,
    new TextEncoder().encode(JSON.stringify(body ?? {}))
  )
  return {
    aesKey,
    body: bytesToBase64(encryptedBody),
    headers: {
      'Content-Type': 'application/json',
      'X-Encrypt-Key': bytesToBase64(encryptedAesKey),
      'X-Encrypt-Iv': bytesToBase64(iv),
      'X-Encrypt-Ts': String(timestampMs)
    }
  }
}

async function decryptResponseEnvelope(payload, aesKey) {
  if (!payload?.encrypted || !aesKey) return payload
  const decrypted = await crypto.subtle.decrypt(
    {
      name: 'AES-GCM',
      iv: base64ToBytes(payload.iv),
      tagLength: 128
    },
    aesKey,
    base64ToBytes(payload.data)
  )
  const bytes = payload.gzip ? gunzipSync(Buffer.from(decrypted)) : Buffer.from(decrypted)
  return JSON.parse(bytes.toString('utf8'))
}

async function parseJsonResponse(response, label) {
  const text = await response.text()
  try {
    return JSON.parse(text)
  } catch {
    throw new Error(`${label}返回的不是 JSON：HTTP ${response.status}`)
  }
}

async function postBusinessApi({ apiBase, path, body, token, cryptoConfig, timeoutMs }) {
  const encryptionEnabled = Boolean(cryptoConfig.crypto?.enabled && cryptoConfig.crypto?.publicKey)
  const encrypted = encryptionEnabled
    ? await prepareEncryptedRequest(
        body,
        cryptoConfig.crypto.publicKey,
        Date.now() + cryptoConfig.serverTimeOffsetMs
      )
    : null
  const response = await fetch(`${apiBase}${path}`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'X-Requested-With': 'XMLHttpRequest',
      ...(encrypted?.headers || { 'Content-Type': 'application/json' })
    },
    body: encrypted?.body ?? JSON.stringify(body ?? {}),
    signal: AbortSignal.timeout(timeoutMs)
  })
  const rawPayload = await parseJsonResponse(response, path)
  const payload = await decryptResponseEnvelope(rawPayload, encrypted?.aesKey)
  if (!response.ok || Number(payload?.code) !== 200) {
    throw new Error(`${path} 请求失败：HTTP ${response.status}，${payload?.msg || '未知错误'}`)
  }
  return payload.data
}

async function resolveWorkspaceId(options) {
  if (options.workspaceId) return String(options.workspaceId)
  const page = await postBusinessApi({
    ...options,
    path: '/api/user/canvas/project/list',
    body: {
      sortBy: 'UPDATED_AT',
      sortDirection: 'DESC',
      pageNum: 1,
      pageSize: 1
    }
  })
  const items = Array.isArray(page?.items)
    ? page.items
    : Array.isArray(page?.rows)
      ? page.rows
      : []
  const workspaceId = items[0]?.workspaceId
  if (workspaceId == null || String(workspaceId).trim() === '') {
    throw new Error('当前账号没有可用于测试的自由画布项目，请先创建画布或传 --workspace-id')
  }
  return String(workspaceId)
}

async function requestTicket(options, workspaceId) {
  const pageId = options.pageId || `canvas-ws-demo-${randomUUID().replaceAll('-', '').slice(0, 24)}`
  return postBusinessApi({
    ...options,
    path: '/api/user/canvas/collaboration/ticket',
    body: { workspaceId, pageId }
  })
}

export function probeCanvasWebsocket(url, options = {}) {
  const timeoutMs = Number(options.timeoutMs) || DEFAULT_TIMEOUT_MS
  const acknowledgedSeq = Number(options.acknowledgedSeq) || 0
  const log = options.log || (() => {})
  return new Promise((resolve, reject) => {
    let connected = false
    let settled = false
    const socket = new WebSocket(url)
    const finish = (error, result) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      try {
        if (socket.readyState === WebSocket.CONNECTING) socket.close()
        else if (socket.readyState === WebSocket.OPEN) socket.close(1000, error ? 'demo failed' : 'demo complete')
      } catch {
        // The result is already settled; a transport that cannot abort CONNECTING will close itself later.
      }
      if (error) reject(error)
      else resolve(result)
    }
    const timer = setTimeout(() => {
      finish(new Error(`等待 CONNECTED/HEARTBEAT_ACK 超时（${timeoutMs}ms）`))
    }, timeoutMs)

    socket.addEventListener('open', () => {
      if (settled) {
        socket.close(1000, 'demo finished')
        return
      }
      log('OPEN', 'WebSocket 握手成功')
    })
    socket.addEventListener('message', (event) => {
      if (settled) return
      let payload
      try {
        payload = JSON.parse(String(event.data))
      } catch {
        log('MESSAGE', String(event.data))
        return
      }
      const type = String(payload?.type || '')
      log('MESSAGE', type || 'UNKNOWN')
      if (type === 'CONNECTED') {
        connected = true
        socket.send(JSON.stringify({ type: 'HEARTBEAT', acknowledgedSeq }))
        log('SEND', 'HEARTBEAT')
      } else if (type === 'HEARTBEAT_ACK' && connected) {
        finish(null, {
          connected: true,
          heartbeatAcknowledged: true,
          sessionId: String(payload?.sessionId || '')
        })
      } else if (type === 'SESSION_CLOSED') {
        finish(new Error(`服务端关闭会话：${payload?.reason || 'SESSION_CLOSED'}`))
      }
    })
    socket.addEventListener('error', (event) => {
      if (settled) return
      const detail = event?.error?.message || event?.message || 'WebSocket 握手或传输失败'
      finish(new Error(detail))
    })
    socket.addEventListener('close', (event) => {
      if (settled) return
      log('CLOSE', `code=${event.code} reason=${event.reason || '<empty>'}`)
      finish(new Error(`连接提前关闭：code=${event.code}`))
    })
  })
}

async function main() {
  const raw = parseArgs(process.argv.slice(2))
  if (raw.help) {
    console.log(usage())
    return
  }
  const timeoutMs = Math.max(1_000, Number(raw['timeout-ms']) || DEFAULT_TIMEOUT_MS)
  const logger = (event, detail) => {
    console.log(`[${new Date().toISOString()}] ${event} ${detail}`)
  }

  if (typeof WebSocket !== 'function') {
    throw new Error('当前 Node.js 不提供 WebSocket，请使用 Node.js 22 或更高版本运行 demo')
  }

  let websocketUrl = process.env.CANVAS_WEBSOCKET_URL || raw.url
  if (!websocketUrl) {
    const token = String(process.env.CANVAS_ACCESS_TOKEN || '').trim()
    if (!token) {
      throw new Error('缺少 CANVAS_ACCESS_TOKEN；请通过环境变量提供，或使用 --url 传入一次性 ticket 地址')
    }
    const apiBase = normalizeBaseUrl(raw['api-base'] || process.env.CANVAS_API_BASE)
    logger('HTTP', `加载公开配置：${apiBase}`)
    const cryptoConfig = await loadPublicConfig(apiBase, timeoutMs)
    const requestOptions = {
      apiBase,
      token,
      cryptoConfig,
      timeoutMs,
      workspaceId: raw['workspace-id'],
      pageId: raw['page-id']
    }
    const workspaceId = await resolveWorkspaceId(requestOptions)
    logger('HTTP', `申请协作 ticket：workspaceId=${workspaceId}`)
    const ticket = await requestTicket(requestOptions, workspaceId)
    websocketUrl = toWebsocketUrl(ticket?.websocketPath, apiBase)
  }

  logger('CONNECT', maskWebsocketUrl(websocketUrl))
  const result = await probeCanvasWebsocket(websocketUrl, { timeoutMs, log: logger })
  logger('SUCCESS', `CONNECTED=true HEARTBEAT_ACK=${result.heartbeatAcknowledged}`)
}

const invokedDirectly = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href
if (invokedDirectly) {
  main().catch((error) => {
    console.error(`[${new Date().toISOString()}] FAILED ${error instanceof Error ? error.message : String(error)}`)
    process.exitCode = 1
  })
}
