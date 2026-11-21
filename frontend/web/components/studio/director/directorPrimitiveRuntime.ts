import { PREVIZ_BOX_SIZE, PREVIZ_CONE_RADIUS, PREVIZ_SPHERE_RADIUS } from '@/utils/previz/sceneUnits'
import { DIRECTOR_PROP_HEIGHT } from '@/utils/studio/directorSceneUnits'
import * as THREE from 'three'
import type { DirectorObject } from '@/types/studio'
import { DIRECTOR_VIEWPORT_GEOMETRY_COLOR } from './directorViewportTheme'

export function buildDirectorProp(object: DirectorObject, selected: boolean): THREE.Group {
  const root = new THREE.Group()
  root.position.fromArray(object.transform.position)
  root.rotation.set(...object.transform.rotation)
  root.scale.fromArray(object.transform.scale)
  root.userData.objectId = object.id
  const material = new THREE.MeshStandardMaterial({
    color: object.color || DIRECTOR_VIEWPORT_GEOMETRY_COLOR,
    roughness: 0.55,
    metalness: 0.02,
    emissive: selected ? new THREE.Color('#162b38') : new THREE.Color('#000000'),
    emissiveIntensity: selected ? 0.28 : 0
  })
  // Keep the reference desk's primitive proportions; AID retains its additional capsule type.
  const geometry = object.shape === 'sphere'
    ? new THREE.SphereGeometry(PREVIZ_SPHERE_RADIUS, 32, 16)
    : object.shape === 'cylinder'
      ? new THREE.CylinderGeometry(0.45, 0.45, DIRECTOR_PROP_HEIGHT.cylinder, 32)
      : object.shape === 'torus'
        ? new THREE.TorusGeometry(0.45, 0.14, 16, 48)
        : object.shape === 'cone'
          ? new THREE.ConeGeometry(PREVIZ_CONE_RADIUS, DIRECTOR_PROP_HEIGHT.cone, 32)
          : object.shape === 'pyramid'
            ? new THREE.ConeGeometry(0.55, DIRECTOR_PROP_HEIGHT.pyramid, 4)
            : object.shape === 'capsule'
              ? new THREE.CapsuleGeometry(0.42, 0.75, 8, 16)
              : new THREE.BoxGeometry(PREVIZ_BOX_SIZE, PREVIZ_BOX_SIZE, PREVIZ_BOX_SIZE)
  const prop = new THREE.Mesh(geometry, material)
  if (object.shape === 'torus') prop.rotation.x = Math.PI / 2
  prop.castShadow = true
  prop.receiveShadow = true
  prop.userData.objectId = object.id
  root.add(prop)
  return root
}
