import type { DirectorCamera, DirectorTransformTuple } from './studio'

export interface DirectorSceneryMeta {
  partIndex: number
  partName: string
  scenePackageMediaId: string
  meshPath: string
}
export interface DirectorActorMeta {
  heightMeters: number
  bodyPreset?: string
  identifyColor?: string
  locomotion: 'idle' | 'walk' | 'run'
}

/** Body performance is independent of the actor's world-space movement path. */
export type DirectorActorAction = 'stand' | 'walk' | 'run' | 'crouch' | 'crouch_cry' | 'sit' | 'wave' | 'pose' | 'nod' | 'shake_head' | 'look_around' | 'talk' | 'bow' | 'think' | 'phone' | 'arms_crossed' | 'hands_hips' | 'reach' | 'cheer' | 'stretch' | 'kneel' | 'march' | 'tiptoe' | 'jump' | 'squat_repeat' | 'lunge' | 'punch' | 'kick' | 'throw' | 'push' | 'pull' | 'dance' | 'wave_both' | 'imported'
export interface DirectorActionFrame { time: number; pose: Record<string, number> }
export interface DirectorActionAsset {
  id: string
  name: string
  duration: number
  loop: boolean
  source: 'bvh' | 'aid'
  frames: DirectorActionFrame[]
}
export interface DirectorActorActionClip {
  id: string
  actorId: string
  action: DirectorActorAction
  assetId?: string
  assetName?: string
  motion?: DirectorActionAsset
  start: number
  end: number
  speed: number
  intensity: number
  /** Seconds spent blending from the preceding held pose. */
  blendIn: number
  /** Explicit snapshot used by a custom-pose clip. */
  pose?: Record<string, number>
}

export type DirectorCameraBodyId = 'virtual-production' | 'lightweight-handheld' | 'digital-cinema'
export type DirectorLensSeriesId = 'cine-prime' | 'cine-zoom' | 'anamorphic-prime' | 'documentary-zoom'
export type DirectorSensorFormat = 'full-frame' | 'super-35' | 'aps-c'

export interface DirectorLensSpec {
  /** 旧工程中的 bodyName 实际表示感光器画幅，继续保留该键以兼容工程文件。 */
  bodyName: DirectorSensorFormat
  lensType: 'prime' | 'zoom'
  focalLengthMm: number
  apertureF?: number
  sensorWidthMm?: number
  cameraBody?: DirectorCameraBodyId
  lensSeries?: DirectorLensSeriesId
}
export interface DirectorPathWaypoint {
  id: string
  time: number
  position: DirectorTransformTuple
  rotation?: DirectorTransformTuple
  lookAt?: DirectorTransformTuple
  lookAtObjectId?: string
  fov?: number
  roll?: number
}
export interface DirectorFollowConstraint {
  targetObjectId: string
  offset?: DirectorTransformTuple
  anchor: 'origin' | 'chest' | 'head'
  continuousLookAt: boolean
  lockedPosition?: DirectorTransformTuple
}
export interface DirectorPathClip {
  id: string
  kind: 'path' | 'follow_relative' | 'follow_track' | 'follow_static'
  targetKind: 'object' | 'camera'
  targetId: string
  start: number
  end: number
  waypoints: DirectorPathWaypoint[]
  /** Editable pen anchors in world coordinates; waypoints are the baked playback path. */
  penAnchors?: Array<{ p: DirectorTransformTuple; hIn?: DirectorTransformTuple; hOut?: DirectorTransformTuple }>
  follow?: DirectorFollowConstraint
  grounded?: boolean
  source?: 'draw' | 'preset' | 'virtual-camera'
  sessionId?: string
  /** 服务端已确认的运镜录制标识；本地绘制路径不填写。 */
  takeId?: string
  preset?: 'orbit' | 'half-orbit' | 'dolly-in' | 'dolly-out' | 'crane' | 'truck' | 'spiral'
  createdAt: string
}
export type DirectorPathDrawPlane = 'xz' | 'xy' | 'yz'

export interface DirectorViewportPrefs {
  shading: 'solid' | 'xray'
  cameraPreset: 'persp' | 'top' | 'front'
  showNameplates: boolean
  timelineOpen?: boolean
  showMonitorPip?: boolean
  burnNameplatesInRecord?: boolean
  pathDrawMode?: false | 'stroke' | 'point' | 'circle' | 'line' | 'rect' | 'pen'
  pathEditClipId?: string
  /** Y is world height; omitted in legacy scenes means horizontal XZ. */
  pathDrawPlane?: DirectorPathDrawPlane
}
export interface DirectorSpaceGenResult {
  scenePackageMediaId: string
  scenePackageUrl: string
  format: 'glb'
  parts: Array<{ meshPath: string; partName: string; locked?: boolean }>
  suggestedCamera?: DirectorCamera
  bounds?: { min: DirectorTransformTuple; max: DirectorTransformTuple }
  truncated?: boolean
  demoSample?: boolean
}
export interface DirectorScenePackageRef {
  resourceId?: string
  bundle?: import('./canvas-api').CanvasModelBundle
  mediaId: string
  url: string
  format: 'glb'
  partCount: number
  sourceImageMediaIds: string[]
  taskId: string
  createdAt: string
  bounds?: DirectorSpaceGenResult['bounds']
}
export interface DirectorSpaceGenJob {
  taskId: string
  status: 'submitting' | 'unknown' | 'queued' | 'running' | 'succeeded' | 'failed' | 'cancelled'
  applyMode?: 'append' | 'replace'
  applyError?: string
  applyWarning?: string
  connectionError?: string
  submission?: { key: string; clientQuoteAmount: number; imageMediaIds: string[] }
  stage?: 'submit' | 'analyze' | 'build' | 'check' | 'refine' | 'finalize'
  progress?: number
  errorMessage?: string
  startedAt: string
  updatedAt: string
  sourceImageMediaIds: string[]
  quoteCredits?: number
  applied?: boolean
  result?: DirectorSpaceGenResult
}
