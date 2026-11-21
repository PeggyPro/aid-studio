import * as THREE from 'three'

export function disposeDirectorObjectTree(root: THREE.Object3D): void {
  const geometries = new Set<THREE.BufferGeometry>()
  const materials = new Set<THREE.Material>()
  const textures = new Set<THREE.Texture>()
  root.traverse((child) => {
    if (
      child instanceof THREE.DirectionalLight ||
      child instanceof THREE.PointLight ||
      child instanceof THREE.SpotLight
    ) {
      child.shadow.map?.dispose()
    }
    if (child instanceof THREE.SkinnedMesh) child.skeleton.boneTexture?.dispose()
    if (!(child instanceof THREE.Mesh || child instanceof THREE.Line || child instanceof THREE.Points)) return
    geometries.add(child.geometry)
    const values = Array.isArray(child.material) ? child.material : [child.material]
    values.forEach((material) => {
      materials.add(material)
      Object.values(material).forEach((value) => {
        if (value instanceof THREE.Texture) textures.add(value)
      })
    })
  })
  geometries.forEach((geometry) => geometry.dispose())
  textures.forEach((texture) => texture.dispose())
  materials.forEach((material) => material.dispose())
}
