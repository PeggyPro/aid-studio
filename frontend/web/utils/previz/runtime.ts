import { PREVIZ_BOX_SIZE, PREVIZ_CONE_HEIGHT, PREVIZ_CONE_RADIUS, PREVIZ_HUMAN_HEIGHT_METERS, PREVIZ_SPHERE_RADIUS } from './sceneUnits'
import * as THREE from "three";
import { ThreePrevizDirectorRuntime } from "@/utils/studio/threePrevizRuntime";
import { AnimationSamples } from "./animationSamples";
import { objectEditsAt } from "./objectAnimation";
import { visibleObjectBounds } from "./objectBounds";
import { pruneGlbObjectSubset } from "./glbSubset";
import { resetGlbObjectSubset, applyGlbObjectSubset } from "./glbObjectOverrides";
import { prepareExportIdentities } from "./exportIdentity";
import { initializeGlbAnimation, validateGlbPlayback, evaluateGlbAnimation, releaseGlbAnimation } from "./glbAnimation";
import { nativeCameraId, nativeCameraOwner, PREVIZ_DIRECTOR_VIEW_ID } from "./nativeCameras";
import { PREVIZ_GLB_MAX_BYTES } from "./limits";
import { initializeGlbObjectOverrides, validateGlbObjectOverrides, resetGlbObjectOverrides, applyGlbObjectOverrides, releaseGlbObjectOverrides } from "./glbObjectOverrides";
import {
  GLTFLoader,
  type GLTF,
} from "three/examples/jsm/loaders/GLTFLoader.js";
import { clone as cloneSkeleton } from "three/examples/jsm/utils/SkeletonUtils.js";
import { GLTFExporter } from "three/examples/jsm/exporters/GLTFExporter.js";
import type { PrevizNode, PrevizScene } from "@/types/previz";
import {
  aspectNumber,
  actorMotion,
  compileSceneEvaluator,
  shotAt,
  validateScene,
} from "./core";

const glbMixers = new WeakMap<THREE.Object3D, THREE.AnimationMixer>();

