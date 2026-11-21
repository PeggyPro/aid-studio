'use client'

import { directorActorHeight } from '@/utils/studio/directorSceneUnits'

import { forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useRef } from 'react'
import { App } from 'antd'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { TransformControls } from 'three/examples/jsm/controls/TransformControls.js'
import type { DirectorCamera, DirectorLensSpec, DirectorModelAsset, DirectorObject, DirectorSceneData, DirectorTransform, DirectorViewMode } from '@/types/studio'
import { resolveDirectorAspectRatio } from '@/utils/studio/directorScene'
import { normalizeDirectorTimeline } from '@/utils/studio/directorTimeline'
import { useDirectorPlaybackStore } from './directorPlaybackStore'
import { applyDirectorPlaybackTransforms } from './directorVideoExport'
import { captureGlbObjectTransformBase, glbObject, glbObjectTransformDelta, pickGlbObjectId } from '@/utils/previz/glbObjectOverrides'
import { applyDirectorPanoramaTransform, isDirectorInlineMediaUrl, isDirectorOrbitCameraEcho, resolveDirectorPanoramaLoadUrl } from './directorPanorama'
import { applyDirectorPanoramaTexture, createDirectorPanoramaSphere, loadDirectorPanoramaBitmapTexture } from './directorPanoramaRuntime'
import { DirectorModelRuntimeCache } from './directorModelLoader'
import { getDirectorCameraFromRig } from './directorCameraGeometry'
import { buildDirectorCameraHelpers, getDirectorCameraRigVisualBounds, previewDirectorCameraRig } from './directorCameraRuntime'
import { disposeDirectorObjectTree } from './directorRuntimeResources'
import { createDirectorEnvironmentRuntime, directorWeatherLightIntensity, type DirectorEnvironmentRuntime } from './directorEnvironmentRuntime'
import { gridFragment, gridVertex } from './directorAtmosphereShaders'
import { PREVIZ_DIRECTOR_VIEW_ID } from '@/utils/previz/nativeCameras'
import { directorModelingCameraResolver } from '@/utils/studio/directorModelingCamera'
import { resolveDirectorActiveCamera, resolveDirectorActiveCameraId, resolveDirectorCamera, resolveDirectorCameraAimObjectId, shouldEnableDirectorOrbitControls } from './directorCameraState'
import {
  DirectorMannequinRuntimeCache,
  alignDirectorMannequinToGround,
  applyDirectorMannequinPose,
  findDirectorMannequinModelRoot
} from './directorMannequinRuntime'
import {
  createDirectorPoseGizmoRuntime,
  disposeDirectorPoseGizmo,
  pickDirectorPoseJointAtPointer,
  refreshDirectorPoseGizmoPositions,
  syncDirectorPoseGizmo,
  type DirectorPoseGizmoRuntime
} from './directorPoseGizmo'
import {
  attachDirectorPoseRotateGizmo,
  clampDirectorPoseJointValue,
  createDirectorPoseRotateGizmo,
  detachDirectorPoseRotateGizmo,
  disposeDirectorPoseRotateGizmo,
  rotationEulerToJointDelta,
  type DirectorPoseRotateGizmoRuntime
} from './directorPoseRotateGizmo'
import {
  DIRECTOR_VIEWPORT_BACKGROUND,
  DIRECTOR_VIEWPORT_CAMERA_FAR,
  DIRECTOR_VIEWPORT_CAMERA_NEAR,
  DIRECTOR_VIEWPORT_DEFAULT_CAMERA,
  DIRECTOR_VIEWPORT_GEOMETRY_COLOR,
  DIRECTOR_VIEWPORT_GROUND_OPACITY,
  DIRECTOR_VIEWPORT_GROUND_SIZE,
  DIRECTOR_VIEWPORT_GRID_CELL_COLOR,
  DIRECTOR_VIEWPORT_GRID_DIVISIONS,
  DIRECTOR_VIEWPORT_GRID_SECTION_COLOR,
  DIRECTOR_VIEWPORT_GRID_SIZE
} from './directorViewportTheme'
import {
  isDirectorViewportClick,
  shouldApplyDirectorStoredCameraPose,
  shouldSuspendDirectorTransformForPointer,
  isDirectorViewportDoubleClick,
  resolveDirectorViewportPick
} from './directorViewportPick'
import { buildDirectorProp as buildProp } from './directorPrimitiveRuntime'
import { resolveDirectorAttachTargetId, resolveDirectorCrowdGroupId } from './directorCrowdGroup'
import { resolveDirectorCameraRigFocusCamera, resolveDirectorFrontFocusCamera } from './directorCameraFocus'

function getDirectorPreviewCamera(runtime: DirectorRuntime, id?: string): THREE.Camera {
  if (id === PREVIZ_DIRECTOR_VIEW_ID) return runtime.editorCamera
  return runtime.modelingCameras.get(id ?? '') ?? runtime.shotCamera
}

function applyDirectorShotCamera(shotCamera: THREE.PerspectiveCamera, camera: DirectorCamera) {
  shotCamera.position.fromArray(camera.position)
  shotCamera.fov = camera.fov
  shotCamera.lookAt(camera.target[0], camera.target[1], camera.target[2])
  shotCamera.rotateZ((camera.roll ?? 0) * Math.PI / 180)
  shotCamera.updateProjectionMatrix()
}

function captureDirectorViewportFrame(runtime: DirectorRuntime | null, sceneData: DirectorSceneData, camera?: THREE.Camera) {
  if (!runtime) return ''
  const helpers = [runtime.cameraRoot, runtime.selectionRoot, runtime.transformControls.getHelper(), runtime.poseGizmo.root, runtime.poseRotateGizmo.controls.getHelper(), runtime.timelineRoot]
  const visibility = helpers.map(helper => helper.visible)
  try {
    helpers.forEach(helper => { helper.visible = false })
    runtime.renderer.render(runtime.scene, camera ?? runtime.shotCamera)
    const source = runtime.renderer.domElement
    const ratio = resolveDirectorAspectRatio(sceneData.aspectRatio) ?? source.width / Math.max(source.height, 1)
    const sourceRatio = source.width / Math.max(source.height, 1)
    let sourceX = 0
    let sourceY = 0
    let sourceWidth = source.width
    let sourceHeight = source.height
    if (sourceRatio > ratio) {
      sourceWidth = source.height * ratio
      sourceX = (source.width - sourceWidth) / 2
    } else if (sourceRatio < ratio) {
      sourceHeight = source.width / ratio
      sourceY = (source.height - sourceHeight) / 2
    }
    const scale = Math.min(1, 1280 / Math.max(sourceWidth, 1))
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.round(sourceWidth * scale))
    canvas.height = Math.max(1, Math.round(sourceHeight * scale))
    const context = canvas.getContext('2d')
    if (!context) return ''
    context.drawImage(source, sourceX, sourceY, sourceWidth, sourceHeight, 0, 0, canvas.width, canvas.height)
    return canvas.toDataURL('image/jpeg', 0.84)
  } catch {
    return ''
  } finally {
    helpers.forEach((helper, index) => { helper.visible = visibility[index] })
    runtime.render()
  }
}

export interface DirectorViewportHandle {
  getRuntime: () => DirectorRuntime | null
  syncSelection: () => void
  capture: () => string
  captureCurrentView: () => string
  captureFromCamera: (camera: DirectorCamera) => string
  previewCamera: (cameraId: string, camera: DirectorCamera, lens?: DirectorLensSpec) => void
  setTransformMode: (mode: 'translate' | 'rotate' | 'scale') => void
  resetView: () => void
  focusOnObject: (objectId: string) => void
  focusOnCamera: (cameraId: string) => boolean
  getCanvas: () => HTMLCanvasElement | null
  beginVideoCapture: (view: DirectorViewMode) => () => void
  renderVideoCaptureFrame: (time: number) => Promise<ImageBitmap>
}

export interface DirectorViewportSelectOptions {
  additive?: boolean
  selectCrowdGroup?: boolean
  modelPartId?: string
}

interface DirectorViewportProps {
  readOnly?: boolean
  sceneData: DirectorSceneData
  selectedObjectId: string | null
  selectedObjectIds?: string[]
  viewMode: DirectorViewMode
  modelingCameraId?: string
  modelingView?: string | null
  selectedModelPart?: string
  poseEditMode?: boolean
  activePoseJoint?: string | null
  onSelect: (id: string | null, options?: DirectorViewportSelectOptions) => void
  onCameraChange: (camera: DirectorCamera) => void
  onDirectorCameraChange: (camera: DirectorCamera) => void
  onObjectTransform: (id: string, transform: DirectorTransform, companionIds?: string[], modelPartId?: string) => void
  onCameraEntityTransform?: (id: string, camera: DirectorCamera) => void
  onActivePoseJointChange?: (joint: string | null) => void
  onPoseJointChange?: (objectId: string, joint: string, value: number) => void
  onTransformStart?: () => void
  onTransformEnd?: () => void
  onReady?: () => void
  onError?: (error: Error) => void
}

export interface DirectorRuntime {
  scene: THREE.Scene
  editorCamera: THREE.PerspectiveCamera
  shotCamera: THREE.PerspectiveCamera
  renderer: THREE.WebGLRenderer
  controls: OrbitControls
  objectRoot: THREE.Group
  modelingObjects: ReadonlyMap<string, THREE.Object3D>
  modelingCameras: ReadonlyMap<string, THREE.Camera>
  selectedModelPart?: string
  lightRoot: THREE.Group
  cameraRoot: THREE.Group
  timelineRoot: THREE.Group
  grid: THREE.Mesh<THREE.PlaneGeometry, THREE.ShaderMaterial>
  ground: THREE.Mesh<THREE.PlaneGeometry, THREE.MeshStandardMaterial>
  environment: DirectorEnvironmentRuntime
  transformControls: TransformControls
  selectionRoot: THREE.Group
  poseGizmo: DirectorPoseGizmoRuntime
  poseRotateGizmo: DirectorPoseRotateGizmoRuntime
  modelCache: DirectorModelRuntimeCache
  mannequinCache: DirectorMannequinRuntimeCache
  panoramaMesh: THREE.Mesh
  panoramaMaterial: THREE.MeshBasicMaterial
  activeCamera: () => THREE.PerspectiveCamera
  render: () => void
}

