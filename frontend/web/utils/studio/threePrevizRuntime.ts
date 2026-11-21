import * as THREE from 'three'
import type { PrevizNode } from '@/types/previz'
import type { ThreePrevizDirectorLayer } from '@/types/threePreviz'
import { DirectorMannequinRuntimeCache, applyDirectorMannequinPose, findDirectorMannequinModelRoot } from '@/components/studio/director/directorMannequinRuntime'
import { DirectorModelRuntimeCache } from '@/components/studio/director/directorModelLoader'
import { buildDirectorProp } from '@/components/studio/director/directorPrimitiveRuntime'
import { cloneDirectorScenery, findDirectorPart, loadDirectorPackage } from '@/components/studio/director/directorScenePackageLoader'
import { disposeDirectorObjectTree } from '@/components/studio/director/directorRuntimeResources'
import { applyDirectorTimelineAt } from './directorTimeline'
import { applyDirectorPanoramaTransform, resolveDirectorPanoramaLoadUrl } from '@/components/studio/director/directorPanorama'
import { applyDirectorPanoramaTexture, createDirectorPanoramaSphere, loadDirectorPanoramaBitmapTexture } from '@/components/studio/director/directorPanoramaRuntime'

function closePanoramaBitmap(texture?: THREE.Texture | null) {
  const image = texture?.image as { close?: () => void } | undefined
  image?.close?.()
}

/** Shared actor/model/path evaluator used by modeling previews and every native export. */
export class ThreePrevizDirectorRuntime {
  private layer?: ThreePrevizDirectorLayer
  private readonly models = new DirectorModelRuntimeCache()
  private readonly mannequins = new DirectorMannequinRuntimeCache()
  private readonly packages = new Map<string, Promise<THREE.Group>>()
  readonly environment = new THREE.Group()
  private readonly panorama = createDirectorPanoramaSphere()
  private panoramaUrl = ''
  private evaluated?: ReturnType<typeof applyDirectorTimelineAt>
  private evaluatedFrame = -1

  async preparePanorama(layer: ThreePrevizDirectorLayer | undefined) {
    const url = layer?.scene.panoramaUrl ? resolveDirectorPanoramaLoadUrl(layer.scene.panoramaUrl) : ''
    const texture = url ? url === this.panoramaUrl ? this.panorama.material.map : await loadDirectorPanoramaBitmapTexture(url) : null
    return { url, texture }
  }

  discardPanorama(value?: { url: string; texture: THREE.Texture | null }) {
    const texture = value?.texture
    if (texture && texture !== this.panorama.material.map) { texture.dispose(); closePanoramaBitmap(texture) }
  }

  setLayer(layer: ThreePrevizDirectorLayer | undefined, panorama?: { url: string; texture: THREE.Texture | null }) {
    this.layer = layer
    this.evaluatedFrame = -1
    for (const child of [...this.environment.children]) if (child !== this.panorama.mesh) { this.environment.remove(child); disposeDirectorObjectTree(child) }
    for (const light of layer?.scene.lights ?? []) if (light.kind === 'ambient' && light.enabled) this.environment.add(new THREE.AmbientLight(light.color, light.intensity))
    if (panorama) {
      const previous = this.panorama.material.map
      applyDirectorPanoramaTexture(this.panorama.material, panorama.texture)
      if (previous && previous !== panorama.texture) closePanoramaBitmap(previous)
      this.panoramaUrl = panorama.url
      this.panorama.mesh.visible = !!panorama.texture
      applyDirectorPanoramaTransform(this.panorama.mesh, layer?.scene.panoramaRadius, layer?.scene.panoramaYaw)
      if (panorama.texture) this.environment.add(this.panorama.mesh)
      else this.panorama.mesh.removeFromParent()
    }
  }

