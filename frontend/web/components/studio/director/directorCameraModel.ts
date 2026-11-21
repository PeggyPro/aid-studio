import * as THREE from 'three'
import type { DirectorCameraVisualProfile, DirectorCameraVisualPart } from './directorCameraVisual'

type SolidRole =
  | 'lens-barrel'
  | 'lens-inner'
  | 'focal-ring'
  | 'aperture-ring'
  | 'front-glass'

export function createDirectorCameraSolidModel(
  cameraId: string,
  profile: DirectorCameraVisualProfile,
  selected: boolean,
): THREE.Group {
  const root = new THREE.Group()
  root.name = `${cameraId}-solid-model`
  root.userData.cameraSolidModel = true

  const [bodyWidth, bodyHeight, bodyDepth] = profile.bodySize
  addBox(root, cameraId, 'body', 'camera-body', [bodyWidth, bodyHeight, bodyDepth], [0, 0, profile.bodyCenterZ], profile.bodyColor, selected)
  addBox(root, cameraId, 'body', 'camera-back', [bodyWidth * 0.72, bodyHeight * 0.68, bodyDepth * 0.18], [0, 0, profile.bodyCenterZ - bodyDepth * 0.57], darken(profile.bodyColor, 0.42), selected)
  addBox(root, cameraId, 'body', 'camera-top-rail', [bodyWidth * 0.48, bodyHeight * 0.1, bodyDepth * 0.54], [0, bodyHeight * 0.56, profile.bodyCenterZ - bodyDepth * 0.04], '#303A47', selected)

  if (profile.bodyDetail === 'monitor') addVirtualProductionDetails(root, cameraId, profile, selected)
  if (profile.bodyDetail === 'handle') addHandheldDetails(root, cameraId, profile, selected)
  if (profile.bodyDetail === 'reels') addCinemaDetails(root, cameraId, profile, selected)

  const lensBarrel = addCylinder(root, cameraId, 'lens', 'lens-barrel', profile.lensColor, selected)
  lensBarrel.rotation.x = Math.PI / 2
  const lensInner = addCylinder(root, cameraId, 'lens', 'lens-inner', '#171B23', selected)
  lensInner.rotation.x = Math.PI / 2
  addTorus(root, cameraId, 'focal', 'focal-ring', profile.focalColor, selected, 0.06)
  addTorus(root, cameraId, 'aperture', 'aperture-ring', profile.apertureColor, selected, 0.055)

  const frontGlass = new THREE.Mesh(
    new THREE.CircleGeometry(1, 40),
    new THREE.MeshPhysicalMaterial({
      color: '#183448',
      emissive: '#071D2B',
      emissiveIntensity: selected ? 0.5 : 0.28,
      metalness: 0.15,
      roughness: 0.08,
      transparent: true,
      opacity: 0.9,
      side: THREE.DoubleSide,
    }),
  )
  registerSolid(frontGlass, cameraId, 'lens', 'front-glass')
  root.add(frontGlass)

  updateDirectorCameraSolidModel(root, profile)
  return root
}

export function updateDirectorCameraSolidModel(
  root: THREE.Object3D,
  profile: DirectorCameraVisualProfile,
): boolean {
  const bodyFrontZ = profile.bodyCenterZ + profile.bodySize[2] / 2
  const lensTipZ = bodyFrontZ + profile.lensLength
  const lensCenterZ = bodyFrontZ + profile.lensLength / 2
  const focalZ = bodyFrontZ + profile.lensLength * 0.58
  const lensBarrel = findSolid(root, 'lens-barrel')
  const lensInner = findSolid(root, 'lens-inner')
  const focalRing = findSolid(root, 'focal-ring')
  const apertureRing = findSolid(root, 'aperture-ring')
  const frontGlass = findSolid(root, 'front-glass')
  if (!lensBarrel || !lensInner || !focalRing || !apertureRing || !frontGlass) return false

  lensBarrel.position.set(0, 0, lensCenterZ)
  lensBarrel.scale.set(profile.lensRadiusX, profile.lensLength, profile.lensRadiusY)
  lensInner.position.set(0, 0, lensTipZ - profile.lensLength * 0.08)
  lensInner.scale.set(profile.lensRadiusX * 0.84, profile.lensLength * 0.16, profile.lensRadiusY * 0.84)
  focalRing.position.set(0, 0, focalZ)
  focalRing.scale.set(profile.lensRadiusX * 1.06, profile.lensRadiusY * 1.06, 1)
  apertureRing.position.set(0, 0, lensTipZ + 0.012)
  apertureRing.scale.set(profile.apertureRadius, profile.apertureRadius, 1)
  frontGlass.position.set(0, 0, lensTipZ + 0.006)
  frontGlass.scale.set(profile.lensRadiusX * 0.86, profile.lensRadiusY * 0.86, 1)
  return true
}