export const DirectorViewport = forwardRef<DirectorViewportHandle, DirectorViewportProps>(
  function DirectorViewport({
    readOnly = false,
    sceneData,
    selectedObjectId,
    selectedObjectIds,
    viewMode,
    modelingCameraId,
    modelingView,
    selectedModelPart,
    poseEditMode = false,
    activePoseJoint = null,
    onSelect,
    onCameraChange,
    onDirectorCameraChange,
    onObjectTransform,
    onCameraEntityTransform,
    onActivePoseJointChange,
    onPoseJointChange,
    onTransformStart,
    onTransformEnd,
    onReady,
    onError
  }, ref) {
    const { message } = App.useApp()
    const hostRef = useRef<HTMLDivElement>(null)
    const readOnlyRef = useRef(readOnly)
    readOnlyRef.current = readOnly
    const runtimeRef = useRef<DirectorRuntime | null>(null)
    const initialSceneRef = useRef(sceneData)
    const onSelectRef = useRef(onSelect)
    const onCameraChangeRef = useRef(onCameraChange)
    const onDirectorCameraChangeRef = useRef(onDirectorCameraChange)
    const onObjectTransformRef = useRef(onObjectTransform)
    const onCameraEntityTransformRef = useRef(onCameraEntityTransform)
    const onActivePoseJointChangeRef = useRef(onActivePoseJointChange)
    const onPoseJointChangeRef = useRef(onPoseJointChange)
    const onTransformStartRef = useRef(onTransformStart)
    const onTransformEndRef = useRef(onTransformEnd)
    const onReadyRef = useRef(onReady)
    const onErrorRef = useRef(onError)
    const sceneDataRef = useRef(sceneData)
    const queuedCameraPreview = useRef<{
      sceneId: string; cameraId: string; base: DirectorCamera; camera: DirectorCamera; lens?: DirectorLensSpec
    } | null>(null)
    const liveShotCamera = useRef<{ base: DirectorCamera; camera: DirectorCamera } | null>(null)
    const viewModeRef = useRef(viewMode)
    const recordingViewRef = useRef<DirectorViewMode | null>(null)
    const selectedObjectIdRef = useRef(selectedObjectId)
    const modelingCameraIdRef = useRef(modelingCameraId)
    modelingCameraIdRef.current = modelingCameraId
    const modelingViewRef = useRef(modelingView)
    modelingViewRef.current = modelingView
    const recordingCameraAt = useRef<((time: number) => string | undefined) | null>(null)
    const selectedModelPartRef = useRef(selectedModelPart)
    selectedModelPartRef.current = selectedModelPart
    const selectedObjectIdsRef = useRef<string[]>(selectedObjectIds ?? (selectedObjectId ? [selectedObjectId] : []))
    const poseEditModeRef = useRef(poseEditMode)
    const activePoseJointRef = useRef(activePoseJoint)
    const lastFocusedCameraSelectionRef = useRef<string | null>(null)
    const reportedModelErrorsRef = useRef(new Set<string>())
    const resolvedSelectedIds = useMemo(
      () => selectedObjectIds ?? (selectedObjectId ? [selectedObjectId] : []),
      [selectedObjectId, selectedObjectIds]
    )
    onSelectRef.current = onSelect
    onCameraChangeRef.current = onCameraChange
    onDirectorCameraChangeRef.current = onDirectorCameraChange
    onObjectTransformRef.current = onObjectTransform
    onCameraEntityTransformRef.current = onCameraEntityTransform
    onActivePoseJointChangeRef.current = onActivePoseJointChange
    onPoseJointChangeRef.current = onPoseJointChange
    onTransformStartRef.current = onTransformStart
    onTransformEndRef.current = onTransformEnd
    onReadyRef.current = onReady
    onErrorRef.current = onError
    sceneDataRef.current = sceneData
    viewModeRef.current = viewMode
    selectedObjectIdRef.current = selectedObjectId
    selectedObjectIdsRef.current = resolvedSelectedIds
    poseEditModeRef.current = poseEditMode
    activePoseJointRef.current = activePoseJoint

    const focusOnRuntimeObject = useCallback((runtime: DirectorRuntime, objectId: string): boolean => {
      if (viewModeRef.current !== 'director') return false
      const camera = focusDirectorRuntimeOnObject(runtime, objectId)
      if (!camera) return false
      onDirectorCameraChangeRef.current(camera)
      return true
    }, [])

    const focusOnRuntimeCamera = useCallback((runtime: DirectorRuntime, cameraId: string): boolean => {
      if (viewModeRef.current !== 'director') return false
      const rig = runtime.cameraRoot.children.find((child) => child.userData.cameraId === cameraId)
      if (!rig) return false
      const cameraEntity = findSceneCamera(sceneDataRef.current, cameraId)
      if (!cameraEntity) return false
      const camera = resolveDirectorCameraRigFocusCamera({
        bounds: resolveDirectorCameraFocusBounds(runtime, sceneDataRef.current, cameraId, cameraEntity, rig),
        worldQuaternion: rig.getWorldQuaternion(new THREE.Quaternion()),
        fov: runtime.editorCamera.fov,
        aspect: runtime.editorCamera.aspect,
      })
      if (!camera) return false
      runtime.editorCamera.position.fromArray(camera.position)
      runtime.editorCamera.fov = camera.fov
      runtime.editorCamera.updateProjectionMatrix()
      runtime.controls.object = runtime.editorCamera
      runtime.controls.target.fromArray(camera.target)
      runtime.controls.update()
      runtime.render()
      onDirectorCameraChangeRef.current(camera)
      return true
    }, [])

    useImperativeHandle(ref, () => ({
      capture: () => captureDirectorViewportFrame(runtimeRef.current, sceneDataRef.current),
      captureCurrentView: () => {
        const runtime = runtimeRef.current
        if (!runtime) return ''
        const camera = viewModeRef.current === 'camera'
          ? getDirectorPreviewCamera(runtime, modelingCameraIdRef.current)
          : runtime.editorCamera
        return captureDirectorViewportFrame(runtime, sceneDataRef.current, camera)
      },
      captureFromCamera: (camera) => {
        const runtime = runtimeRef.current
        if (!runtime) return ''
        const previous = {
          position: runtime.shotCamera.position.clone(),
          quaternion: runtime.shotCamera.quaternion.clone(),
          fov: runtime.shotCamera.fov
        }
        try {
          applyDirectorShotCamera(runtime.shotCamera, camera)
          return captureDirectorViewportFrame(runtime, sceneDataRef.current)
        } finally {
          runtime.shotCamera.position.copy(previous.position)
          runtime.shotCamera.quaternion.copy(previous.quaternion)
          runtime.shotCamera.fov = previous.fov
          runtime.shotCamera.updateProjectionMatrix()
          runtime.render()
        }
      },
      previewCamera: (cameraId, camera, lens) => {
        const scene = sceneDataRef.current
        const base = findSceneCamera(scene, cameraId)
        if (base) queuedCameraPreview.current = { sceneId: scene.id, cameraId, base, camera, lens }
      },
      setTransformMode: (mode) => {
        const selectedId = selectedObjectIdRef.current
        const cameraSelected = selectedId === 'main-camera' || sceneDataRef.current.cameraBookmarks?.some((item) => item.id === selectedId)
        runtimeRef.current?.transformControls.setMode(cameraSelected && mode === 'scale' ? 'translate' : mode)
      },
      getRuntime: () => runtimeRef.current,
      syncSelection: () => {
        const runtime = runtimeRef.current
        if (!runtime) return
        runtime.selectedModelPart = selectedModelPartRef.current
        attachSelectedRuntimeTarget(runtime, sceneDataRef.current, selectedObjectIdsRef.current, poseEditModeRef.current)
      },
      getCanvas: () => runtimeRef.current?.renderer.domElement ?? null,
      beginVideoCapture: (recordingView) => {
        const runtime = runtimeRef.current
        if (!runtime) throw new Error('三维视口尚未准备好')
        const controlsEnabled = runtime.controls.enabled
        runtime.controls.enabled = false
        recordingViewRef.current = recordingView
        recordingCameraAt.current = directorModelingCameraResolver(sceneDataRef.current, modelingViewRef.current ?? modelingCameraIdRef.current)
        runtime.render()
        return () => {
          recordingViewRef.current = null
          recordingCameraAt.current = null
          runtime.controls.enabled = controlsEnabled
          runtime.render()
        }
      },
      renderVideoCaptureFrame: async (time) => {
        const runtime = runtimeRef.current
        if (!runtime || !recordingViewRef.current) throw new Error('录制视口尚未准备好')
        useDirectorPlaybackStore.getState().setPlayhead(time)
        applyDirectorPlaybackTransforms({
          sceneData: sceneDataRef.current,
          playhead: time,
          editingObjectId: null,
          objectRoot: runtime.objectRoot,
          cameraRoot: runtime.cameraRoot,
          shotCamera: runtime.shotCamera
        })
        runtime.render()
        return createImageBitmap(runtime.renderer.domElement)
      },
      resetView: () => {
        const runtime = runtimeRef.current
        if (!runtime) return
        const camera = viewModeRef.current === 'camera' ? resolveDirectorActiveCamera(sceneDataRef.current) : sceneDataRef.current.directorCamera
        if (!camera) return
        const active = runtime.activeCamera()
        active.position.fromArray(camera.position)
        active.fov = camera.fov
        active.updateProjectionMatrix()
        runtime.controls.target.fromArray(camera.target)
        runtime.controls.update()
        runtime.render()
      },
      focusOnObject: (objectId: string) => {
        const runtime = runtimeRef.current
        if (runtime) focusOnRuntimeObject(runtime, objectId)
      },
      focusOnCamera: (cameraId: string) => {
        const runtime = runtimeRef.current
        return runtime ? focusOnRuntimeCamera(runtime, cameraId) : false
      },
    }), [focusOnRuntimeCamera, focusOnRuntimeObject])

    useEffect(() => {
      const host = hostRef.current
      if (!host) return
      const initialScene = initialSceneRef.current
      const scene = new THREE.Scene()
      scene.fog = null
      const editorCameraData = initialScene.directorCamera ?? DIRECTOR_VIEWPORT_DEFAULT_CAMERA
      const editorCamera = new THREE.PerspectiveCamera(editorCameraData.fov, 1, DIRECTOR_VIEWPORT_CAMERA_NEAR, DIRECTOR_VIEWPORT_CAMERA_FAR)
      editorCamera.position.fromArray(editorCameraData.position)
      const initialShotCamera = resolveDirectorActiveCamera(initialScene)
      const shotCamera = new THREE.PerspectiveCamera(initialShotCamera.fov, 1, DIRECTOR_VIEWPORT_CAMERA_NEAR, DIRECTOR_VIEWPORT_CAMERA_FAR)
      shotCamera.position.fromArray(initialShotCamera.position)
      let renderer: THREE.WebGLRenderer
      try {
        renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true })
      } catch (error: unknown) {
        onErrorRef.current?.(error instanceof Error ? error : new Error('当前设备无法初始化 WebGL 三维视口'))
        return
      }
      renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
      renderer.shadowMap.enabled = true
      renderer.shadowMap.type = THREE.PCFShadowMap
      renderer.outputColorSpace = THREE.SRGBColorSpace
      renderer.toneMapping = THREE.ACESFilmicToneMapping
      renderer.setClearColor(DIRECTOR_VIEWPORT_BACKGROUND, 1)
      host.appendChild(renderer.domElement)

      const controls = new OrbitControls(viewModeRef.current === 'camera' ? shotCamera : editorCamera, renderer.domElement)
      controls.target.fromArray(viewModeRef.current === 'camera' ? initialShotCamera.target : editorCameraData.target)
      controls.enableDamping = false
      controls.maxPolarAngle = Math.PI * 0.49

      const objectRoot = new THREE.Group()
      objectRoot.name = 'director-objects'
      scene.add(objectRoot)
      const lightRoot = new THREE.Group()
      lightRoot.name = 'director-lights'
      scene.add(lightRoot)
      const cameraRoot = new THREE.Group()
      cameraRoot.name = 'director-cameras'
      scene.add(cameraRoot)
      const timelineRoot = new THREE.Group()
      timelineRoot.name = 'director-timeline-guides'
      scene.add(timelineRoot)

      const groundMaterial = new THREE.MeshStandardMaterial({
        transparent: true,
        opacity: initialScene.groundOpacity ?? DIRECTOR_VIEWPORT_GROUND_OPACITY,
      })
      const ground = new THREE.Mesh(new THREE.PlaneGeometry(DIRECTOR_VIEWPORT_GROUND_SIZE, DIRECTOR_VIEWPORT_GROUND_SIZE), groundMaterial)
      ground.rotation.x = -Math.PI / 2
      ground.visible = initialScene.groundVisible ?? true
      ground.receiveShadow = true
      scene.add(ground)
      const environment = createDirectorEnvironmentRuntime(scene, groundMaterial, window.matchMedia('(prefers-reduced-motion: reduce)').matches)
      environment.sync(initialScene)
      const grid = new THREE.Mesh(
        new THREE.PlaneGeometry(DIRECTOR_VIEWPORT_GRID_SIZE, DIRECTOR_VIEWPORT_GRID_SIZE),
        new THREE.ShaderMaterial({
          uniforms: {
            uCell: { value: DIRECTOR_VIEWPORT_GRID_SIZE / DIRECTOR_VIEWPORT_GRID_DIVISIONS },
            uAxes: { value: new THREE.Color(DIRECTOR_VIEWPORT_GRID_SECTION_COLOR) },
            uLines: { value: new THREE.Color(DIRECTOR_VIEWPORT_GRID_CELL_COLOR) },
          },
          vertexShader: gridVertex,
          fragmentShader: gridFragment,
          transparent: true,
          depthWrite: false,
          toneMapped: false,
        })
      )
      grid.rotation.x = -Math.PI / 2
      grid.position.y = 0.01
      grid.renderOrder = 1
      grid.visible = initialScene.gridVisible ?? true
      scene.add(grid)

      const transformControls = new TransformControls(viewModeRef.current === 'camera' ? shotCamera : editorCamera, renderer.domElement)
      transformControls.enabled = !readOnlyRef.current
      transformControls.setSize(0.55)
      const transformHelper = transformControls.getHelper()
      scene.add(transformHelper)
      const selectionRoot = new THREE.Group()
      selectionRoot.name = 'director-selection'
      scene.add(selectionRoot)
      const poseGizmo = createDirectorPoseGizmoRuntime()
      poseGizmo.root.name = 'director-pose-gizmo'
      scene.add(poseGizmo.root)
      const poseRotateGizmo = createDirectorPoseRotateGizmo(
        viewModeRef.current === 'camera' ? shotCamera : editorCamera,
        renderer.domElement
      )
      scene.add(poseRotateGizmo.controls.getHelper())
      poseRotateGizmo.controls.getHelper().visible = false
      const modelCache = new DirectorModelRuntimeCache()
      const mannequinCache = new DirectorMannequinRuntimeCache()
      const { mesh: panoramaMesh, material: panoramaMaterial } = createDirectorPanoramaSphere()
      scene.add(panoramaMesh)

      const activeCamera = () => viewModeRef.current === 'camera' ? shotCamera : editorCamera
      const captureCamera = () => runtimeRef.current ? getDirectorPreviewCamera(runtimeRef.current,
        recordingViewRef.current === 'camera' ? recordingCameraAt.current?.(useDirectorPlaybackStore.getState().playhead) : modelingCameraIdRef.current) : shotCamera
      const render = () => {
        const modelingFrame = scene.userData.threePrevizFrame as (() => void) | undefined
        modelingFrame?.()
        renderer.toneMappingExposure = sceneDataRef.current.modeling?.project.scene.environment.exposure ?? 1
        if (recordingViewRef.current) {
          environment.setTime(useDirectorPlaybackStore.getState().playhead)
          const helpers = [cameraRoot, timelineRoot, selectionRoot, transformHelper, poseGizmo.root, poseRotateGizmo.controls.getHelper(), grid]
          const visibility = helpers.map((helper) => helper.visible)
          helpers.forEach((helper) => { helper.visible = false })
          try {
            renderer.render(scene, recordingViewRef.current === 'director' ? editorCamera : captureCamera())
          } finally {
            helpers.forEach((helper, index) => { helper.visible = visibility[index] })
          }
          return
        }
        if (viewModeRef.current === 'camera') {
          const stored = resolveDirectorActiveCamera(sceneDataRef.current)
          const live = liveShotCamera.current
          const roll = live?.base === stored ? live.camera.roll : stored.roll
          shotCamera.lookAt(controls.target)
          shotCamera.rotateZ((roll ?? 0) * Math.PI / 180)
        }
        renderer.render(scene, viewModeRef.current === 'camera' ? captureCamera() : editorCamera)
      }
      const flushCamera = () => {
        if (viewModeRef.current === 'camera' && modelingCameraIdRef.current) return
        if (!shouldEnableDirectorOrbitControls(sceneDataRef.current, viewModeRef.current)) return
        const camera = activeCamera()
        const next = {
          position: camera.position.toArray() as [number, number, number],
          target: controls.target.toArray() as [number, number, number],
          fov: camera.fov,
          roll: viewModeRef.current === 'camera' ? resolveDirectorActiveCamera(sceneDataRef.current).roll ?? 0 : 0,
        }
        if (viewModeRef.current === 'camera') onCameraChangeRef.current(next)
        else onDirectorCameraChangeRef.current(next)
      }
      let syncCameraTimer = 0
      const syncCamera = () => {
        window.clearTimeout(syncCameraTimer)
        syncCameraTimer = window.setTimeout(flushCamera, 120)
      }
      controls.addEventListener('change', render)
      controls.addEventListener('end', syncCamera)
      const transformSession = {
        primaryId: null as string | null,
        starts: new Map<string, { position: THREE.Vector3; rotation: THREE.Euler; scale: THREE.Vector3 }>()
      }
      const snapshotCompanionTransforms = (primary: THREE.Object3D) => {
        const primaryId = typeof primary.userData.objectId === 'string' ? primary.userData.objectId : null
        transformSession.primaryId = primaryId
        transformSession.starts.clear()
        if (!primaryId) return
        const ids = selectedObjectIdsRef.current.includes(primaryId)
          ? selectedObjectIdsRef.current
          : [primaryId]
        ids.forEach((id) => {
          const target = objectRoot.children.find((child) => child.userData.objectId === id)
          if (!target) return
          transformSession.starts.set(id, {
            position: target.position.clone(),
            rotation: target.rotation.clone(),
            scale: target.scale.clone()
          })
        })
      }
      const previewCompanionTransforms = (primary: THREE.Object3D) => {
        const start = transformSession.primaryId ? transformSession.starts.get(transformSession.primaryId) : undefined
        if (!start) return
        const deltaPosition = primary.position.clone().sub(start.position)
        const deltaRotation = new THREE.Euler(
          primary.rotation.x - start.rotation.x,
          primary.rotation.y - start.rotation.y,
          primary.rotation.z - start.rotation.z
        )
        const deltaScale = new THREE.Vector3(
          start.scale.x === 0 ? 1 : primary.scale.x / start.scale.x,
          start.scale.y === 0 ? 1 : primary.scale.y / start.scale.y,
          start.scale.z === 0 ? 1 : primary.scale.z / start.scale.z
        )
        transformSession.starts.forEach((origin, id) => {
          if (id === transformSession.primaryId) return
          const target = objectRoot.children.find((child) => child.userData.objectId === id)
          const objectData = sceneDataRef.current.objects.find((item) => item.id === id)
          if (!target || objectData?.locked) return
          target.position.copy(origin.position).add(deltaPosition)
          target.rotation.set(
            origin.rotation.x + deltaRotation.x,
            origin.rotation.y + deltaRotation.y,
            origin.rotation.z + deltaRotation.z
          )
          target.scale.copy(origin.scale).multiply(deltaScale)
        })
      }
      let modelPartDrag: { id: string; part: string; root: THREE.Object3D; object: THREE.Object3D; before: DirectorTransform } | null = null
      const beginTransform = () => {
        if (readOnlyRef.current) return
        controls.enabled = false
        const object = transformControls.object
        const id = selectedObjectIdRef.current
        const root = id ? runtimeRef.current?.modelingObjects.get(id) : undefined
        const part = selectedModelPartRef.current
        modelPartDrag = null
        if (root && object) {
          useDirectorPlaybackStore.getState().setPlaying(false)
          if (part && id && glbObject(root, part) === object) {
            captureGlbObjectTransformBase(root, part)
            modelPartDrag = { id, part, root, object, before: readRuntimeTransform(object) }
          }
        }
        const editingId = typeof object?.userData.cameraId === 'string'
          ? object.userData.cameraId
          : typeof object?.userData.objectId === 'string'
            ? object.userData.objectId
            : null
        useDirectorPlaybackStore.getState().setEditingObjectId(editingId)
        if (object && typeof object.userData.objectId === 'string') snapshotCompanionTransforms(object)
        else {
          transformSession.primaryId = null
          transformSession.starts.clear()
        }
        onTransformStartRef.current?.()
      }
      const handleTransformChange = () => {
        const object = transformControls.object
        if (object && typeof object.userData.objectId === 'string') previewCompanionTransforms(object)
        render()
      }
      transformControls.addEventListener('mouseDown', beginTransform)
      transformControls.addEventListener('objectChange', handleTransformChange)
      const finishTransform = () => {
        if (readOnlyRef.current) return
        controls.enabled = shouldEnableDirectorOrbitControls(sceneDataRef.current, viewModeRef.current)
        useDirectorPlaybackStore.getState().setEditingObjectId(null)
        const object = transformControls.object
        const partDrag = modelPartDrag
        modelPartDrag = null
        if (partDrag) {
          const transformed = readRuntimeTransform(partDrag.object)
          partDrag.object.position.fromArray(partDrag.before.position)
          partDrag.object.rotation.set(...partDrag.before.rotation)
          partDrag.object.scale.fromArray(partDrag.before.scale)
          try {
            if (object === partDrag.object && selectedObjectIdRef.current === partDrag.id && selectedModelPartRef.current === partDrag.part) {
              const delta = glbObjectTransformDelta(partDrag.root, partDrag.part, transformed)
              onObjectTransformRef.current(partDrag.id, { position: delta.positionOffset!, rotation: delta.rotationOffset!, scale: delta.scaleMultiplier! }, [], partDrag.part)
            }
          } catch (error) { message.warning(error instanceof Error ? error.message : '模型部件无法变换') }
          onTransformEndRef.current?.()
          return
        }
        const cameraId = typeof object?.userData.cameraId === 'string' ? object.userData.cameraId : null
        if (object && cameraId) {
          const cameraData = findSceneCamera(sceneDataRef.current, cameraId)
          if (cameraData) {
            const targetDistance = new THREE.Vector3(...cameraData.target).distanceTo(new THREE.Vector3(...cameraData.position))
            onCameraEntityTransformRef.current?.(
              cameraId,
              getDirectorCameraFromRig(
                object.position.toArray() as [number, number, number],
                object.quaternion,
                targetDistance,
                cameraData.fov
              )
            )
          }
          transformSession.primaryId = null
          transformSession.starts.clear()
          onTransformEndRef.current?.()
          return
        }
        const objectId = typeof object?.userData.objectId === 'string' ? object.userData.objectId
          : typeof object?.userData.previzNodeId === 'string' ? object.userData.previzNodeId : null
        if (object && objectId) {
          const companionIds = selectedObjectIdsRef.current.includes(objectId)
            ? selectedObjectIdsRef.current.filter((id) => id !== objectId)
            : []
          onObjectTransformRef.current(objectId, readRuntimeTransform(object), companionIds)
        }
        transformSession.primaryId = null
        transformSession.starts.clear()
        onTransformEndRef.current?.()
      }
      transformControls.addEventListener('mouseUp', finishTransform)

      const computePoseJointValue = (joint: string, startValue: number): number => {
        const delta = rotationEulerToJointDelta(
          joint,
          poseRotateGizmo.pivot.rotation,
          poseRotateGizmo.controls.axis as string | null
        )
        return clampDirectorPoseJointValue(startValue + delta)
      }

      const applyPoseJointPreview = (joint: string, startValue: number): number => {
        const objectId = selectedObjectIdRef.current
        if (!objectId) return startValue
        const value = computePoseJointValue(joint, startValue)
        const object = sceneDataRef.current.objects.find((item) => item.id === objectId)
        const objectTarget = runtimeRef.current?.objectRoot.children.find((child) => child.userData.objectId === objectId)
        if (object && objectTarget) {
          const model = findDirectorMannequinModelRoot(objectTarget)
          if (model) {
            applyDirectorMannequinPose(
              model,
              { ...(object.pose ?? {}), [joint]: value },
              model.userData.directorRestPose
            )
          }
        }
        runtimeRef.current?.render()
        return value
      }

      const beginPoseRotate = () => {
        const joint = poseRotateGizmo.activeJoint
        const objectId = selectedObjectIdRef.current
        if (joint && objectId) {
          const object = sceneDataRef.current.objects.find((item) => item.id === objectId)
          poseRotateGizmo.startValue = object?.pose[joint] ?? 0
        }
        poseRotateGizmo.dragging = true
        controls.enabled = false
        onTransformStartRef.current?.()
      }
      const handlePoseRotateChange = () => {
        const joint = poseRotateGizmo.activeJoint
        if (!joint) return
        const unclamped = poseRotateGizmo.startValue + rotationEulerToJointDelta(
          joint,
          poseRotateGizmo.pivot.rotation,
          poseRotateGizmo.controls.axis as string | null
        )
        const value = clampDirectorPoseJointValue(unclamped)
        if (value !== unclamped) {
          poseRotateGizmo.startValue = value
          poseRotateGizmo.pivot.rotation.set(0, 0, 0)
        }
        applyPoseJointPreview(joint, poseRotateGizmo.startValue)
      }
      const finishPoseRotate = () => {
        const joint = poseRotateGizmo.activeJoint
        const objectId = selectedObjectIdRef.current
        if (!joint || !objectId) return
        const value = computePoseJointValue(joint, poseRotateGizmo.startValue)
        poseRotateGizmo.pivot.rotation.set(0, 0, 0)
        poseRotateGizmo.dragging = false
        onPoseJointChangeRef.current?.(objectId, joint, value)
        poseRotateGizmo.startValue = value
        const runtime = runtimeRef.current
        if (runtime) {
          const objectTarget = runtime.objectRoot.children.find((child) => child.userData.objectId === objectId)
          const object = sceneDataRef.current.objects.find((item) => item.id === objectId)
          const model = objectTarget ? findDirectorMannequinModelRoot(objectTarget) : undefined
          if (model && object) {
            applyDirectorMannequinPose(model, { ...(object.pose ?? {}), [joint]: value }, model.userData.directorRestPose)
          }
          syncDirectorPoseGizmo(runtime.poseGizmo, objectTarget, joint)
        }
        controls.enabled = shouldEnableDirectorOrbitControls(sceneDataRef.current, viewModeRef.current)
        onTransformEndRef.current?.()
        runtimeRef.current?.render()
      }
      poseRotateGizmo.controls.addEventListener('mouseDown', beginPoseRotate)
      poseRotateGizmo.controls.addEventListener('objectChange', handlePoseRotateChange)
      poseRotateGizmo.controls.addEventListener('mouseUp', finishPoseRotate)

      const resize = () => {
        const width = Math.max(host.clientWidth, 1)
        const height = Math.max(host.clientHeight, 1)
        renderer.setSize(width, height, false)
        editorCamera.aspect = width / height
        shotCamera.aspect = width / height
        editorCamera.updateProjectionMatrix()
        shotCamera.updateProjectionMatrix()
        render()
      }
      const resizeObserver = new ResizeObserver(resize)
      resizeObserver.observe(host)

      const raycaster = new THREE.Raycaster()
      raycaster.params.Line = { threshold: 0.18 }
      const pointer = new THREE.Vector2()
      const updatePointer = (event: PointerEvent) => {
        const rect = renderer.domElement.getBoundingClientRect()
        pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1
        pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1
        raycaster.setFromCamera(pointer, activeCamera())
      }
      const intersectsTransformHelper = (caster: THREE.Raycaster): boolean => {
        const helpers = [transformControls.getHelper(), poseRotateGizmo.controls.getHelper()]
        for (const helper of helpers) {
          if (!helper.visible) continue
          if (caster.intersectObject(helper, true).length > 0) return true
        }
        return false
      }
      const pickPoseJointAtPointer = (event: PointerEvent): string | null => {
        const runtime = runtimeRef.current
        if (!runtime || !selectedObjectIdRef.current) return null
        const objectTarget = runtime.objectRoot.children.find(
          (child) => child.userData.objectId === selectedObjectIdRef.current
        )
        if (!objectTarget) return null
        if (runtime.poseGizmo.handles.size === 0) {
          syncDirectorPoseGizmo(runtime.poseGizmo, objectTarget, activePoseJointRef.current)
        } else if (!runtime.poseRotateGizmo.dragging) {
          refreshDirectorPoseGizmoPositions(runtime.poseGizmo, objectTarget)
        }
        const rect = renderer.domElement.getBoundingClientRect()
        return pickDirectorPoseJointAtPointer(
          runtime.poseGizmo,
          objectTarget,
          raycaster,
          activeCamera(),
          pointer,
          rect
        )
      }
      const selectPoseJoint = (joint: string, event: PointerEvent) => {
        const object = sceneDataRef.current.objects.find((item) => item.id === selectedObjectIdRef.current)
        if (!object || object.locked === true) return
        onActivePoseJointChangeRef.current?.(joint)
        const runtime = runtimeRef.current
        const objectTarget = runtime?.objectRoot.children.find((child) => child.userData.objectId === selectedObjectIdRef.current)
        const model = objectTarget ? findDirectorMannequinModelRoot(objectTarget) : undefined
        if (runtime && model) {
          attachDirectorPoseRotateGizmo(
            runtime.poseRotateGizmo,
            runtime.scene,
            model,
            joint,
            object.pose[joint] ?? 0
          )
          syncDirectorPoseGizmo(runtime.poseGizmo, objectTarget, joint)
          runtime.render()
        }
        event.preventDefault()
        event.stopPropagation()
        event.stopImmediatePropagation()
      }
      const pickSelectable = (part = false) => {
        const modeling = runtimeRef.current?.modelingObjects
        const hit = raycaster.intersectObjects([...objectRoot.children, ...cameraRoot.children, ...(modeling?.values() ?? [])], true)
          .find(({ object }) => isWorldVisible(object))
        const id = findObjectId(hit?.object)
        const root = id ? modeling?.get(id) : undefined
        return { id, part: root && hit ? pickGlbObjectId(root, hit.object, part) : undefined }
      }
      const pointerPick = {
        active: false,
        pointerId: 0,
        x: 0,
        y: 0,
        helperHit: false,
        objectId: null as string | null,
        modelPartId: undefined as string | undefined,
        shift: false,
        transformSuspended: false
      }
      let lastObjectClick: { id: string; at: number } | null = null
      const isTransformDragging = () => Boolean(
        (transformControls as { dragging?: boolean }).dragging
        || poseRotateGizmo.dragging
      )
      const restoreSuspendedTransform = () => {
        if (!pointerPick.transformSuspended) return
        pointerPick.transformSuspended = false
        transformControls.enabled = !readOnlyRef.current
      }
      const updateHoverCursor = (event: PointerEvent) => {
        const rect = renderer.domElement.getBoundingClientRect()
        const inside = event.clientX >= rect.left && event.clientX <= rect.right
          && event.clientY >= rect.top && event.clientY <= rect.bottom
        if (!inside) {
          renderer.domElement.style.cursor = 'grab'
          return
        }
        if (isTransformDragging() || (pointerPick.active && !isDirectorViewportClick(
          event.clientX - pointerPick.x,
          event.clientY - pointerPick.y
        ))) {
          renderer.domElement.style.cursor = 'grabbing'
          return
        }
        updatePointer(event)
        const poseJoint = poseEditModeRef.current && selectedObjectIdRef.current
          ? pickPoseJointAtPointer(event)
          : null
        renderer.domElement.style.cursor = poseJoint || intersectsTransformHelper(raycaster) || pickSelectable().id
          ? 'pointer'
          : 'grab'
      }
      const handlePointerDown = (event: PointerEvent) => {
        if (readOnlyRef.current) return
        if (event.button !== 0) return
        if (isTransformDragging()) return
        restoreSuspendedTransform()
        // 捕获阶段先恢复轨道控制器，让当前这次按下就能开始拖动画布。
        controls.enabled = shouldEnableDirectorOrbitControls(sceneDataRef.current, viewModeRef.current)
        updatePointer(event)
        if (poseEditModeRef.current && selectedObjectIdRef.current) {
          const joint = pickPoseJointAtPointer(event)
          if (joint) {
            selectPoseJoint(joint, event)
            return
          }
          if (intersectsTransformHelper(raycaster)) return
        }
        pointerPick.active = true
        pointerPick.pointerId = event.pointerId
        pointerPick.x = event.clientX
        pointerPick.y = event.clientY
        pointerPick.helperHit = intersectsTransformHelper(raycaster)
        const picked = pickSelectable(event.altKey)
        pointerPick.objectId = picked.id
        pointerPick.modelPartId = picked.part
        pointerPick.shift = event.shiftKey || event.ctrlKey || event.metaKey
        if (shouldSuspendDirectorTransformForPointer({
          pickedId: pointerPick.objectId,
          selectedId: selectedObjectIdRef.current,
          dragging: false
        })) {
          pointerPick.transformSuspended = true
          transformControls.enabled = false
        }
      }
      const handlePointerUp = (event: PointerEvent) => {
        if (readOnlyRef.current) return
        if (!pointerPick.active || event.pointerId !== pointerPick.pointerId) return
        pointerPick.active = false
        restoreSuspendedTransform()
        if (!isTransformDragging()) {
          // 防止变换中断后轨道控制器一直被关掉，首次进入时表现为“拖不动”。
          controls.enabled = shouldEnableDirectorOrbitControls(sceneDataRef.current, viewModeRef.current)
          const isClick = event.button === 0 && isDirectorViewportClick(
            event.clientX - pointerPick.x,
            event.clientY - pointerPick.y
          )
          if (isClick) {
            const action = resolveDirectorViewportPick(pointerPick.helperHit, pointerPick.objectId)
            if (action.type !== 'keep') {
              const now = Date.now()
              const selectCrowdGroup = Boolean(
                action.id
                && !pointerPick.shift
                && isDirectorViewportDoubleClick(lastObjectClick, action.id, now)
                && sceneDataRef.current.objects.some((item) => item.id === action.id && Boolean(resolveDirectorCrowdGroupId(item)))
              )
              lastObjectClick = action.id ? { id: action.id, at: now } : null
              onSelectRef.current(action.id, {
                additive: pointerPick.shift,
                selectCrowdGroup,
                modelPartId: pointerPick.modelPartId,
              })
            }
          }
        }
        updateHoverCursor(event)
      }
      const handlePointerCancel = (event: PointerEvent) => {
        if (!pointerPick.active || event.pointerId !== pointerPick.pointerId) return
        pointerPick.active = false
        restoreSuspendedTransform()
        if (!isTransformDragging()) {
          controls.enabled = shouldEnableDirectorOrbitControls(sceneDataRef.current, viewModeRef.current)
        }
        renderer.domElement.style.cursor = 'grab'
      }
      const handlePointerLeave = () => {
        if (!pointerPick.active && !isTransformDragging()) {
          renderer.domElement.style.cursor = 'grab'
        }
      }
      const handleWindowBlur = () => {
        pointerPick.active = false
        restoreSuspendedTransform()
        if (!isTransformDragging()) {
          controls.enabled = shouldEnableDirectorOrbitControls(sceneDataRef.current, viewModeRef.current)
        }
        renderer.domElement.style.cursor = 'grab'
      }
      renderer.domElement.addEventListener('pointerdown', handlePointerDown, true)
      renderer.domElement.addEventListener('pointermove', updateHoverCursor, true)
      renderer.domElement.addEventListener('pointerleave', handlePointerLeave)
      window.addEventListener('pointerup', handlePointerUp)
      window.addEventListener('pointercancel', handlePointerCancel)
      window.addEventListener('blur', handleWindowBlur)
      const handleContextLost = (event: Event) => {
        event.preventDefault()
        onErrorRef.current?.(new Error('WebGL 上下文已丢失，请重试加载导演台'))
      }
      renderer.domElement.addEventListener('webglcontextlost', handleContextLost)

      runtimeRef.current = { scene, editorCamera, shotCamera, renderer, controls, objectRoot, modelingObjects: new Map(), modelingCameras: new Map(), lightRoot, cameraRoot, timelineRoot, grid, ground, environment, transformControls, selectionRoot, poseGizmo, poseRotateGizmo, modelCache, mannequinCache, panoramaMesh, panoramaMaterial, activeCamera, render }
      resize()
      let readyFollowUp = 0
      const readyFrame = window.requestAnimationFrame(() => {
        onReadyRef.current?.()
        // 加载遮罩卸载后再强制 resize + 重绑，避免首帧手柄挂在占位体上。
        readyFollowUp = window.requestAnimationFrame(() => {
          const runtime = runtimeRef.current
          if (!runtime) return
          resize()
          rebindSelectedTransform(
            runtime,
            sceneDataRef.current,
            selectedObjectIdsRef.current,
            poseEditModeRef.current,
            viewModeRef.current
          )
          runtime.render()
        })
      })

      return () => {
        runtimeRef.current = null
        window.cancelAnimationFrame(readyFrame)
        window.cancelAnimationFrame(readyFollowUp)
        resizeObserver.disconnect()
        renderer.domElement.removeEventListener('pointerdown', handlePointerDown, true)
        renderer.domElement.removeEventListener('pointermove', updateHoverCursor, true)
        renderer.domElement.removeEventListener('pointerleave', handlePointerLeave)
        window.removeEventListener('pointerup', handlePointerUp)
        window.removeEventListener('pointercancel', handlePointerCancel)
        window.removeEventListener('blur', handleWindowBlur)
        poseRotateGizmo.controls.removeEventListener('mouseDown', beginPoseRotate)
        poseRotateGizmo.controls.removeEventListener('objectChange', handlePoseRotateChange)
        poseRotateGizmo.controls.removeEventListener('mouseUp', finishPoseRotate)
        renderer.domElement.removeEventListener('webglcontextlost', handleContextLost)
        window.clearTimeout(syncCameraTimer)
        controls.removeEventListener('change', render)
        controls.removeEventListener('end', syncCamera)
        transformControls.removeEventListener('mouseDown', beginTransform)
        transformControls.removeEventListener('objectChange', handleTransformChange)
        transformControls.removeEventListener('mouseUp', finishTransform)
        transformControls.detach()
        scene.remove(transformHelper)
        transformHelper.removeFromParent()
        transformControls.dispose()
        modelCache.dispose()
        mannequinCache.dispose()
        controls.dispose()
        disposeDirectorObjectTree(objectRoot)
        disposeDirectorObjectTree(lightRoot)
        disposeDirectorObjectTree(cameraRoot)
        disposeDirectorObjectTree(timelineRoot)
        disposeDirectorObjectTree(selectionRoot)
        disposeDirectorPoseGizmo(poseGizmo)
        disposeDirectorPoseRotateGizmo(poseRotateGizmo, scene)
        applyDirectorPanoramaTexture(panoramaMaterial, null)
        panoramaMesh.geometry.dispose()
        panoramaMaterial.dispose()
        environment.dispose()
        ground.geometry.dispose()
        groundMaterial.dispose()
        grid.geometry.dispose()
        const gridMaterials = Array.isArray(grid.material) ? grid.material : [grid.material]
        gridMaterials.forEach((material) => material.dispose())
        renderer.dispose()
        renderer.forceContextLoss()
        renderer.domElement.remove()
      }
    }, [message])

    useEffect(() => {
      const runtime = runtimeRef.current
      if (!runtime) return
      runtime.modelCache.retainOnly(sceneData.modelAssets ?? [])
      const existingObjects = new Map(runtime.objectRoot.children.filter(child => !child.userData.directorScenery).map((child) => [child.userData.objectId as string, child]))
      const assetsById = new Map((sceneData.modelAssets ?? []).map((asset) => [asset.id, asset]))
      const replaceWithMannequin = (
        sourceObject: DirectorObject,
        placeholder: THREE.Group,
        expectedModelAssetId: string | null
      ) => {
        const sourceSelected = resolvedSelectedIds.includes(sourceObject.id)
        void runtime.mannequinCache.load(sourceObject, sourceSelected).then((mannequin) => {
          const current = runtime.objectRoot.children.find((child) => child.userData.objectId === sourceObject.id)
          const latestObject = sceneDataRef.current.objects.find((item) => item.id === sourceObject.id)
          const latestAsset = latestObject?.modelAssetId
            ? sceneDataRef.current.modelAssets?.find((item) => item.id === latestObject.modelAssetId)
            : undefined
          if (
            runtimeRef.current !== runtime ||
            current !== placeholder ||
            !latestObject ||
            latestObject.kind !== 'actor' ||
            latestObject.visible === false ||
            (latestAsset?.id ?? null) !== expectedModelAssetId
          ) {
            disposeDirectorObjectTree(mannequin)
            return
          }
          mannequin.traverse((child) => markObject(child, latestObject.id))
          reportedModelErrorsRef.current.delete('builtin-mannequin')
          applyTransform(mannequin, latestObject)
          mannequin.userData.renderSignature = directorObjectSignature(latestObject, latestAsset)
          replaceRuntimeObject(runtime, placeholder, mannequin)
          rebindSelectedTransform(
            runtime,
            sceneDataRef.current,
            selectedObjectIdsRef.current,
            poseEditModeRef.current,
            viewModeRef.current
          )
          if (poseEditModeRef.current && selectedObjectIdRef.current === latestObject.id) {
            focusOnRuntimeObject(runtime, latestObject.id)
          }
          runtime.render()
        }).catch((error: unknown) => {
          const current = runtime.objectRoot.children.find((child) => child.userData.objectId === sourceObject.id)
          const latestObject = sceneDataRef.current.objects.find((item) => item.id === sourceObject.id)
          const latestAsset = latestObject?.modelAssetId
            ? sceneDataRef.current.modelAssets?.find((item) => item.id === latestObject.modelAssetId)
            : undefined
          if (
            runtimeRef.current !== runtime ||
            current !== placeholder ||
            !latestObject ||
            latestObject.kind !== 'actor' ||
            latestObject.visible === false ||
            (latestAsset?.id ?? null) !== expectedModelAssetId ||
            reportedModelErrorsRef.current.has('builtin-mannequin')
          ) return
          reportedModelErrorsRef.current.add('builtin-mannequin')
          const reason = error instanceof Error ? error.message : '内置人体模型不可用'
          message.warning(`${reason}，请刷新导演台后重试`)
        })
      }
      for (const object of sceneData.objects.filter((item) => item.visible !== false && item.kind !== 'scenery')) {
        const selected = resolvedSelectedIds.includes(object.id)
        const modelAsset = object.modelAssetId ? assetsById.get(object.modelAssetId) : undefined
        const signature = directorObjectSignature(object, modelAsset)
        const existing = existingObjects.get(object.id)
        if (existing instanceof THREE.Group && existing.userData.renderSignature === signature) {
          applyTransform(existing, object)
          if (!runtime.poseRotateGizmo.dragging && !runtime.poseRotateGizmo.controls.axis) {
            applyDirectorActorPose(existing, object)
          }
          existingObjects.delete(object.id)
          continue
        }
        if (existing) {
          detachTransformIfTarget(runtime, existing)
          runtime.objectRoot.remove(existing)
          disposeDirectorObjectTree(existing)
          existingObjects.delete(object.id)
        }
        const useBuiltInMannequin = object.kind === 'actor' && !modelAsset
        const group = modelAsset ? new THREE.Group() : object.kind === 'actor' ? buildActorAnchor(object) : buildProp(object, selected)
        if (modelAsset) markObject(group, object.id)
        group.userData.renderSignature = signature
        if (modelAsset || useBuiltInMannequin) group.userData.directorPendingModel = true
        runtime.objectRoot.add(group)
        if (modelAsset) {
          void runtime.modelCache.load(modelAsset, object).then((imported) => {
            const current = runtime.objectRoot.children.find((child) => child.userData.objectId === object.id)
            const latestObject = sceneDataRef.current.objects.find((item) => item.id === object.id)
            const latestAsset = latestObject?.modelAssetId
              ? sceneDataRef.current.modelAssets?.find((item) => item.id === latestObject.modelAssetId)
              : undefined
            if (
              runtimeRef.current !== runtime ||
              current !== group ||
              !latestObject ||
              latestObject.visible === false ||
              latestAsset?.id !== modelAsset.id
            ) {
              disposeDirectorObjectTree(imported)
              return
            }
            imported.traverse((child) => markObject(child, latestObject.id))
            reportedModelErrorsRef.current.delete(modelAsset.id)
            applyTransform(imported, latestObject)
            imported.userData.renderSignature = directorObjectSignature(latestObject, latestAsset)
            replaceRuntimeObject(runtime, group, imported)
            rebindSelectedTransform(
              runtime,
              sceneDataRef.current,
              selectedObjectIdsRef.current,
              poseEditModeRef.current,
              viewModeRef.current
            )
            if (poseEditModeRef.current && selectedObjectIdRef.current === latestObject.id) {
              focusOnRuntimeObject(runtime, latestObject.id)
            }
            runtime.render()
          }).catch((error: unknown) => {
            const current = runtime.objectRoot.children.find((child) => child.userData.objectId === object.id)
            const latestObject = sceneDataRef.current.objects.find((item) => item.id === object.id)
            const latestAsset = latestObject?.modelAssetId
              ? sceneDataRef.current.modelAssets?.find((item) => item.id === latestObject.modelAssetId)
              : undefined
            if (
              runtimeRef.current !== runtime ||
              current !== group ||
              !latestObject ||
              latestObject.visible === false ||
              latestAsset?.id !== modelAsset.id
            ) return
            if (!reportedModelErrorsRef.current.has(modelAsset.id)) {
              reportedModelErrorsRef.current.add(modelAsset.id)
              const reason = error instanceof Error ? error.message : '模型资源不可用'
              message.error(`${modelAsset.name} 加载失败：${reason}`)
              onErrorRef.current?.(new Error(`${modelAsset.name} 加载失败：${reason}`))
            }
          })
        } else if (useBuiltInMannequin) {
          replaceWithMannequin(object, group, null)
        }
      }
      existingObjects.forEach((existing) => {
        detachTransformIfTarget(runtime, existing)
        runtime.objectRoot.remove(existing)
        disposeDirectorObjectTree(existing)
      })
      attachSelectedRuntimeTarget(runtime, sceneDataRef.current, resolvedSelectedIds, poseEditModeRef.current)
      syncSelectionRing(runtime, sceneDataRef.current, resolvedSelectedIds)
      if (!runtime.poseRotateGizmo.dragging) {
        syncPoseGizmoRuntime(runtime, sceneDataRef.current, selectedObjectId, poseEditModeRef.current, activePoseJointRef.current)
      }
      runtime.render()
    }, [sceneData.modelAssets, sceneData.objects, selectedObjectId, resolvedSelectedIds, poseEditMode, activePoseJoint, focusOnRuntimeObject, message])

    useEffect(() => {
      const runtime = runtimeRef.current
      if (!runtime) return
      disposeDirectorObjectTree(runtime.lightRoot)
      runtime.lightRoot.clear()
      for (const lightData of sceneData.lights.filter((light) => light.enabled)) {
        runtime.lightRoot.add(buildLight(lightData, sceneData))
      }
      runtime.render()
    }, [sceneData.lights, sceneData.environmentPreset, sceneData.weather])

    useEffect(() => {
      const runtime = runtimeRef.current
      if (!runtime) return
      runtime.environment.sync(sceneData)
      runtime.render()
    }, [sceneData.environmentColor, sceneData.environmentPreset, sceneData.weather, sceneData.lights])

    useEffect(() => {
      const runtime = runtimeRef.current
      if (!runtime) return
      const currentScene = sceneDataRef.current
      runtime.grid.visible = currentScene.gridVisible ?? true
      runtime.transformControls.setTranslationSnap(currentScene.gridSnap ? 0.25 : null)
      runtime.transformControls.setRotationSnap(currentScene.gridSnap ? Math.PI / 12 : null)
      runtime.ground.visible = currentScene.groundVisible ?? true
      runtime.ground.material.opacity = currentScene.groundOpacity ?? DIRECTOR_VIEWPORT_GROUND_OPACITY
      const shotCamera = resolveDirectorActiveCamera(currentScene)
      const editorCamera = currentScene.directorCamera
      const orbitEcho = viewMode === 'director' && editorCamera
        ? isDirectorOrbitCameraEcho(
          {
            position: runtime.editorCamera.position.toArray() as [number, number, number],
            target: runtime.controls.target.toArray() as [number, number, number],
            fov: runtime.editorCamera.fov
          },
          editorCamera
        )
        : viewMode === 'camera' && isDirectorOrbitCameraEcho(
          {
            position: runtime.shotCamera.position.toArray() as [number, number, number],
            target: runtime.controls.target.toArray() as [number, number, number],
            fov: runtime.shotCamera.fov
          },
          shotCamera
        )
      if (shouldApplyDirectorStoredCameraPose(orbitEcho)) {
        runtime.shotCamera.position.fromArray(shotCamera.position)
        runtime.shotCamera.fov = shotCamera.fov
        runtime.shotCamera.updateProjectionMatrix()
        if (editorCamera) {
          runtime.editorCamera.position.fromArray(editorCamera.position)
          runtime.editorCamera.fov = editorCamera.fov
          runtime.editorCamera.updateProjectionMatrix()
        }
        const target = viewMode === 'camera' ? shotCamera.target : editorCamera?.target ?? DIRECTOR_VIEWPORT_DEFAULT_CAMERA.target
        runtime.controls.target.fromArray(target)
        runtime.controls.update()
      }
      runtime.controls.object = runtime.activeCamera()
      runtime.selectedModelPart = selectedModelPart
      // Camera rigs are rebuilt below; a detached rig cannot drive TransformControls.
      runtime.transformControls.detach()
      buildDirectorCameraHelpers(runtime.cameraRoot, currentScene, viewMode, selectedObjectId)
      const cameraSelected = selectedObjectId === 'main-camera'
        || currentScene.cameraBookmarks?.some((item) => item.id === selectedObjectId)
      if (viewMode !== 'director' || !cameraSelected) {
        lastFocusedCameraSelectionRef.current = null
      } else if (selectedObjectId && lastFocusedCameraSelectionRef.current !== selectedObjectId) {
        if (focusOnRuntimeCamera(runtime, selectedObjectId)) {
          lastFocusedCameraSelectionRef.current = selectedObjectId
        }
      }
      attachSelectedRuntimeTarget(runtime, currentScene, resolvedSelectedIds, poseEditMode)
      syncSelectionRing(runtime, currentScene, resolvedSelectedIds)
      if (!runtime.poseRotateGizmo.dragging) {
        syncPoseGizmoRuntime(runtime, currentScene, selectedObjectId, poseEditMode, activePoseJoint)
      }
      runtime.controls.enabled = !(viewMode === 'camera' && modelingCameraId) && shouldEnableDirectorOrbitControls(currentScene, viewMode)
      runtime.transformControls.camera = runtime.activeCamera()
      runtime.poseRotateGizmo.controls.camera = runtime.activeCamera()
      runtime.render()
    }, [
      sceneData.gridVisible,
      sceneData.gridSnap,
      sceneData.groundVisible,
      sceneData.groundOpacity,
      sceneData.camera,
      modelingCameraId,
      selectedModelPart,
      sceneData.directorCamera,
      sceneData.cameraBookmarks,
      sceneData.activeCameraId,
      sceneData.mainCameraLocked,
      sceneData.aspectRatio,
      selectedObjectId,
      resolvedSelectedIds,
      viewMode,
      poseEditMode,
      activePoseJoint,
      focusOnRuntimeCamera
    ])

    useEffect(() => {
      const runtime = runtimeRef.current
      if (!runtime) return
      const hasPanorama = Boolean(sceneData.panoramaUrl)
      runtime.controls.maxPolarAngle = hasPanorama ? Math.PI * 0.96 : Math.PI * 0.49
      runtime.controls.minPolarAngle = hasPanorama ? Math.PI * 0.04 : 0
      runtime.render()
    }, [sceneData.panoramaUrl])

    useEffect(() => {
      const runtime = runtimeRef.current
      if (!runtime) return
      const timeline = normalizeDirectorTimeline(sceneData.timeline)
      buildTimelineGuides(runtime.timelineRoot, timeline, viewMode)
      runtime.render()
    }, [sceneData.timeline, viewMode])

    useEffect(() => {
      const runtime = runtimeRef.current
      if (!runtime) return
      let frameId = 0
      let lastTs = performance.now()
      const weatherStart = lastTs
      let lastWeatherFrame = 0
      let timelineSource: DirectorSceneData['timeline']
      let hasTimelineTracks = false
      const tick = (now: number) => {
        const delta = Math.min(0.1, Math.max(0, (now - lastTs) / 1000))
        lastTs = now
        const playback = useDirectorPlaybackStore.getState()
        const wasPlaying = playback.isPlaying
        if (!readOnlyRef.current) playback.advance(delta)
        const current = sceneDataRef.current
        if (timelineSource !== current.timeline) {
          timelineSource = current.timeline
          const timeline = normalizeDirectorTimeline(timelineSource)
          hasTimelineTracks = Boolean(timeline.tracks.length || timeline.cameraTracks.length || timeline.pathClips?.length
            || timeline.poseTracks.length || timeline.actionClips?.length)
        }
        const hasTracks = Boolean(current.modeling) || hasTimelineTracks
        const playbackFrame = readOnlyRef.current || wasPlaying || (hasTracks && playback.editingObjectId === null)
        if (playbackFrame) {
          applyDirectorPlaybackTransforms({
            sceneData: current,
            playhead: readOnlyRef.current ? current.timeline?.currentTime || 0 : useDirectorPlaybackStore.getState().playhead,
            editingObjectId: readOnlyRef.current ? null : playback.editingObjectId,
            poseEditingObjectId: poseEditModeRef.current && !wasPlaying && !recordingViewRef.current ? selectedObjectIdRef.current : null,
            objectRoot: runtime.objectRoot,
            cameraRoot: runtime.cameraRoot,
            shotCamera: runtime.shotCamera
          })
        }
        const preview = queuedCameraPreview.current
        queuedCameraPreview.current = null
        let previewChanged = false
        if (preview && preview.sceneId === current.id && findSceneCamera(current, preview.cameraId) === preview.base) {
          const { cameraId, camera, lens, base } = preview
          const previousRig = runtime.cameraRoot.children.find(child => child.userData.cameraId === cameraId)
          const rig = previewDirectorCameraRig(runtime.cameraRoot, current, cameraId, camera, lens, selectedObjectIdRef.current)
          if (rig) {
            if (previousRig && previousRig !== rig && runtime.transformControls.object === previousRig) {
              runtime.transformControls.detach()
              attachSelectedRuntimeTarget(runtime, current, selectedObjectIdsRef.current, poseEditModeRef.current)
            }
            syncDirectorCameraSelectionBox(runtime, cameraId)
            previewChanged = true
          }
          if (viewModeRef.current === 'camera' && resolveDirectorActiveCameraId(current) === cameraId) {
            liveShotCamera.current = { base, camera }
            applyDirectorShotCamera(runtime.shotCamera, camera)
            runtime.controls.target.fromArray(camera.target)
            previewChanged = true
          }
        }
        const weatherFrame = !recordingViewRef.current && !document.hidden && runtime.environment.animated
          && now - lastWeatherFrame >= 1000 / 30
        if (weatherFrame) {
          lastWeatherFrame = now
          runtime.environment.setTime((now - weatherStart) / 1000)
        }
        if (playbackFrame || previewChanged || weatherFrame) runtime.render()
        frameId = window.requestAnimationFrame(tick)
      }
      frameId = window.requestAnimationFrame(tick)
      return () => window.cancelAnimationFrame(frameId)
    }, [])

    useEffect(() => {
      const runtime = runtimeRef.current
      if (!runtime) return
      applyDirectorPanoramaTransform(runtime.panoramaMesh, sceneData.panoramaRadius, sceneData.panoramaYaw)
      runtime.render()
    }, [sceneData.panoramaRadius, sceneData.panoramaYaw])

    useEffect(() => {
      const runtime = runtimeRef.current
      if (!runtime) return
      let disposed = false
      runtime.panoramaMesh.visible = false
      if (!sceneData.panoramaUrl) {
        applyDirectorPanoramaTexture(runtime.panoramaMaterial, null)
        runtime.render()
        return
      }
      const sourceUrl = sceneData.panoramaUrl
      const applyTexture = (loaded: THREE.Texture) => {
        if (disposed) {
          loaded.dispose()
          return
        }
        applyDirectorPanoramaTransform(runtime.panoramaMesh, sceneDataRef.current.panoramaRadius, sceneDataRef.current.panoramaYaw)
        applyDirectorPanoramaTexture(runtime.panoramaMaterial, loaded)
        runtime.panoramaMesh.visible = true
        runtime.render()
      }
      const fail = () => {
        if (disposed) return
        runtime.panoramaMesh.visible = false
        applyDirectorPanoramaTexture(runtime.panoramaMaterial, null)
        runtime.render()
        message.error(isDirectorInlineMediaUrl(sourceUrl)
          ? '全景图读取失败，请换一张图片重新上传'
          : '远程全景图受跨域限制，无法贴进 3D 场景。请用左侧「上传全景图」再选一次这张图')
      }
      const loadUrl = resolveDirectorPanoramaLoadUrl(sourceUrl)
      void loadDirectorPanoramaBitmapTexture(loadUrl)
        .catch(() => loadUrl === sourceUrl ? Promise.reject(new Error('panorama load failed')) : loadDirectorPanoramaBitmapTexture(sourceUrl))
        .then((texture) => applyTexture(texture))
        .catch(() => fail())
      return () => {
        disposed = true
      }
    }, [sceneData.panoramaUrl, message])

    return <div ref={hostRef} className={`director-viewport director-viewport--${viewMode}${poseEditMode ? ' director-viewport--pose-edit' : ''}`} aria-label="导演台三维视口" />
  }
)

