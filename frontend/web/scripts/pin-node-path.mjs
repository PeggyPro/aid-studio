import { delimiter, dirname } from 'node:path'

/**
 * Turbopack starts PostCSS in a new process by looking up the command `node` on PATH.
 * That lookup must find the same Node that is already running Next. An older `node`
 * earlier on PATH exits while loading the worker, and the app endpoint never compiles.
 */
export function pinCurrentNodeOnPath() {
  const nodeDir = dirname(process.execPath).replace(/[\\/]+$/, '')
  const keys = Object.keys(process.env).filter((key) => key.toLowerCase() === 'path')
  if (!keys.length) keys.push('PATH')
  const current = process.env[keys[0]] || ''
  const same = (part) => part.replace(/[\\/]+$/, '').toLowerCase() === nodeDir.toLowerCase()
  const next = [nodeDir, ...current.split(delimiter).filter((part) => part && !same(part))].join(delimiter)
  for (const key of keys) process.env[key] = next
}