function addVirtualProductionDetails(root: THREE.Group, cameraId: string, profile: DirectorCameraVisualProfile, selected: boolean) {
  const [width, height, depth] = profile.bodySize
  addBox(root, cameraId, 'body', 'vp-monitor', [width * 0.82, height * 0.42, depth * 0.12], [0, height * 0.72, profile.bodyCenterZ - depth * 0.08], darken(profile.bodyColor, 0.34), selected)
  addBox(root, cameraId, 'body', 'vp-monitor-screen', [width * 0.66, height * 0.29, depth * 0.025], [0, height * 0.72, profile.bodyCenterZ - depth * 0.151], '#102F3D', selected)
  addBox(root, cameraId, 'body', 'vp-side-module', [width * 0.12, height * 0.66, depth * 0.5], [width * 0.56, 0, profile.bodyCenterZ], darken(profile.bodyColor, 0.28), selected)
}

function addHandheldDetails(root: THREE.Group, cameraId: string, profile: DirectorCameraVisualProfile, selected: boolean) {
  const [width, height, depth] = profile.bodySize
  const handleColor = darken(profile.bodyColor, 0.4)
  addBox(root, cameraId, 'body', 'handheld-handle-top', [width * 0.72, height * 0.13, depth * 0.46], [0, height * 0.73, profile.bodyCenterZ], handleColor, selected)
  addBox(root, cameraId, 'body', 'handheld-handle-left', [width * 0.11, height * 0.34, depth * 0.12], [-width * 0.31, height * 0.58, profile.bodyCenterZ - depth * 0.17], handleColor, selected)
  addBox(root, cameraId, 'body', 'handheld-grip', [width * 0.18, height * 0.72, depth * 0.22], [width * 0.64, -height * 0.08, profile.bodyCenterZ - depth * 0.12], '#26352F', selected)
}

function addCinemaDetails(root: THREE.Group, cameraId: string, profile: DirectorCameraVisualProfile, selected: boolean) {
  const [width, height] = profile.bodySize
  const reelColor = darken(profile.bodyColor, 0.28)
  for (const zOffset of [-height * 0.23, height * 0.23]) {
    const reel = new THREE.Mesh(
      new THREE.CylinderGeometry(height * 0.24, height * 0.24, 0.055, 28),
      createSolidMaterial(reelColor, selected, 0.58),
    )
    reel.rotation.z = Math.PI / 2
    reel.position.set(width / 2 + 0.032, height * 0.52, profile.bodyCenterZ + zOffset)
    registerSolid(reel, cameraId, 'reel', `cinema-reel-${zOffset}`)
    root.add(reel)
  }
  addBox(root, cameraId, 'body', 'cinema-base', [width * 1.12, height * 0.12, profile.bodySize[2] * 0.84], [0, -height * 0.57, profile.bodyCenterZ], '#2B3442', selected)
}

function addBox(
  root: THREE.Group,
  cameraId: string,
  part: DirectorCameraVisualPart,
  role: string,
  size: [number, number, number],
  position: [number, number, number],
  color: string,
  selected: boolean,
): THREE.Mesh {
  const mesh = new THREE.Mesh(new THREE.BoxGeometry(...size, 2, 2, 2), createSolidMaterial(color, selected))
  mesh.position.fromArray(position)
  registerSolid(mesh, cameraId, part, role)
  root.add(mesh)
  return mesh
}

function addCylinder(
  root: THREE.Group,
  cameraId: string,
  part: DirectorCameraVisualPart,
  role: SolidRole,
  color: string,
  selected: boolean,
): THREE.Mesh {
  const mesh = new THREE.Mesh(new THREE.CylinderGeometry(1, 1, 1, 32, 1), createSolidMaterial(color, selected, 0.62))
  registerSolid(mesh, cameraId, part, role)
  root.add(mesh)
  return mesh
}

function addTorus(
  root: THREE.Group,
  cameraId: string,
  part: DirectorCameraVisualPart,
  role: SolidRole,
  color: string,
  selected: boolean,
  tube: number,
): THREE.Mesh {
  const mesh = new THREE.Mesh(new THREE.TorusGeometry(1, tube, 10, 36), createSolidMaterial(color, selected, 0.38))
  registerSolid(mesh, cameraId, part, role)
  root.add(mesh)
  return mesh
}

function createSolidMaterial(color: string, selected: boolean, roughness = 0.42): THREE.MeshStandardMaterial {
  return new THREE.MeshStandardMaterial({
    color,
    emissive: color,
    emissiveIntensity: selected ? 0.16 : 0.07,
    metalness: 0.46,
    roughness,
  })
}

function registerSolid(object: THREE.Object3D, cameraId: string, part: DirectorCameraVisualPart, role: string): void {
  object.userData.objectId = cameraId
  object.userData.cameraId = cameraId
  object.userData.cameraVisualPart = part
  object.userData.cameraSolidRole = role
  if (object instanceof THREE.Mesh) {
    object.castShadow = true
    object.receiveShadow = true
  }
}

function findSolid(root: THREE.Object3D, role: SolidRole): THREE.Object3D | null {
  let match: THREE.Object3D | null = null
  root.traverse((object) => {
    if (!match && object.userData.cameraSolidRole === role) match = object
  })
  return match
}

function darken(color: string, amount: number): string {
  return `#${new THREE.Color(color).multiplyScalar(1 - amount).getHexString()}`
}
