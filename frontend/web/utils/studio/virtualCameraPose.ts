import { MathUtils, Vector3 } from 'three'
import type { DirectorCamera, DirectorLensSpec, DirectorTransformTuple as Vec } from '@/types/studio'
import { updateDirectorCameraOrientation } from './directorCameraPose'
import { directorLensFov } from './directorLens'

export const VIRTUAL_CAMERA_PITCH_LIMIT = 89
const STICK_DEAD_ZONE = 0.12

export interface VirtualCameraControl {
  seq: number
  t: number
  move: { x: number; y: number }
  crane: -1 | 0 | 1
  look: { yaw: number; pitch: number }
  focalMm: number
  recording: 'start' | 'stop' | 'idle'
  layout: 'landscape' | 'portrait'
}

/** A circular range keeps diagonal speed consistent and gives fine control near center. */
export function virtualCameraStickInput(x: number, y: number) {
  const length = Math.hypot(x, y)
  if (length <= STICK_DEAD_ZONE) return { x: 0, y: 0 }
  const strength = Math.pow((Math.min(1, length) - STICK_DEAD_ZONE) / (1 - STICK_DEAD_ZONE), 1.8)
  return { x: x / length * strength, y: y / length * strength }
}

/** Integrate angular speed on the control clock, independently of pointer-event frequency. */
export function advanceVirtualCameraLook(look: VirtualCameraControl['look'], turn: VirtualCameraControl['move'], seconds: number, sensitivity: number) {
  const step = MathUtils.clamp(seconds, 0, 0.05) * sensitivity
  return {
    yaw: MathUtils.euclideanModulo(look.yaw + turn.x * 42 * step + 180, 360) - 180,
    pitch: MathUtils.clamp(look.pitch + turn.y * 28 * step, -VIRTUAL_CAMERA_PITCH_LIMIT, VIRTUAL_CAMERA_PITCH_LIMIT)
  }
}

const finite = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value)

export function clampVirtualCameraControl(raw: unknown): VirtualCameraControl | null {
  if (!raw || typeof raw !== 'object') return null
  const value = raw as Partial<VirtualCameraControl>
  if (
    !finite(value.seq) || !finite(value.t) || !value.move || !finite(value.move.x) || !finite(value.move.y) ||
    !value.look || !finite(value.look.yaw) || !finite(value.look.pitch) || !finite(value.focalMm) ||
    ![-1, 0, 1].includes(value.crane as number) ||
    !['start', 'stop', 'idle'].includes(value.recording ?? '') ||
    !['landscape', 'portrait'].includes(value.layout ?? '')
  ) return null
  return {
    seq: Math.max(0, Math.trunc(value.seq)),
    t: value.t,
    move: { x: MathUtils.clamp(value.move.x, -1, 1), y: MathUtils.clamp(value.move.y, -1, 1) },
    crane: value.crane as -1 | 0 | 1,
    look: { yaw: value.look.yaw, pitch: MathUtils.clamp(value.look.pitch, -VIRTUAL_CAMERA_PITCH_LIMIT, VIRTUAL_CAMERA_PITCH_LIMIT) },
    focalMm: MathUtils.clamp(value.focalMm, 12, 200),
    recording: value.recording as VirtualCameraControl['recording'],
    layout: value.layout as VirtualCameraControl['layout'],
  }
}

function tuple(vector: Vector3): Vec {
  return vector.toArray().map((value) => Number(value.toFixed(6))) as Vec
}

export function applyVirtualCameraControl(
  camera: DirectorCamera,
  control: VirtualCameraControl,
  dt: number,
  moveSpeed = 4,
): DirectorCamera {
  const oriented = updateDirectorCameraOrientation(camera, {
    yaw: control.look.yaw,
    pitch: MathUtils.clamp(control.look.pitch, -VIRTUAL_CAMERA_PITCH_LIMIT, VIRTUAL_CAMERA_PITCH_LIMIT),
  })
  const position = new Vector3().fromArray(oriented.position)
  const target = new Vector3().fromArray(oriented.target)
  const forward = target.clone().sub(position)
  if (forward.lengthSq() < 0.000001) forward.set(0, 0, -1)
  forward.normalize()
  const horizontalForward = new Vector3(forward.x, 0, forward.z)
  if (horizontalForward.lengthSq() < 0.000001) horizontalForward.set(0, 0, -1)
  horizontalForward.normalize()
  const right = horizontalForward.clone().cross(new Vector3(0, 1, 0)).normalize()
  const seconds = MathUtils.clamp(Number.isFinite(dt) ? dt : 0, 0, 1)
  const translation = right.multiplyScalar(control.move.x * moveSpeed * seconds)
    .add(forward.multiplyScalar(control.move.y * moveSpeed * seconds))
    .add(new Vector3(0, control.crane * moveSpeed * seconds, 0))
  position.add(translation)
  target.add(translation)
  return { ...oriented, position: tuple(position), target: tuple(target) }
}

export function shouldApplySeq(lastApplied: number, incoming: number): boolean {
  return Number.isFinite(incoming) && incoming > lastApplied
}

export function smoothLook(
  previous: { yaw: number; pitch: number },
  next: { yaw: number; pitch: number },
  stability01: number,
): { yaw: number; pitch: number } {
  const amount = 1 - MathUtils.clamp(stability01, 0, 1)
  return {
    yaw: Number(MathUtils.lerp(previous.yaw, next.yaw, amount).toFixed(4)),
    pitch: Number(MathUtils.lerp(previous.pitch, next.pitch, amount).toFixed(4)),
  }
}

export function focalMmToCamera(
  camera: DirectorCamera,
  lens: DirectorLensSpec,
  focalMm: number,
  aspect = 16 / 9,
): { camera: DirectorCamera; lens: DirectorLensSpec } {
  const nextLens = { ...lens, focalLengthMm: MathUtils.clamp(focalMm, 12, 200) }
  return { lens: nextLens, camera: { ...camera, fov: directorLensFov(nextLens, aspect) } }
}
