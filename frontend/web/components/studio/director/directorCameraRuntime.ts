import * as THREE from 'three'
import type { DirectorCamera, DirectorLensSpec, DirectorSceneData, DirectorViewMode } from '@/types/studio'
import {
  applyDirectorCameraRigTransform,
  getDirectorCameraHitArea,
  getDirectorCameraWireframeLines,
} from './directorCameraGeometry'
import {
  resolveDirectorCameraVisualPartColor,
  resolveDirectorCameraVisualProfile,
} from './directorCameraVisual'
import { createDirectorCameraSolidModel, updateDirectorCameraSolidModel } from './directorCameraModel'
import { disposeDirectorObjectTree } from './directorRuntimeResources'
import { listDirectorCameraEntities, resolveDirectorCameraLens } from './directorCameraState'

export function buildDirectorCameraHelpers(
  root: THREE.Group,
  scene: DirectorSceneData,
  viewMode: DirectorViewMode,
  selectedId: string | null
): void {
  disposeDirectorObjectTree(root)
  root.clear()
  if (viewMode !== 'director') return
  listDirectorCameraEntities(scene).forEach((item) => root.add(buildCameraRig(
    item.id,
    item.camera,
    item.lens,
    selectedId === item.id,
    item.locked,
  )))
}

export function previewDirectorCameraRig(
  root: THREE.Group,
  scene: DirectorSceneData,
  cameraId: string,
  camera: DirectorCamera,
  lens?: DirectorLensSpec,
  selectedId: string | null = cameraId,
): THREE.Object3D | null {
  const current = root.children.find((child) => child.userData.cameraId === cameraId)
  if (!current) return null
  const previewLens = lens ?? resolveDirectorCameraLens(scene, cameraId)
  const profile = resolveDirectorCameraVisualProfile(previewLens)
  const structureSignature = directorCameraRigStructureSignature(profile.lens)
  const geometrySignature = directorCameraRigGeometrySignature(camera, profile.lens)
  if (current.userData.structureSignature === structureSignature) {
    const geometryReady = current.userData.geometrySignature === geometrySignature
      || updateCameraRigGeometry(current, camera, profile)
    if (geometryReady) {
      applyDirectorCameraRigTransform(current, camera)
      current.userData.visualSignature = directorCameraRigVisualSignature(camera, profile.lens)
      current.userData.geometrySignature = geometrySignature
      current.updateMatrixWorld(true)
      return current
    }
  }

  const replacement = buildCameraRig(
    cameraId,
    camera,
    previewLens,
    selectedId === cameraId,
    current.userData.locked === true,
  )
  root.remove(current)
  disposeDirectorObjectTree(current)
  root.add(replacement)
  replacement.updateMatrixWorld(true)
  return replacement
}

export function getDirectorCameraRigVisualBounds(rig: THREE.Object3D): THREE.Box3 {
  const cached = rig.userData.visualLocalBounds
  const localBounds = cached instanceof THREE.Box3 ? cached : cacheDirectorCameraRigLocalBounds(rig)
  rig.updateWorldMatrix(true, false)
  return localBounds.clone().applyMatrix4(rig.matrixWorld)
}

function cacheDirectorCameraRigLocalBounds(rig: THREE.Object3D): THREE.Box3 {
  const bounds = new THREE.Box3()
  rig.updateWorldMatrix(true, true)
  const inverseRigMatrix = rig.matrixWorld.clone().invert()
  const relativeMatrix = new THREE.Matrix4()
  rig.traverse((child) => {
    const part = child.userData.cameraVisualPart
    if (!part || part === 'frustum' || part === 'target') return
    const geometry = (child as THREE.Line | THREE.Mesh).geometry
    if (!geometry) return
    geometry.computeBoundingBox()
    if (geometry.boundingBox) {
      relativeMatrix.multiplyMatrices(inverseRigMatrix, child.matrixWorld)
      bounds.union(geometry.boundingBox.clone().applyMatrix4(relativeMatrix))
    }
  })
  rig.userData.visualLocalBounds = bounds
  return bounds
}

