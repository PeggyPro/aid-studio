import * as THREE from 'three'
import { FBXLoader } from 'three/examples/jsm/loaders/FBXLoader.js'
import { OBJLoader } from 'three/examples/jsm/loaders/OBJLoader.js'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { clone as cloneSkeleton } from 'three/examples/jsm/utils/SkeletonUtils.js'
import type { DirectorModelAsset, DirectorModelAssetKind, DirectorModelFormat } from '@/types/studio'
import { studioId } from '@/utils/studio/studioGraph'
import { isDirectorBuiltinModelUrl } from '@/utils/studio/directorModelCatalog'
import { disposeDirectorObjectTree } from './directorRuntimeResources'
import { loadCanvasModelBundle } from '@/utils/canvas/canvasModelBundle'
import { DIRECTOR_MANNEQUIN_ASSET_ID } from '@/config/directorMannequin'
import { createDirectorMannequinInstance } from './directorMannequinRuntime'
import type { DirectorObject } from '@/types/studio'

export const MAX_DIRECTOR_MODEL_FILE_BYTES = 20 * 1024 * 1024

export type DirectorModelSourceLoader = (asset: DirectorModelAsset) => Promise<THREE.Object3D>
export type DirectorModelFetcher = (url: string) => Promise<Response>

const MODEL_EXTENSION_PATTERN = /\.(fbx|obj|glb)$/i
export const DIRECTOR_MODEL_FILE_ACCEPT = '.glb,.fbx,.obj'

export function validateDirectorModelFile(file: Pick<File, 'name' | 'size'>): DirectorModelFormat {
  const extension = file.name.match(MODEL_EXTENSION_PATTERN)?.[1]?.toLowerCase()
  if (extension !== 'fbx' && extension !== 'obj' && extension !== 'glb') {
    throw new Error('仅支持 GLB、FBX、OBJ 模型')
  }
  if (file.size <= 0) throw new Error('模型文件内容为空')
  if (file.size > MAX_DIRECTOR_MODEL_FILE_BYTES) throw new Error('模型文件不能超过 20MB')
  return extension
}

export function createDirectorModelAsset(
  file: Pick<File, 'name' | 'size'>,
  url: string,
  kind: DirectorModelAssetKind
): DirectorModelAsset {
  const format = validateDirectorModelFile(file)
  return {
    id: studioId('director-model'),
    name: file.name.replace(MODEL_EXTENSION_PATTERN, ''),
    fileName: file.name,
    url,
    format,
    kind,
    source: 'local',
    byteSize: file.size,
    createdAt: new Date().toISOString()
  }
}

/** 上传 OSS 前先在本地解析一次，避免扩展名正确但内容损坏的模型进入资产库。 */
export async function validateDirectorModelContents(file: Pick<File, 'name' | 'size' | 'arrayBuffer' | 'text'>): Promise<void> {
  const format = validateDirectorModelFile(file)
  let source: THREE.Object3D
  try {
    source = await parseDirectorModel(format, await file.arrayBuffer())
  } catch (error) {
    throw new Error(error instanceof DirectorGlbError ? error.message : '模型文件无法解析，请确认文件未损坏')
  }
  let hasGeometry = false
  source.traverse((child) => {
    if (child instanceof THREE.Mesh && (child.geometry.getAttribute('position')?.count ?? 0) > 0) hasGeometry = true
  })
  disposeModelObject(source)
  if (!hasGeometry) throw new Error('模型文件中没有可用的三维几何体')
}

export class DirectorModelRuntimeCache {
  private readonly sources = new Map<string, Promise<THREE.Object3D>>()
  private disposed = false

  constructor(private readonly sourceLoader: DirectorModelSourceLoader = loadModelSource) {}

  async load(asset: DirectorModelAsset, actor?: DirectorObject): Promise<THREE.Group> {
    if (this.disposed) throw new Error('模型加载器已释放')
    const cacheKey = modelCacheKey(asset)
    let sourcePromise = this.sources.get(cacheKey)
    if (!sourcePromise) {
      sourcePromise = this.sourceLoader(asset)
      sourcePromise.catch(() => {
        if (this.sources.get(cacheKey) === sourcePromise) this.sources.delete(cacheKey)
      })
      this.sources.set(cacheKey, sourcePromise)
    }
    const source = await sourcePromise
    if (this.disposed) throw new Error('模型加载器已释放')
    if (this.sources.get(cacheKey) !== sourcePromise) throw new Error('模型资源已从缓存移除')
    if (actor && asset.id === DIRECTOR_MANNEQUIN_ASSET_ID && asset.source === 'builtin') return createDirectorMannequinInstance(source, actor, false)
    return normalizeImportedModel(cloneModelSource(source), asset.source === 'builtin' || asset.source === 'library')
  }