function buildTimelineGuides(
  root: THREE.Group,
  timeline: ReturnType<typeof normalizeDirectorTimeline>,
  viewMode: DirectorViewMode
) {
  disposeDirectorObjectTree(root)
  root.clear()
  if (viewMode !== 'director') return
  for (const clip of timeline.pathClips ?? []) {
    const points = clip.waypoints.map(p => new THREE.Vector3(...p.position))
    if (points.length > 1) root.add(new THREE.Line(new THREE.BufferGeometry().setFromPoints(points), new THREE.LineBasicMaterial({ color: clip.targetKind === 'camera' ? '#74e7fd' : '#f7cf5b' })))
  }
  timeline.tracks.forEach((track) => {
    const points = track.keyframes
      .slice()
      .sort((left, right) => left.time - right.time)
      .map((item) => new THREE.Vector3().fromArray(item.value.position))
    if (!points.length) return
    if (points.length > 1) {
      root.add(new THREE.Line(
        new THREE.BufferGeometry().setFromPoints(points),
        new THREE.LineDashedMaterial({ color: '#f7cf5b', dashSize: 0.18, gapSize: 0.1, opacity: 0.9, transparent: true })
      ))
      const line = root.children.at(-1)
      if (line instanceof THREE.Line) line.computeLineDistances()
    }
    points.forEach((point) => {
      const marker = new THREE.Mesh(
        new THREE.SphereGeometry(0.075, 10, 8),
        new THREE.MeshBasicMaterial({ color: '#f7cf5b' })
      )
      marker.position.copy(point)
      root.add(marker)
    })
  })
  timeline.cameraTracks.forEach((track) => {
    const points = track.keyframes
      .slice()
      .sort((left, right) => left.time - right.time)
      .map((item) => new THREE.Vector3().fromArray(item.value.position))
    if (points.length < 2) return
    root.add(new THREE.Line(
      new THREE.BufferGeometry().setFromPoints(points),
      new THREE.LineDashedMaterial({ color: '#74e7fd', dashSize: 0.16, gapSize: 0.1, opacity: 0.85, transparent: true })
    ))
    const line = root.children.at(-1)
    if (line instanceof THREE.Line) line.computeLineDistances()
  })
}

