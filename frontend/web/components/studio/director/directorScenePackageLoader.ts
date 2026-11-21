import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { Group, Mesh, Texture, Vector3, Quaternion, Euler, type Object3D } from 'three'
import type { DirectorObject, DirectorSceneData, DirectorSpaceGenResult } from '@/types/studio'
import { disposeDirectorObjectTree } from './directorRuntimeResources'
import { DEFAULT_DIRECTOR_SCENE } from '@/config/studioMeta'
import type { CanvasModelBundle } from '@/types/canvas-api'
import { loadCanvasModelBundle } from '@/utils/canvas/canvasModelBundle'

export function resolveDirectorSuggestedCamera(
  scene: DirectorSceneData,
  suggested: DirectorSpaceGenResult['suggestedCamera'],
) {
  const defaults = DEFAULT_DIRECTOR_SCENE.camera
  const untouched =
    !scene.cameraBookmarks?.length &&
    scene.camera.fov === defaults.fov &&
    scene.camera.position.every((value, index) => value === defaults.position[index]) &&
    scene.camera.target.every((value, index) => value === defaults.target[index])
  const valid =
    suggested &&
    Array.isArray(suggested.position) &&
    suggested.position.length === 3 &&
    suggested.position.every(Number.isFinite) &&
    Array.isArray(suggested.target) &&
    suggested.target.length === 3 &&
    suggested.target.every(Number.isFinite) &&
    Number.isFinite(suggested.fov) &&
    suggested.fov > 0 &&
    suggested.fov < 180
  return untouched && valid ? structuredClone(suggested) : scene.camera
}

export function parseDirectorSpaceResult(input: unknown): DirectorSpaceGenResult {
  const value = typeof input === 'string' ? JSON.parse(input) : input
  if (!value || typeof value !== 'object') throw new Error('空间结果异常，请重试或联系支持')
  const r = value as DirectorSpaceGenResult
  if (
    !r.scenePackageMediaId ||
    !/^https:\/\//.test(r.scenePackageUrl) ||
    r.format !== 'glb' ||
    !Array.isArray(r.parts) ||
    !r.parts.length ||
    r.parts.some((p) => !p || typeof p.meshPath !== 'string' || !p.meshPath)
  )
    throw new Error('空间结果异常，请重试或联系支持')
  return { ...r, parts: r.parts.slice(0, 256), truncated: r.truncated || r.parts.length > 256 }
}

export async function loadDirectorPackage(url: string, bundle?: CanvasModelBundle): Promise<Group> {
  if (bundle) return loadCanvasModelBundle(bundle)
  if (!/^https:\/\//.test(url)) throw new Error('场景包必须使用 HTTPS 持久资源')
  const response = await fetch(url, { signal: AbortSignal.timeout(120000) })
  if (!response.ok) throw new Error('场景包下载失败')
  const limit = 200 * 1024 * 1024
  if (Number(response.headers.get('content-length')) > limit) throw new Error('场景包不能超过 200MB')
  const reader = response.body?.getReader()
  if (!reader) throw new Error('场景包下载失败')
  const chunks: Uint8Array<ArrayBuffer>[] = []
  let size = 0
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      size += value.byteLength
      if (size > limit) throw new Error('场景包不能超过 200MB')
      chunks.push(new Uint8Array(value))
    }
  } finally {
    await reader.cancel()
  }
  const bytes = await new Blob(chunks).arrayBuffer()
  // A scene package must be self-contained; external glTF dependencies are not part of this contract.
  const loader = new GLTFLoader()
  loader.manager.setURLModifier((resource) => {
    if (!resource.startsWith('blob:') && !resource.startsWith('data:')) throw new Error('场景包包含外部资源')
    return resource
  })
  return (await loader.parseAsync(bytes, '')).scene
}

export function findDirectorPart(root: Object3D, path: string): Object3D | undefined {
  if (!path.includes('/')) return root.getObjectByName(path)
  return path
    .split('/')
    .filter(Boolean)
    .reduce<Object3D | undefined>(
      (parent, name, index) =>
        index === 0 && name === root.name ? root : parent?.children.find((c) => c.name === name),
      root,
    )
}

export function cloneDirectorScenery(source: Object3D, object: DirectorObject, xray: boolean): Group {
  const root = new Group()
  const part = source.clone(true)
  part.position.set(0, 0, 0)
  part.rotation.set(0, 0, 0)
  part.scale.set(1, 1, 1)
  part.traverse((child) => {
    child.userData.objectId = object.id
    if (child instanceof Mesh) {
      child.geometry = child.geometry.clone()
      const copy = (material: import('three').Material) => {
        const value = material.clone()
        for (const [key, texture] of Object.entries(value)) if (texture instanceof Texture) (value as unknown as Record<string, unknown>)[key] = texture.clone()
        if (xray) { value.transparent = true; value.opacity = Math.min(value.opacity, 0.35); value.depthWrite = false }
        return value
      }
      child.material = Array.isArray(child.material) ? child.material.map(copy) : copy(child.material)
    }
  })
  root.add(part)
  root.userData.objectId = object.id
  root.position.fromArray(object.transform.position)
  root.rotation.set(...object.transform.rotation)
  root.scale.fromArray(object.transform.scale)
  return root
}