  retainOnly(assets: readonly DirectorModelAsset[]): void {
    if (this.disposed) return
    const retainedKeys = new Set(assets.map(modelCacheKey))
    for (const [cacheKey, source] of this.sources) {
      if (retainedKeys.has(cacheKey)) continue
      this.sources.delete(cacheKey)
      void source.then(disposeModelObject).catch(() => undefined)
    }
  }

  dispose(): void {
    if (this.disposed) return
    this.disposed = true
    this.sources.forEach((source) => {
      void source.then(disposeModelObject).catch(() => undefined)
    })
    this.sources.clear()
  }
}

async function loadModelSource(asset: DirectorModelAsset): Promise<THREE.Object3D> {
  if (asset.bundle) return loadCanvasModelBundle(asset.bundle)
  const bytes = await downloadDirectorModelBytes(asset.url)
  let source: THREE.Object3D
  try {
    source = await parseDirectorModel(asset.format, toArrayBuffer(bytes))
  } catch (error) {
    throw new Error(error instanceof DirectorGlbError ? error.message : '远程模型无法解析，请确认资源未损坏')
  }
  if (!hasModelGeometry(source)) {
    disposeModelObject(source)
    throw new Error('远程模型中没有可用的三维几何体')
  }
  return source
}

function modelCacheKey(asset: DirectorModelAsset): string {
  return `${asset.format}:${asset.url}`
}

function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const buffer = new ArrayBuffer(bytes.byteLength)
  new Uint8Array(buffer).set(bytes)
  return buffer
}

export async function downloadDirectorModelBytes(
  url: string,
  fetcher: DirectorModelFetcher = (source) => fetch(source),
  maximumBytes = MAX_DIRECTOR_MODEL_FILE_BYTES
): Promise<Uint8Array> {
  assertRemoteModelUrl(url)
  let response: Response
  try {
    response = await fetcher(url)
  } catch {
    throw new Error('远程模型下载失败')
  }
  if (!response.ok) throw new Error(`远程模型下载失败（HTTP ${response.status}）`)
  if (response.url) assertRemoteModelUrl(response.url)
  const declaredLength = Number(response.headers.get('content-length'))
  if (Number.isFinite(declaredLength) && declaredLength > maximumBytes) {
    throw new Error('远程模型不能超过 20MB')
  }
  if (!response.body) {
    const bytes = new Uint8Array(await response.arrayBuffer())
    if (bytes.byteLength > maximumBytes) throw new Error('远程模型不能超过 20MB')
    return bytes
  }
  const reader = response.body.getReader()
  const chunks: Uint8Array[] = []
  let total = 0
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      total += value.byteLength
      if (total > maximumBytes) {
        await reader.cancel()
        throw new Error('远程模型不能超过 20MB')
      }
      chunks.push(value)
    }
  } finally {
    reader.releaseLock()
  }
  const bytes = new Uint8Array(total)
  let offset = 0
  chunks.forEach((chunk) => {
    bytes.set(chunk, offset)
    offset += chunk.byteLength
  })
  return bytes
}

function assertRemoteModelUrl(value: string): void {
  if (isDirectorBuiltinModelUrl(value)) return
  let url: URL
  try {
    url = new URL(value)
  } catch {
    throw new Error('远程模型地址无效')
  }
  if ((url.protocol !== 'http:' && url.protocol !== 'https:') || url.username || url.password) {
    throw new Error('远程模型地址仅支持无凭据的 HTTP(S) URL')
  }
}

function cloneModelSource(source: THREE.Object3D): THREE.Object3D {
  const cloned = cloneSkeleton(source)
  cloned.traverse((child) => {
    if (!(child instanceof THREE.Mesh)) return
    child.geometry = child.geometry.clone()
    child.material = Array.isArray(child.material)
      ? child.material.map(cloneMaterial)
      : cloneMaterial(child.material)
  })
  return cloned
}

