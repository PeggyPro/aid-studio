import { MathUtils, PerspectiveCamera, Vector3 } from 'three'
import type { DirectorCamera } from '@/types/studio'
import { resolveDirectorCameraOrientation } from '@/utils/studio/directorCameraPose'

interface DirectorFixedCameraControlsOptions {
  onStart?: (cameraId: string) => void
  onChange?: (cameraId: string, camera: DirectorCamera) => void
  onEnd?: (cameraId: string, camera: DirectorCamera) => void
}

const MIN_FOV = 10
const MAX_FOV = 100
const MAX_PITCH = 89
const ROTATION_SENSITIVITY = 0.18
const SETTLE_DELAY_MS = 90

function cameraTuple(vector: Vector3): DirectorCamera['position'] {
  return vector.toArray().map((value) => Number(value.toFixed(6))) as DirectorCamera['position']
}

function clampPitch(value: number): number {
  return MathUtils.clamp(value, -MAX_PITCH, MAX_PITCH)
}

/**
 * A filming-head controller: drag changes only yaw/pitch and wheel changes only FOV.
 * The camera position is copied from the scene and never derived from a moving orbit target.
 */
export class DirectorFixedCameraControls {
  enabled = false

  private cameraId: string | null = null
  private readonly fixedPosition = new Vector3()
  private readonly target = new Vector3()
  private targetDistance = 1
  private roll = 0
  private yaw = 0
  private pitch = 0
  private fov = 50
  private desiredYaw = 0
  private desiredPitch = 0
  private desiredFov = 50
  private pointerId: number | null = null
  private pointerX = 0
  private pointerY = 0
  private transactionActive = false
  private settleAfter = 0
  private lastUpdateAt = 0
  private signature = ''

  private readonly handlePointerDown = (event: PointerEvent) => {
    if (!this.enabled || event.button !== 0 || !this.cameraId) return
    event.preventDefault()
    this.canvas.focus({ preventScroll: true })
    this.pointerId = event.pointerId
    this.pointerX = event.clientX
    this.pointerY = event.clientY
    this.canvas.setPointerCapture(event.pointerId)
    this.beginInteraction()
  }

  private readonly handlePointerMove = (event: PointerEvent) => {
    if (!this.enabled || this.pointerId !== event.pointerId) return
    event.preventDefault()
    const deltaX = event.clientX - this.pointerX
    const deltaY = event.clientY - this.pointerY
    this.pointerX = event.clientX
    this.pointerY = event.clientY
    this.desiredYaw -= deltaX * ROTATION_SENSITIVITY
    this.desiredPitch = clampPitch(this.desiredPitch - deltaY * ROTATION_SENSITIVITY)
    this.settleAfter = performance.now() + SETTLE_DELAY_MS
  }

  private readonly handlePointerUp = (event: PointerEvent) => {
    if (this.pointerId !== event.pointerId) return
    if (this.canvas.hasPointerCapture(event.pointerId)) this.canvas.releasePointerCapture(event.pointerId)
    this.pointerId = null
    this.settleAfter = performance.now() + SETTLE_DELAY_MS
  }

  private readonly handleWheel = (event: WheelEvent) => {
    if (!this.enabled || !this.cameraId) return
    event.preventDefault()
    this.beginInteraction()
    this.desiredFov = MathUtils.clamp(this.desiredFov + event.deltaY * 0.025, MIN_FOV, MAX_FOV)
    this.settleAfter = performance.now() + 140
  }

