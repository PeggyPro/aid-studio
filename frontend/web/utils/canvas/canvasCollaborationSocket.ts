import { resolveCanvasApiUrl } from '@/utils/canvas/canvasApiClient'

export interface CanvasCollaborationSocketEnv {
  nodeEnv?: string
  proxyTarget?: string
  pageHref?: string
  wsProxyPort?: string | number
  wsProxyEnabled?: string
}

export const CANVAS_COLLABORATION_MAX_RECONNECT_ATTEMPTS = 4

/** 断开后重新申请 ticket 的等待时间：2s 起，上限 30s。 */
export function canvasCollaborationReconnectDelay(attempt: number): number {
  const safeAttempt = Math.max(0, Math.min(Math.floor(attempt), 4))
  return Math.min(30_000, 2_000 * (2 ** safeAttempt))
}

/** Only an established session may reconnect automatically, and retries are bounded. */
export function canvasCollaborationShouldAutoReconnect(hadConnected: boolean, attempt: number): boolean {
  return hadConnected && attempt < CANVAS_COLLABORATION_MAX_RECONNECT_ATTEMPTS
}

/**
 * 智能体对话打开期间，暂停的协作走和手动重试相同的连接入口。
 * 第一次立即恢复（打开面板时已经断开，或面板开着时刚进入暂停）。
 * 随后的暂停沿用同一套退避，避免握手失败时连续申请 ticket。
 */
export function canvasAgentCollaborationResumeDelay(attempt: number): number {
  const safeAttempt = Math.max(0, Math.floor(attempt))
  if (safeAttempt === 0) return 0
  return canvasCollaborationReconnectDelay(safeAttempt - 1)
}

function developmentWsProxyPort(env: CanvasCollaborationSocketEnv, nodeEnv: string | undefined): number | null {
  if (nodeEnv !== 'development') return null
  const enabled = env.wsProxyEnabled ?? process.env.AID_DEV_WS_PROXY
  if (enabled === '0' || enabled === 'false') return null
  const raw = env.wsProxyPort ?? process.env.AID_DEV_WS_PROXY_PORT
  if (raw == null || raw === '') return null
  const port = Number(raw)
  if (!Number.isInteger(port) || port <= 0 || port > 65535) return null
  return port
}

function hostWithPort(hostname: string, port: number): string {
  return hostname.includes(':') ? `[${hostname}]:${port}` : `${hostname}:${port}`
}

/**
 * 协作 WebSocket 地址。
 * Next `/url` rewrite 不能升级 WebSocket。开发态默认连 `npm run dev` 拉起的本地 WS 代理，
 * 由代理把 Origin 改成后端源再转发，避免浏览器直连 `localhost → 远端` 被握手 403。
 */
export function canvasCollaborationWebsocketUrl(
  websocketPath: string,
  env: CanvasCollaborationSocketEnv = {}
): string {
  const raw = String(websocketPath || '').trim()
  if (!raw) throw new Error('协作通道地址为空')
  if (/^wss?:\/\//i.test(raw)) return raw
  if (/^https?:\/\//i.test(raw)) {
    const absolute = new URL(raw)
    absolute.protocol = absolute.protocol === 'https:' ? 'wss:' : 'ws:'
    return absolute.toString()
  }
  const path = raw.startsWith('/') ? raw : `/${raw}`
  const nodeEnv = env.nodeEnv ?? process.env.NODE_ENV
  const proxyTarget = env.proxyTarget ?? process.env.AID_DEV_PROXY_TARGET
  const href = env.pageHref ?? (typeof window === 'undefined' ? 'http://localhost' : window.location.href)
  const wsProxyPort = developmentWsProxyPort(env, nodeEnv)
  if (wsProxyPort != null) {
    const page = new URL(href)
    const protocol = page.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${protocol}//${hostWithPort(page.hostname, wsProxyPort)}${path}`
  }
  if (nodeEnv === 'development' && proxyTarget) {
    const origin = new URL(proxyTarget)
    origin.protocol = origin.protocol === 'https:' ? 'wss:' : 'ws:'
    return new URL(path, origin.origin).toString()
  }
  const httpPath = nodeEnv === 'development' ? `/url${path}` : resolveCanvasApiUrl(path)
  const target = new URL(httpPath, href)
  target.protocol = target.protocol === 'https:' ? 'wss:' : 'ws:'
  return target.toString()
}