function resolveDirectorCameraFocusBounds(
  runtime: DirectorRuntime,
  scene: DirectorSceneData,
  cameraId: string,
  camera: DirectorCamera,
  rig: THREE.Object3D,
): THREE.Box3 {
  const bounds = getDirectorCameraRigVisualBounds(rig)
  const explicitTargetId = resolveDirectorCameraAimObjectId(scene, cameraId)
  const targetObject = explicitTargetId
    ? scene.objects.find((item) => item.id === explicitTargetId)
    : scene.objects
      .filter((item) => item.kind === 'actor' && item.visible !== false)
      .map((item) => ({ item, distance: cameraAimPoint(item).distanceTo(new THREE.Vector3().fromArray(camera.target)) }))
      .sort((left, right) => left.distance - right.distance)[0]?.item

  if (targetObject) {
    const runtimeTarget = runtime.objectRoot.children.find((child) => child.userData.objectId === targetObject.id)
    if (runtimeTarget) {
      runtimeTarget.updateWorldMatrix(true, true)
      const targetBounds = new THREE.Box3().setFromObject(runtimeTarget, true)
      if (!targetBounds.isEmpty()) return bounds.union(targetBounds)
    }
    const center = cameraAimPoint(targetObject)
    const height = targetObject.kind === 'actor'
      ? directorActorHeight(targetObject)
      : Math.max(0.5, targetObject.transform.scale[1])
    const halfWidth = targetObject.kind === 'actor' ? height * 0.22 : Math.max(0.3, targetObject.transform.scale[0] * 0.5)
    return bounds.union(new THREE.Box3(
      new THREE.Vector3(center.x - halfWidth, center.y - height * 0.85, center.z - halfWidth),
      new THREE.Vector3(center.x + halfWidth, center.y + height * 0.15, center.z + halfWidth),
    ))
  }

  const target = new THREE.Vector3().fromArray(camera.target)
  return bounds.expandByPoint(target.clone().addScalar(0.22)).expandByPoint(target.clone().addScalar(-0.22))
}