export interface PrevizRuntime {
  scene: THREE.Scene;
  objects: Map<string, THREE.Object3D>;
  cameras: Map<string, THREE.PerspectiveCamera | THREE.OrthographicCamera>;
  mixers: THREE.AnimationMixer[];
  frame: (frame: number) => THREE.PerspectiveCamera | THREE.OrthographicCamera;
  refresh: () => void;
  sync: (document: PrevizScene) => Promise<void>;
  bounds: (id?: string) => THREE.Box3;
  dispose: () => void;
}
export function disposeTree(root: THREE.Object3D) {
  const geometries = new Set<THREE.BufferGeometry>(),
    materials = new Set<THREE.Material>(),
    textures = new Set<THREE.Texture>();
  root.traverse((o) => {
    const mesh = o as THREE.Mesh;
    if (mesh.geometry) geometries.add(mesh.geometry);
    if (mesh.material)
      (Array.isArray(mesh.material) ? mesh.material : [mesh.material]).forEach(
        (m) => {
          materials.add(m);
          Object.values(m).forEach((v) => {
            if (v instanceof THREE.Texture) textures.add(v);
          });
        },
      );
  });
  textures.forEach((t) => {
    const image = t.source?.data;
    if (typeof ImageBitmap !== "undefined" && image instanceof ImageBitmap)
      image.close();
    t.dispose();
  });
  materials.forEach((m) => m.dispose());
  geometries.forEach((g) => g.dispose());
}
export function dataUrlBytes(url: string): ArrayBuffer {
  const data = atob(url.slice(url.indexOf(",") + 1)),
    bytes = new Uint8Array(data.length);
  for (let i = 0; i < data.length; i++) bytes[i] = data.charCodeAt(i);
  return bytes.buffer;
}
/** GLB must be self-contained: no network URLs, external buffers or script-like content. */
export function inspectGlb(buffer: ArrayBuffer) {
  if (buffer.byteLength > PREVIZ_GLB_MAX_BYTES || buffer.byteLength < 20)
    throw new Error("GLB 文件不能超过 32MB，且须包含有效模型");
  const view = new DataView(buffer);
  if (
    view.getUint32(0, true) !== 0x46546c67 ||
    view.getUint32(4, true) !== 2 ||
    view.getUint32(8, true) !== buffer.byteLength ||
    view.getUint32(16, true) !== 0x4e4f534a
  )
    throw new Error("仅支持 GLB 2.0 二进制模型");
  const length = view.getUint32(12, true);
  if (length + 20 > buffer.byteLength) throw new Error("GLB JSON 区间损坏");
  const json = JSON.parse(
    new TextDecoder().decode(new Uint8Array(buffer, 20, length)),
  );
  if (
    json.asset?.version !== "2.0" ||
    (json.nodes?.length ?? 0) > 2000 ||
    (json.meshes?.length ?? 0) > 1000 ||
    (json.textures?.length ?? 0) > 64
  )
    throw new Error("GLB 格式或对象／纹理数量超出限制");
  const nodeParents = new Map<number, number>();
  (json.nodes ?? []).forEach((node: { children?: number[] }, index: number) => {
    for (const child of node.children ?? []) {
      if (
        !Number.isInteger(child) ||
        child < 0 ||
        child >= json.nodes.length ||
        nodeParents.has(child)
      )
        throw new Error("模型包含无效节点或多重父级");
      nodeParents.set(child, index);
    }
  });
  for (let index = 0; index < (json.nodes?.length ?? 0); index++) {
    const visited = new Set([index]);
    let parent = nodeParents.get(index);
    while (parent !== undefined) {
      if (visited.has(parent)) throw new Error("模型节点包含循环引用");
      visited.add(parent);
      if (visited.size > 100) throw new Error("模型层级超过 100 层");
      parent = nodeParents.get(parent);
    }
  }
  for (const bufferView of json.bufferViews ?? [])
    if (
      !Number.isInteger(bufferView.byteLength) ||
      bufferView.byteLength < 0 ||
      bufferView.byteLength > buffer.byteLength ||
      (bufferView.byteOffset ?? 0) < 0 ||
      (bufferView.byteOffset ?? 0) + bufferView.byteLength >
        (json.buffers?.[bufferView.buffer]?.byteLength ?? 0)
    )
      throw new Error("模型缓冲区越界");
  for (const accessor of json.accessors ?? [])
    if (
      !Number.isInteger(accessor.count) ||
      accessor.count < 0 ||
      accessor.count > 1_500_000
    )
      throw new Error("模型属性数量超出限制");
  if (
    (json.buffers ?? []).some((b: { uri?: string }) => b.uri) ||
    (json.images ?? []).some(
      (i: { uri?: string; mimeType?: string }) =>
        i.uri ||
        !["image/png", "image/jpeg", "image/webp"].includes(i.mimeType ?? ""),
    )
  )
    throw new Error("GLB 必须内嵌全部资源，仅支持 PNG／JPEG／WebP 纹理");
  const allowedExtensions = new Set([
    // Keep in sync with the bundled GLTFLoader's built-in plugins. Decoder-
    // dependent compression formats remain rejected until a decoder is bound.
    "KHR_lights_punctual",
    "KHR_materials_unlit",
    "KHR_materials_pbrSpecularGlossiness",
    "KHR_mesh_quantization",
    "KHR_materials_emissive_strength",
    "KHR_materials_clearcoat",
    "KHR_materials_dispersion",
    "KHR_materials_sheen",
    "KHR_materials_transmission",
    "KHR_materials_volume",
    "KHR_materials_ior",
    "KHR_materials_specular",
    "KHR_materials_iridescence",
    "KHR_materials_anisotropy",
    "EXT_materials_bump",
    "KHR_texture_transform",
    "EXT_texture_webp",
  ]);
  if (
    (json.extensionsRequired ?? []).some(
      (extension: string) => !allowedExtensions.has(extension),
    )
  )
    throw new Error("模型使用未支持的压缩或必需扩展，请导出为标准未压缩 GLB");
  let indices = 0;
  for (const mesh of json.meshes ?? [])
    for (const p of mesh.primitives ?? []) {
      indices +=
        (p.indices === undefined
          ? json.accessors?.[p.attributes?.POSITION]?.count
          : json.accessors?.[p.indices]?.count) ?? 0;
    }
  if (indices > 1_500_000) throw new Error("模型超过 50 万三角面，请简化模型");
  return json;
}
function material(color: string) {
  return new THREE.MeshStandardMaterial({
    color,
    roughness: 0.82,
    metalness: 0.03,
  });
}
function mesh(
  geometry: THREE.BufferGeometry,
  mat: THREE.Material,
  x = 0,
  y = 0,
  z = 0,
) {
  const result = new THREE.Mesh(geometry, mat);
  result.position.set(x, y, z);
  result.castShadow = true;
  result.receiveShadow = true;
  return result;
}
function box(
  group: THREE.Group,
  mat: THREE.Material,
  size: number[],
  pos: number[],
  radius = 0,
) {
  // Distinct furniture components, not a single placeholder cube.
  const child = mesh(
    radius
      ? new THREE.CapsuleGeometry(
          radius,
          Math.max(0.01, size[1] - radius * 2),
          4,
          8,
        )
      : new THREE.BoxGeometry(...(size as [number, number, number])),
    mat,
    ...(pos as [number, number, number]),
  );
  group.add(child);
  return child;
}
function actor(mat: THREE.Material): THREE.Group {
  const group = new THREE.Group();
  group.add(
    mesh(new THREE.CapsuleGeometry(0.22, 0.35, 6, 14), mat, 0, 1.18, 0),
  );
  const pelvis = mesh(new THREE.SphereGeometry(0.23, 14, 10), mat, 0, 0.87, 0);
  pelvis.scale.set(1, 0.65, 0.7);
  group.add(pelvis);
  group.add(
    mesh(new THREE.CylinderGeometry(0.075, 0.09, 0.12, 12), mat, 0, 1.57, 0),
  );
  const head = mesh(new THREE.SphereGeometry(0.16, 18, 14), mat, 0, 1.77, 0);
  head.scale.set(0.85, 1.15, 1);
  group.add(head);
  for (const side of [-1, 1]) {
    const arm = new THREE.Group();
    arm.name = side === -1 ? "leftArm" : "rightArm";
    arm.position.set(side * 0.28, 1.43, 0);
    arm.add(
      mesh(new THREE.CapsuleGeometry(0.067, 0.25, 4, 10), mat, 0, -0.16, 0),
    );
    const elbow = new THREE.Group();
    elbow.name = side === -1 ? "leftElbow" : "rightElbow";
    elbow.position.y = -0.34;
    elbow.add(
      mesh(new THREE.CapsuleGeometry(0.055, 0.23, 4, 10), mat, 0, -0.14, 0),
    );
    elbow.add(mesh(new THREE.SphereGeometry(0.065, 10, 8), mat, 0, -0.31, 0));
    arm.add(elbow);
    group.add(arm);
    const leg = new THREE.Group();
    leg.name = side === -1 ? "leftLeg" : "rightLeg";
    leg.position.set(side * 0.12, 0.87, 0);
    leg.add(
      mesh(new THREE.CapsuleGeometry(0.095, 0.25, 4, 10), mat, 0, -0.19, 0),
    );
    const knee = new THREE.Group();
    knee.name = side === -1 ? "leftKnee" : "rightKnee";
    knee.position.y = -0.43;
    knee.add(
      mesh(new THREE.CapsuleGeometry(0.073, 0.25, 4, 10), mat, 0, -0.18, 0),
    );
    const foot = mesh(
      new THREE.SphereGeometry(0.1, 12, 8),
      mat,
      0,
      -0.36,
      0.06,
    );
    foot.scale.set(0.8, 0.55, 1.6);
    knee.add(foot);
    leg.add(knee);
    group.add(leg);
  }
  const bounds = new THREE.Box3().setFromObject(group);
  const scale = PREVIZ_HUMAN_HEIGHT_METERS / (bounds.max.y - bounds.min.y);
  group.scale.setScalar(scale);
  group.position.y = -bounds.min.y * scale;
  return new THREE.Group().add(group);
}
function geometryNode(node: PrevizNode): THREE.Object3D {
  const mat = material(node.color),
    group = new THREE.Group();
  if (node.kind === "ACTOR") return actor(mat);
  if (node.kind === "BOX")
    group.add(mesh(new THREE.BoxGeometry(PREVIZ_BOX_SIZE, PREVIZ_BOX_SIZE, PREVIZ_BOX_SIZE), mat, 0, PREVIZ_BOX_SIZE / 2, 0));
  if (node.kind === "SPHERE")
    group.add(mesh(new THREE.SphereGeometry(PREVIZ_SPHERE_RADIUS, 24, 16), mat, 0, PREVIZ_SPHERE_RADIUS, 0));
  if (node.kind === "CONE")
    group.add(mesh(new THREE.ConeGeometry(PREVIZ_CONE_RADIUS, PREVIZ_CONE_HEIGHT, 32), mat, 0, PREVIZ_CONE_HEIGHT / 2, 0));
  if (node.kind === "PLANE") {
    const floor = mesh(new THREE.PlaneGeometry(1, 1), mat);
    floor.rotation.x = -Math.PI / 2;
    group.add(floor);
  }
  if (node.kind === "WALL") box(group, mat, [4, 3, 0.18], [0, 1.5, 0]);
  if (node.kind === "TABLE") {
    box(group, mat, [2.6, 0.1, 1.1], [0, 0.78, 0]);
    for (const x of [-1.15, 1.15])
      for (const z of [-0.4, 0.4])
        box(group, mat, [0.08, 0.72, 0.08], [x, 0.36, z]);
  }
  if (node.kind === "CHAIR") {
    box(group, mat, [0.5, 0.08, 0.5], [0, 0.46, 0]);
    box(group, mat, [0.5, 0.48, 0.07], [0, 0.72, -0.23]);
    for (const x of [-0.2, 0.2])
      for (const z of [-0.2, 0.2])
        box(group, mat, [0.045, 0.43, 0.045], [x, 0.215, z]);
  }
  if (node.kind === "TREE") {
    group.add(mesh(new THREE.CylinderGeometry(0.1, 0.18, 2, 10), mat, 0, 1, 0));
    for (const [x, y, z, r] of [
      [0, 2.5, 0, 0.9],
      [-0.45, 2.1, 0.1, 0.65],
      [0.45, 2.2, 0.1, 0.7],
    ])
      group.add(mesh(new THREE.IcosahedronGeometry(r, 2), mat, x, y, z));
  }
  if (node.kind === "ARCH") {
    const shape = new THREE.Shape();
    shape.moveTo(-1.6, 0);
    shape.lineTo(-1.6, 2.3);
    shape.absarc(0, 2.3, 1.6, Math.PI, 0, true);
    shape.lineTo(1.6, 0);
    shape.lineTo(1.2, 0);
    shape.lineTo(1.2, 2.3);
    shape.absarc(0, 2.3, 1.2, 0, Math.PI, false);
    shape.lineTo(-1.2, 0);
    shape.closePath();
    const arch = mesh(
      new THREE.ExtrudeGeometry(shape, {
        depth: 0.45,
        bevelEnabled: true,
        bevelThickness: 0.03,
        bevelSize: 0.03,
        bevelSegments: 2,
        steps: 1,
      }),
      mat,
    );
    group.add(arch);
  }
  // Unused material in a light/camera shell must also be released.
  if (!group.children.length) mat.dispose();
  return group;
}
const actorJoints = new WeakMap<
  THREE.Object3D,
  Record<string, THREE.Object3D>
