import fs from 'node:fs/promises';
import { BuildError } from './errors.mjs';
import { DEFAULT_LIMITS } from './contract.mjs';

export async function inspectGlb(file, limits = DEFAULT_LIMITS) {
  const stat = await fs.lstat(file);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size > limits.glbBytes)
    throw new BuildError('ARTIFACT_LIMIT', 'GLB 文件无效或超过大小限制');
  const data = await fs.readFile(file);
  if (data.length < 20 || data.readUInt32LE(0) !== 0x46546c67 || data.readUInt32LE(4) !== 2
      || data.readUInt32LE(8) !== data.length) throw new BuildError('INVALID_GLB', 'GLB 文件头无效');
  let json;
  let chunks = 0;
  for (let offset = 12; offset < data.length;) {
    if (offset + 8 > data.length) throw new BuildError('INVALID_GLB', 'GLB 数据块不完整');
    const length = data.readUInt32LE(offset); const type = data.readUInt32LE(offset + 4);
    if (length % 4 || offset + 8 + length > data.length) throw new BuildError('INVALID_GLB', 'GLB 数据块越界');
    if (chunks++ === 0 && type !== 0x4e4f534a) throw new BuildError('INVALID_GLB', 'GLB 首块必须为 JSON');
    if (type === 0x4e4f534a) {
      if (json) throw new BuildError('INVALID_GLB', 'GLB 包含重复 JSON 块');
      try { json = JSON.parse(data.subarray(offset + 8, offset + 8 + length).toString('utf8')); }
      catch { throw new BuildError('INVALID_GLB', 'GLB JSON 无效'); }
    }
    offset += 8 + length;
  }
  if (json?.asset?.version !== '2.0') throw new BuildError('INVALID_GLB', 'GLB 版本无效');
  if ([...(json.buffers ?? []), ...(json.images ?? [])].some(x => x.uri && !x.uri.startsWith('data:')))
    throw new BuildError('EXTERNAL_ASSET_UNRESOLVED', '交付 GLB 仍引用外部文件');
  const meshes = (json.meshes ?? []).map(mesh => (mesh.primitives ?? []).reduce((sum, p) => {
    const count = json.accessors?.[p.indices ?? p.attributes?.POSITION]?.count;
    if (!Number.isSafeInteger(count) || count < 0) throw new BuildError('INVALID_GLB', '几何数量无效');
    const mode = p.mode ?? 4;
    if (mode === 4 && count % 3) throw new BuildError('INVALID_GLB', '三角面索引数量无效');
    return sum + (mode === 4 ? count / 3 : mode === 5 || mode === 6 ? Math.max(0,count - 2) : 0);
  },0));
  let sceneTriangles = 0;
  const ids = new Set();
  for (const node of json.nodes ?? []) {
    const id = node.extras?.hf_id;
    if (typeof id !== 'string' || !id.trim() || ids.has(id))
      throw new BuildError('INVALID_OBJECT_ID', '导出对象必须具有非空且唯一的 hf_id');
    ids.add(id);
    if (node.mesh === undefined) continue;
    if (meshes[node.mesh] === undefined) throw new BuildError('INVALID_GLB', '网格引用无效');
    const attributes = node.extensions?.EXT_mesh_gpu_instancing?.attributes;
    const indices = attributes ? Object.values(attributes) : [];
    const counts = indices.map(index => json.accessors?.[index]?.count);
    if (counts.some(n=>!Number.isSafeInteger(n)||n<0) || new Set(counts).size > 1)
      throw new BuildError('INVALID_GLB', '实例数量无效');
    sceneTriangles += meshes[node.mesh] * (counts[0] ?? 1);
  }
  if (sceneTriangles > limits.triangles) throw new BuildError('ARTIFACT_LIMIT', '场景实例展开后的三角面数量超过限制');
  const nodes=json.nodes??[],reachable=new Set(),pending=[...(json.scenes?.[json.scene??0]?.nodes??[])];
  while(pending.length){const index=pending.pop();if(!Number.isInteger(index)||index<0||index>=nodes.length||reachable.has(index))continue;reachable.add(index);for(const child of nodes[index].children??[])pending.push(child);}
  const sceneCameras=[...reachable].map(index=>nodes[index]).filter(node=>Number.isInteger(node.camera)
    &&['perspective','orthographic'].includes(json.cameras?.[node.camera]?.type));
  // Publish only usable identifiers; a partial inventory must never become an exclusive enum.
  const cameraOptions=sceneCameras.filter(node=>node.extras.hf_id.length<=200).slice(0,32)
    .map(node=>({id:node.extras.hf_id,name:String(node.name??node.extras.hf_id).slice(0,200),projection:json.cameras[node.camera].type}));
  return { glbBytes: data.length, uniqueMeshTriangles: meshes.reduce((a,b)=>a+b,0),
    sceneTriangles, objectCount: (json.nodes ?? []).length, animationCount: (json.animations ?? []).length,
    cameraOptions,cameraOptionsComplete:sceneCameras.length<=32&&sceneCameras.every(node=>node.extras.hf_id.length<=200),
    cameraCount: (json.cameras ?? []).length, lightCount: (json.extensions?.KHR_lights_punctual?.lights ?? []).length };
}
