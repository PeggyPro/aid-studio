import React, { useEffect, useRef, useState } from 'react';
import { Alert, Select, Space, Spin } from 'antd';
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { FBXLoader } from 'three/addons/loaders/FBXLoader.js';
import { OBJLoader } from 'three/addons/loaders/OBJLoader.js';
import { MTLLoader } from 'three/addons/loaders/MTLLoader.js';
import { clone } from 'three/addons/utils/SkeletonUtils.js';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import type { CanvasResource } from '@/api/aid/canvasAudit';
import type { ResourceState } from './auditResources';
import { applyFrozenPose } from './frozenPose';
import { evaluateFrozenTimeline } from './frozenTimeline';
import { frozenPrimitive } from './frozenGeometry';

function dispose(root: THREE.Object3D) {
  root.traverse((o: any) => {
    o.geometry?.dispose();
    for (const m of o.material ? (Array.isArray(o.material) ? o.material : [o.material]) : []) {
      for (const value of Object.values(m))
        if (value instanceof THREE.Texture) {
          const image = value.source?.data;
          if (image && typeof image.close === 'function') image.close();
          value.dispose();
        }
      m.dispose();
    }
  });
}
const vector = (v: any, fallback: [number, number, number] = [0, 0, 0]): [number, number, number] =>
  Array.isArray(v) && v.length === 3 && v.every(Number.isFinite) ? (v as [number, number, number]) : fallback;
