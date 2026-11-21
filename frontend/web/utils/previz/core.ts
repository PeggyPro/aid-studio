import { createRandomUuid } from '@/utils/clientId'
import type {
  PrevizCommand,
  PrevizKind,
  PrevizNode,
  PrevizProject,
  PrevizScene,
  PrevizShot,
  PrevizTrack,
  Vec3,
} from "@/types/previz";
import { cameraChoices, nativeCameraOwner } from "./nativeCameras";
import { PREVIZ_GLB_MAX_BYTES } from "./limits";
import { glbObjectsForAsset,glbObjectsForNode } from "./glbObjects";

export const PREVIZ_KINDS: Record<PrevizKind, string> = {
  ACTOR: "白模人物",
  BOX: "立方体",
  SPHERE: "球体",
  CONE: "棱锥",
  PLANE: "地面",
  TABLE: "桌子",
  CHAIR: "椅子",
  TREE: "树木",
  ARCH: "门廊",
  WALL: "墙体",
  GLB: "导入模型",
  CAMERA: "拍摄相机",
  LIGHT: "灯光",
};
export const uid = () => createRandomUuid();
export const copy = <T>(value: T): T => structuredClone(value);
export const clamp = (n: number, min: number, max: number) =>
  Math.min(max, Math.max(min, n));
export function createNode(
  kind: PrevizKind,
  name = PREVIZ_KINDS[kind],
): PrevizNode {
  return {
    id: uid(),
    name,
    kind,
    parentId: null,
    visible: true,
    locked: false,
    color: kind === "ACTOR" ? "#e4ddcf" : "#c5c8c5",
    localTransform: {
      position: [0, 0, 0],
      rotation: [0, 0, 0],
      scale: [1, 1, 1],
    },
    ...(kind === "CAMERA"
      ? {
          camera: { fov: 45, near: 0.1, far: 200, target: [0, 1, 0] as Vec3 },
          localTransform: {
            position: [6, 4, 8] as Vec3,
            rotation: [0, 0, 0] as Vec3,
            scale: [1, 1, 1] as Vec3,
          },
        }
      : {}),
    ...(kind === "ACTOR" ? { action: "idle" as const } : {}),
    ...(kind === "LIGHT"
      ? {
          light: { kind: "directional" as const, intensity: 2 },
          localTransform: {
            position: [4, 7, 3] as Vec3,
            rotation: [0, 0, 0] as Vec3,
            scale: [1, 1, 1] as Vec3,
          },
        }
      : {}),
  };
}
export function createScene(
  template: "empty" | "meeting" | "street" | "chase" = "empty",
): PrevizScene {
  const ground = createNode("PLANE", "摄影棚地面");
  ground.localTransform.scale = [20, 1, 20];
  const wide = createNode("CAMERA", "01 · 空间全景");
  const scene: PrevizScene = {
    schemaVersion: 3,
    sdkVersion: "aid-previz-web@1.0.0",
    units: "meter",
    upAxis: "Y",
    assets: [],
    nodes: template === "empty" ? [wide] : [ground, wide],
    timeline: { fps: 30, frameCount: 240, tracks: [] },
    shots: [
      {
        id: uid(),
        name: "空间交代",
        cameraId: wide.id,
        startFrame: 0,
        endFrameExclusive: 240,
      },
    ],
    aspectRatio: "16:9",
    environment: {
      presetCode: "studio",
      background: "#090b0e",
      exposure: 1.15,
    },
  };
  if (template === "empty") return scene;
  const actor = createNode("ACTOR", "人物 A");
  actor.localTransform.position = [-3, 0, 0];
  actor.action = template === "chase" ? "run" : "walk";
  const other = createNode("ACTOR", "人物 B");
  other.localTransform.position = [1.7, 0, -0.8];
  other.localTransform.rotation[1] = -0.8;
  other.color = "#9eaead";
  scene.nodes.push(actor, other);
  const close = createNode("CAMERA", "02 · 人物中景");
  close.localTransform.position = [4, 2.3, 4];
  close.camera!.target = [0.3, 1.1, 0];
  scene.nodes.push(close);
  scene.shots = [
    { ...scene.shots[0], endFrameExclusive: 150 },
    {
      id: uid(),
      name: "人物关系",
      cameraId: close.id,
      startFrame: 150,
      endFrameExclusive: 240,
    },
  ];
  scene.timeline.tracks.push({
    id: uid(),
    nodeId: actor.id,
    channel: "position",
    interpolation: "linear",
    keys: [
      { frame: 0, value: [-3, 0, 0] },
      { frame: 150, value: [-0.5, 0, 0.3] },
      { frame: 239, value: [-0.5, 0, 0.3] },
    ],
  });
  const table = createNode("TABLE", "会面长桌");
  table.localTransform.position = [0.5, 0, -1.2];
  scene.nodes.push(table);
  for (const x of [-0.5, 1.5]) {
    const chair = createNode("CHAIR");
    chair.localTransform.position = [x, 0, -2.2];
    scene.nodes.push(chair);
  }
  if (template !== "meeting") {
    scene.nodes = scene.nodes.filter(
      (n) => !["TABLE", "CHAIR"].includes(n.kind),
    );
    for (const x of [-4, 4]) {
      const arch = createNode("ARCH", "街巷门廊");
      arch.localTransform.position = [x, 0, -3];
      scene.nodes.push(arch);
      const tree = createNode("TREE");
      tree.localTransform.position = [x, 0, 2];
      scene.nodes.push(tree);
    }
    if (template === "chase") {
      other.action = "run";
      actor.localTransform.position = [0, 0, -2];
      const path = scene.timeline.tracks[0];
      path.keys = [
        { frame: 0, value: [0, 0, -2] },
        { frame: 239, value: [0, 0, 4] },
      ];
      other.localTransform.position = [0.8, 0, -4];
      wide.localTransform.position = [3.5, 4.2, 5.5];
      wide.camera!.followNodeId = actor.id;
      wide.camera!.followPosition = true;
      wide.camera!.followOffset = [3.5, 4.2, 7.5];
      close.camera!.followNodeId = actor.id;
      close.localTransform.position = [-4, 2.4, 7];
      scene.timeline.tracks.push({
        id: uid(),
        nodeId: other.id,
        channel: "position",
        interpolation: "linear",
        keys: [
          { frame: 0, value: [0.8, 0, -4] },
          { frame: 239, value: [0.8, 0, 2] },
        ],
      });
    }
  }
  return scene;
}
export function createProject(
  title = "未命名预演",
  template: Parameters<typeof createScene>[0] = "empty",
): PrevizProject {
  const now = new Date().toISOString();
  return {
    id: uid(),
    title,
    revision: 0,
    versionId: uid(),
    createdAt: now,
    updatedAt: now,
    scene: createScene(template),
    messages: [],
    versions: [],
    draft: "",
    annotations: [],
  };
}
function fail(message: string): never {
  throw new Error(message);
}
const isVec = (v: unknown): v is Vec3 =>
  Array.isArray(v) &&
  v.length === 3 &&
  v.every(
    (n) => typeof n === "number" && Number.isFinite(n) && Math.abs(n) <= 10000,
  );
