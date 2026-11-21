import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';

export const MAX_MODEL_BYTES = 20 * 1024 * 1024;
export interface ModelGeometryInfo {
  width: number;
  height: number;
  depth: number;
  meshCount: number;
  vertexCount: number;
}
export function disposeModel(root: THREE.Object3D) {
  const textures = new Set<THREE.Texture>(),
    materials = new Set<THREE.Material>(),
    geometries = new Set<THREE.BufferGeometry>();
  root.traverse((object) => {
    if (!(object instanceof THREE.Mesh)) return;
    geometries.add(object.geometry);
    for (const material of Array.isArray(object.material) ? object.material : [object.material]) {
      materials.add(material);
      for (const value of Object.values(material)) if (value instanceof THREE.Texture) textures.add(value);
    }
  });
  textures.forEach((texture) => {
    const image = texture.source?.data;
    if (typeof ImageBitmap !== 'undefined' && image instanceof ImageBitmap) image.close();
    texture.dispose();
  });
  materials.forEach((material) => material.dispose());
  geometries.forEach((geometry) => geometry.dispose());
}
export async function parseModel(file: Blob) {
  if (!file.size || file.size > MAX_MODEL_BYTES) throw new Error('请选择不超过20MiB的GLB文件');
  const buffer = await file.arrayBuffer(),
    header = new DataView(buffer);
  if (
    buffer.byteLength < 20 ||
    header.getUint32(0, true) !== 0x46546c67 ||
    header.getUint32(4, true) !== 2 ||
    header.getUint32(8, true) !== buffer.byteLength ||
    header.getUint32(16, true) !== 0x4e4f534a
  )
    throw new Error('GLB文件头或版本无效');
  const length = header.getUint32(12, true);
  if (length > buffer.byteLength - 20) throw new Error('GLB内容不完整');
  const json = JSON.parse(new TextDecoder().decode(new Uint8Array(buffer, 20, length)));
  for (const item of [...(json.buffers ?? []), ...(json.images ?? [])]) {
    if (item.uri && !String(item.uri).startsWith('data:'))
      throw new Error('GLB必须内嵌全部材质和纹理，不能依赖外部文件');
  }
  const manager = new THREE.LoadingManager();
  manager.setURLModifier((url) => {
    if (url.startsWith('blob:') || url.startsWith('data:')) return url;
    throw new Error('模型包含外部依赖');
  });
  const loaded = await new GLTFLoader(manager).parseAsync(buffer, '');
  const model = loaded.scene;
  try {
    model.updateMatrixWorld(true);
    const bounds = new THREE.Box3().setFromObject(model, true),
      size = bounds.getSize(new THREE.Vector3());
    let meshCount = 0,
      vertexCount = 0;
    model.traverse((object) => {
      if (object instanceof THREE.Mesh) {
        meshCount++;
        vertexCount += object.geometry.getAttribute('position')?.count ?? 0;
      }
    });
    if (
      !vertexCount ||
      bounds.isEmpty() ||
      ![size.x, size.y, size.z].every(Number.isFinite) ||
      Math.max(size.x, size.y, size.z) <= 0
    )
      throw new Error('模型没有可预览的有效网格');
    return {
      model,
      bounds,
      info: { width: size.x, height: size.y, depth: size.z, meshCount, vertexCount } satisfies ModelGeometryInfo
    };
  } catch (error) {
    disposeModel(model);
    throw error;
  }
}