  private readonly handleKeyDown = (event: KeyboardEvent) => {
    if (!this.enabled || !this.cameraId) return
    const changesAngle = ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)
    const changesFov = ['+', '=', '-', '_'].includes(event.key)
    if (!changesAngle && !changesFov) return
    event.preventDefault()
    this.beginInteraction()
    if (event.key === 'ArrowLeft') this.desiredYaw += 4
    if (event.key === 'ArrowRight') this.desiredYaw -= 4
    if (event.key === 'ArrowUp') this.desiredPitch = clampPitch(this.desiredPitch + 3)
    if (event.key === 'ArrowDown') this.desiredPitch = clampPitch(this.desiredPitch - 3)
    if (event.key === '+' || event.key === '=') this.desiredFov = MathUtils.clamp(this.desiredFov - 2, MIN_FOV, MAX_FOV)
    if (event.key === '-' || event.key === '_') this.desiredFov = MathUtils.clamp(this.desiredFov + 2, MIN_FOV, MAX_FOV)
    this.settleAfter = performance.now() + 140
  }

  private readonly preventContextMenu = (event: MouseEvent) => event.preventDefault()

  constructor(
    private readonly camera: PerspectiveCamera,
    private readonly canvas: HTMLCanvasElement,
    private readonly options: DirectorFixedCameraControlsOptions,
  ) {
    canvas.tabIndex = 0
    canvas.setAttribute('role', 'application')
    canvas.setAttribute('aria-label', '固定机位环视：拖动旋转拍摄角度，滚轮调整视野，方向键微调角度')
    canvas.addEventListener('pointerdown', this.handlePointerDown)
    canvas.addEventListener('pointermove', this.handlePointerMove)
    canvas.addEventListener('pointerup', this.handlePointerUp)
    canvas.addEventListener('pointercancel', this.handlePointerUp)
    canvas.addEventListener('wheel', this.handleWheel, { passive: false })
    canvas.addEventListener('keydown', this.handleKeyDown)
    canvas.addEventListener('contextmenu', this.preventContextMenu)
  }

  sync(cameraId: string, camera: DirectorCamera, force = false): void {
    const signature = JSON.stringify(camera)
    if (!force && this.transactionActive && this.cameraId === cameraId) return
    if (!force && this.cameraId === cameraId && signature === this.signature) return
    const orientation = resolveDirectorCameraOrientation(camera)
    this.cameraId = cameraId
    this.signature = signature
    this.fixedPosition.fromArray(camera.position)
    this.targetDistance = orientation.distance
    this.roll = camera.roll ?? 0
    this.yaw = orientation.yaw
    this.pitch = orientation.pitch
    this.fov = camera.fov
    this.desiredYaw = this.yaw
    this.desiredPitch = this.pitch
    this.desiredFov = this.fov
    this.applyCamera()
  }

  setEnabled(enabled: boolean): void {
    if (this.enabled === enabled) return
    this.enabled = enabled
    if (!enabled) this.finishInteraction(true)
  }

  update(now = performance.now()): boolean {
    if (!this.cameraId) return false
    const deltaSeconds = this.lastUpdateAt > 0 ? Math.min(0.05, Math.max(0.001, (now - this.lastUpdateAt) / 1000)) : 1 / 60
    this.lastUpdateAt = now
    const alpha = 1 - Math.exp(-18 * deltaSeconds)
    const previousYaw = this.yaw
    const previousPitch = this.pitch
    const previousFov = this.fov
    this.yaw = MathUtils.lerp(this.yaw, this.desiredYaw, alpha)
    this.pitch = MathUtils.lerp(this.pitch, this.desiredPitch, alpha)
    this.fov = MathUtils.lerp(this.fov, this.desiredFov, alpha)
    if (Math.abs(this.desiredYaw - this.yaw) < 0.002) this.yaw = this.desiredYaw
    if (Math.abs(this.desiredPitch - this.pitch) < 0.002) this.pitch = this.desiredPitch
    if (Math.abs(this.desiredFov - this.fov) < 0.002) this.fov = this.desiredFov
    const changed = previousYaw !== this.yaw || previousPitch !== this.pitch || previousFov !== this.fov
    if (changed) {
      this.applyCamera()
      const camera = this.readCamera()
      this.signature = JSON.stringify(camera)
      if (this.enabled && this.transactionActive && this.cameraId) this.options.onChange?.(this.cameraId, camera)
    }
    const settled = this.yaw === this.desiredYaw && this.pitch === this.desiredPitch && this.fov === this.desiredFov
    if (this.transactionActive && this.pointerId === null && settled && now >= this.settleAfter) this.finishInteraction(false)
    return changed
  }

  readCamera(): DirectorCamera {
    return {
      position: cameraTuple(this.fixedPosition),
      target: cameraTuple(this.target),
      fov: Number(this.fov.toFixed(4)),
      roll: Number(this.roll.toFixed(2)),
    }
  }

  getTarget(): Vector3 {
    return this.target
  }

  dispose(): void {
    this.finishInteraction(true)
    this.canvas.removeEventListener('pointerdown', this.handlePointerDown)
    this.canvas.removeEventListener('pointermove', this.handlePointerMove)
    this.canvas.removeEventListener('pointerup', this.handlePointerUp)
    this.canvas.removeEventListener('pointercancel', this.handlePointerUp)
    this.canvas.removeEventListener('wheel', this.handleWheel)
    this.canvas.removeEventListener('keydown', this.handleKeyDown)
    this.canvas.removeEventListener('contextmenu', this.preventContextMenu)
  }

  private beginInteraction(): void {
    if (this.transactionActive) return
    this.transactionActive = true
    if (this.cameraId) this.options.onStart?.(this.cameraId)
  }

  private finishInteraction(snapToTarget: boolean): void {
    if (!this.transactionActive) return
    if (snapToTarget) {
      this.yaw = this.desiredYaw
      this.pitch = this.desiredPitch
      this.fov = this.desiredFov
      this.applyCamera()
      if (this.cameraId) this.options.onChange?.(this.cameraId, this.readCamera())
    }
    const cameraId = this.cameraId
    const camera = this.readCamera()
    this.transactionActive = false
    this.pointerId = null
    if (cameraId) this.options.onEnd?.(cameraId, camera)
  }

  private applyCamera(): void {
    const yaw = MathUtils.degToRad(this.yaw)
    const pitch = MathUtils.degToRad(this.pitch)
    const cosPitch = Math.cos(pitch)
    this.target.set(
      this.fixedPosition.x + Math.sin(yaw) * cosPitch * this.targetDistance,
      this.fixedPosition.y + Math.sin(pitch) * this.targetDistance,
      this.fixedPosition.z - Math.cos(yaw) * cosPitch * this.targetDistance,
    )
    this.camera.position.copy(this.fixedPosition)
    this.camera.fov = this.fov
    this.camera.updateProjectionMatrix()
  }
}
