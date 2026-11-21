import { PREVIZ_DIRECTOR_VIEW_ID } from '@/utils/previz/nativeCameras'
import { directorActorHeight } from '@/utils/studio/directorSceneUnits'
import { PCFShadowMap, PerspectiveCamera, Vector3, WebGLRenderer } from 'three'
import type { Object3D, Quaternion } from 'three'
import type { DirectorCamera, DirectorSceneData } from '@/types/studio'
import { resolveDirectorRecordCamera } from '@/utils/studio/directorRecordCamera'
import { applyDirectorPlaybackTransforms } from './directorVideoExport'
import { useDirectorPlaybackStore } from './directorPlaybackStore'
import type { DirectorRuntime } from './DirectorViewport'
import { DirectorFixedCameraControls } from './directorFixedCameraControls'

interface DirectorPrevizInteractionOptions {
  onStart?: (cameraId: string) => void
  onEnd?: (cameraId: string, camera: DirectorCamera) => void
  onCameraChange?: (cameraId: string, camera: DirectorCamera) => void
}

interface DirectorObjectTransformSnapshot {
  object: Object3D
  position: Vector3
  quaternion: Quaternion
  scale: Vector3
}

interface DirectorRuntimeTransformSnapshot {
  objects: DirectorObjectTransformSnapshot[]
  shotCameraFov: number
}

function snapshotDirectorRuntimeTransforms(runtime: DirectorRuntime): DirectorRuntimeTransformSnapshot {
  const snapshots: DirectorObjectTransformSnapshot[] = []
  const capture = (object: Object3D) => snapshots.push({
    object,
    position: object.position.clone(),
    quaternion: object.quaternion.clone(),
    scale: object.scale.clone(),
  })
  runtime.objectRoot.traverse(capture)
  runtime.cameraRoot.traverse(capture)
  capture(runtime.shotCamera)
  return { objects: snapshots, shotCameraFov: runtime.shotCamera.fov }
}

function restoreDirectorRuntimeTransforms(runtime: DirectorRuntime, snapshot: DirectorRuntimeTransformSnapshot | null): void {
  if (!snapshot) return
  snapshot.objects.forEach(({ object, position, quaternion, scale }) => {
    object.position.copy(position)
    object.quaternion.copy(quaternion)
    object.scale.copy(scale)
  })
  runtime.shotCamera.fov = snapshot.shotCameraFov
  runtime.shotCamera.updateProjectionMatrix()
  runtime.objectRoot.updateMatrixWorld(true)
  runtime.cameraRoot.updateMatrixWorld(true)
  runtime.shotCamera.updateMatrixWorld(true)
}

export class DirectorPrevizRenderer {
  readonly canvas = document.createElement('canvas')
  private readonly renderer = new WebGLRenderer({ antialias: true, powerPreference: 'high-performance' })
  readonly previewCanvas = this.renderer.domElement
  readonly nameplateCanvas = document.createElement('canvas')
  private readonly camera = new PerspectiveCamera(50, 16 / 9, 0.01, 1000)
  private readonly controls: DirectorFixedCameraControls | null
  private readonly renderTarget = new Vector3()
  private cameraRoll = 0

  constructor(interaction?: DirectorPrevizInteractionOptions) {
    this.renderer.shadowMap.enabled = true
    this.renderer.shadowMap.type = PCFShadowMap
    if (!interaction) {
      this.controls = null
      return
    }
    this.controls = new DirectorFixedCameraControls(this.camera, this.previewCanvas, {
      onStart: interaction.onStart,
      onChange: interaction.onCameraChange,
      onEnd: interaction.onEnd,
    })
  }

  resize(width: number, height: number) {
    this.canvas.width = width
    this.canvas.height = height
    this.nameplateCanvas.width = width
    this.nameplateCanvas.height = height
    this.renderer.setSize(width, height, false)
    this.camera.aspect = width / height
    this.camera.updateProjectionMatrix()
  }

  syncInteractiveCamera(cameraId: string, camera: DirectorCamera, force = false): void {
    if (!this.controls) {
      this.syncPassiveCamera(camera)
      return
    }
    this.cameraRoll = camera.roll ?? 0
    this.controls.sync(cameraId, camera, force)
    this.renderTarget.copy(this.controls.getTarget())
  }

  setInteractionEnabled(enabled: boolean): void {
    if (!this.controls) return
    this.controls.setEnabled(enabled)
  }

  updateInteraction(): boolean {
    if (!this.controls) return false
    const changed = this.controls.update()
    this.renderTarget.copy(this.controls.getTarget())
    return changed
  }

  drawInteractive(runtime: DirectorRuntime, scene: DirectorSceneData, time: number, nameplates: boolean, showGrid = false): void {
    this.drawFrame(runtime, scene, time, nameplates ? this.nameplateCanvas : null, false, showGrid)
  }

