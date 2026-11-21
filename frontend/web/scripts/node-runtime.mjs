import { spawnSync } from 'child_process'
import { readFileSync, realpathSync } from 'fs'
import { dirname, delimiter } from 'path'

const project = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'))

/** The same toolchain applies to install, dev, build and nested Node workers. */
export function assertNodeVersion() {
  const expectedNode = project.engines.node
  const expectedNpm = project.engines.npm
  const nvmVersion = readFileSync(new URL('../.nvmrc', import.meta.url), 'utf8').trim()
  if (nvmVersion !== expectedNode || project.packageManager !== `npm@${expectedNpm}`
    || project.devEngines.runtime.version !== expectedNode
    || project.devEngines.packageManager.version !== expectedNpm) {
    throw new Error('[aid-pc] Node/npm 版本声明不一致，请同步 package.json 与 .nvmrc。')
  }
  const help = `请切换到 Node ${expectedNode} / npm ${expectedNpm}，例如 nvm install ${expectedNode} 后 nvm use ${expectedNode}，再重新打开终端执行 npm ci。`
  if (process.versions.node !== expectedNode) {
    throw new Error(`[aid-pc] 当前 Node ${process.version}，项目固定使用 Node ${expectedNode}。${help}`)
  }
  // Direct Node invocations do not use npm; npm lifecycle invocations identify their CLI here.
  const userAgent = process.env.npm_config_user_agent
  if (userAgent && !userAgent.startsWith(`npm/${expectedNpm} `)) {
    throw new Error(`[aid-pc] 当前包管理器 ${userAgent.split(' ')[0]}，项目固定使用 npm ${expectedNpm}。${help}`)
  }
}

/** Turbopack starts PostCSS workers by name, rather than process.execPath. */
export function nodeRuntimeEnv(source = process.env) {
  assertNodeVersion()
  const env = { ...source }
  const keys = Object.keys(env).filter(key => process.platform === 'win32' ? key.toLowerCase() === 'path' : key === 'PATH').sort()
  const inheritedPath = keys.length ? env[keys[0]] : ''
  for (const key of keys) delete env[key]
  env[process.platform === 'win32' ? 'Path' : 'PATH'] = dirname(process.execPath) + (inheritedPath ? delimiter + inheritedPath : '')
  return env
}

export function prepareNodeRuntime(cwd, source = process.env) {
  const env = nodeRuntimeEnv(source)
  const probe = spawnSync('node', ['-p', 'JSON.stringify({execPath:process.execPath,version:process.version})'], {
    cwd, env, encoding: 'utf8', windowsHide: true, timeout: 10000
  })
  let child
  try { child = JSON.parse(probe.stdout || '') } catch { /* Report a single actionable startup error below. */ }
  const canonical = value => {
    const path = realpathSync(value)
    return process.platform === 'win32' ? path.toLowerCase() : path
  }
  if (probe.error || probe.status !== 0 || !child || child.version !== process.version || canonical(child.execPath) !== canonical(process.execPath)) {
    throw new Error('[aid-pc] 子进程未能使用当前 Node。请检查项目目录中的 node 可执行文件及终端运行环境，再重新启动；开发服务尚未启动。')
  }
  return env
}
