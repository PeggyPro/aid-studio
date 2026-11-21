import path from 'node:path';
import { BuildError } from './errors.mjs';

export const PROTOCOL = 'aid.scene-build/1';
export const DEFAULT_LIMITS = Object.freeze({ files: 125, fileBytes: 2 * 1024 ** 2,
  sourceBytes: 8 * 1024 ** 2, glbBytes: 32 * 1024 ** 2, triangles: 250000,
  nativeProjectBytes: 128 * 1024 ** 2, timeoutMs: 120000, logBytes: 256 * 1024 });

export function validateJob(job) {
  const fail = (message) => { throw new BuildError('INVALID_BUILD_REQUEST', message); };
  if (!job || job.protocol !== PROTOCOL) fail('不支持的构建协议');
  if (Buffer.byteLength(JSON.stringify(job), 'utf8') > 32768) fail('任务描述超过32KB限制；源码请通过独立快照传输');
  for (const name of ['jobId', 'projectId', 'sceneId', 'sourceVersionId'])
    if (typeof job[name] !== 'string' || !/^[a-zA-Z0-9_-]{1,100}$/.test(job[name])) fail(`${name} 不合法`);
  if (!['threejs', 'blender'].includes(job.engine)) fail('必须明确选择 Three.js 或 Blender 引擎');
  // A tool call contains the approved plan's interpretation, not a direct user engine selector.
  if (!job.engineSelection || !['user', 'project', 'default', 'plan'].includes(job.engineSelection.origin))
    fail('缺少引擎选择依据');
  if (typeof job.sourceSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(job.sourceSha256)) fail('缺少源码快照指纹');
  if (typeof job.entry !== 'string' || !job.entry || job.entry.includes('\\') || job.entry.includes(':')
      || path.posix.isAbsolute(job.entry) || job.entry.split('/').some(x => !x || x === '.' || x === '..'))
    fail('入口必须为工程内相对路径');
  const ext = path.posix.extname(job.entry);
  if (!(job.engine === 'threejs' ? ['.js', '.mjs'] : ['.py']).includes(ext)) fail('入口扩展名与引擎不匹配');
  return job;
}

// Receives semantic intent from the authorized run context. A model/vendor
// name is intentionally not an input to engine resolution.
export function resolveEngine({ requestedEngine, projectEngine, defaultEngine = 'threejs' }) {
  const engine = requestedEngine ?? projectEngine ?? defaultEngine;
  if (!['threejs', 'blender'].includes(engine)) throw new BuildError('INVALID_ENGINE', '引擎选择不合法');
  return { engine, engineSelection: { origin: requestedEngine != null ? 'user' : projectEngine != null ? 'project' : 'default' } };
}