>();
function poseActor(
  root: THREE.Object3D,
  node: PrevizNode,
  motion: ReturnType<typeof actorMotion>,
  frame: number,
) {
  const walking = ["walk", "run"].includes(node.action ?? "") && motion.moving;
  const running = node.action === "run",
    stride = running ? 1.5 : 1.0;
  const phase = (motion.distance / stride) * Math.PI * 2;
  let joints = actorJoints.get(root);
  if (!joints) {
    joints = Object.fromEntries(
      ["left", "right"].flatMap((side) =>
        ["Leg", "Knee", "Arm", "Elbow"].map((part) => [
          `${side}${part}`,
          root.getObjectByName(`${side}${part}`),
        ]),
      ),
    ) as Record<string, THREE.Object3D>;
    actorJoints.set(root, joints);
  }
  for (const side of ["left", "right"]) {
    const sign = side === "left" ? 1 : -1,
      cycle = phase + (side === "left" ? 0 : Math.PI);
    const leg = joints[`${side}Leg`],
      knee = joints[`${side}Knee`],
      arm = joints[`${side}Arm`],
      elbow = joints[`${side}Elbow`];
    if (node.action === "sit") {
      leg.rotation.x = -Math.PI / 2;
      knee.rotation.x = Math.PI / 2;
    } else if (walking) {
      // Two-bone sagittal gait: stance foot stays on the ground, swing foot lifts.
      const z = Math.cos(cycle) * (running ? 0.3 : 0.22),
        y = -0.78 + Math.max(0, Math.sin(cycle)) * (running ? 0.16 : 0.1);
      const a = 0.43,
        b = 0.4,
        d = Math.min(a + b - 0.001, Math.hypot(y, z));
      leg.rotation.x =
        Math.atan2(-z, -y) -
        Math.acos(
          THREE.MathUtils.clamp((a * a + d * d - b * b) / (2 * a * d), -1, 1),
        );
      knee.rotation.x =
        Math.PI -
        Math.acos(
          THREE.MathUtils.clamp((a * a + b * b - d * d) / (2 * a * b), -1, 1),
        );
    } else {
      leg.rotation.x = 0;
      knee.rotation.x = 0;
    }
    arm.rotation.x = walking ? Math.cos(cycle) * (running ? 0.65 : 0.35) : 0;
    arm.rotation.z =
      node.action === "wave" && side === "right"
        ? -2.3 + Math.sin(frame / 8) * 0.15
        : sign * 0.08;
    elbow.rotation.x =
      node.action === "wave" && side === "right"
        ? -0.8
        : running && walking
          ? -0.9
          : -0.1;
  }
}
async function runtimeObject(
  node: PrevizNode,
  document: PrevizScene,
  loadAsset: (dataUrl: string) => Promise<GLTF>,
): Promise<THREE.Object3D> {
  let object: THREE.Object3D;
  if (node.kind === "CAMERA") {
    const c = node.camera!;
    object = c.projection === "orthographic" ? new THREE.OrthographicCamera(
      -c.orthoHeight! * aspectNumber(document.aspectRatio) / 2, c.orthoHeight! * aspectNumber(document.aspectRatio) / 2,
      c.orthoHeight! / 2, -c.orthoHeight! / 2, c.near, c.far) : new THREE.PerspectiveCamera(
      c.fov,
      aspectNumber(document.aspectRatio),
      c.near,
      c.far,
    );
  } else if (node.kind === "LIGHT") {
    const { kind, intensity } = node.light!;
    object =
      kind === "point"
        ? new THREE.PointLight(node.color, intensity, 40)
        : kind === "spot"
          ? new THREE.SpotLight(node.color, intensity, 40, 0.6, 0.5)
          : new THREE.DirectionalLight(node.color, intensity);
  } else if (node.kind === "GLB") {
    const gltf = await loadAsset(
      document.assets.find((a) => a.id === node.assetId)!.dataUrl,
    );
    const content = cloneSkeleton(gltf.scene);
    const subset=pruneGlbObjectSubset(content,node.assetObjectIds,gltf.animations);
    object = content;
    if (node.assetPivotId) {
      content.updateMatrixWorld(true);
      let pivot: THREE.Object3D | undefined;
      content.traverse(child=>{if(child.userData.hf_id===node.assetPivotId){if(pivot)throw new Error("模型锚点重复");pivot=child;}});
      if(!pivot||!Number.isFinite(pivot.matrixWorld.determinant())||Math.abs(pivot.matrixWorld.determinant())<1e-12)throw new Error("模型对象锚点无效");
      const anchor=new THREE.Group();anchor.matrixAutoUpdate=false;anchor.matrix.copy(pivot.matrixWorld).invert();
      object=new THREE.Group();object.add(anchor);anchor.add(content);
    }
    initializeGlbObjectOverrides(object,subset.knownIds);
    object.userData.previzSharedAsset = true;
    // Recheck decoded geometry / image bounds, not just declarations in the header.
    let vertices = 0;
    let textureTooLarge = false;
    object.traverse((o) => {
      const m = o as THREE.Mesh;
      vertices += m.geometry?.attributes.position?.count ?? 0;
      const mats = m.material
        ? Array.isArray(m.material)
          ? m.material
          : [m.material]
        : [];
      mats.forEach((mat) =>
        Object.values(mat).forEach((v) => {
          if (
            v instanceof THREE.Texture &&
            (v.image?.width > 4096 || v.image?.height > 4096)
          )
            textureTooLarge = true;
        }),
      );
    });
    if (vertices > 1_500_000 || textureTooLarge) {
      throw new Error("解码后的模型或纹理超过限制（纹理最大 4096px）");
    }
    if (subset.clips.length) {
      const mixer = initializeGlbAnimation(object, subset.clips);
      glbMixers.set(object, mixer);
    }
  } else object = geometryNode(node);
  object.userData.previzNodeId = node.id;
  object.traverse((child) => {
    if (child !== object) delete child.userData.previzNodeId;
    if (child instanceof THREE.Mesh) {
      child.castShadow = true;
      child.receiveShadow = true;
    }
  });
  return object;
}
/** Persistent scene graph. Numeric edits never decode assets or recreate the renderer. */
export async function buildRuntime(
  initial: PrevizScene,
  options: { batch?: boolean; externalObjects?: Map<string, THREE.Object3D>; skipEnvironment?: boolean } = {},
): Promise<PrevizRuntime> {
  let document = initial,
    evaluator = compileSceneEvaluator(initial),
    lastFrame = -1,
    syncVersion = 0,
    disposed = false;
  const director = new ThreePrevizDirectorRuntime();
  const observer = new THREE.PerspectiveCamera();
  let directorSignatures = new Map<string, string>();
  const borrowed = new Set<THREE.Object3D>();
  const scene = new THREE.Scene(),
    objects = new Map<string, THREE.Object3D>(),
    cameras = new Map<string, THREE.PerspectiveCamera | THREE.OrthographicCamera>(),
    mixers: THREE.AnimationMixer[] = [];
  let definitions = new Map<string, PrevizNode>(),
    assets = new Map<string, string>();
  const assetCache = new Map<string, { promise: Promise<GLTF>; gltf?: GLTF }>();
  function loadAsset(dataUrl: string) {
    const cached = assetCache.get(dataUrl);
    if (cached) return cached.promise;
    const bytes = dataUrlBytes(dataUrl);
    inspectGlb(bytes);
    const entry: { promise: Promise<GLTF>; gltf?: GLTF } = {
      promise: new GLTFLoader().parseAsync(bytes, ""),
    };
    entry.promise = entry.promise
      .then((gltf) => {
        entry.gltf = gltf;
        if (disposed) disposeTree(gltf.scene);
        return gltf;
      })
      .catch((error) => {
        assetCache.delete(dataUrl);
        throw error;
      });
    assetCache.set(dataUrl, entry);
    return entry.promise;
  }
  const sky = new THREE.HemisphereLight("#d9e8ff", "#665b4b", 2.4),
    sun = new THREE.DirectionalLight("#fff0da", 3.5);
  sun.position.set(-3, 8, 5);
  sun.castShadow = true;
  sun.shadow.mapSize.set(1024, 1024);
  sun.shadow.camera.left = sun.shadow.camera.bottom = -12;
  sun.shadow.camera.right = sun.shadow.camera.top = 12;
  sun.shadow.bias = -0.0005;
  sun.shadow.normalBias = 0.025;
  if (!options.skipEnvironment || !initial.directorLayer?.scene.lights.length) {
    scene.add(director.environment);
    if (!initial.directorLayer?.scene.lights.length) scene.add(sky, sun);
  }
  type Batch = { mesh: THREE.InstancedMesh; sources: THREE.Mesh[] };
  let batches: Batch[] = [];
  const originalVisibility = new Map<THREE.Mesh, boolean>();
  const zero = new THREE.Matrix4().makeScale(0, 0, 0),
    target = new THREE.Vector3(),
    position = new THREE.Vector3();
  const centers = new Map<string, THREE.Vector3>();
  function clearBatches() {
    batches.forEach((b) => {
      scene.remove(b.mesh);
      b.mesh.dispose();
    });
    batches = [];
    originalVisibility.forEach((visible, mesh) => {
      mesh.visible = visible;
    });
    originalVisibility.clear();
  }
  function makeBatches() {
    clearBatches();
    if (!options.batch) return;
    const groups = new Map<string, THREE.Mesh[]>();
    for (const [id, object] of objects) {
      const n = definitions.get(id)!;
      if (["GLB", "CAMERA", "LIGHT"].includes(n.kind)) continue;
      object.traverse((child) => {
        if (!(child instanceof THREE.Mesh) || Array.isArray(child.material))
          return;
        let owner: THREE.Object3D | null = child;
        while (owner && !owner.userData.previzNodeId) owner = owner.parent;
        if (owner !== object) return;
        const geometry = child.geometry as THREE.BufferGeometry & {
          parameters?: unknown;
        };
        const mat = child.material as THREE.MeshStandardMaterial;
        const key = `${geometry.type}:${JSON.stringify(geometry.parameters)}:${mat.color.getHexString()}:${mat.roughness}:${mat.metalness}`;
        const list = groups.get(key) ?? [];
        list.push(child);
        groups.set(key, list);
      });
    }
    for (const sources of groups.values()) {
      const first = sources[0],
        mesh = new THREE.InstancedMesh(
          first.geometry,
          first.material,
          sources.length,
        );
      mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
      mesh.castShadow = true;
      mesh.receiveShadow = true;
      mesh.frustumCulled = false;
      mesh.name = "previz_viewport_batch";
      sources.forEach((source) => {
        originalVisibility.set(source, source.visible);
        source.visible = false;
      });
      scene.add(mesh);
      batches.push({ mesh, sources });
    }
  }
  function updateBatches() {
    for (const batch of batches) {
      batch.sources.forEach((source, i) => {
        let parent = source.parent,
          visible = originalVisibility.get(source) !== false;
        while (parent && parent !== scene) {
          if (!parent.visible) visible = false;
          parent = parent.parent;
        }
        batch.mesh.setMatrixAt(i, visible ? source.matrixWorld : zero);
      });
      batch.mesh.instanceMatrix.needsUpdate = true;
    }
  }
  function release(object: THREE.Object3D) {
    if (borrowed.has(object)) { borrowed.delete(object); return; }
    const mixer = glbMixers.get(object);
    if (mixer) {
      releaseGlbAnimation(object);
      mixer.stopAllAction();
      mixer.uncacheRoot(object);
      glbMixers.delete(object);
      const i = mixers.indexOf(mixer);
      if (i >= 0) mixers.splice(i, 1);
    }
    object.traverse((child) => {
      if (child instanceof THREE.Light) child.dispose();
    });
    releaseGlbObjectOverrides(object);
    if (!object.userData.previzSharedAsset) disposeTree(object);
    object.removeFromParent();
  }
  async function sync(next: PrevizScene) {
    validateScene(next);
    const version = ++syncVersion;
    const signatures = new Map(next.nodes.map(node => [node.id, director.signature(node, next.directorLayer)]));
    const nextAssets = new Map(next.assets.map((a) => [a.id, a.dataUrl]));
    const additions = new Map<string, THREE.Object3D>();
    let panorama: Awaited<ReturnType<ThreePrevizDirectorRuntime['preparePanorama']>> | undefined;
    try {
      if (!options.skipEnvironment) panorama = await director.preparePanorama(next.directorLayer);
      for (const node of next.nodes) {
        const old = definitions.get(node.id);
        if (
          !old ||
          directorSignatures.get(node.id) !== signatures.get(node.id) ||
          (options.externalObjects?.has(node.id) && objects.get(node.id) !== options.externalObjects.get(node.id)) ||
          old.kind !== node.kind ||
          old.light?.kind !== node.light?.kind ||
          old.camera?.projection !== node.camera?.projection ||
          old.assetId !== node.assetId ||
          old.assetPivotId !== node.assetPivotId ||
          JSON.stringify(old.assetObjectIds)!==JSON.stringify(node.assetObjectIds) ||
          (node.kind === "GLB" &&
            assets.get(node.assetId!) !== nextAssets.get(node.assetId!))
        ) {
          const external = options.externalObjects?.get(node.id);
          const object = external ?? await director.create(node, next.directorLayer) ?? await runtimeObject(node, next, loadAsset);
          if (external) borrowed.add(external);
          object.userData.previzNodeId = node.id;
          additions.set(node.id, object);
        }
        if (node.kind === "GLB") {
          const object = additions.get(node.id) || objects.get(node.id)!;
          validateGlbObjectOverrides(object, node.assetOverrides);
          validateGlbPlayback(object, node.assetPlayback);
        }
      }
      for(const shot of next.shots){
        const owner=nativeCameraOwner(shot.cameraId);if(!owner)continue;
        let found=false;
        (additions.get(owner)||objects.get(owner))?.traverse(child=>{
          if((child instanceof THREE.PerspectiveCamera||child instanceof THREE.OrthographicCamera)
            &&typeof child.userData.hf_id==="string"&&nativeCameraId(owner,child.userData.hf_id)===shot.cameraId)found=true;
        });
        if(!found)throw new Error("镜头引用的原生相机不存在，原场景保持不变");
      }
    } catch (error) {
      additions.forEach(release);
      director.discardPanorama(panorama);
      throw error;
    }
    if (disposed || version !== syncVersion) {
      additions.forEach(release);
      director.discardPanorama(panorama);
      return;
    }
    clearBatches();
    // Detach children first so removing a parent never disposes a retained child.
    objects.forEach((o) => { if (!borrowed.has(o)) scene.add(o); });
    const ids = new Set(next.nodes.map((n) => n.id));
    for (const [id, object] of objects)
      if (!ids.has(id) || additions.has(id)) {
        if (additions.get(id) !== object) release(object);
        objects.delete(id);
        cameras.delete(id);
      }
    additions.forEach((o, id) => {
      objects.set(id, o);
      if (!borrowed.has(o)) scene.add(o);
      if (o instanceof THREE.PerspectiveCamera || o instanceof THREE.OrthographicCamera) cameras.set(id, o);
      const mixer = glbMixers.get(o);
      if (mixer) mixers.push(mixer);
    });
    document = next;
    director.setLayer(next.directorLayer, panorama);
    directorSignatures = signatures;
    for(const id of cameras.keys())if(id.startsWith("@native/"))cameras.delete(id);
    for(const node of next.nodes)if(node.kind==="GLB")objects.get(node.id)!.traverse(child=>{
      if((child instanceof THREE.PerspectiveCamera||child instanceof THREE.OrthographicCamera)&&typeof child.userData.hf_id==="string"){
        if(child instanceof THREE.PerspectiveCamera)child.aspect=aspectNumber(next.aspectRatio);
        else {const height=child.top-child.bottom,center=(child.left+child.right)/2;child.left=center-height*aspectNumber(next.aspectRatio)/2;child.right=center+height*aspectNumber(next.aspectRatio)/2;}
        child.updateProjectionMatrix();cameras.set(nativeCameraId(node.id,child.userData.hf_id),child);
      }
    });
    const directorScene = next.directorLayer?.scene;
    if (cameras.get(PREVIZ_DIRECTOR_VIEW_ID) === observer) cameras.delete(PREVIZ_DIRECTOR_VIEW_ID);
    if (directorScene && !cameras.has(PREVIZ_DIRECTOR_VIEW_ID)) {
      const view = directorScene.directorCamera ?? directorScene.camera;
      observer.position.fromArray(view.position);
      observer.lookAt(...view.target);
      observer.rotateZ((view.roll ?? 0) * Math.PI / 180);
      observer.fov = view.fov;
      observer.aspect = aspectNumber(next.aspectRatio);
      observer.updateProjectionMatrix();
      cameras.set(PREVIZ_DIRECTOR_VIEW_ID, observer);
    }
    if(next.shots.some(shot=>!cameras.has(shot.cameraId)))throw new Error("镜头引用的原生相机不存在");
    evaluator = compileSceneEvaluator(next);
    definitions = new Map(next.nodes.map((n) => [n.id, n]));
    assets = nextAssets;
    const retained = new Set(nextAssets.values());
    for (const [key, entry] of assetCache)
      if (entry.gltf && !retained.has(key)) {
        disposeTree(entry.gltf.scene);
        assetCache.delete(key);
      }
    centers.clear();
    scene.background = new THREE.Color(next.environment.background);
    sky.intensity = next.environment.presetCode === "night" ? 0.6 : 2.4;
    sun.intensity = next.environment.presetCode === "night" ? 0.9 : 3.5;
    for (const n of next.nodes) {
      const o = objects.get(n.id)!;
      if (borrowed.has(o)) continue;
      o.name = `previz_${n.id}`;
      o.position.fromArray(n.localTransform.position);
      o.rotation.set(...n.localTransform.rotation);
      o.scale.fromArray(n.localTransform.scale);
      o.visible = n.visible;
      if (o instanceof THREE.PerspectiveCamera || o instanceof THREE.OrthographicCamera) {
        const c = n.camera!;
        if(o instanceof THREE.PerspectiveCamera){o.fov = c.fov;o.aspect = aspectNumber(next.aspectRatio);}
        else {o.top=c.orthoHeight!/2;o.bottom=-c.orthoHeight!/2;o.left=-c.orthoHeight!*aspectNumber(next.aspectRatio)/2;o.right=c.orthoHeight!*aspectNumber(next.aspectRatio)/2;}
        o.near = c.near;
        o.far = c.far;
        o.updateProjectionMatrix();
      }
      if (o instanceof THREE.Light) {
        o.color.set(n.color);
        o.intensity = n.light!.intensity;
      } else if (n.kind !== "GLB" && !o.userData.threePrevizDirector)
        o.traverse((child) => {
          if (child instanceof THREE.Mesh) {
            let owner: THREE.Object3D | null = child;
            while (owner && !owner.userData.previzNodeId) owner = owner.parent;
            if (owner !== o) return;
            const mats = Array.isArray(child.material)
              ? child.material
              : [child.material];
            mats.forEach((m) => {
              if (m instanceof THREE.MeshStandardMaterial) m.color.set(n.color);
            });
          }
        });
    }
    for (const n of next.nodes)
      if (n.parentId && !borrowed.has(objects.get(n.id)!)) objects.get(n.parentId)!.add(objects.get(n.id)!);
    scene.updateMatrixWorld(true);
    for(const node of next.nodes)if(node.kind==="GLB"){const object=objects.get(node.id)!;resetGlbObjectSubset(object);applyGlbObjectSubset(object,node.assetObjectIds);}
    for (const [id, object] of objects) {
      const bounds = visibleObjectBounds(object);
      const center = bounds.isEmpty()
        ? new THREE.Vector3(0, definitions.get(id)?.kind === "ACTOR" ? 1 : 0, 0)
        : object.worldToLocal(bounds.getCenter(new THREE.Vector3()));
      centers.set(id, center);
    }
    makeBatches();
    lastFrame = -1;
  }
  const frame = (requested: number) => {
    const f = Math.max(
      0,
      Math.min(document.timeline.frameCount - 1, requested),
    );
    const active = cameras.get(shotAt(document, f).cameraId)!;
    if (f === lastFrame) return active;
    for (const node of document.nodes) {
      const o = objects.get(node.id)!,
        evaluated = evaluator.node(director.evaluate(node, f), f),
        t = evaluated.localTransform;
      if (lastFrame < 0 || evaluator.tracks.has(node.id) || document.directorLayer?.bindings[node.id]) {
        o.position.fromArray(t.position);
        o.rotation.set(...t.rotation);
        o.scale.fromArray(t.scale);
      }
      o.visible = node.visible;
      if (document.directorLayer?.bindings[node.id]) director.pose(node, o);
      else if (node.kind === "ACTOR")
        poseActor(
          o,
          evaluated,
          actorMotion(
            evaluator.tracks
              .get(node.id)
              ?.find((t) => t.channel === "position"),
            f,
          ),
          f,
        );
    }
    for (const node of document.nodes) if (node.kind === "GLB") { resetGlbObjectSubset(objects.get(node.id)!); resetGlbObjectOverrides(objects.get(node.id)!, node.assetOverrides); }
    for (const node of document.nodes) if (node.kind === "GLB") evaluateGlbAnimation(objects.get(node.id)!, node.assetPlayback, f / 30);
    for (const node of document.nodes) if (node.kind === "GLB") applyGlbObjectOverrides(objects.get(node.id)!, objectEditsAt(node, evaluator.objectTracks.get(node.id) || [], f));
    for (const node of document.nodes) if (node.kind === "GLB") applyGlbObjectSubset(objects.get(node.id)!,node.assetObjectIds);
    scene.updateMatrixWorld();
    for (const node of document.nodes)
      if (node.camera) {
        const camera = cameras.get(node.id)!,
          evaluated = evaluator.node(director.evaluate(node, f), f);
        if (node.camera.followNodeId) {
          const subject = objects.get(node.camera.followNodeId)!;
          target
            .copy(centers.get(node.camera.followNodeId)!)
            .applyMatrix4(subject.matrixWorld);
          if (node.camera.followPosition) {
            subject
              .getWorldPosition(position)
              .add(new THREE.Vector3(...node.camera.followOffset!));
            if (camera.parent && camera.parent !== scene)
              camera.parent.worldToLocal(position);
            camera.position.copy(position);
          }
        } else target.fromArray(evaluated.camera!.target);
        if (camera instanceof THREE.PerspectiveCamera && evaluated.camera) { camera.fov = evaluated.camera.fov; camera.updateProjectionMatrix(); }
        camera.lookAt(target);
        if(evaluated.camera!.roll)camera.rotateZ(evaluated.camera!.roll);
        camera.updateMatrixWorld();
      }
    scene.updateMatrixWorld();
    updateBatches();
    lastFrame = f;
    return active;
  };
  const bounds = (id?: string) => {
    scene.updateMatrixWorld();
    const box = new THREE.Box3();
    if (id && objects.has(id)) return visibleObjectBounds(objects.get(id)!,box);
    for (const [key, o] of objects) {
      const n = definitions.get(key)!;
      let ancestor: THREE.Object3D | null = o,
        visible = true;
      while (ancestor) {
        if (!ancestor.visible) visible = false;
        ancestor = ancestor.parent;
      }
      if (visible && !["CAMERA", "LIGHT", "PLANE"].includes(n.kind))
        box.union(visibleObjectBounds(o));
    }
    return box;
  };
  const dispose = () => {
    disposed = true;
    syncVersion++;
    clearBatches();
    objects.forEach((o) => { if (!borrowed.has(o)) scene.add(o); });
    objects.forEach(release);
    objects.clear();
    cameras.clear();
    assetCache.forEach((entry) => {
      if (entry.gltf) disposeTree(entry.gltf.scene);
    });
    assetCache.clear();
    director.dispose();
    sky.dispose();
    sun.dispose();
    scene.clear();
  };
  try {
    await sync(initial);
    frame(0);
  } catch (e) {
    dispose();
    throw e;
  }
  return {
    scene,
    objects,
    cameras,
    mixers,
    frame,
    sync,
    bounds,
    refresh: () => {
      scene.updateMatrixWorld();
      updateBatches();
    },
    dispose,
  };
}
export async function exportGlb(
  document: PrevizScene,
  signal: AbortSignal,
  progress: (p: number) => void,
) {
  const runtime = await buildRuntime(document);
  try {
    const tracks: THREE.KeyframeTrack[] = [],
      fps = document.timeline.fps;
    // Bake the same frame evaluator, including semantic mannequin joints and camera hard cuts
    // (shot selection itself lives in the portable AID package, not the GLB standard).
    const animated: THREE.Object3D[] = [];
    const included = new Set<string>();
    runtime.objects.forEach((o) =>
      o.traverse((child) => {
        let parent: THREE.Object3D | null = child;
        while (parent) {
          if (!parent.visible) return;
          parent = parent.parent;
        }
        if (!included.has(child.uuid)) {
          included.add(child.uuid);
          animated.push(child);
        }
      }),
    );
    const aimedLights: Array<THREE.DirectionalLight | THREE.SpotLight> = [];
    runtime.scene.traverse(object => {
      if (!(object instanceof THREE.DirectionalLight || object instanceof THREE.SpotLight)) return;
      for (let parent: THREE.Object3D | null = object; parent; parent = parent.parent) if (!parent.visible) return;
      const target = object.target;
      if (target.parent === object && target.position.equals(new THREE.Vector3(0, 0, -1))) return;
      aimedLights.push(object);
      if (!included.has(object.uuid)) { included.add(object.uuid); animated.push(object); }
    });
    const orientLights = () => {
      runtime.scene.updateMatrixWorld(true);
      const targets = aimedLights.map(light => light.target.getWorldPosition(new THREE.Vector3()));
      aimedLights.forEach((light, i) => light.lookAt(targets[i]));
      runtime.scene.updateMatrixWorld(true);
    };
    const samples = animated.map((o) => ({
        object: o,
        positions: new AnimationSamples(),
        quaternions: new AnimationSamples(),
        scales: new AnimationSamples(),
        morphs: (o as THREE.Mesh).morphTargetInfluences?.length
          ? new AnimationSamples() : undefined,
      }));
    for (let f = 0; f <= document.timeline.frameCount; f++) {
      signal.throwIfAborted();
      runtime.frame(f);
      orientLights();
      samples.forEach((s) => {
        const time = f / fps;
        s.positions.append(time, s.object.position.toArray());
        s.quaternions.append(time, s.object.quaternion.toArray());
        s.scales.append(time, s.object.scale.toArray());
        if (s.morphs) s.morphs.append(time, (s.object as THREE.Mesh).morphTargetInfluences!);
      });
      if (f % 30 === 0) {
        progress(f / document.timeline.frameCount);
        await new Promise((resolve) => setTimeout(resolve, 0));
      }
    }
    samples.forEach((s) => {
      s.positions.finish(); s.quaternions.finish(); s.scales.finish(); s.morphs?.finish();
      tracks.push(
        new THREE.VectorKeyframeTrack(
          `${s.object.uuid}.position`,
          s.positions.times,
          s.positions.values,
        ),
        new THREE.QuaternionKeyframeTrack(
          `${s.object.uuid}.quaternion`,
          s.quaternions.times,
          s.quaternions.values,
        ),
        new THREE.VectorKeyframeTrack(
          `${s.object.uuid}.scale`,
          s.scales.times,
          s.scales.values,
        ),
      );
      if (s.morphs) tracks.push(new THREE.NumberKeyframeTrack(
        `${s.object.uuid}.morphTargetInfluences`, s.morphs.times, s.morphs.values,
      ));
    });
    runtime.frame(0);
    orientLights();
    aimedLights.forEach(light => {
      const target = new THREE.Object3D(); target.position.set(0, 0, -1);
      light.add(target); light.target = target;
    });
    prepareExportIdentities(runtime.scene, signal);
    const output = await new GLTFExporter().parseAsync(runtime.scene, {
      binary: true,
      onlyVisible: true,
      animations: [new THREE.AnimationClip("AID-Previz", document.timeline.frameCount / fps, tracks)],
    });
    signal.throwIfAborted();
    return new Blob([output as ArrayBuffer], { type: "model/gltf-binary" });
  } finally {
    runtime.dispose();
  }
}
