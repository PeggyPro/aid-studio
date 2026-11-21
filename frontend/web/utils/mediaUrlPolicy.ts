export type MediaUrlDecision =
  | { allowed: true; kind: 'local' | 'public-http' }
  | { allowed: false; reason: string }

function blockedIpv4(hostname: string): boolean {
  const parts = hostname.split('.').map(Number)
  if (parts.length !== 4 || parts.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) return false
  const [a, b] = parts
  return a === 0 || a === 10 || a === 127 || a >= 224 ||
    (a === 100 && b >= 64 && b <= 127) ||
    (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    (a === 198 && (b === 18 || b === 19))
}

function blockedIpv6(hostname: string): boolean {
  const host = hostname.replace(/^\[|\]$/g, '').toLowerCase()
  if (!host.includes(':')) return false
  if (host === '::' || host === '::1') return true
  if (host.startsWith('fc') || host.startsWith('fd') || /^fe[89ab]/.test(host)) return true
  const mappedIpv4 = host.match(/(?:^|:)ffff:(\d+\.\d+\.\d+\.\d+)$/)?.[1]
  return mappedIpv4 ? blockedIpv4(mappedIpv4) : false
}

function blockedHostname(hostname: string): boolean {
  const host = hostname.replace(/^\[|\]$/g, '').replace(/\.$/, '').toLowerCase()
  return host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local') ||
    host.endsWith('.internal') || blockedIpv4(host) || blockedIpv6(host)
}

/**
 * Web 端的最小媒体边界：过滤不受支持的协议以及明显的本机和私网地址。
 * DNS/IP 二次解析与重定向逃逸仍必须由服务端 Media Resolver 负责。
 */
export function inspectMediaUrl(value: unknown): MediaUrlDecision {
  const raw = String(value || '').trim()
  if (!raw) return { allowed: false, reason: '媒体地址为空' }
  if (raw.startsWith('/') || raw.startsWith('./') || raw.startsWith('../') || raw.startsWith('blob:')) {
    return { allowed: true, kind: 'local' }
  }
  if (/^data:(?:image|video|audio)\//i.test(raw)) return { allowed: true, kind: 'local' }
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    return { allowed: false, reason: '媒体地址格式无效' }
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    return { allowed: false, reason: '仅允许 HTTP(S) 媒体地址' }
  }
  if (url.username || url.password) return { allowed: false, reason: '媒体地址不能包含登录凭据' }
  if (blockedHostname(url.hostname)) return { allowed: false, reason: '不允许访问本机或私网媒体地址' }
  return { allowed: true, kind: 'public-http' }
}

export function studioRemoteMediaUrlProblem(value: unknown): string | null {
  const decision = inspectMediaUrl(value)
  return decision.allowed ? null : decision.reason
}

export function requireSafeStudioMediaUrl(value: unknown): string {
  const url = String(value || '').trim()
  const problem = studioRemoteMediaUrlProblem(url)
  if (problem) throw new Error(problem)
  return url
}