function cloneMaterial(material: THREE.Material): THREE.Material {
  const cloned = material.clone()
  const record = cloned as unknown as Record<string, unknown>
  for (const [key, value] of Object.entries(record)) {
    if (value instanceof THREE.Texture) record[key] = value.clone()
  }
  return cloned
}

function normalizeImportedModel(source: THREE.Object3D, preserveScale = false): THREE.Group {
  const root = new THREE.Group()
  root.name = 'director-imported-model'
  root.add(source)

  source.traverse((child) => {
    if (!(child instanceof THREE.Mesh)) return
    child.castShadow = true
    child.receiveShadow = true
  })

  const bounds = new THREE.Box3().setFromObject(source)
  if (bounds.isEmpty()) return root
  const size = bounds.getSize(new THREE.Vector3())
  const maxDimension = Math.max(size.x, size.y, size.z)
  if (!Number.isFinite(maxDimension) || maxDimension <= 0) return root

  if (!preserveScale) source.scale.multiplyScalar(2 / maxDimension)
  const normalizedBounds = new THREE.Box3().setFromObject(source)
  const center = normalizedBounds.getCenter(new THREE.Vector3())
  source.position.add(new THREE.Vector3(-center.x, -normalizedBounds.min.y, -center.z))
  return root
}

class DirectorGlbError extends Error {}

/** Both the pre-upload gate and runtime use the same parser and resource boundary. */
async function parseDirectorModel(format: DirectorModelFormat, bytes: ArrayBuffer): Promise<THREE.Object3D> {
  if (format === 'fbx') return new FBXLoader().parse(bytes, '')
  if (format === 'obj') return new OBJLoader().parse(new TextDecoder().decode(bytes))
  if (format !== 'glb') throw new Error('不支持的模型格式')
  inspectDirectorGlb(bytes)
  const manager = new THREE.LoadingManager()
  manager.setURLModifier(url => {
    if (!/^(blob:|data:)/i.test(url)) throw new DirectorGlbError('GLB 必须内嵌纹理和缓冲区，请重新导出为单文件模型')
    return url
  })
  return (await new GLTFLoader(manager).parseAsync(bytes, '')).scene
}

/** A single-file import cannot safely restore external sidecar files after upload. */
export function inspectDirectorGlb(bytes: ArrayBuffer): void {
  if (bytes.byteLength < 20) throw new DirectorGlbError('GLB 文件损坏，请导出有效的 GLB 2.0 模型')
  const view = new DataView(bytes)
  const jsonLength = view.getUint32(12, true)
  if (view.getUint32(0, true) !== 0x46546c67 || view.getUint32(4, true) !== 2
    || view.getUint32(8, true) !== bytes.byteLength || view.getUint32(16, true) !== 0x4e4f534a
    || jsonLength < 4 || jsonLength % 4 !== 0 || 20 + jsonLength > bytes.byteLength) {
    throw new DirectorGlbError('GLB 文件损坏，请导出有效的 GLB 2.0 模型')
  }
  const json = JSON.parse(new TextDecoder().decode(new Uint8Array(bytes, 20, jsonLength))) as {
    asset?: { version?: string }; buffers?: Array<{ uri?: string }>; images?: Array<{ uri?: string }>; extensionsRequired?: string[]
  }
  if (json.asset?.version !== '2.0') throw new DirectorGlbError('仅支持 GLB 2.0 模型')
  if ([...(json.buffers ?? []), ...(json.images ?? [])].some(resource => resource.uri && !/^data:/i.test(resource.uri))) {
    throw new DirectorGlbError('GLB 必须内嵌纹理和缓冲区，请重新导出为单文件模型')
  }
  if (json.extensionsRequired?.some(extension => ['KHR_draco_mesh_compression', 'KHR_texture_basisu', 'EXT_meshopt_compression'].includes(extension))) {
    throw new DirectorGlbError('GLB 使用未支持的压缩格式，请导出为标准未压缩 GLB')
  }
}

function hasModelGeometry(source: THREE.Object3D): boolean {
  let hasGeometry = false
  source.traverse((child) => {
    if (child instanceof THREE.Mesh && (child.geometry.getAttribute('position')?.count ?? 0) > 0) hasGeometry = true
  })
  return hasGeometry
}

function disposeModelObject(root: THREE.Object3D): void {
  disposeDirectorObjectTree(root)
}
