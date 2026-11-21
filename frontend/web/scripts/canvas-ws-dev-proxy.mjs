import http from 'node:http'
import https from 'node:https'

const DEFAULT_PORT = 3015
export const CANVAS_PROXY_BANNER = 'aid-pc canvas websocket dev proxy'
const DIRECTOR_SIGNAL_PATH = '/api/user/studio/director/virtual-camera/ws'

function isLocalDirectorUpgrade(req, socket) {
  if (!['127.0.0.1', '::1', '::ffff:127.0.0.1', socket.localAddress].includes(socket.remoteAddress)) return false
  try {
    return new URL(req.headers.origin).hostname === new URL(`http://${req.headers.host}`).hostname
  } catch {
    return false
  }
}

/** Only forward the known realtime endpoints. */
export function canvasWsBackendPath(requestUrl, basePath = '') {
  const url = new URL(requestUrl || '/', 'http://127.0.0.1')
  let pathname = url.pathname || '/'
  if (pathname.startsWith('/url/')) pathname = pathname.slice(4)
  if (!pathname.startsWith('/ws/') && (pathname !== DIRECTOR_SIGNAL_PATH || url.search)) return null
  const prefix = String(basePath).replace(/\/+$/, '')
  return `${prefix}${pathname}${url.search}`
}

/** 浏览器 Origin 是 localhost，握手会被 Spring 白名单 403；改成后端自身源再转发。 */
export function canvasWsBackendHeaders(incoming = {}, targetOrigin) {
  const target = new URL(targetOrigin)
  const headers = { ...incoming }
  headers.host = target.host
  headers.origin = target.origin
  if (headers.referer) headers.referer = `${target.origin}/`
  return headers
}

export function createCanvasWsDevProxy({
  listenPort = DEFAULT_PORT,
  target = process.env.NEXT_PROXY_TARGET || 'http://127.0.0.1:8080',
  directorTarget,
  directorOrigin
} = {}) {
  const targetUrl = new URL(target)
  // A separately configured canvas HTTP API must also own virtual camera tickets and signaling.
  const directorUrl = /^https?:\/\//i.test(directorTarget?.trim() || '') ? new URL(directorTarget) : targetUrl
  const directorOriginUrl = directorOrigin ? new URL(directorOrigin) : null
  if (directorOriginUrl && (directorOriginUrl.protocol !== 'https:' || directorOriginUrl.href !== directorOriginUrl.origin + '/'))
    throw new Error('AID_DEV_DIRECTOR_ORIGIN 必须是 HTTPS 站点来源')
  const server = http.createServer((_req, res) => {
    res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' })
    res.end(CANVAS_PROXY_BANNER)
  })

  server.on('upgrade', (req, socket, head) => {
    const route = canvasWsBackendPath(req.url)
    if (!route) {
      socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n')
      socket.destroy()
      return
    }
    const director = route === DIRECTOR_SIGNAL_PATH
    if (director && !isLocalDirectorUpgrade(req, socket)) {
      socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n')
      return
    }

    const upstream = director ? directorUrl : targetUrl
    const client = upstream.protocol === 'https:' ? https : http
    const headers = canvasWsBackendHeaders(req.headers, upstream.origin)
    if (director && directorOriginUrl) {
      headers.origin = directorOriginUrl.origin
      if (headers.referer) headers.referer = directorOriginUrl.href
    }
    const proxyReq = client.request({
      protocol: upstream.protocol,
      hostname: upstream.hostname,
      port: upstream.port || (upstream.protocol === 'https:' ? 443 : 80),
      path: canvasWsBackendPath(req.url, upstream.pathname),
      method: 'GET',
      headers
    })
    let upstreamSocket
    let upstreamResponse
    const fail = () => {
      proxyReq.destroy()
      upstreamSocket?.destroy()
      upstreamResponse?.destroy()
      socket.destroy()
    }
    proxyReq.on('error', fail)
    socket.on('error', fail)
    socket.once('close', () => {
      proxyReq.destroy()
      upstreamSocket?.destroy()
      upstreamResponse?.destroy()
    })

    proxyReq.on('upgrade', (proxyRes, proxySocket, proxyHead) => {
      upstreamSocket = proxySocket
      proxySocket.on('error', fail)
      proxySocket.once('close', () => { if (!socket.destroyed) socket.end() })
      if (socket.destroyed) { proxySocket.destroy(); return }
      const lines = [`HTTP/1.1 ${proxyRes.statusCode} ${proxyRes.statusMessage || 'Switching Protocols'}`]
      for (const [key, value] of Object.entries(proxyRes.headers)) {
        if (value == null) continue
        lines.push(`${key}: ${Array.isArray(value) ? value.join(', ') : value}`)
      }
      socket.write(`${lines.join('\r\n')}\r\n\r\n`)
      if (proxyHead?.length) socket.write(proxyHead)
      // Upgrade head bytes are already WebSocket data, not an HTTP request body.
      if (head?.length) proxySocket.write(head)
      proxySocket.pipe(socket)
      socket.pipe(proxySocket)
    })

    proxyReq.on('response', (proxyRes) => {
      upstreamResponse = proxyRes
      proxyRes.on('error', fail)
      proxyRes.once('aborted', fail)
      if (socket.destroyed) { proxyRes.destroy(); return }
      const lines = [`HTTP/1.1 ${proxyRes.statusCode} ${proxyRes.statusMessage || ''}`]
      for (const [key, value] of Object.entries(proxyRes.headers)) {
        if (value == null) continue
        lines.push(`${key}: ${Array.isArray(value) ? value.join(', ') : value}`)
      }
      socket.write(`${lines.join('\r\n')}\r\n\r\n`)
      proxyRes.pipe(socket)
    })

    proxyReq.end()
  })

  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(listenPort, () => {
      const port = server.address()?.port
      console.info(`[aid-pc] canvas websocket proxy ws://127.0.0.1:${port} -> ${targetUrl.origin}`)
      resolve(server)
    })
  })
}

export function canvasWsDevProxyPort(env = process.env) {
  const port = Number(env.AID_DEV_WS_PROXY_PORT || DEFAULT_PORT)
  if (!Number.isInteger(port) || port <= 0 || port > 65535) {
    throw new Error(`AID_DEV_WS_PROXY_PORT 无效: ${env.AID_DEV_WS_PROXY_PORT}`)
  }
  return port
}