function cameraAimPoint(object: DirectorObject): THREE.Vector3 {
  const [x, y, z] = object.transform.position
  const height = object.kind === 'actor'
    ? directorActorHeight(object) * 0.85
    : Math.max(0.1, object.transform.scale[1]) * 0.5
  return new THREE.Vector3(x, y + height, z)
}

function syncDirectorCameraSelectionBox(runtime: DirectorRuntime, cameraId: string): void {
  const rig = runtime.cameraRoot.children.find((child) => child.userData.cameraId === cameraId)
  const helper = runtime.selectionRoot.getObjectByName(`${cameraId}-camera-selection`)
  if (!rig || !(helper instanceof THREE.Box3Helper)) return
  const bounds = getDirectorCameraRigVisualBounds(rig)
  if (bounds.isEmpty()) return
  helper.box.copy(bounds)
  helper.updateMatrixWorld(true)
}

function syncSelectionRing(runtime: DirectorRuntime, scene: DirectorSceneData, selectedIds: string[]) {
  disposeDirectorObjectTree(runtime.selectionRoot)
  runtime.selectionRoot.clear()
  selectedIds.forEach((selectedId, index) => {
    const objectData = scene.objects.find((item) => item.id === selectedId)
    if (!objectData) {
      const cameraTarget = runtime.cameraRoot.children.find((child) => child.userData.cameraId === selectedId)
      if (!cameraTarget) return
      const bounds = getDirectorCameraRigVisualBounds(cameraTarget)
      if (bounds.isEmpty()) return
      const helper = new THREE.Box3Helper(bounds, index === 0 ? '#F6FBFF' : '#9BCBFF')
      const material = helper.material as THREE.LineBasicMaterial
      helper.name = `${selectedId}-camera-selection`
      material.transparent = true
      material.opacity = index === 0 ? 0.92 : 0.55
      material.depthTest = false
      helper.renderOrder = 10
      runtime.selectionRoot.add(helper)
      return
    }
    const target = runtime.objectRoot.children.find((child) => child.userData.objectId === selectedId)
    if (!target) return
    target.updateMatrixWorld(true)
    const bounds = new THREE.Box3().setFromObject(target)
    if (bounds.isEmpty()) return
    const center = bounds.getCenter(new THREE.Vector3())
    const size = bounds.getSize(new THREE.Vector3())
    const radius = Math.max(size.x, size.z, 0.35) * (objectData.kind === 'actor' ? 0.52 : 0.62)
    const ring = new THREE.Mesh(
      new THREE.RingGeometry(radius * 0.82, radius, 64),
      new THREE.MeshBasicMaterial({
        color: index === 0 ? '#4da3ff' : '#7ec0ff',
        transparent: true,
        opacity: index === 0 ? 0.72 : 0.46,
        side: THREE.DoubleSide,
        depthWrite: false
      })
    )
    ring.name = `${selectedId}-selection-ring`
    ring.rotation.x = -Math.PI / 2
    ring.position.set(center.x, bounds.min.y + 0.02, center.z)
    ring.renderOrder = 6
    runtime.selectionRoot.add(ring)
  })
}

