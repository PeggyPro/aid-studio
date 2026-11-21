import { spawn } from 'node:child_process';
import { BuildError } from './errors.mjs';
import path from 'node:path';
import { validateJob } from './contract.mjs';

/** Infrastructure configuration only. None of these values come from model output.
 * Output remains in a size-bounded tmpfs until the controller validates and copies it.
 * No host output directory or daemon socket is exposed to generated source. */
export function containerArguments(job, { image, inputDirectory, name, memoryMiB = 2048,
  cpus = 2, pids = 128, outputMiB = 128, tempMiB = 128 }) {
  validateJob(job);
  if (typeof image !== 'string' || !/^[a-zA-Z0-9./:_-]+@sha256:[a-f0-9]{64}$/.test(image))
    throw new BuildError('SANDBOX_IMAGE_UNPINNED', '执行镜像必须使用已核验的固定摘要');
  if (typeof name !== 'string' || !/^aid-scene-[a-z0-9-]{1,80}$/.test(name))
    throw new BuildError('INVALID_CONTAINER_NAME', '容器标识无效');
  if (typeof inputDirectory !== 'string' || !path.isAbsolute(inputDirectory) || /[,\r\n\0]/.test(inputDirectory))
    throw new BuildError('INVALID_SANDBOX_INPUT', '隔离输入目录无效');
  for (const [key, value] of Object.entries({ memoryMiB, cpus, pids, outputMiB, tempMiB }))
    if (!Number.isSafeInteger(value) || value < 1)
      throw new BuildError('INVALID_LIMITS', `${key} 必须为正整数`);
  if (outputMiB + tempMiB >= memoryMiB)
    throw new BuildError('INVALID_LIMITS', '临时文件限额必须为引擎保留运行内存');
  const entry = `/input/${job.entry}`;
  const command = job.engine === 'threejs'
    ? ['node', '/opt/aid/src/three-runner.mjs', entry, '/output/scene.glb']
    : ['blender', '--background', '--factory-startup', '--disable-autoexec', '--threads', String(cpus),
      '--python-exit-code', '1', '--python', '/opt/aid/src/blender-runner.py', '--', entry,
      '/output/scene.glb', String(job.keepBlend === true)];
  return ['create', '--name', name, '--pull', 'never', '--network', 'none', '--read-only',
    '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--user', '65532:65532',
    '--memory', `${memoryMiB}m`, '--memory-swap', `${memoryMiB}m`, '--cpus', String(cpus),
    '--pids-limit', String(pids), '--init', '--log-driver', 'none', '--workdir', '/input',
    '--mount', `type=bind,source=${inputDirectory},target=/input,readonly`,
    '--tmpfs', `/output:rw,noexec,nosuid,nodev,size=${outputMiB}m,uid=65532,gid=65532,mode=0700`,
    '--tmpfs', `/tmp:rw,noexec,nosuid,nodev,size=${tempMiB}m,uid=65532,gid=65532,mode=0700`,
    '--env', 'HOME=/tmp', '--env', 'PYTHONNOUSERSITE=1', '--env', 'PYTHONUTF8=1',
    '--entrypoint', command[0], image, ...command.slice(1)];
}

// Local acceptance never follows DOCKER_HOST or a user's remote Docker context.
export async function inspectLocalSandbox({ executable = 'docker', timeoutMs = 5000 } = {}) {
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 30000)
    throw new BuildError('INVALID_LIMITS', '隔离环境检查超时配置无效');
  const endpoint = process.platform === 'win32'
    ? 'npipe:////./pipe/docker_engine' : 'unix:///var/run/docker.sock';
  const env = {};
  for (const key of ['PATH', 'SystemRoot', 'WINDIR', 'HOME', 'USERPROFILE'])
    if (process.env[key]) env[key] = process.env[key];
  const result = await new Promise(resolve => {
    const child = spawn(executable, ['--host', endpoint, 'info', '--format', '{{json .}}'],
      { env, shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '', overflow = false, errorCode;
    const append = chunk => {
      if (output.length + chunk.length > 65536) { overflow = true; child.kill(); return; }
      output += chunk.toString('utf8');
    };
    child.stdout.on('data', append);
    // Never expose daemon diagnostics: they can include internal registry configuration.
    child.stderr.resume();
    child.on('error', error => { errorCode = error.code; });
    let timedOut = false;
    const timer = setTimeout(() => { timedOut = true; child.kill(); }, timeoutMs);
    child.on('close', code => {
      clearTimeout(timer);
      resolve({ code, output, overflow, timedOut, errorCode });
    });
  });
  const unavailable = reason => ({ available: false, reason, endpoint, fallbackAllowed: false });
  if (result.errorCode) return unavailable(result.errorCode === 'ENOENT' ? 'SANDBOX_NOT_INSTALLED' : 'SANDBOX_START_FAILED');
  if (result.timedOut) return unavailable('SANDBOX_PROBE_TIMEOUT');
  if (result.overflow || result.code !== 0) return unavailable('SANDBOX_DAEMON_UNAVAILABLE');
  let info;
  try { info = JSON.parse(result.output); } catch { return unavailable('SANDBOX_INVALID_RESPONSE'); }
  if (info.OSType !== 'linux') return unavailable('SANDBOX_LINUX_REQUIRED');
  if (!Array.isArray(info.SecurityOptions) || !info.SecurityOptions.some(x => /^name=seccomp(?:,|$)/.test(x)))
    return unavailable('SANDBOX_SECCOMP_REQUIRED');
  return { available: true, endpoint, serverVersion: String(info.ServerVersion ?? ''),
    executionVerified: false, fallbackAllowed: false };
}

export function requireSandboxReady(report) {
  if (report?.available !== true || report.executionVerified !== true)
    throw new BuildError('SANDBOX_REQUIRED', '隔离执行环境尚未通过实际验证，不能执行生成源码');
}
