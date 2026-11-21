import * as THREE from 'three'
import { applyDirectorPanoramaTransform } from './directorPanorama'

export function createDirectorPanoramaSphere() {
  const geometry = new THREE.SphereGeometry(1, 64, 40)
  const material = new THREE.MeshBasicMaterial({
    side: THREE.BackSide,
    depthWrite: false,
    fog: false,
    toneMapped: false
  })
  const mesh = new THREE.Mesh(geometry, material)
  mesh.name = 'director-panorama-sphere'
  mesh.frustumCulled = false
  mesh.renderOrder = -10
  mesh.visible = false
  applyDirectorPanoramaTransform(mesh)
  return { mesh, material }
}

export async function loadDirectorPanoramaBitmapTexture(url: string): Promise<THREE.Texture> {
  const response = await fetch(url)
  if (!response.ok) throw new Error('全景图加载失败')
  const blob = await response.blob()
  if (!blob.size) throw new Error('全景图内容为空')
  const bitmap = await createImageBitmap(blob)
  const texture = new THREE.Texture(bitmap)
  texture.colorSpace = THREE.SRGBColorSpace
  texture.needsUpdate = true
  return texture
}

export function applyDirectorPanoramaTexture(
  material: THREE.MeshBasicMaterial,
  texture: THREE.Texture | null
) {
  const previous = material.map
  material.map = texture
  material.needsUpdate = true
  if (previous && previous !== texture) previous.dispose()
}