function syncPoseGizmoRuntime(
  runtime: DirectorRuntime,
  scene: DirectorSceneData,
  selectedId: string | null,
  poseEditMode: boolean,
  activeJoint: string | null
): void {
  if (!poseEditMode || !selectedId) {
    disposeDirectorPoseGizmo(runtime.poseGizmo)
    runtime.poseGizmo.root.visible = false
    detachDirectorPoseRotateGizmo(runtime.poseRotateGizmo)
    return
  }

  const objectRoot = runtime.objectRoot.children.find((child) => child.userData.objectId === selectedId)
  const objectData = scene.objects.find((item) => item.id === selectedId)
  runtime.poseGizmo.root.visible = true
  if (!runtime.poseRotateGizmo.dragging) {
    syncDirectorPoseGizmo(runtime.poseGizmo, objectRoot, activeJoint)
  }

  if (!activeJoint || !objectRoot || !objectData || objectData.locked === true) {
    detachDirectorPoseRotateGizmo(runtime.poseRotateGizmo)
    return
  }

  const model = findDirectorMannequinModelRoot(objectRoot)
  if (!model) {
    detachDirectorPoseRotateGizmo(runtime.poseRotateGizmo)
    return
  }

  const startValue = objectData.pose[activeJoint] ?? 0
  if (runtime.poseRotateGizmo.activeJoint !== activeJoint) {
    attachDirectorPoseRotateGizmo(runtime.poseRotateGizmo, runtime.scene, model, activeJoint, startValue)
    return
  }

  if (!runtime.poseRotateGizmo.controls.axis && !runtime.poseRotateGizmo.dragging) {
    runtime.poseRotateGizmo.startValue = startValue
  }
}

