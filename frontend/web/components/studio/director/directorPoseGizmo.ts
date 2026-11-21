import * as THREE from 'three'
import { DIRECTOR_MANNEQUIN_BONE_NAMES } from './directorMannequinRuntime'
import { disposeDirectorObjectTree } from './directorRuntimeResources'

export const DIRECTOR_POSE_JOINT_HANDLES = [
  { joint: 'spine', bone: DIRECTOR_MANNEQUIN_BONE_NAMES.pelvis, parentBone: null },
  { joint: 'chest', bone: DIRECTOR_MANNEQUIN_BONE_NAMES.spine, parentBone: DIRECTOR_MANNEQUIN_BONE_NAMES.pelvis },
  { joint: 'neck', bone: DIRECTOR_MANNEQUIN_BONE_NAMES.neck, parentBone: DIRECTOR_MANNEQUIN_BONE_NAMES.spine },
  { joint: 'headX', bone: DIRECTOR_MANNEQUIN_BONE_NAMES.head, parentBone: DIRECTOR_MANNEQUIN_BONE_NAMES.neck },
  { joint: 'leftShoulder', bone: DIRECTOR_MANNEQUIN_BONE_NAMES.leftShoulder, parentBone: DIRECTOR_MANNEQUIN_BONE_NAMES.spine },
  { joint: 'leftElbow', bone: DIRECTOR_MANNEQUIN_BONE_NAMES.leftElbow, parentBone: DIRECTOR_MANNEQUIN_BONE_NAMES.leftShoulder },
  { joint: 'leftWrist', bone: DIRECTOR_MANNEQUIN_BONE_NAMES.leftWrist, parentBone: DIRECTOR_MANNEQUIN_BONE_NAMES.leftElbow },
  { joint: 'rightShoulder', bone: DIRECTOR_MANNEQUIN_BONE_NAMES.rightShoulder, parentBone: DIRECTOR_MANNEQUIN_BONE_NAMES.spine },
  { joint: 'rightElbow', bone: DIRECTOR_MANNEQUIN_BONE_NAMES.rightElbow, parentBone: DIRECTOR_MANNEQUIN_BONE_NAMES.rightShoulder },
  { joint: 'rightWrist', bone: DIRECTOR_MANNEQUIN_BONE_NAMES.rightWrist, parentBone: DIRECTOR_MANNEQUIN_BONE_NAMES.rightElbow },
  { joint: 'leftHip', bone: DIRECTOR_MANNEQUIN_BONE_NAMES.leftHip, parentBone: DIRECTOR_MANNEQUIN_BONE_NAMES.pelvis },
  { joint: 'leftKnee', bone: DIRECTOR_MANNEQUIN_BONE_NAMES.leftKnee, parentBone: DIRECTOR_MANNEQUIN_BONE_NAMES.leftHip },
  { joint: 'leftAnkle', bone: DIRECTOR_MANNEQUIN_BONE_NAMES.leftAnkle, parentBone: DIRECTOR_MANNEQUIN_BONE_NAMES.leftKnee },
  { joint: 'rightHip', bone: DIRECTOR_MANNEQUIN_BONE_NAMES.rightHip, parentBone: DIRECTOR_MANNEQUIN_BONE_NAMES.pelvis },
  { joint: 'rightKnee', bone: DIRECTOR_MANNEQUIN_BONE_NAMES.rightKnee, parentBone: DIRECTOR_MANNEQUIN_BONE_NAMES.rightHip },
  { joint: 'rightAnkle', bone: DIRECTOR_MANNEQUIN_BONE_NAMES.rightAnkle, parentBone: DIRECTOR_MANNEQUIN_BONE_NAMES.rightKnee }
] as const

export type DirectorPoseJoint = (typeof DIRECTOR_POSE_JOINT_HANDLES)[number]['joint']

const JOINT_HANDLE_RADIUS = 0.062
const JOINT_HANDLE_ACTIVE_RADIUS = 0.078

export interface DirectorPoseGizmoRuntime {
  root: THREE.Group
  handles: Map<string, THREE.Mesh>
  lines: THREE.Line[]
}

export function createDirectorPoseGizmoRuntime(): DirectorPoseGizmoRuntime {
  return { root: new THREE.Group(), handles: new Map(), lines: [] }
}

function findMannequinModel(objectRoot: THREE.Object3D): THREE.Object3D | undefined {
  let found: THREE.Object3D | undefined
  objectRoot.traverse((child) => {
    if (child.name === 'director-ue-mannequin' || child.getObjectByName(DIRECTOR_MANNEQUIN_BONE_NAMES.pelvis)) {
      found = child.name === 'director-ue-mannequin' ? child : child
    }
  })
  if (found) return found
  return objectRoot.getObjectByName(DIRECTOR_MANNEQUIN_BONE_NAMES.pelvis)?.parent ?? undefined
}