/** Used by manual commands, mock candidates, import, save and export. Never evaluate model code. */
export function validateScene(value: unknown): asserts value is PrevizScene {
  if (!value || typeof value !== "object") fail("工程场景格式无效");
  const s = value as PrevizScene;
  if (
    !((s.schemaVersion === 3 && s.sdkVersion === "aid-previz-web@1.0.0") || (s.schemaVersion === 4 && s.sdkVersion === "aid-previz-web@1.1.0")) ||
    s.units !== "meter" ||
    s.upAxis !== "Y"
  )
    fail("不支持的工程版本或坐标规范");
  if(s.schemaVersion===3 && (Array.isArray(s.nodes)&&s.nodes.some(n=>n.assetObjectIds!==undefined||n.assetPivotId!==undefined)||Array.isArray(s.timeline?.tracks)&&s.timeline.tracks.some(t=>t.objectId!==undefined)))fail("对象分层与动画需要新版工程格式，请升级编辑器");
  if (!["16:9", "9:16", "1:1", "4:3", "3:4", "21:9"].includes(s.aspectRatio))
    fail("不支持的画面比例");
  if (
    !s.timeline ||
    s.timeline.fps !== 30 ||
    !Number.isInteger(s.timeline.frameCount) ||
    s.timeline.frameCount < 30 ||
    s.timeline.frameCount > (s.directorLayer ? 2_592_000 : 1800)
  )
    fail("预演时长须为 1–60 秒、30fps");
  if (
    !Array.isArray(s.nodes) ||
    s.nodes.length > (s.directorLayer ? 4000 : 1000) ||
    !Array.isArray(s.assets) ||
    s.assets.length > 32 ||
    !Array.isArray(s.timeline.tracks) ||
    s.timeline.tracks.length > 4000
  )
    fail("工程对象、资产或轨道超出限制");
  if (
    !s.environment ||
    !["studio", "day", "night"].includes(s.environment.presetCode) ||
    !/^#[\da-f]{6}$/i.test(s.environment.background) ||
    !Number.isFinite(s.environment.exposure) ||
    s.environment.exposure < 2 ** -16 ||
    s.environment.exposure > 2 ** 8
  )
    fail("环境参数无效");
  const ids = new Set<string>(),
    assets = new Set<string>();
  let assetBytes = 0;
  for (const a of s.assets) {
    if (
      !a ||
      typeof a.id !== "string" ||
      assets.has(a.id) ||
      typeof a.name !== "string" ||
      a.name.length > 200 ||
      !["GLB", "IMAGE"].includes(a.kind) ||
      typeof a.dataUrl !== "string" ||
      a.dataUrl.length > (a.kind === "GLB" ? "data:model/gltf-binary;base64,".length + Math.ceil(PREVIZ_GLB_MAX_BYTES / 3) * 4 : 40_000_000)
    )
      fail("资产格式无效或文件过大");
    const prefix =
      a.kind === "GLB"
        ? /^data:model\/gltf-binary;base64,[A-Za-z0-9+/=]+$/
        : /^data:image\/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$/;
    if (!prefix.test(a.dataUrl))
      fail("只支持工程内嵌的 GLB、PNG、JPEG 或 WebP 资产");
    if (a.kind === "GLB") {
      const encoded = a.dataUrl.slice(a.dataUrl.indexOf(",") + 1);
      const bytes = encoded.length / 4 * 3 - (encoded.endsWith("==") ? 2 : encoded.endsWith("=") ? 1 : 0);
      if (encoded.length % 4 !== 0 || bytes > PREVIZ_GLB_MAX_BYTES) fail("GLB文件超过32MB或编码无效");
    }
    if (a.buildOrigin !== undefined) {
      const origin = a.buildOrigin;
      if (a.kind !== "GLB" || !origin || typeof origin !== "object" || Array.isArray(origin)
          || typeof origin.projectId !== "string" || !/^[A-Za-z0-9_-]{1,100}$/.test(origin.projectId)
          || typeof origin.stepId !== "string" || !/^[1-9]\d{0,19}$/.test(origin.stepId)
          || typeof origin.sourceVersionId !== "string" || !/^[a-f0-9]{64}$/.test(origin.sourceVersionId)
          || typeof origin.sha256 !== "string" || !/^[a-f0-9]{64}$/.test(origin.sha256)
          || !["threejs", "blender"].includes(origin.engine)) fail("模型构建来源格式无效");
      if ((origin.runtimeRunId !== undefined || origin.sceneId !== undefined)
          && (typeof origin.runtimeRunId !== "string" || !/^[1-9]\d{0,18}$/.test(origin.runtimeRunId)
          || typeof origin.sceneId !== "string" || !/^[A-Za-z0-9_-]{1,100}$/.test(origin.sceneId)
          || origin.editorVersion !== undefined && (typeof origin.editorVersion !== "string" || !/^[a-f0-9]{64}$/.test(origin.editorVersion))))
        fail("模型场景来源格式无效");
      if(origin.entry!==undefined&&(typeof origin.entry!=="string"||!origin.entry||origin.entry.length>240||origin.entry.startsWith("/")
          ||/[\\:]/.test(origin.entry)||origin.entry.split("/").some(part=>!part||part==="."||part==="..")))fail("模型构建入口来源格式无效");
    }
    assetBytes += a.dataUrl.length;
    if (assetBytes > 65_000_000) fail("工程内嵌资产总量超过限制，请拆分工程");
    assets.add(a.id);
  }
  for (const n of s.nodes) {
    if (
      !n ||
      typeof n.id !== "string" ||
      !n.id ||
      ids.has(n.id) ||
      !Object.hasOwn(PREVIZ_KINDS, n.kind) ||
      typeof n.name !== "string" ||
      n.name.length > 120
    )
      fail("对象 ID、类型或名称无效");
    if (
      typeof n.visible !== "boolean" ||
      typeof n.locked !== "boolean" ||
      !/^#[\da-f]{6}$/i.test(n.color)
    )
      fail("对象外观参数无效");
    const t = n.localTransform;
    if(n.assetPivotId !== undefined && (n.kind !== "GLB" || typeof n.assetPivotId !== "string" || !n.assetObjectIds?.includes(n.assetPivotId)))fail("模型对象锚点须属于采用范围");
    if(n.assetObjectIds !== undefined){
      if(n.kind !== "GLB" || !Array.isArray(n.assetObjectIds) || !n.assetObjectIds.length || n.assetObjectIds.length>5000 || new Set(n.assetObjectIds).size!==n.assetObjectIds.length)fail("增量模型对象范围无效");
      const ids=new Set(glbObjectsForAsset(s.assets.find(a=>a.id===n.assetId)).map(o=>o.id));
      if(n.assetObjectIds.some(id=>typeof id!=="string"||!ids.has(id)))fail("增量模型对象不存在");
    }
    if (n.assetPlayback !== undefined) {
      const playback = n.assetPlayback;
      if (n.kind !== "GLB" || !playback || typeof playback !== "object" || Array.isArray(playback)
        || !Number.isSafeInteger(playback.clipIndex) || playback.clipIndex < 0 || playback.clipIndex > 2147483647
        || typeof playback.loop !== "boolean" || playback.clipName !== undefined && (typeof playback.clipName !== "string" || playback.clipName.length > 200)
        || Object.keys(playback).some(key => !["clipIndex", "clipName", "loop"].includes(key))) fail("模型动画选择无效");
    }
    if (n.assetOverrides !== undefined) {
      if (n.kind !== "GLB" || !n.assetOverrides || typeof n.assetOverrides !== "object" || Array.isArray(n.assetOverrides)
          || Object.keys(n.assetOverrides).length > 5000) fail("模型内部编辑数据无效");
      for (const [id, patch] of Object.entries(n.assetOverrides)) {
        if (!id.trim() || id.length > 200 || !patch || typeof patch !== "object" || Array.isArray(patch)
            || Object.keys(patch).some(key => !["positionOffset", "rotationOffset", "scaleMultiplier", "color", "visible"].includes(key))
            || patch.positionOffset !== undefined && !isVec(patch.positionOffset)
            || patch.rotationOffset !== undefined && !isVec(patch.rotationOffset)
            || patch.scaleMultiplier !== undefined && (!isVec(patch.scaleMultiplier) || patch.scaleMultiplier.some(value => value <= 0 || value > 100))
            || patch.color !== undefined && !/^#[\da-f]{6}$/i.test(patch.color)
            || patch.visible !== undefined && typeof patch.visible !== "boolean") fail("模型内部对象编辑参数无效");
      }
    }
    if (
      !t ||
      !isVec(t.position) ||
      !isVec(t.rotation) ||
      !isVec(t.scale) ||
      t.scale.some((n) => n <= 0 || n > 100)
    )
      fail("对象变换含无效数值；缩放须大于 0 且不超过 100");
    if (
      n.motionFacing !== undefined &&
      (n.kind !== "ACTOR" || !["path", "manual"].includes(n.motionFacing))
    )
      fail("人物朝向模式无效");
    if (n.camera?.followOffset !== undefined && !isVec(n.camera.followOffset))
      fail("跟随机位偏移无效");
    if (
      n.camera?.followPosition !== undefined &&
      typeof n.camera.followPosition !== "boolean"
    )
      fail("机位跟随参数无效");
    if (
      n.camera?.followPosition &&
      (!n.camera.followNodeId || !n.camera.followOffset)
    )
      fail("移动跟随需要主体和偏移");
    if (n.action && !["idle", "walk", "run", "wave", "sit"].includes(n.action))
      fail("不支持的人物动作");
    if (
      n.kind === "CAMERA" &&
      (!n.camera ||
        !isVec(n.camera.target) ||
        !Number.isFinite(n.camera.fov) ||
        n.camera.fov < 1 ||
        n.camera.fov >= 180 ||
        n.camera.projection !== undefined && !["perspective","orthographic"].includes(n.camera.projection) ||
        n.camera.projection === "orthographic" && (!Number.isFinite(n.camera.orthoHeight) || n.camera.orthoHeight! <= 0 || n.camera.orthoHeight! > 1000000) ||
        n.camera.roll !== undefined && !Number.isFinite(n.camera.roll) ||
        !Number.isFinite(n.camera.near) ||
        !Number.isFinite(n.camera.far) ||
        n.camera.near < 0 || n.camera.projection !== "orthographic" && n.camera.near === 0 ||
        n.camera.far <= n.camera.near ||
        n.camera.far > 10000)
    )
      fail("相机参数无效");
    if (
      n.kind === "LIGHT" &&
      (!n.light ||
        !["directional", "point", "spot"].includes(n.light.kind) ||
        !Number.isFinite(n.light.intensity) ||
        n.light.intensity < 0 ||
        n.light.intensity > 20)
    )
      fail("灯光参数无效");
    if (
      n.kind === "GLB" &&
      !s.assets.some((a) => a.id === n.assetId && a.kind === "GLB")
    )
      fail("模型资产缺失");
    ids.add(n.id);
  }
  const byId = new Map(s.nodes.map((n) => [n.id, n]));
  for (const n of s.nodes) {
    if (n.camera?.followNodeId) {
      const target = byId.get(n.camera.followNodeId);
      if (!target || ["CAMERA", "LIGHT", "PLANE"].includes(target.kind))
        fail("跟随主体不存在或不支持");
      let ancestor: PrevizNode | undefined = target;
      const parents = new Set<string>();
      while (ancestor) {
        if (ancestor.id === n.id || parents.has(ancestor.id))
          fail("机位不能跟随自身的子级或循环层级");
        parents.add(ancestor.id);
        ancestor = ancestor.parentId ? byId.get(ancestor.parentId) : undefined;
      }
    }
    const seen = new Set([n.id]);
    let parent = n.parentId;
    while (parent !== null) {
      if (!byId.has(parent) || seen.has(parent))
        fail("对象父级不存在或形成循环");
      seen.add(parent);
      parent = byId.get(parent)!.parentId;
    }
  }
  const trackIds = new Set<string>(),
    channels = new Set<string>();
  for (const t of s.timeline.tracks) {
    if (
      !t ||
      typeof t.id !== "string" ||
      trackIds.has(t.id) ||
      channels.has(JSON.stringify([t.nodeId,t.objectId ?? null,t.channel])) ||
      !ids.has(t.nodeId) ||
      !["position", "rotation", "scale", "target"].includes(t.channel) ||
      !["linear", "step"].includes(t.interpolation) ||
      !Array.isArray(t.keys) ||
      t.keys.length < 1 ||
      t.keys.length > 1800
    )
      fail("动画轨道无效或通道重复");
    if (t.objectId !== undefined) {
      const owner = byId.get(t.nodeId);
      if (typeof t.objectId !== "string" || !t.objectId.trim() || t.objectId.length > 200 || owner?.kind !== "GLB" || t.channel === "target"
        || !glbObjectsForNode(owner!,s.assets.find(a => a.id === owner.assetId)).some(p => p.id === t.objectId)) fail("内部动画目标不存在或不支持");
    }
    if (t.channel === "target" && byId.get(t.nodeId)?.kind !== "CAMERA")
      fail("只有相机可设置目标轨道");
    let prev = -1;
    for (const k of t.keys) {
      if (
        !k ||
        !Number.isInteger(k.frame) ||
        k.frame <= prev ||
        k.frame >= s.timeline.frameCount ||
        !isVec(k.value) ||
        (t.channel === "scale" && k.value.some((v) => v <= 0 || v > 100))
      )
        fail("关键帧须有序且处于预演范围");
      prev = k.frame;
    }
    trackIds.add(t.id);
    channels.add(JSON.stringify([t.nodeId,t.objectId ?? null,t.channel]));
  }
  if (!Array.isArray(s.shots) || s.shots.length < 1 || s.shots.length > 60)
    fail("至少需要一个镜头，最多 60 个");
  const shotIds = new Set<string>();
  const availableCameras = new Set(cameraChoices(s).map(camera=>camera.id));
  let end = 0;
  for (const shot of s.shots) {
    if (
      !shot ||
      typeof shot.id !== "string" ||
      shotIds.has(shot.id) ||
      typeof shot.name !== "string" ||
      shot.name.length > 120 ||
      !availableCameras.has(shot.cameraId) ||
      shot.startFrame !== end ||
      !Number.isInteger(shot.endFrameExclusive) ||
      shot.endFrameExclusive <= end ||
      shot.endFrameExclusive > s.timeline.frameCount
    )
      fail("镜头必须连续覆盖时间轴并引用有效相机");
    shotIds.add(shot.id);
    end = shot.endFrameExclusive;
  }
  if (end !== s.timeline.frameCount) fail("镜头未覆盖完整预演时间");
}
export function applyCommands(
  scene: PrevizScene,
  commands: PrevizCommand[],
): PrevizScene {
  let next = copy(scene);
  for (const c of commands) {
    if (c.type === "scene.replace") next = copy(c.scene);
    else if (c.type === "node.add") next.nodes.push(copy(c.node));
    else if (c.type === "node.update") {
      const node = next.nodes.find((n) => n.id === c.id);
      if (!node) fail("目标对象已删除");
      Object.assign(node, copy(c.patch));
      if(c.patch.kind){if(node.kind!=='ACTOR'){delete node.action;delete node.motionFacing;}if(node.kind!=='CAMERA')delete node.camera;if(node.kind!=='LIGHT')delete node.light;}
    } else if (c.type === "node.remove") {
      const removed = new Set([c.id]);
      let count = 0;
      while (count !== removed.size) {
        count = removed.size;
        next.nodes.forEach((n) => {
          if (n.parentId && removed.has(n.parentId)) removed.add(n.id);
        });
      }
      if (next.shots.some((s) => removed.has(s.cameraId) || removed.has(nativeCameraOwner(s.cameraId) || "")))
        fail("该相机正在被镜头使用，请先更换镜头机位");
      next.nodes = next.nodes.filter((n) => !removed.has(n.id));
      next.nodes.forEach((n) => {
        if (n.camera?.followNodeId && removed.has(n.camera.followNodeId)) {
          delete n.camera.followNodeId;
          delete n.camera.followPosition;
          delete n.camera.followOffset;
        }
      });
      next.timeline.tracks = next.timeline.tracks.filter(
        (t) => !removed.has(t.nodeId),
      );
    } else if (c.type === "timeline.replace") {
      next.timeline = copy(c.timeline);
      next.shots = copy(c.shots);
    } else if (c.type === "scene.settings") Object.assign(next, copy(c.patch));
    else if (c.type === "assets.replace") next.assets = copy(c.assets);
    else fail("不支持的编辑命令");
  }
  if(next.nodes.some(n=>n.assetObjectIds!==undefined||n.assetPivotId!==undefined)||next.timeline.tracks.some(t=>t.objectId!==undefined)){
    next.schemaVersion=4;next.sdkVersion="aid-previz-web@1.1.0";
  }
  validateScene(next);
  return next;
}
export function valueAt(track: PrevizTrack, frame: number): Vec3 {
  const keys = track.keys;
  if (frame <= keys[0].frame) return [...keys[0].value];
  for (let i = 1; i < keys.length; i++)
    if (frame < keys[i].frame) {
      const a = keys[i - 1],
        b = keys[i],
        t =
          track.interpolation === "step"
            ? 0
            : (frame - a.frame) / (b.frame - a.frame);
      return a.value.map((v, axis) => v + (b.value[axis] - v) * t) as Vec3;
    }
  return [...keys[keys.length - 1].value];
}
/** Compile once per document revision, never scan all tracks for every object/frame. */
export function compileSceneEvaluator(scene: PrevizScene) {
  const tracks = new Map<string, PrevizTrack[]>();
  const objectTracks = new Map<string, PrevizTrack[]>();
  for (const track of scene.timeline.tracks) {
    const target = track.objectId ? objectTracks : tracks;
    const group = target.get(track.nodeId) ?? [];
    group.push(track);
    target.set(track.nodeId, group);
  }
  return {
    tracks,
    objectTracks,
    node: (node: PrevizNode, frame: number) =>
      evaluateNode(node, tracks.get(node.id) ?? [], frame),
  };
}
export function actorMotion(track: PrevizTrack | undefined, frame: number) {
  let distance = 0,
    heading: number | undefined,
    moving = false;
  if (!track) return { distance, heading, moving };
  for (let i = 1; i < track.keys.length; i++) {
    const a = track.keys[i - 1],
      b = track.keys[i];
    const dx = b.value[0] - a.value[0],
      dz = b.value[2] - a.value[2],
      length = Math.hypot(dx, dz);
    const fraction = clamp((frame - a.frame) / (b.frame - a.frame), 0, 1);
    if (length > 1e-6 && frame >= a.frame) heading = Math.atan2(dx, dz);
    if (track.interpolation !== "step") {
      distance += length * fraction;
      if (frame >= a.frame && frame < b.frame && length > 1e-6) moving = true;
    }
    if (frame < b.frame) break;
  }
  return { distance, heading, moving };
}
function evaluateNode(
  node: PrevizNode,
  tracks: PrevizTrack[],
  frame: number,
): PrevizNode {
  if (!tracks.length) return node;
  const result = {
    ...node,
    localTransform: { ...node.localTransform },
    ...(node.camera ? { camera: { ...node.camera } } : {}),
  };
  for (const track of tracks) {
    if (track.channel === "target" && result.camera)
      result.camera.target = valueAt(track, frame);
    else if (track.channel !== "target")
      result.localTransform[track.channel] = valueAt(track, frame);
  }
  if (
    node.kind === "ACTOR" &&
    node.motionFacing !== "manual" &&
    !tracks.some((t) => t.channel === "rotation")
  ) {
    const heading = actorMotion(
      tracks.find((t) => t.channel === "position"),
      frame,
    ).heading;
    if (heading !== undefined)
      result.localTransform.rotation = [
        result.localTransform.rotation[0],
        heading,
        result.localTransform.rotation[2],
      ];
  }
  return result;
}
export function nodeAt(
  scene: PrevizScene,
  node: PrevizNode,
  frame: number,
): PrevizNode {
  return evaluateNode(
    node,
    scene.timeline.tracks.filter((t) => t.nodeId === node.id && !t.objectId),
    frame,
  );
}
export function shotAt(scene: PrevizScene, frame: number): PrevizShot {
  return (
    scene.shots.find(
      (s) => frame >= s.startFrame && frame < s.endFrameExclusive,
    ) ?? scene.shots[scene.shots.length - 1]
  );
}
export function resizeDuration(
  scene: PrevizScene,
  frameCount: number,
): PrevizCommand {
  const next = copy(scene),
    ratio = frameCount / scene.timeline.frameCount;
  next.timeline.frameCount = frameCount;
  for (const track of next.timeline.tracks) {
    const keys = new Map<number, Vec3>();
    track.keys.forEach((k) =>
      keys.set(Math.min(frameCount - 1, Math.round(k.frame * ratio)), k.value),
    );
    track.keys = [...keys]
      .map(([frame, value]) => ({ frame, value }))
      .sort((a, b) => a.frame - b.frame);
  }
  let end = 0;
  next.shots.forEach((s, i) => {
    s.startFrame = end;
    end =
      i === next.shots.length - 1
        ? frameCount
        : clamp(
            Math.round(s.endFrameExclusive * ratio),
            end + 1,
            frameCount - (next.shots.length - i - 1),
          );
    s.endFrameExclusive = end;
  });
  return { type: "scene.replace", scene: next };
}
export function setKey(
  scene: PrevizScene,
  nodeId: string,
  channel: PrevizTrack["channel"],
  frame: number,
  value: Vec3,
  objectId?: string,
): PrevizCommand {
  const timeline = copy(scene.timeline);
  let track = timeline.tracks.find(
    (t) => t.nodeId === nodeId && t.objectId === objectId && t.channel === channel,
  );
  if (!track) {
    track = { id: uid(), nodeId, ...(objectId ? {objectId} : {}), channel, interpolation: "linear", keys: [] };
    timeline.tracks.push(track);
  }
  track.keys = [
    ...track.keys.filter((k) => k.frame !== frame),
    { frame, value },
  ].sort((a, b) => a.frame - b.frame);
  return { type: "timeline.replace", timeline, shots: scene.shots };
}
export function aspectNumber(aspect: string) {
  const [w, h] = aspect.split(":").map(Number);
  return w / h;
}