  draw(
    runtime: DirectorRuntime,
    scene: DirectorSceneData,
    time: number,
    mode: 'active_camera' | 'director',
    nameplates: boolean,
    modelingCameraId?: string,
  ) {
    const camera =
      mode === 'director' ? (scene.directorCamera ?? scene.camera) : resolveDirectorRecordCamera(scene, time)
    this.syncPassiveCamera(camera)
    this.drawFrame(runtime, scene, time, nameplates ? this.canvas : null, true, false, mode === 'active_camera' ? modelingCameraId : undefined)
  }

  /** Passive streams use the same optical pose without allocating interactive controls. */
  private syncPassiveCamera(camera: DirectorCamera): void {
    this.camera.position.fromArray(camera.position)
    this.renderTarget.fromArray(camera.target)
    this.cameraRoll = camera.roll ?? 0
    this.camera.fov = camera.fov
    this.camera.updateProjectionMatrix()
  }

  private drawFrame(
    runtime: DirectorRuntime,
    scene: DirectorSceneData,
    time: number,
    nameplateCanvas: HTMLCanvasElement | null,
    applyPlayback: boolean,
    showGrid: boolean,
    modelingCameraId?: string,
  ): void {
    const transformSnapshot = applyPlayback ? snapshotDirectorRuntimeTransforms(runtime) : null
    const environmentTime = runtime.environment.time
    const helpers: Object3D[] = [
      runtime.cameraRoot,
      runtime.timelineRoot,
      runtime.selectionRoot,
      runtime.poseGizmo.root,
      runtime.transformControls.getHelper(),
      runtime.poseRotateGizmo.controls.getHelper(),
    ]
    if (!showGrid) helpers.push(runtime.grid)
    const visible = helpers.map((o) => o.visible)
    try {
      if (applyPlayback) {
        runtime.environment.setTime(time)
        applyDirectorPlaybackTransforms({
          sceneData: scene,
          playhead: time,
          objectRoot: runtime.objectRoot,
          cameraRoot: runtime.cameraRoot,
          shotCamera: runtime.shotCamera,
          editingObjectId: useDirectorPlaybackStore.getState().editingObjectId,
        })
      }
      helpers.forEach((o) => {
        o.visible = false
      })
      this.camera.lookAt(this.renderTarget)
      this.camera.rotateZ(this.cameraRoll * Math.PI / 180)
      this.renderer.toneMapping = runtime.renderer.toneMapping
      this.renderer.toneMappingExposure = runtime.renderer.toneMappingExposure
      const renderCamera = modelingCameraId === PREVIZ_DIRECTOR_VIEW_ID
        ? runtime.editorCamera
        : runtime.modelingCameras.get(modelingCameraId ?? '') ?? this.camera
      this.renderer.render(runtime.scene, renderCamera)
      const output = applyPlayback ? this.canvas : nameplateCanvas
      const ctx = output?.getContext('2d')
      if (!ctx || !output) return
      ctx.clearRect(0, 0, output.width, output.height)
      if (applyPlayback) ctx.drawImage(this.previewCanvas, 0, 0)
      if (nameplateCanvas) {
        const objects = new Map(runtime.objectRoot.children.map(object => [object.userData.objectId, object]))
        for (const actor of scene.objects) {
          if (actor.kind !== 'actor' || actor.visible === false) continue
          const p = new Vector3(...actor.transform.position)
          objects.get(actor.id)?.getWorldPosition(p)
          p.y += directorActorHeight(actor) + 0.2
          p.project(renderCamera)
          if (p.z < -1 || p.z > 1) continue
          const x = ((p.x + 1) * output.width) / 2,
            y = ((1 - p.y) * output.height) / 2
          ctx.font = `${Math.max(12, output.height / 45)}px sans-serif`
          ctx.textAlign = 'center'
          const width = ctx.measureText(actor.name).width + 16
          ctx.fillStyle = 'rgba(0,0,0,.75)'
          ctx.fillRect(x - width / 2, y - 20, width, 26)
          ctx.fillStyle = actor.actorMeta?.identifyColor ?? actor.color
          ctx.fillRect(x - width / 2, y - 20, 3, 26)
          ctx.fillStyle = '#ffffff'
          ctx.fillText(actor.name, x, y)
        }
      }
    } finally {
      if (applyPlayback) runtime.environment.setTime(environmentTime)
      helpers.forEach((o, i) => {
        o.visible = visible[i]
      })
      restoreDirectorRuntimeTransforms(runtime, transformSnapshot)
    }
  }
  dispose() {
    this.controls?.dispose()
    this.renderer.dispose()
    this.renderer.forceContextLoss()
  }
}