function transform(root: THREE.Object3D, t: any) {
  root.position.fromArray(vector(t?.position));
  root.rotation.set(...vector(t?.rotation));
  root.scale.fromArray(vector(t?.scale, [1, 1, 1]));
}
function interpretImportedAsset(source: THREE.Object3D, preserveScale: boolean) {
  const root = new THREE.Group();
  root.add(source);
  const bounds = new THREE.Box3().setFromObject(source),
    size = bounds.getSize(new THREE.Vector3());
  const dimension = Math.max(size.x, size.y, size.z);
  if (!bounds.isEmpty() && Number.isFinite(dimension) && dimension > 0) {
    if (!preserveScale) source.scale.multiplyScalar(2 / dimension);
    const normalized = new THREE.Box3().setFromObject(source),
      center = normalized.getCenter(new THREE.Vector3());
    source.position.add(new THREE.Vector3(-center.x, -normalized.min.y, -center.z));
  }
  // This is the established asset interpretation. The object's world transform is applied outside it.
  return root;
}
/** All imported bytes and dependencies come from the fixed authenticated resource set. */
async function loadFrozenModel(resource: CanvasResource, files: ResourceState): Promise<THREE.Object3D> {
  const bundle = resource.bundle;
  if (!bundle || !bundle.files?.length) throw new Error('模型缺少完整冻结资源闭包');
  const byPath = new Map(bundle.files.map((f) => [f.path, files.urls[String(f.resourceId)]]));
  if ([...byPath.values()].some((v) => !v)) throw new Error('模型依赖文件尚未完整加载');
  const rootFile = bundle.files.find((f) => f.path === bundle.rootPath);
  const bytes = rootFile && files.blobs[String(rootFile.resourceId)];
  if (!bytes) throw new Error('模型入口文件缺失');
  const prefix = bundle.rootPath.includes('/') ? bundle.rootPath.slice(0, bundle.rootPath.lastIndexOf('/') + 1) : '';
  const known = new Set(Object.values(files.urls));
  const references = new Map<string, Set<string>>();
  const addReference = (uri: string, path: string) => {
    const existing = references.get(uri) || new Set<string>();
    existing.add(path);
    references.set(uri, existing);
  };
  for (const r of bundle.references || []) {
    const owner = r.ownerPath || bundle.rootPath;
    const dir = owner.includes('/') ? owner.slice(0, owner.lastIndexOf('/') + 1) : '';
    addReference(r.uri, r.path);
    addReference(dir + r.uri, r.path);
    if(bundle.format.toLowerCase()==='fbx') {
      // FBXLoader.parseImages removes the Windows directory before requesting a texture.
      // Only declared references contribute aliases; ambiguous aliases still fail closed.
      const loaderUri=r.uri.split('\\').pop()!;
      addReference(loaderUri,r.path); addReference(dir+loaderUri,r.path);
    }
  }
  const referenced = (uri: string) => {
    const paths = references.get(uri);
    if (paths && paths.size > 1) throw new Error('模型依赖路径有歧义，无法安全还原');
    return paths ? [...paths][0] : undefined;
  };
  const manager = new THREE.LoadingManager();
  let failed = false,
    loadingDependencies = false;
  const waiters: (() => void)[] = [];
  manager.onStart = () => {
    loadingDependencies = true;
  };
  manager.onLoad = () => {
    loadingDependencies = false;
    waiters.splice(0).forEach((resolve) => resolve());
  };
  manager.onError = () => {
    failed = true;
  };
  manager.setURLModifier((uri) => {
    try {
      // GLB embedded images produce local blob URLs without outgoing network requests.
      if (known.has(uri) || uri.startsWith('blob:') || uri.startsWith('data:')) return uri;
      const relative = uri.startsWith('frozen:///') ? uri.slice('frozen:///'.length) : uri;
      const path = referenced(relative) || referenced(uri) || relative;
      const url = byPath.get(path) || byPath.get(prefix + path);
      if (!url) throw new Error('模型引用了未登记的外部依赖');
      return url;
    } catch (error) {
      failed = true;
      throw error;
    }
  });
  const format = bundle.format.toLowerCase();
  let model: THREE.Object3D;
  if (format === 'glb' || format === 'gltf')
    model = (await new GLTFLoader(manager).parseAsync(await bytes.arrayBuffer(), `frozen:///${prefix}`)).scene;
  else if (format === 'fbx') model = new FBXLoader(manager).parse(await bytes.arrayBuffer(), `frozen:///${prefix}`);
  else if (format === 'obj') {
    const loader = new OBJLoader(manager);
    let mergedMaterials: any;
    const text = await bytes.text();
    const libraries = [...text.matchAll(/^mtllib\s+(.+)$/gm)].map((m) => m[1].trim());
    for (const library of libraries) {
      const mapped = referenced(prefix + library) || referenced(library) || prefix + library;
      const file = bundle.files.find((f) => f.path === mapped);
      const blob = file && files.blobs[String(file.resourceId)];
      if (!blob) throw new Error('OBJ材质文件未登记');
      const mtlPrefix = mapped.includes('/') ? mapped.slice(0, mapped.lastIndexOf('/') + 1) : '';
      const materials = new MTLLoader(manager).parse(await blob.text(), `frozen:///${mtlPrefix}`);
      materials.preload();
      if (!mergedMaterials) mergedMaterials = materials;
      else {
        Object.assign(mergedMaterials.materials, materials.materials);
        Object.assign(mergedMaterials.materialsInfo, materials.materialsInfo);
      }
    }
    if (mergedMaterials) loader.setMaterials(mergedMaterials);
    model = loader.parse(text);
  } else throw new Error('当前模型格式无法只读预览');
  if (loadingDependencies)
    await new Promise<void>((resolve, reject) => {
      const timer = window.setTimeout(() => reject(new Error('模型依赖加载超时')), 30000);
      waiters.push(() => {
        clearTimeout(timer);
        resolve();
      });
    });
  if (failed) {
    dispose(model);
    throw new Error('模型依赖解码失败');
  }
  let hasGeometry=false;
  model.traverse((object)=>{if(object instanceof THREE.Mesh && (object.geometry.getAttribute('position')?.count||0)>0) hasGeometry=true;});
  if(!hasGeometry){dispose(model);throw new Error('固定模型没有可检查的网格顶点');}
  // Keep the source transform, hierarchy and skeleton exactly as frozen.
  return model;
}