export function syncDirectorPoseGizmo(
  runtime: DirectorPoseGizmoRuntime,
  objectRoot: THREE.Object3D | undefined,
  activeJoint: string | null
): void {
  disposeDirectorPoseGizmo(runtime)
  runtime.root.clear()
  if (!objectRoot) return

  const model = findMannequinModel(objectRoot)
  if (!model) return

  model.updateMatrixWorld(true)
  const worldPositions = new Map<string, THREE.Vector3>()

  for (const handle of DIRECTOR_POSE_JOINT_HANDLES) {
    const bone = model.getObjectByName(handle.bone)
    if (!bone) continue
    const world = new THREE.Vector3()
    bone.getWorldPosition(world)
    worldPositions.set(handle.joint, world)

    const radius = activeJoint === handle.joint ? JOINT_HANDLE_ACTIVE_RADIUS : JOINT_HANDLE_RADIUS
    const sphere = new THREE.Mesh(
      new THREE.SphereGeometry(radius, 16, 12),
      new THREE.MeshBasicMaterial({
        color: activeJoint === handle.joint ? '#ffffff' : '#52ddff',
        transparent: true,
        opacity: activeJoint === handle.joint ? 0.95 : 0.82,
        depthTest: false
      })
    )
    sphere.name = `pose-handle-${handle.joint}`
    sphere.userData.poseJoint = handle.joint
    sphere.renderOrder = 12
    sphere.position.copy(world)
    runtime.handles.set(handle.joint, sphere)
    runtime.root.add(sphere)
  }

  for (const handle of DIRECTOR_POSE_JOINT_HANDLES) {
    if (!handle.parentBone) continue
    const parentHandle = DIRECTOR_POSE_JOINT_HANDLES.find((item) => item.bone === handle.parentBone)
    if (!parentHandle) continue
    const start = worldPositions.get(parentHandle.joint)
    const end = worldPositions.get(handle.joint)
    if (!start || !end) continue
    const line = new THREE.Line(
      new THREE.BufferGeometry().setFromPoints([start.clone(), end.clone()]),
      new THREE.LineBasicMaterial({ color: '#52ddff', transparent: true, opacity: 0.55, depthTest: false })
    )
    line.renderOrder = 11
    runtime.lines.push(line)
    runtime.root.add(line)
  }
}

export function disposeDirectorPoseGizmo(runtime: DirectorPoseGizmoRuntime): void {
  disposeDirectorObjectTree(runtime.root)
  runtime.handles.clear()
  runtime.lines = []
}

export function refreshDirectorPoseGizmoPositions(
  runtime: DirectorPoseGizmoRuntime,
  objectRoot: THREE.Object3D | undefined
): void {
  if (!objectRoot || runtime.handles.size === 0) return
  const model = findMannequinModel(objectRoot)
  if (!model) return
  model.updateMatrixWorld(true)
  const worldPositions = new Map<string, THREE.Vector3>()
  for (const handle of DIRECTOR_POSE_JOINT_HANDLES) {
    const sphere = runtime.handles.get(handle.joint)
    const bone = model.getObjectByName(handle.bone)
    if (!sphere || !bone) continue
    const world = new THREE.Vector3()
    bone.getWorldPosition(world)
    sphere.position.copy(world)
    worldPositions.set(handle.joint, world)
  }
  for (let lineIndex = 0; lineIndex < runtime.lines.length; lineIndex += 1) {
    const handle = DIRECTOR_POSE_JOINT_HANDLES.filter((item) => item.parentBone)[lineIndex]
    if (!handle?.parentBone) continue
    const parentHandle = DIRECTOR_POSE_JOINT_HANDLES.find((item) => item.bone === handle.parentBone)
    if (!parentHandle) continue
    const start = worldPositions.get(parentHandle.joint)
    const end = worldPositions.get(handle.joint)
    const line = runtime.lines[lineIndex]
    if (!start || !end || !line) continue
    const geometry = line.geometry as THREE.BufferGeometry
    geometry.setFromPoints([start.clone(), end.clone()])
  }
}

export function pickDirectorPoseJoint(runtime: DirectorPoseGizmoRuntime, raycaster: THREE.Raycaster): string | null {
  const meshes = [...runtime.handles.values()]
  if (!meshes.length) return null
  const hit = raycaster.intersectObjects(meshes, false)[0]
  const joint = hit?.object.userData.poseJoint
  return typeof joint === 'string' ? joint : null
}

/** 屏幕空间拾取：旋转环遮挡时仍可点到附近关节球。 */
export function pickDirectorPoseJointAtPointer(
  runtime: DirectorPoseGizmoRuntime,
  objectRoot: THREE.Object3D | undefined,
  raycaster: THREE.Raycaster,
  camera: THREE.Camera,
  pointerNdc: THREE.Vector2,
  canvasRect: DOMRect,
  pixelRadius = 28
): string | null {
  const meshJoint = pickDirectorPoseJoint(runtime, raycaster)
  if (meshJoint) return meshJoint
  if (!objectRoot) return null

  const model = findMannequinModel(objectRoot)
  if (!model) return null
  model.updateMatrixWorld(true)

  const pointerPxX = ((pointerNdc.x + 1) / 2) * canvasRect.width
  const pointerPxY = ((-pointerNdc.y + 1) / 2) * canvasRect.height
  const radiusSq = pixelRadius * pixelRadius
  const projected = new THREE.Vector3()

  let bestJoint: string | null = null
  let bestDistSq = radiusSq
  for (const handle of DIRECTOR_POSE_JOINT_HANDLES) {
    const bone = model.getObjectByName(handle.bone)
    if (!bone) continue
    bone.getWorldPosition(projected)
    projected.project(camera)
    const screenX = ((projected.x + 1) / 2) * canvasRect.width
    const screenY = ((-projected.y + 1) / 2) * canvasRect.height
    const dx = screenX - pointerPxX
    const dy = screenY - pointerPxY
    const distSq = dx * dx + dy * dy
    if (distSq <= bestDistSq) {
      bestDistSq = distSq
      bestJoint = handle.joint
    }
  }
  return bestJoint
}

export function clampDirectorPoseJointValue(value: number): number {
  return THREE.MathUtils.clamp(value, -110, 110)
}

export function adjustDirectorPoseJointValue(startValue: number, deltaY: number): number {
  return clampDirectorPoseJointValue(startValue + deltaY * -0.45)
}