function isDirectorPendingModel(object: THREE.Object3D | null | undefined): boolean {
  return Boolean(object?.userData?.directorPendingModel)
}

function detachTransformIfTarget(runtime: DirectorRuntime, target: THREE.Object3D | null | undefined): void {
  if (!target) return
  if (runtime.transformControls.object === target) runtime.transformControls.detach()
}

function replaceRuntimeObject(
  runtime: DirectorRuntime,
  previous: THREE.Object3D,
  next: THREE.Object3D
): void {
  detachTransformIfTarget(runtime, previous)
  runtime.objectRoot.remove(previous)
  disposeDirectorObjectTree(previous)
  runtime.objectRoot.add(next)
}

function attachSelectedRuntimeTarget(
  runtime: DirectorRuntime,
  scene: DirectorSceneData,
  selectedIds: string[],
  poseEditMode = false
): void {
  if (poseEditMode) {
    runtime.transformControls.detach()
    return
  }
  const model = scene.modeling?.project.scene.nodes.find(node => node.id === selectedIds[0])
  if (model) {
    const root = runtime.modelingObjects.get(model.id)
    const target = root && runtime.selectedModelPart ? glbObject(root, runtime.selectedModelPart) : root
    if (target && model.visible && !model.locked && scene.viewMode !== 'camera' && !model.camera?.followPosition) runtime.transformControls.attach(target)
    else runtime.transformControls.detach()
    return
  }
  const selectedId = resolveDirectorAttachTargetId(scene, selectedIds)
  if (!selectedId) {
    runtime.transformControls.detach()
    return
  }
  const objectData = scene.objects.find((item) => item.id === selectedId)
  const objectTarget = runtime.objectRoot.children.find((child) => child.userData.objectId === selectedId)
  if (objectData && objectTarget && objectData.locked !== true) {
    // 异步人体/模型尚未就绪时不要挂到占位体上，否则销毁占位体会让手柄失效，需双击重绑。
    if (isDirectorPendingModel(objectTarget)) {
      runtime.transformControls.detach()
      return
    }
    runtime.transformControls.attach(objectTarget)
    return
  }
  const cameraTarget = runtime.cameraRoot.children.find((child) => child.userData.cameraId === selectedId)
  const cameraLocked = selectedId === 'main-camera'
    ? scene.mainCameraLocked === true
    : scene.cameraBookmarks?.find((bookmark) => bookmark.id === selectedId)?.locked === true
  if (cameraTarget && !cameraLocked) {
    runtime.transformControls.attach(cameraTarget)
    return
  }
  runtime.transformControls.detach()
}

