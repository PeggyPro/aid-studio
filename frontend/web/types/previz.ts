import type { AssistantContextRef } from "./assistant-api";
/** 3D-HUB v3 Web contract. Backend endpoints remain proposals until PREVIZ is enabled. */
export type Vec3 = [number, number, number];
export type PrevizKind =
  | "ACTOR"
  | "BOX"
  | "SPHERE"
  | "CONE"
  | "PLANE"
  | "TABLE"
  | "CHAIR"
  | "TREE"
  | "ARCH"
  | "WALL"
  | "GLB"
  | "CAMERA"
  | "LIGHT";
export interface PrevizTransform {
  position: Vec3;
  rotation: Vec3;
  scale: Vec3;
}
export interface PrevizNode {
  /** Transient bridge identity. Not a server-side model/asset identity. */
  directorBinding?: { kind: 'object' | 'camera' | 'light'; id: string };
  id: string;
  name: string;
  parentId: string | null;
  kind: PrevizKind;
  localTransform: PrevizTransform;
  color: string;
  visible: boolean;
  locked: boolean;
  assetId?: string;
  /** Render an explicit set of GLB subtrees while retaining skeleton/dependency structure. */
  assetObjectIds?: string[];
  /** Place a scoped source object at this instance's transform without applying its source pose twice. */
  assetPivotId?: string;
  /** Omitted retains legacy mixed playback; a selection evaluates only that native clip. */
  assetPlayback?: { clipIndex: number; clipName?: string; loop: boolean };
  /** Stable source-object edits, applied after native animation without duplicating the model. */
  assetOverrides?: Record<string, {
    positionOffset?: Vec3;
    rotationOffset?: Vec3;
    scaleMultiplier?: Vec3;
    color?: string;
    visible?: boolean;
  }>;
  camera?: {
    projection?: "perspective" | "orthographic";
    orthoHeight?: number;
    roll?: number;
    fov: number;
    near: number;
    far: number;
    target: Vec3;
    followNodeId?: string;
    followPosition?: boolean;
    followOffset?: Vec3;
  };
  light?: { kind: "directional" | "point" | "spot"; intensity: number };
  motionFacing?: "path" | "manual";
  action?: "idle" | "walk" | "run" | "wave" | "sit";
}
export interface PrevizKeyframe {
  frame: number;
  value: Vec3;
}
export interface PrevizTrack {
  id: string;
  nodeId: string;
  /** Stable GLB-local object identity, scoped by nodeId. Omitted targets the whole instance. */
  objectId?: string;
  channel: "position" | "rotation" | "scale" | "target";
  interpolation: "linear" | "step";
  keys: PrevizKeyframe[];
}
export interface PrevizShot {
  id: string;
  name: string;
  cameraId: string;
  startFrame: number;
  endFrameExclusive: number;
}
export interface PrevizAsset {
  id: string;
  name: string;
  kind: "GLB" | "IMAGE";
  mime: string;
  dataUrl: string;
  /** Local embedded assets only. Server assetVersionId/digest are reserved, not fabricated. */
  assetVersionId?: string;
  digest?: string;
  /** Provenance hint only; the server rechecks project permission and receipt before reuse. */
  buildOrigin?: {
    projectId: string;
    stepId: string;
    sourceVersionId: string;
    engine: "threejs" | "blender";
    sha256: string;
    runtimeRunId?: string;
    sceneId?: string;
    editorVersion?: string;
    entry?: string;
  };
}
export interface PrevizScene {
  /** Runtime projection only. The unified authoring document owns the director entities. */
  directorLayer?: import('./threePreviz').ThreePrevizDirectorLayer;
  schemaVersion: 3 | 4;
  units: "meter";
  upAxis: "Y";
  sdkVersion: "aid-previz-web@1.0.0" | "aid-previz-web@1.1.0";
  nodes: PrevizNode[];
  assets: PrevizAsset[];
  timeline: { fps: 30; frameCount: number; tracks: PrevizTrack[] };
  shots: PrevizShot[];
  aspectRatio: "16:9" | "9:16" | "1:1" | "4:3" | "3:4" | "21:9";
  environment: {
    presetCode: "studio" | "day" | "night";
    background: string;
    exposure: number;
  };
}
export type PrevizCommand =
  | { type: "node.add"; node: PrevizNode }
  | { type: "node.update"; id: string; patch: Partial<Omit<PrevizNode, "id">> }
  | { type: "node.remove"; id: string }
  | { type: "scene.replace"; scene: PrevizScene }
  | {
      type: "scene.settings";
      patch: Partial<Pick<PrevizScene, "aspectRatio" | "environment">>;
    }
  | {
      type: "timeline.replace";
      timeline: PrevizScene["timeline"];
      shots: PrevizShot[];
    }
  | { type: "assets.replace"; assets: PrevizAsset[] };