function buildCameraRig(
  id: string,
  camera: DirectorCamera,
  lens: DirectorLensSpec,
  selected: boolean,
  locked: boolean,
): THREE.Group {
  const group = new THREE.Group()
  const profile = resolveDirectorCameraVisualProfile(lens)
  group.name = `director-camera-${id}`
  applyDirectorCameraRigTransform(group, camera)
  group.userData.objectId = id
  group.userData.cameraId = id
  group.userData.locked = locked
  group.userData.visualSignature = directorCameraRigVisualSignature(camera, profile.lens)
  group.userData.geometrySignature = directorCameraRigGeometrySignature(camera, profile.lens)
  group.userData.structureSignature = directorCameraRigStructureSignature(profile.lens)
  group.userData.cameraBody = profile.lens.cameraBody
  group.userData.lensSeries = profile.lens.lensSeries
  group.add(createDirectorCameraSolidModel(id, profile, selected))
  getDirectorCameraWireframeLines(camera, profile.lens).forEach((wire, index) => {
    const line = new THREE.Line(
      new THREE.BufferGeometry().setFromPoints(wire.points.map((point) => new THREE.Vector3().fromArray(point))),
      new THREE.LineBasicMaterial({
        color: resolveDirectorCameraVisualPartColor(profile, wire.part),
        opacity: resolvePartOpacity(wire.part, selected),
        transparent: true,
        depthTest: false,
      })
    )
    line.name = `${id}-${wire.part}-${index}`
    line.renderOrder = selected ? 8 : 6
    line.userData.objectId = id
    line.userData.cameraId = id
    line.userData.cameraVisualPart = wire.part
    line.userData.cameraVisualIndex = index
    group.add(line)
  })
  const hitArea = getDirectorCameraHitArea(camera, profile.lens)
  const hit = new THREE.Mesh(
    new THREE.BoxGeometry(1, 1, 1),
    new THREE.MeshBasicMaterial({ transparent: true, opacity: 0, depthWrite: false })
  )
  hit.name = `${id}-hit-area`
  hit.position.fromArray(hitArea.position)
  hit.scale.fromArray(hitArea.size)
  hit.userData.objectId = id
  hit.userData.cameraId = id
  hit.userData.cameraVisualPart = 'hit'
  group.add(hit)
  cacheDirectorCameraRigLocalBounds(group)
  return group
}

function updateCameraRigGeometry(
  rig: THREE.Object3D,
  camera: DirectorCamera,
  profile: ReturnType<typeof resolveDirectorCameraVisualProfile>,
): boolean {
  const wires = getDirectorCameraWireframeLines(camera, profile.lens)
  const lines = new Map<number, THREE.Line>()
  const hit = rig.children.find((child) => child.userData.cameraVisualPart === 'hit')
  rig.children.forEach((child) => {
    if (child instanceof THREE.Line && Number.isInteger(child.userData.cameraVisualIndex)) {
      lines.set(child.userData.cameraVisualIndex as number, child)
    }
  })
  const solidModel = rig.children.find((child) => child.userData.cameraSolidModel === true)
  if (lines.size !== wires.length || !(hit instanceof THREE.Mesh) || !solidModel) return false

  wires.forEach((wire, index) => {
    const line = lines.get(index)
    if (!line) return
    const position = line.geometry.getAttribute('position') as THREE.BufferAttribute | undefined
    if (!position || position.count !== wire.points.length) {
      line.geometry.setFromPoints(wire.points.map((point) => new THREE.Vector3().fromArray(point)))
    } else {
      wire.points.forEach((point, pointIndex) => position.setXYZ(pointIndex, point[0], point[1], point[2]))
      position.needsUpdate = true
    }
    line.geometry.computeBoundingBox()
    line.geometry.computeBoundingSphere()
  })
  const hitArea = getDirectorCameraHitArea(camera, profile.lens)
  hit.position.fromArray(hitArea.position)
  hit.scale.fromArray(hitArea.size)
  if (!updateDirectorCameraSolidModel(solidModel, profile)) return false
  cacheDirectorCameraRigLocalBounds(rig)
  return true
}

function resolvePartOpacity(part: string, selected: boolean): number {
  if (part === 'target') return selected ? 0.72 : 0.4
  if (part === 'frustum') return selected ? 0.88 : 0.48
  return selected ? 0.9 : 0.5
}

function directorCameraRigVisualSignature(camera: DirectorCamera, lens: DirectorLensSpec): string {
  return [
    Number(camera.fov.toFixed(4)),
    lens.cameraBody,
    lens.lensSeries,
    lens.lensType,
    Number(lens.focalLengthMm.toFixed(3)),
    Number((lens.apertureF ?? 0).toFixed(3)),
    lens.bodyName,
  ].join('|')
}

function directorCameraRigGeometrySignature(camera: DirectorCamera, lens: DirectorLensSpec): string {
  return [
    Number(camera.fov.toFixed(4)),
    Number(lens.focalLengthMm.toFixed(3)),
    Number((lens.apertureF ?? 0).toFixed(3)),
  ].join('|')
}

function directorCameraRigStructureSignature(lens: DirectorLensSpec): string {
  return [lens.cameraBody, lens.lensSeries, lens.lensType, lens.bodyName].join('|')
}
