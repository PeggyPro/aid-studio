import { execFile } from 'node:child_process'
import http from 'node:http'
import { CANVAS_PROXY_BANNER } from './canvas-ws-dev-proxy.mjs'

const SCRIPT_NAME = 'dev-with-canvas-ws.mjs'

export function listeningPidsFromNetstat(output, port) {
  const pids = new Set()
  const expected = `:${port}`
  for (const line of String(output).split(/\r?\n/)) {
    const parts = line.trim().split(/\s+/)
    if (parts.length < 5 || parts[0] !== 'TCP' || parts[3] !== 'LISTENING') continue
    const local = parts[1]
    if (!local.endsWith(expected)) continue
    const pid = Number(parts[4])
    if (Number.isInteger(pid) && pid > 0) pids.add(pid)
  }
  return [...pids]
}

/** The process that owns this dev session, never the current process or an unrelated parent. */
export function devProcessToStop(chain, selfPid) {
  return chain.find((proc) => proc.pid !== selfPid && String(proc.commandLine || '').includes(SCRIPT_NAME))?.pid ?? null
}

export function nextDevPort(args) {
  for (let i = 0; i < args.length; i++) {
    const arg = args[i]
    if (arg === '-p' || arg === '--port') {
      const port = Number(args[i + 1])
      return Number.isInteger(port) && port > 0 ? port : 3000
    }
    if (arg.startsWith('--port=')) {
      const port = Number(arg.slice('--port='.length))
      return Number.isInteger(port) && port > 0 ? port : 3000
    }
  }
  return 3000
}

function execText(command, args) {
  return new Promise((resolve, reject) => {
    execFile(command, args, { encoding: 'utf8', windowsHide: true, timeout: 8000 }, (error, stdout) => {
      if (error && !stdout) reject(error)
      else resolve(stdout || '')
    })
  })
}

function proxyBanner(port) {
  return new Promise((resolve) => {
    const req = http.get({ hostname: '127.0.0.1', port, timeout: 800 }, (res) => {
      const chunks = []
      res.on('data', (chunk) => chunks.push(chunk))
      res.on('end', () => resolve(Buffer.concat(chunks).toString('utf8').trim()))
    })
    req.on('error', () => resolve(''))
    req.on('timeout', () => { req.destroy(); resolve('') })
  })
}

async function listeningPids(port) {
  if (process.platform !== 'win32') return []
  const output = await execText('netstat', ['-ano', '-p', 'TCP'])
  return listeningPidsFromNetstat(output, port)
}

async function processInfo(pid) {
  const script = `$p = Get-CimInstance Win32_Process -Filter "ProcessId=${pid}"; if ($null -eq $p) { '' } else { "$($p.ParentProcessId)\`n$($p.CommandLine)" }`
  const output = await execText('powershell.exe', ['-NoProfile', '-Command', script])
  const newline = output.indexOf('\n')
  if (newline < 0) return null
  const parentPid = Number(output.slice(0, newline).trim())
  const commandLine = output.slice(newline + 1).trim()
  if (!commandLine) return null
  return { pid, parentPid: Number.isInteger(parentPid) ? parentPid : 0, commandLine }
}

async function processChain(pid) {
  const chain = []
  const seen = new Set()
  let current = pid
  while (current && !seen.has(current) && chain.length < 8) {
    seen.add(current)
    const info = await processInfo(current)
    if (!info) break
    chain.push(info)
    current = info.parentPid
  }
  return chain
}

function stopProcessTree(pid) {
  return execText('taskkill', ['/PID', String(pid), '/T', '/F']).catch(() => '')
}

async function waitUntilQuiet(ports) {
  const deadline = Date.now() + 8000
  while (Date.now() < deadline) {
    const busy = []
    for (const port of ports) if ((await listeningPids(port)).length) busy.push(port)
    if (!busy.length) return
    await new Promise((resolve) => setTimeout(resolve, 200))
  }
}

/**
 * A branch switch starts a new dev process while the previous one still owns the
 * canvas proxy port. Replace that previous session instead of failing the listen.
 */
export async function releasePreviousDevSession({ proxyPort, nextPort, selfPid = process.pid }) {
  const banner = await proxyBanner(proxyPort)
  if (!banner) return
  if (banner !== CANVAS_PROXY_BANNER) {
    throw new Error(`端口 ${proxyPort} 已被其他程序占用，开发服务没有启动。`)
  }
  const listeners = await listeningPids(proxyPort)
  let stopped = false
  for (const pid of listeners) {
    const chain = await processChain(pid)
    const owner = devProcessToStop(chain, selfPid)
    if (!owner) continue
    console.info(`[aid-pc] 端口 ${proxyPort} 仍由上次开发服务占用，已结束该进程并重新启动。`)
    await stopProcessTree(owner)
    stopped = true
  }
  if (!stopped) throw new Error(`端口 ${proxyPort} 仍由上次开发服务占用，但无法结束该进程。`)
  await waitUntilQuiet([proxyPort, nextPort])
}
