import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { DEFAULT_LIMITS } from './contract.mjs';
import { BuildError } from './errors.mjs';

const prohibited = new Set(['node_modules', '.git', 'package.json', 'package-lock.json']);
export async function readSnapshot(root, limits = DEFAULT_LIMITS) {
  const base = await fs.realpath(root);
  const files = [];
  let total = 0;
  let visited = 0;
  async function visit(relative = '') {
    if (relative.split('/').length > 16) throw new BuildError('SOURCE_LIMIT', '源码目录层级超限');
    const entries = await fs.readdir(path.join(base, relative), { withFileTypes: true });
    entries.sort((a,b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0);
    for (const item of entries) {
      if (++visited > limits.files * 17) throw new BuildError('SOURCE_LIMIT', '源码目录数量超限');
      const name = relative ? `${relative}/${item.name}` : item.name;
      if (prohibited.has(item.name.toLowerCase()) || item.name.includes(':') || item.name.includes('\\'))
        throw new BuildError('SOURCE_PATH_FORBIDDEN', `源码快照包含禁止路径：${name}`);
      const disk = path.join(base, ...name.split('/'));
      const stat = await fs.lstat(disk);
      if (stat.isSymbolicLink()) throw new BuildError('SOURCE_SYMLINK_FORBIDDEN', '源码快照不能包含链接');
      const resolved = await fs.realpath(disk);
      const inside = path.relative(base, resolved);
      if (inside.startsWith(`..${path.sep}`) || inside === '..' || path.isAbsolute(inside))
        throw new BuildError('SOURCE_PATH_FORBIDDEN', '源码路径越界');
      if (stat.isDirectory()) { await visit(name); continue; }
      if (!stat.isFile()) throw new BuildError('SOURCE_PATH_FORBIDDEN', '只支持普通文件');
      if (stat.size > limits.fileBytes) throw new BuildError('SOURCE_LIMIT', '单个源码文件超过限额');
      const data = await fs.readFile(disk);
      total += data.length;
      if (data.length > limits.fileBytes || total > limits.sourceBytes || files.length >= limits.files)
        throw new BuildError('SOURCE_LIMIT', '源码快照超过限额');
      files.push({ name, data });
    }
  }
  await visit();
  files.sort((a,b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0);
  const digest = createHash('sha256');
  for (const file of files) {
    const name = Buffer.from(file.name); const header = Buffer.alloc(8);
    header.writeUInt32BE(name.length); header.writeUInt32BE(file.data.length, 4);
    digest.update(header).update(name).update(file.data);
  }
  return { files, sha256: digest.digest('hex'), bytes: total };
}

export async function materializeSnapshot(snapshot, destination) {
  // Destination is created exclusively by the trusted worker, never supplied
  // by generated code or shared with another running task.
  await fs.mkdir(destination, { recursive: false });
  for (const { name, data } of snapshot.files) {
    const target = path.join(destination, ...name.split('/'));
    await fs.mkdir(path.dirname(target), { recursive: true });
    await fs.writeFile(target, data, { flag: 'wx' });
  }
}