function rebindSelectedTransform(
  runtime: DirectorRuntime,
  scene: DirectorSceneData,
  selectedIds: string[],
  poseEditMode: boolean,
  viewMode: DirectorViewMode = scene.viewMode === 'camera' ? 'camera' : 'director'
): void {
  runtime.controls.enabled = shouldEnableDirectorOrbitControls(scene, viewMode)
  attachSelectedRuntimeTarget(runtime, scene, selectedIds, poseEditMode)
}

function findSceneCamera(scene: DirectorSceneData, cameraId: string): DirectorCamera | null {
  return resolveDirectorCamera(scene, cameraId)
}

function focusDirectorRuntimeOnObject(runtime: DirectorRuntime, objectId: string): DirectorCamera | null {
  const target = runtime.modelingObjects.get(objectId) ?? runtime.objectRoot.children.find((child) => child.userData.objectId === objectId)
  if (!target) return null
  target.updateWorldMatrix(true, true)
  const bounds = new THREE.Box3().setFromObject(target, true)
  const camera = resolveDirectorFrontFocusCamera({
    bounds,
    worldQuaternion: target.getWorldQuaternion(new THREE.Quaternion()),
    fov: runtime.editorCamera.fov,
    aspect: runtime.editorCamera.aspect
  })
  if (!camera) return null

  runtime.editorCamera.position.fromArray(camera.position)
  runtime.editorCamera.fov = camera.fov
  runtime.editorCamera.updateProjectionMatrix()
  runtime.controls.object = runtime.editorCamera
  runtime.controls.target.fromArray(camera.target)
  runtime.controls.update()
  runtime.render()
  return camera
}

function buildActorAnchor(object: DirectorObject): THREE.Group {
  const root = new THREE.Group()
  applyTransform(root, object)
  markObject(root, object.id)
  root.userData.directorPendingModel = true
  const hit = new THREE.Mesh(
    new THREE.CapsuleGeometry(0.28, 1.1, 4, 8),
    new THREE.MeshBasicMaterial({ visible: false })
  )
  hit.name = `${object.id}-pick-proxy`
  hit.position.y = 0.85
  hit.userData.objectId = object.id
  root.add(hit)
  return root
}


function buildLight(data: DirectorSceneData['lights'][number], scene: DirectorSceneData): THREE.Object3D {
  const group = new THREE.Group()
  group.userData.lightId = data.id
  const intensity = directorWeatherLightIntensity(scene, data.id, data.intensity)
  let light: THREE.Light
  if (data.kind === 'ambient') {
    light = new THREE.HemisphereLight(data.color, scene.environmentPreset === 'day' ? '#938b77' : '#555968', intensity)
  } else if (data.kind === 'point') {
    light = new THREE.PointLight(data.color, intensity, 20, 1.7)
  } else if (data.kind === 'spot') {
    const spot = new THREE.SpotLight(data.color, intensity, 28, Math.PI / 5, 0.5, 1.4)
    light = spot
  } else {
    light = new THREE.DirectionalLight(data.color, intensity)
  }
  light.position.fromArray(data.position)
  light.castShadow = data.kind !== 'ambient' && data.id !== 'fill' && data.id !== 'rim'
  group.add(light)
  if (light instanceof THREE.DirectionalLight || light instanceof THREE.SpotLight) {
    light.target.position.set(0, 1.1, 0)
    group.add(light.target)
  }
  if (
    light instanceof THREE.DirectionalLight ||
    light instanceof THREE.PointLight ||
    light instanceof THREE.SpotLight
  ) {
    light.shadow.mapSize.set(1024, 1024)
    light.shadow.bias = -0.00016
    light.shadow.normalBias = 0.024
    light.shadow.radius = 2
  }
  if (light instanceof THREE.DirectionalLight) {
    const camera = light.shadow.camera
    camera.left = camera.bottom = -16
    camera.right = camera.top = 16
    camera.far = 80
    camera.updateProjectionMatrix()
  }
  return group
}

function applyTransform(group: THREE.Group, object: DirectorObject) {
  group.position.fromArray(object.transform.position)
  group.rotation.set(...object.transform.rotation)
  group.scale.fromArray(object.transform.scale)
}

function applyDirectorActorPose(objectRoot: THREE.Object3D, object: DirectorObject): void {
  if (object.kind !== 'actor') return
  const model = findDirectorMannequinModelRoot(objectRoot)
  if (!model) return
  applyDirectorMannequinPose(model, object.pose ?? {}, model.userData.directorRestPose)
}

function directorObjectSignature(
  object: DirectorObject,
  asset?: DirectorModelAsset
): string {
  return JSON.stringify([
    object.kind,
    object.color,
    object.shape,
    object.modelAssetId,
    asset?.url,
    object.locked
  ])
}

function markObject(object: THREE.Object3D, objectId: string) {
  object.userData.objectId = objectId
}

function findObjectId(object?: THREE.Object3D): string | null {
  let current = object
  while (current) {
    if (typeof current.userData.objectId === 'string') return current.userData.objectId
    if (typeof current.userData.previzNodeId === 'string') return current.userData.previzNodeId
    current = current.parent ?? undefined
  }
  return null
}

function isWorldVisible(object: THREE.Object3D): boolean {
  for (let current: THREE.Object3D | null = object; current; current = current.parent) if (!current.visible) return false
  return true
}

function readRuntimeTransform(object: THREE.Object3D): DirectorTransform {
  return { position: object.position.toArray(), rotation: [object.rotation.x, object.rotation.y, object.rotation.z], scale: object.scale.toArray() }
}
