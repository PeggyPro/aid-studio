import * as THREE from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { FBXLoader } from 'three/examples/jsm/loaders/FBXLoader.js'
import { OBJLoader } from 'three/examples/jsm/loaders/OBJLoader.js'
import { MTLLoader } from 'three/examples/jsm/loaders/MTLLoader.js'
import type { CanvasModelBundle } from '@/types/canvas-api'
import { fetchCanvasPublicationFile } from './canvasPublicationFiles'

/** A model has a closed resource set. Loaders cannot silently fetch an external URI. */
export async function loadCanvasModelBundle(
  bundle: CanvasModelBundle,
): Promise<THREE.Group> {
  const urls = new Map<string, string>(),
    blobs = new Map<string, Blob>()
  const known = new Set<string>()
  let bytes = 0
  try {
    if (!bundle.files.length || bundle.files.length > 256)
      throw new Error('模型依赖清单无效')
    for (const file of bundle.files) {
      const blob = await fetchCanvasPublicationFile(file.resourceUrl)
      bytes += blob.size
      if (blob.size > 64 * 1024 * 1024 || bytes > 256 * 1024 * 1024)
        throw new Error('模型依赖超过大小限制')
      const url = URL.createObjectURL(blob)
      urls.set(file.path, url)
      known.add(url)
      blobs.set(file.path, blob)
    }
    const root = blobs.get(bundle.rootPath)
    if (!root) throw new Error('模型根文件缺失')
    const aliases = new Map<string, Set<string>>()
    const add = (uri: string, path: string) => {
      const values = aliases.get(uri) || new Set<string>()
      values.add(path)
      aliases.set(uri, values)
    }
    for (const ref of bundle.references) {
      const prefix = ref.ownerPath.includes('/')
        ? ref.ownerPath.slice(0, ref.ownerPath.lastIndexOf('/') + 1)
        : ''
      add(ref.uri, ref.path)
      add(prefix + ref.uri, ref.path)
      if (bundle.format === 'fbx') {
        const uri = ref.uri.split('\\').pop()!
        add(uri, ref.path)
        add(prefix + uri, ref.path)
      }
    }
    const resolve = (uri: string) => {
      if (known.has(uri) || /^(blob:|data:)/.test(uri)) return uri
      const relative = uri.replace(/^canvas-bundle:\/\/\//, '')
      const mapped = aliases.get(relative) || aliases.get(uri)
      if (mapped && mapped.size !== 1) throw new Error('模型依赖路径有歧义')
      const path = mapped ? [...mapped][0] : relative
      const result = urls.get(path)
      if (!result) throw new Error('模型请求了未登记的外部依赖：' + relative)
      return result
    }
    const manager = new THREE.LoadingManager()
    manager.setURLModifier(resolve)
    let pending = false,
      failed = false
    let finish: (() => void) | undefined
    manager.onStart = () => {
      pending = true
    }
    manager.onLoad = () => {
      pending = false
      finish?.()
    }
    manager.onError = () => {
      failed = true
    }
    const prefix = bundle.rootPath.includes('/')
      ? bundle.rootPath.slice(0, bundle.rootPath.lastIndexOf('/') + 1)
      : ''
    let result: THREE.Group
    if (bundle.format === 'glb' || bundle.format === 'gltf')
      result = (
        await new GLTFLoader(manager).parseAsync(
          await root.arrayBuffer(),
          'canvas-bundle:///' + prefix,
        )
      ).scene
    else if (bundle.format === 'fbx')
      result = new FBXLoader(manager).parse(
        await root.arrayBuffer(),
        'canvas-bundle:///' + prefix,
      )
    else {
      const loader = new OBJLoader(manager),
        text = await root.text()
      let merged: MTLLoader.MaterialCreator | undefined
      for (const match of text.matchAll(/^mtllib\s+(.+)$/gm)) {
        const requested = prefix + match[1].trim(),
          mapped = aliases.get(requested) || aliases.get(match[1].trim())
        if (mapped && mapped.size !== 1) throw new Error('OBJ材质路径有歧义')
        const path = mapped ? [...mapped][0] : requested,
          mtl = blobs.get(path)
        if (!mtl) throw new Error('OBJ材质依赖缺失')
        const directory = path.includes('/')
          ? path.slice(0, path.lastIndexOf('/') + 1)
          : ''
        const materials = new MTLLoader(manager).parse(
          await mtl.text(),
          'canvas-bundle:///' + directory,
        )
        materials.preload()
        if (!merged) merged = materials
        else {
          Object.assign(merged.materials, materials.materials)
          Object.assign(merged.materialsInfo, materials.materialsInfo)
        }
      }
      if (merged) loader.setMaterials(merged)
      result = loader.parse(text)
    }
    if (pending)
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(
          () => reject(new Error('模型依赖读取超时')),
          30000,
        )
        finish = () => {
          clearTimeout(timer)
          resolve()
        }
      })
    if (failed) throw new Error('模型依赖读取失败')
    return result
  } finally {
    urls.forEach((url) => URL.revokeObjectURL(url))
  }
}