  signature(node: PrevizNode, layer = this.layer): string {
    const binding = layer?.bindings[node.id]
    if (binding?.kind !== 'object') return ''
    const scene = layer!.scene
    const object = scene.objects.find(object => object.id === binding.id)
    const asset = scene.modelAssets?.find(asset => asset.id === object?.modelAssetId)
    return JSON.stringify([object?.kind, object?.shape, object?.color, object?.modelAssetId, asset?.url, asset?.bundle, object?.actorMeta?.heightMeters, object?.scenery, scene.scenePackage, scene.scenePackages])
  }

  async create(node: PrevizNode, layer = this.layer): Promise<THREE.Object3D | undefined> {
    const binding = layer?.bindings[node.id]
    if (binding?.kind !== 'object') return undefined
    const scene = layer!.scene
    const object = scene.objects.find(object => object.id === binding.id)
    if (!object) throw new Error('3D预演对象绑定已失效')
    let result: THREE.Object3D
    const asset = scene.modelAssets?.find(asset => asset.id === object.modelAssetId)
    if (asset) result = await this.models.load(asset, object)
    else if (object.kind === 'actor') result = await this.mannequins.load(object, false)
    else if (object.kind === 'scenery' && object.scenery) {
      const packageRef = [scene.scenePackage, ...(scene.scenePackages ?? [])].find(pkg => pkg?.mediaId === object.scenery!.scenePackageMediaId)
      if (!packageRef) throw new Error(`场景部件 ${object.name} 缺少原模型包`)
      let pending = this.packages.get(packageRef.url)
      if (!pending) { pending = loadDirectorPackage(packageRef.url, packageRef.bundle); this.packages.set(packageRef.url, pending); void pending.catch(() => this.packages.delete(packageRef.url)) }
      const source = await pending
      const part = findDirectorPart(source, object.scenery.meshPath)
      if (!part) throw new Error(`场景包缺少部件 ${object.name}`)
      result = cloneDirectorScenery(part, object, false)
    } else result = buildDirectorProp(object, false)
    result.userData.previzNodeId = node.id
    result.userData.threePrevizDirector = true
    return result
  }

  evaluate(node: PrevizNode, frame: number): PrevizNode {
    const binding = this.layer?.bindings[node.id]
    if (!binding) return node
    if (frame !== this.evaluatedFrame) {
      this.evaluated = applyDirectorTimelineAt(this.layer!.scene, frame / 30)
      this.evaluatedFrame = frame
    }
    const scene = this.evaluated!
    if (binding.kind === 'object') {
      const object = scene.objects.find(object => object.id === binding.id)
      return object ? { ...node, localTransform: object.transform } : node
    }
    if (binding.kind === 'camera') {
      const camera = binding.id === 'main-camera' ? scene.camera : scene.cameraBookmarks?.find(camera => camera.id === binding.id)?.camera
      return camera ? { ...node, localTransform: { ...node.localTransform, position: camera.position }, camera: { ...node.camera!, target: camera.target, fov: camera.fov, roll: (camera.roll ?? 0) * Math.PI / 180 } } : node
    }
    return node
  }

  pose(node: PrevizNode, object: THREE.Object3D) {
    const binding = this.layer?.bindings[node.id]
    if (binding?.kind !== 'object') return
    const actor = this.evaluated?.objects.find(item => item.id === binding.id)
    const root = findDirectorMannequinModelRoot(object)
    if (root && actor?.kind === 'actor') applyDirectorMannequinPose(root, actor.pose, root.userData.directorRestPose)
  }

  dispose() {
    this.models.dispose()
    this.mannequins.dispose()
    this.packages.forEach(promise => { void promise.then(disposeDirectorObjectTree).catch(() => undefined) })
    this.packages.clear()
    const texture = this.panorama.material.map
    applyDirectorPanoramaTexture(this.panorama.material, null)
    closePanoramaBitmap(texture)
    this.panorama.mesh.removeFromParent()
    this.panorama.mesh.geometry.dispose()
    this.panorama.material.dispose()
    disposeDirectorObjectTree(this.environment)
  }
}