export interface PrevizMessage {
  id: string;
  role: "user" | "assistant";
  text: string;
  createdAt: string;
  status?: "done" | "cancelled" | "failed";
  references?: string[];
}
export interface PrevizAnnotation {
  id: string;
  frame: number;
  nodeIds: string[];
  image: string;
  note: string;
  points: [number, number][];
}
export interface PrevizVersion {
  id: string;
  revision: number;
  summary: string;
  createdAt: string;
  scene: PrevizScene;
}
export interface PrevizProject {
  remoteWorkspaceId?: string;
  remoteRole?: 'OWNER'|'EDITOR'|'VIEWER';
  remoteDocumentVersion?: string;
  remoteSourceVersion?: string | null;
  editorVersion?: string;
  adoptedBuildSteps?: string[];
  id: string;
  title: string;
  revision: number;
  versionId: string;
  createdAt: string;
  updatedAt: string;
  scene: PrevizScene;
  messages: PrevizMessage[];
  versions: PrevizVersion[];
  thumbnail?: string;
  draft: string;
  initialBuildPrompt?: string;
  initialTextModelCode?: string;
  initialReferenceImages?: Extract<AssistantContextRef, {kind:"PREVIZ_IMAGE"}>[];
  buildDrafts?: Record<string, string>;
  annotations: PrevizAnnotation[];
}
export interface PrevizSaveRequest {
  project: PrevizProject;
  expectedRevision: number;
  operationId: string;
  summary: string;
}
export interface PrevizAgentRequest {
  projectId: string;
  baseVersionId: string;
  expectedRevision: number;
  clientRequestId: string;
  prompt: string;
  selectedObjectIds: string[];
  selectedShotIds: string[];
  frameRange: [number, number];
  referenceAssetIds: string[];
  annotations: PrevizAnnotation[];
  modelCode: string;
  scene: PrevizScene;
}
export interface PrevizCandidate {
  id: string;
  baseVersionId: string;
  commands: PrevizCommand[];
  summary: string;
}
export interface PrevizDeliveryRequest {
  projectId: string;
  versionId: string;
  exportId: string;
  operationId: string;
  target: {
    kind: "CANVAS" | "FLOW";
    workspaceId?: string;
    projectId?: string;
    episodeId?: string;
    storyboardId?: string;
  };
  createVideoDraft: boolean;
}
export interface PrevizCapabilities {
  mode: "mock" | "remote";
  models: { code: string; name: string }[];
  cloudSave: boolean;
  officialExport: boolean;
  delivery: boolean;
  reason?: string;
}
export interface PrevizWorkingCopyStore {
  list(): Promise<PrevizProject[]>;
  get(id: string): Promise<PrevizProject | undefined>;
  create(project: PrevizProject): Promise<PrevizProject>;
  save(request: PrevizSaveRequest): Promise<PrevizProject>;
  remove(id: string, expectedRevision: number): Promise<void>;
}
export interface PrevizGateway extends PrevizWorkingCopyStore {
  capabilities: PrevizCapabilities;
  generate(
    request: PrevizAgentRequest,
    signal: AbortSignal,
    onStage: (stage: string) => void,
  ): Promise<PrevizCandidate>;
  deliver(
    request: PrevizDeliveryRequest,
  ): Promise<{ id: string; status: "SIMULATED" | "DELIVERED" }>;
}
