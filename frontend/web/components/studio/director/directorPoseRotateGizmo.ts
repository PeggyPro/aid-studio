import * as THREE from 'three'
import { TransformControls } from 'three/examples/jsm/controls/TransformControls.js'
import { DIRECTOR_POSE_JOINT_HANDLES } from './directorPoseGizmo'

/** 关节旋转主要映射到骨骼局部欧拉轴（与 applyDirectorMannequinPose 一致）。 */
const JOINT_EULER_AXIS: Record<string, { axis: 'x' | 'y' | 'z'; sign: number }> = {
  spine: { axis: 'z', sign: -1 },
  chest: { axis: 'x', sign: 1 },
  neck: { axis: 'y', sign: 1 },
  headX: { axis: 'z', sign: 1 },
  headY: { axis: 'x', sign: 1 },
  leftShoulder: { axis: 'z', sign: -1 },
  rightShoulder: { axis: 'z', sign: -1 },
  leftElbow: { axis: 'z', sign: -1 },
  rightElbow: { axis: 'z', sign: -1 },
  leftWrist: { axis: 'z', sign: 1 },
  rightWrist: { axis: 'z', sign: 1 },
  leftHip: { axis: 'z', sign: 1 },
  rightHip: { axis: 'z', sign: 1 },
  leftKnee: { axis: 'z', sign: -1 },
  rightKnee: { axis: 'z', sign: -1 },
  leftAnkle: { axis: 'z', sign: 1 },
  rightAnkle: { axis: 'z', sign: 1 }
}

const CONTROL_AXIS_TO_EULER: Record<string, 'x' | 'y' | 'z'> = {
  X: 'x',
  Y: 'y',
  Z: 'z'
}

export interface DirectorPoseRotateGizmoRuntime {
  anchor: THREE.Object3D
  pivot: THREE.Object3D
  controls: TransformControls
  activeJoint: string | null
  startValue: number
  dragging: boolean
}

export function createDirectorPoseRotateGizmo(
  camera: THREE.Camera,
  domElement: HTMLElement
): DirectorPoseRotateGizmoRuntime {
  const anchor = new THREE.Object3D()
  anchor.name = 'director-pose-rotate-anchor'
  const pivot = new THREE.Object3D()
  pivot.name = 'director-pose-rotate-pivot'
  anchor.add(pivot)

  const controls = new TransformControls(camera, domElement)
  controls.setMode('rotate')
  controls.setSpace('local')
  controls.setSize(0.5)
  controls.showX = true
  controls.showY = true
  controls.showZ = true
  // 默认关闭，避免与主 TransformControls / OrbitControls 抢同一 canvas 的指针事件。
  controls.enabled = false

  return { anchor, pivot, controls, activeJoint: null, startValue: 0, dragging: false }
}

export function configurePoseRotateAxes(
  controls: TransformControls,
  joint: string
): void {
  const mapping = JOINT_EULER_AXIS[joint]
  controls.showX = true
  controls.showY = true
  controls.showZ = true
  if (!mapping) return
  // 主环加粗靠 TransformControls 自带 hover 高亮；非主轴环仍可拖但灵敏度降低。
  void mapping
}

/** 拖拽灵敏度：略低于 1 让旋转更细腻、不易跳变。 */
const POSE_ROTATION_DRAG_SENSITIVITY = 0.82

export function resolvePoseJointBone(model: THREE.Object3D, joint: string): THREE.Object3D | null {
  const handle = DIRECTOR_POSE_JOINT_HANDLES.find((item) => item.joint === joint)
  if (!handle) return null  
  return model.getObjectByName(handle.bone) ?? null
}

/** 将旋转环拖拽角度映射为关节角度增量。优先使用当前拖拽的环轴。 */
export function rotationEulerToJointDelta(
  joint: string,
  rotation: THREE.Euler,
  controlAxis: string | null | undefined
): number {
  const mapping = JOINT_EULER_AXIS[joint]
  if (!mapping) return 0

  const axis = controlAxis ? CONTROL_AXIS_TO_EULER[controlAxis] ?? mapping.axis : mapping.axis
  const radians = rotation[axis]
  const sign = controlAxis
    ? (CONTROL_AXIS_TO_EULER[controlAxis] === mapping.axis ? mapping.sign : mapping.sign * 0.35)
    : mapping.sign
  return THREE.MathUtils.radToDeg(radians) * sign * POSE_ROTATION_DRAG_SENSITIVITY
}

export function clampDirectorPoseJointValue(value: number): number {
  return THREE.MathUtils.clamp(value, -110, 110)
}

export function attachDirectorPoseRotateGizmo(
  runtime: DirectorPoseRotateGizmoRuntime,
  _scene: THREE.Scene,
  model: THREE.Object3D,
  joint: string,
  startValue: number
): boolean {
  const bone = resolvePoseJointBone(model, joint)
  if (!bone) return false

  if (runtime.anchor.parent) {
    runtime.anchor.parent.remove(runtime.anchor)
  }
  runtime.controls.detach()
  runtime.pivot.rotation.set(0, 0, 0)

  bone.add(runtime.anchor)
  runtime.anchor.position.set(0, 0, 0)
  runtime.anchor.quaternion.set(0, 0, 0, 1)
  runtime.pivot.updateMatrixWorld(true)

  configurePoseRotateAxes(runtime.controls, joint)
  runtime.controls.enabled = true
  runtime.controls.attach(runtime.pivot)
  runtime.controls.getHelper().visible = true
  runtime.activeJoint = joint
  runtime.startValue = startValue
  return true
}

export function detachDirectorPoseRotateGizmo(runtime: DirectorPoseRotateGizmoRuntime): void {
  runtime.controls.detach()
  runtime.controls.enabled = false
  runtime.controls.getHelper().visible = false
  runtime.pivot.rotation.set(0, 0, 0)
  if (runtime.anchor.parent) {
    runtime.anchor.parent.remove(runtime.anchor)
  }
  runtime.activeJoint = null
  runtime.startValue = 0
  runtime.dragging = false
}

export function disposeDirectorPoseRotateGizmo(
  runtime: DirectorPoseRotateGizmoRuntime,
  scene: THREE.Scene
): void {
  detachDirectorPoseRotateGizmo(runtime)
  runtime.controls.dispose()
  if (!runtime.anchor.parent) scene.remove(runtime.anchor)
}
