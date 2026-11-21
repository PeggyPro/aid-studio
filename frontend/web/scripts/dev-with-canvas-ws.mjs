import { spawn } from 'node:child_process'
import { prepareNodeRuntime } from './node-runtime.mjs'
import { existsSync, readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { canvasWsDevProxyPort, createCanvasWsDevProxy } from './canvas-ws-dev-proxy.mjs'
import { nextDevPort, releasePreviousDevSession } from './dev-session.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')

function applyEnvFile(file) {
  if (!existsSync(file)) return
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) continue
    const eq = trimmed.indexOf('=')
    if (eq <= 0) continue
    const key = trimmed.slice(0, eq).trim()
    let value = trimmed.slice(eq + 1).trim()
    if (
      (value.startsWith('"') && value.endsWith('"'))
      || (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1)
    }
    if (process.env[key] == null) process.env[key] = value
  }
}

applyEnvFile(resolve(root, '.env.development.local'))
applyEnvFile(resolve(root, '.env.local'))
applyEnvFile(resolve(root, '.env.development'))
applyEnvFile(resolve(root, '.env'))

let runtimeEnv
try { runtimeEnv = prepareNodeRuntime(root) } catch (error) {
  console.error(error.message)
  process.exit(1)
}

const listenPort = canvasWsDevProxyPort()
const nextPort = nextDevPort(process.argv.slice(2))
let server
try {
  await releasePreviousDevSession({ proxyPort: listenPort, nextPort })
  server = await createCanvasWsDevProxy({
    listenPort,
    target: process.env.NEXT_PROXY_TARGET || 'http://127.0.0.1:8080',
    directorTarget: process.env.NEXT_PUBLIC_CANVAS_API_BASE_URL,
    directorOrigin: process.env.AID_DEV_DIRECTOR_ORIGIN
  })
} catch (error) {
  const message = error?.code === 'EADDRINUSE'
    ? `端口 ${listenPort} 已被占用，开发服务没有启动。`
    : error instanceof Error ? error.message : String(error)
  console.error(`[aid-pc] ${message}`)
  process.exit(1)
}

runtimeEnv.AID_DEV_WS_PROXY_PORT = String(listenPort)
if (runtimeEnv.AID_DEV_WS_PROXY == null) runtimeEnv.AID_DEV_WS_PROXY = '1'

const nextBin = resolve(root, 'node_modules/next/dist/bin/next')
const nextArgs = process.argv.slice(2)
const hasPortOption = nextArgs.some((arg) => /^(?:--port(?:=|$)|-p(?:=|[0-9]|$))/.test(arg))
// A default Next port can silently move to 3001, leaving localhost:3000 on another checkout.
if (!hasPortOption && !process.env.PORT) nextArgs.unshift('--port', '3000')
const child = spawn(process.execPath, [nextBin, 'dev', ...nextArgs], {
  cwd: root,
  env: runtimeEnv,
  stdio: 'inherit'
})

let shuttingDown = false
function shutdown() {
  if (shuttingDown) return
  shuttingDown = true
  server.close()
  if (!child.killed) child.kill('SIGINT')
}

child.on('exit', (code, signal) => {
  server.close()
  if (signal && !shuttingDown) process.kill(process.pid, signal)
  process.exit(code ?? 0)
})

process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
