import { spawn } from 'child_process'
import { dirname, resolve } from 'path'
import { fileURLToPath } from 'url'
import { prepareNodeRuntime } from './node-runtime.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
let env
try { env = prepareNodeRuntime(root) } catch (error) {
  console.error(error.message)
  process.exit(1)
}
const child = spawn(process.execPath, [resolve(root, 'node_modules/next/dist/bin/next'), ...process.argv.slice(2)], {
  cwd: root, env, stdio: 'inherit'
})
child.on('error', error => { console.error(`[aid-pc] Next 启动失败：${error.message}`); process.exitCode = 1 })
child.on('exit', (code, signal) => {
  process.exitCode = signal === 'SIGINT' ? 130 : signal === 'SIGTERM' ? 143 : code === null ? 1 : code
})
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { if (!child.killed) child.kill(signal) })