export default function ThreePreview({
  scene,
  resources,
  files,
  onReady,
  visible = true
}: {
  scene: Record<string, any>;
  resources: CanvasResource[];
  files: ResourceState;
  onReady?: (ready: boolean) => void;
  visible?: boolean;
}) {
  const host = useRef<HTMLDivElement>(null);
  const readyCallback = useRef(onReady);
  readyCallback.current = onReady;
  const [error, setError] = useState(''),
    [loading, setLoading] = useState(true);
  const [cameraId, setCameraId] = useState<string>(scene.activeCameraId || 'main-camera');
  const [reviewMode, setReviewMode] = useState<'original' | 'assist'>('original');
  const [reviewTarget, setReviewTarget] = useState('all');
  useEffect(() => {
    readyCallback.current?.(false);
    setLoading(true);
    setError('');
    if (!files.ready || !host.current) return;
    let active = true;
    let renderer: THREE.WebGLRenderer | undefined;
    let observer: ResizeObserver | undefined;
    let controls: OrbitControls | undefined;
    const world = new THREE.Scene();
    const sourceModels: THREE.Object3D[] = [];
    async function build() {
      const unsupported: string[] = [];
      const reviewRoots = new Map<string, THREE.Object3D>();
      const evaluated = evaluateFrozenTimeline(scene);
      const byId = new Map(resources.map((r) => [String(r.resourceId), r]));
      const modelCache = new Map<string, Promise<THREE.Object3D>>();
      const model = async (id: any) => {
        const key = String(id);
        const resource = byId.get(key);
        if (!resource) throw new Error('场景引用的模型资源不存在');
        if (!modelCache.has(key))
          modelCache.set(
            key,
            loadFrozenModel(resource, files).then((root) => {
              if (!active) {
                dispose(root);
                throw new Error('预览已关闭');
              }
              sourceModels.push(root);
              return root;
            })
          );
        return clone(await modelCache.get(key)!);
      };
      world.background = new THREE.Color(scene.environmentColor || '#20252b');
      for (const item of evaluated.objects || []) {
        let root: THREE.Object3D;
        if (item.kind === 'actor' && !item.modelAssetId)
          throw new Error('人物对象缺少显式固定模型，不能用默认模型替代');
        if (item.modelAssetId) {
          const asset = scene.modelAssets?.find((a: any) => a.id === item.modelAssetId);
          if (!asset?.resourceId) throw new Error('模型素材缺少固定资源编号');
          if (item.kind === 'actor' && asset.kind !== 'actor') throw new Error('人物对象引用的固定模型种类不匹配');
          root = interpretImportedAsset(await model(asset.resourceId), asset.source === 'builtin' || asset.source === 'library');
        } else if (item.scenery?.scenePackageResourceId) {
          const packageRoot = await model(item.scenery.scenePackageResourceId);
          const meshPath = String(item.scenery.meshPath || '');
          if (!meshPath) throw new Error('场景包部件路径缺失');
          const parts = meshPath.split('/').filter(Boolean);
          let part: THREE.Object3D | undefined = meshPath.includes('/')
            ? packageRoot
            : packageRoot.getObjectByName(meshPath);
          if (meshPath.includes('/'))
            for (const [index, name] of parts.entries())
              part =
                index === 0 && name === packageRoot.name
                  ? packageRoot
                  : part?.children.find((child) => child.name === name);
          if (!part && parts.length) throw new Error('场景包指定部件路径不存在');
          root = part || packageRoot;
          root.removeFromParent();
          root.position.set(0, 0, 0);
          root.rotation.set(0, 0, 0);
          root.scale.set(1, 1, 1);
          root.traverse((child: any) => {
            if (child instanceof THREE.Mesh)
              child.material = new THREE.MeshStandardMaterial({
                color: item.color || '#d9dce0',
                transparent: scene.viewportPrefs?.shading === 'xray',
                opacity: scene.viewportPrefs?.shading === 'xray' ? 0.35 : 1,
                depthWrite: scene.viewportPrefs?.shading !== 'xray'
              });
          });
        } else {
          const shape = item.shape || item.kind;
          const geometry = frozenPrimitive(shape);
          if (!geometry) {
            unsupported.push(`${item.name || item.id}：缺少可忠实还原的固定模型（${shape}）`);
            continue;
          }
          root = new THREE.Mesh(
            geometry,
            new THREE.MeshStandardMaterial({ color: item.color || '#999999', side: THREE.DoubleSide })
          );
        }
        root.name = item.name || item.id;
        root.visible = item.visible !== false;
        const wrapper = new THREE.Group();
        wrapper.add(root);
        transform(wrapper, item.transform);
        if (item.pose) applyFrozenPose(root, item.pose);
        world.add(wrapper);
        reviewRoots.set(String(item.id), wrapper);
      }
      for (const light of scene.lights || []) {
        const intensity = Number(light.intensity ?? 1),
          color = light.color || '#ffffff';
        const l =
          light.kind === 'ambient'
            ? new THREE.AmbientLight(color, intensity)
            : light.kind === 'directional'
              ? new THREE.DirectionalLight(color, intensity)
              : light.kind === 'point'
                ? new THREE.PointLight(color, intensity)
                : new THREE.SpotLight(color, intensity);
        l.position.fromArray(vector(light.position));
        l.visible = light.enabled !== false;
        world.add(l);
      }
      const cameraData =
        cameraId === 'main-camera'
          ? evaluated.camera
          : evaluated.cameraBookmarks?.find((b: any) => b.id === cameraId)?.camera;
      if (!cameraData) throw new Error('固定相机资料缺失');
      const cam = cameraData;
      const ratio = String(scene.aspectRatio || '16:9')
        .split(':')
        .map(Number);
      const aspect = ratio.length === 2 ? ratio[0] / ratio[1] : Number(scene.aspectRatio) || 16 / 9;
      const camera = new THREE.PerspectiveCamera(Number(cam.fov || 50), aspect, 0.01, 10000000);
      camera.position.fromArray(vector(cam.position));
      camera.lookAt(new THREE.Vector3(...vector(cam.target)));
      camera.rotateZ(THREE.MathUtils.degToRad(Number(cam.roll || 0)));
      let reviewCenter: THREE.Vector3 | undefined;
      if (reviewMode === 'assist') {
        // The review camera and lights are transient; frozen object transforms remain untouched.
        const bounds = new THREE.Box3();
        const targets = reviewTarget === 'all' ? [...reviewRoots.values()] : [reviewRoots.get(reviewTarget.slice(7))];
        for (const target of targets) if (target?.visible) bounds.expandByObject(target);
        const sphere = bounds.isEmpty() ? new THREE.Sphere(new THREE.Vector3(), 1) : bounds.getBoundingSphere(new THREE.Sphere());
        if (![sphere.center.x, sphere.center.y, sphere.center.z, sphere.radius].every(Number.isFinite))
          throw new Error('场景边界无效，无法建立辅助审查视角');
        reviewCenter = sphere.center;
        const halfFov = Math.min(THREE.MathUtils.degToRad(camera.fov) / 2, Math.atan(Math.tan(THREE.MathUtils.degToRad(camera.fov) / 2) * aspect));
        const distance = Math.max(sphere.radius, 0.01) / Math.sin(halfFov) * 1.2;
        if (!Number.isFinite(distance) || distance <= 0) throw new Error('场景视野无效，无法建立辅助审查视角');
        camera.far = Math.max(camera.far, distance + sphere.radius * 2);
        camera.updateProjectionMatrix();
        camera.position.copy(reviewCenter).add(new THREE.Vector3(1, 0.7, 1).normalize().multiplyScalar(distance));
        camera.rotation.set(0, 0, 0);
        camera.lookAt(reviewCenter);
        world.add(new THREE.HemisphereLight('#ffffff', '#8a8a8a', 2));
        const keyLight = new THREE.DirectionalLight('#ffffff', 3);
        keyLight.position.copy(camera.position);
        keyLight.target.position.copy(reviewCenter);
        world.add(keyLight, keyLight.target);
      }
      if (scene.panoramaResourceId) {
        const url = files.urls[String(scene.panoramaResourceId)];
        if (!url) throw new Error('全景固定资源缺失');
        const texture = await new THREE.TextureLoader().loadAsync(url);
        texture.mapping = THREE.EquirectangularReflectionMapping;
        texture.colorSpace = THREE.SRGBColorSpace;
        world.background = texture;
        world.backgroundRotation.y = Number(scene.panoramaYaw || 0);
      }
      if (!active) {
        dispose(world);
        sourceModels.forEach(dispose);
        return;
      }
      if (!visible) {
        if (unsupported.length) setError(unsupported.join('；'));
        setLoading(false);
        readyCallback.current?.(unsupported.length === 0);
        return;
      }
      renderer = new THREE.WebGLRenderer({ antialias: true });
      renderer.domElement.addEventListener('webglcontextlost', (event) => {
        if (active) {
          event.preventDefault();
          setError('场景渲染连接已丢失，请重新查询固定版本');
          readyCallback.current?.(false);
        }
      });
      renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
      renderer.outputColorSpace = THREE.SRGBColorSpace;
      const container = host.current!;
      container.appendChild(renderer.domElement);
      const render = () => {
        if (!renderer || !active) return;
        const width = container.clientWidth,
          height = container.clientHeight;
        renderer.setSize(width, height, false);
        renderer.setScissorTest(false);
        renderer.setClearColor('#20252b');
        renderer.clear();
        const fitW = Math.min(width, height * aspect),
          fitH = fitW / aspect;
        renderer.setViewport((width - fitW) / 2, (height - fitH) / 2, fitW, fitH);
        renderer.render(world, camera);
      };
      observer = new ResizeObserver(render);
      observer.observe(container);
      if (reviewCenter) {
        controls = new OrbitControls(camera, renderer.domElement);
        controls.target.copy(reviewCenter);
        controls.enableDamping = false;
        controls.addEventListener('change', render);
        controls.update();
      }
      render();
      if (unsupported.length) setError(unsupported.join('；'));
      setLoading(false);
      readyCallback.current?.(unsupported.length === 0);
    }
    build().catch((e) => {
      if (active) {
        setError(e instanceof Error ? e.message : '场景预览失败');
        setLoading(false);
        readyCallback.current?.(false);
      }
    });
    return () => {
      active = false;
      observer?.disconnect();
      controls?.dispose();
      if (renderer) {
        renderer.dispose();
        renderer.forceContextLoss();
        renderer.domElement.remove();
      }
      dispose(world);
      sourceModels.forEach(dispose);
      if (world.background instanceof THREE.Texture) world.background.dispose();
      readyCallback.current?.(false);
    };
  }, [scene, resources, files, cameraId, visible, reviewMode, reviewTarget]);
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Space wrap>
        <span>{reviewMode === 'original' ? '固定机位' : '辅助审查机位'} · 原坐标/比例 · 时间线 {scene.timeline?.currentTime ?? 0} 秒</span>
        <Select
          aria-label="审核查看方式"
          value={reviewMode}
          onChange={setReviewMode}
          options={[{ value: 'original', label: '原始视角' }, { value: 'assist', label: '辅助审查' }]}
        />
        <Select
          aria-label="检查固定相机"
          value={cameraId}
          onChange={setCameraId}
          disabled={reviewMode === 'assist'}
          options={[
            { value: 'main-camera', label: '主相机' },
            ...(scene.cameraBookmarks || []).map((b: any) => ({ value: b.id, label: b.name || b.id }))
          ]}
        />
        {reviewMode === 'assist' && <Select
          aria-label="辅助审查对象"
          value={reviewTarget}
          onChange={setReviewTarget}
          options={[{ value: 'all', label: '完整场景边界' }, ...(scene.objects || []).map((item: any) => ({ value: `object:${item.id}`, label: `${item.name || item.id} · ${item.id}` }))]}
          style={{ minWidth: 180 }}
        />}
      </Space>
      {reviewMode === 'assist' && <Alert type="info" showIcon message="辅助审查不修改作品"
        description="仅在本地临时补光并按场景或对象边界取景，可拖动旋转、缩放和平移审查相机。对象坐标、比例、姿态、时间线及保存的相机和灯光保持不变；切回原始视角恢复作品原貌，无保存操作。" />}
      {error && <Alert type="error" showIcon message="场景尚未完整还原，不能通过审核" description={error} />}
      <div ref={host} className="canvas-audit-three" aria-label="固定完整场景只读预览" />
      {loading && <Spin tip="读取固定模型与场景资源" />}
    </Space>
  );
}
