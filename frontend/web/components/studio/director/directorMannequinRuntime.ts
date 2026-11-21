import { PREVIZ_HUMAN_HEIGHT_METERS } from '@/utils/previz/sceneUnits'
import * as THREE from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { clone as cloneSkeleton } from 'three/examples/jsm/utils/SkeletonUtils.js'
import type { DirectorObject } from '@/types/studio'
import { withAppBasePath } from '@/utils/appBasePath'
import { disposeDirectorObjectTree } from './directorRuntimeResources'
import { DIRECTOR_MANNEQUIN_ASSET_PATH } from '@/config/directorMannequin'
import { applyDirectorHandPose } from './directorHandPose'

/**
 * UE Mannequin runtime adapted from storyai-3d-director-desk (MIT).
 * The bundled model keeps its own attribution in public/models/director.
 */
export { DIRECTOR_MANNEQUIN_ASSET_PATH } from '@/config/directorMannequin'

type DirectorMannequinSourceLoader = (url: string) => Promise<THREE.Object3D>
type RestPose = Record<string, {
  position: [number, number, number]
  quaternion: [number, number, number, number]
  scale: [number, number, number]
}>

export const DIRECTOR_MANNEQUIN_BONE_NAMES = {
  pelvis: 'Bip001_Pelvis_03',
  spine: 'Bip001_Spine1_05',
  neck: 'Bip001_Neck_06',
  head: 'Bip001_Head_055',
  leftShoulder: 'Bip001_L_UpperArm_08',
  rightShoulder: 'Bip001_R_UpperArm_032',
  leftElbow: 'Bip001_L_Forearm_09',
  rightElbow: 'Bip001_R_Forearm_033',
  leftWrist: 'Bip001_L_Hand_010',
  rightWrist: 'Bip001_R_Hand_034',
  leftHip: 'Bip001_L_Thigh_057',
  rightHip: 'Bip001_R_Thigh_061',
  leftKnee: 'Bip001_L_Calf_058',
  rightKnee: 'Bip001_R_Calf_062',
  leftAnkle: 'Bip001_L_Foot_059',
  rightAnkle: 'Bip001_R_Foot_063'
} as const

const DIRECTOR_MANNEQUIN_NEUTRAL_ROTATIONS: Partial<Record<(typeof DIRECTOR_MANNEQUIN_BONE_NAMES)[keyof typeof DIRECTOR_MANNEQUIN_BONE_NAMES], [number, number, number]>> = {
  [DIRECTOR_MANNEQUIN_BONE_NAMES.leftShoulder]: [0, degrees(25), 0],
  [DIRECTOR_MANNEQUIN_BONE_NAMES.rightShoulder]: [0, degrees(-25), 0],
  [DIRECTOR_MANNEQUIN_BONE_NAMES.leftElbow]: [0, 0, degrees(25)],
  [DIRECTOR_MANNEQUIN_BONE_NAMES.rightElbow]: [0, 0, degrees(25)]
}

export class DirectorMannequinRuntimeCache {
  private source: Promise<THREE.Object3D> | null = null
  private disposed = false

  constructor(private readonly sourceLoader: DirectorMannequinSourceLoader = loadDirectorMannequinSource) {}

  async load(object: DirectorObject, selected: boolean): Promise<THREE.Group> {
    if (this.disposed) throw new Error('人体模型加载器已释放')
    if (!this.source) {
      this.source = this.sourceLoader(withAppBasePath(DIRECTOR_MANNEQUIN_ASSET_PATH))
      this.source.catch(() => {
        this.source = null
      })
    }
    const source = await this.source
    if (this.disposed) throw new Error('人体模型加载器已释放')
    return createDirectorMannequinInstance(source, object, selected)
  }

  dispose(): void {
    if (this.disposed) return
    this.disposed = true
    void this.source?.then(disposeDirectorObjectTree).catch(() => undefined)
    this.source = null
  }
}

export function createDirectorMannequinInstance(
  source: THREE.Object3D,
  object: Pick<DirectorObject, 'color' | 'pose'>,
  selected: boolean
): THREE.Group {
  const model = cloneSkeleton(source)
  isolateDirectorMannequinMaterials(model, object.color, selected)
  const restPose = captureDirectorMannequinRestPose(model)

  const root = new THREE.Group()
  root.name = 'director-ue-mannequin'
  root.add(model)
  // Normalize the source once in its rest pose. Pose changes must not rescale a
  // crouching or leaning actor back to its standing height.
  normalizeDirectorMannequin(model)
  model.userData.directorRestPose = restPose
  applyDirectorMannequinPose(model, {}, restPose)
  alignDirectorMannequinToGround(model)
  model.userData.directorRestY = model.position.y
  applyDirectorMannequinPose(model, object.pose, restPose)
  return root
}

export function captureDirectorMannequinRestPose(source: THREE.Object3D): RestPose {
  const restPose: RestPose = {}
  source.traverse((child) => {
    if (!(child instanceof THREE.Bone)) return
    restPose[child.name] = {
      position: child.position.toArray() as [number, number, number],
      quaternion: child.quaternion.toArray() as [number, number, number, number],
      scale: child.scale.toArray() as [number, number, number]
    }
  })
  return restPose
}

/** Resolve the skinned mannequin model under an actor object root. */
export function findDirectorMannequinModelRoot(objectRoot: THREE.Object3D): THREE.Object3D | undefined {
  if (objectRoot.name === 'director-ue-mannequin') return objectRoot.children[0] ?? objectRoot
  let found: THREE.Object3D | undefined
  objectRoot.traverse((child) => {
    if (child.name === 'director-ue-mannequin') found = child.children[0] ?? child
  })
  return found
}

export function applyDirectorMannequinPose(
  source: THREE.Object3D,
  pose: Record<string, number>,
  restPose = (source.userData.directorRestPose ??= captureDirectorMannequinRestPose(source))
): void {
  if (!Number.isFinite(source.userData.directorRestY)) source.userData.directorRestY = source.position.y
  source.position.y = source.userData.directorRestY + (pose.rootHeight ?? 0)
  source.traverse((child) => {
    if (!(child instanceof THREE.Bone)) return
    const rest = restPose[child.name]
    if (!rest) return
    child.position.fromArray(rest.position)
    child.quaternion.fromArray(rest.quaternion)
    child.scale.fromArray(rest.scale)
  })

  Object.entries(DIRECTOR_MANNEQUIN_NEUTRAL_ROTATIONS).forEach(([name, rotation]) => {
    if (rotation) rotateBone(source, name, ...rotation)
  })

  // The retopology GLB uses the same non-standard local axes as the reference
  // desk: pitch is Z for spine/limbs, yaw is X and roll is Y.
  rotateBone(source, DIRECTOR_MANNEQUIN_BONE_NAMES.pelvis, 0, 0, -degrees(pose.spine))
  rotateBone(source, DIRECTOR_MANNEQUIN_BONE_NAMES.spine, degrees(pose.chest), 0, 0)
  rotateBone(source, DIRECTOR_MANNEQUIN_BONE_NAMES.neck, 0, degrees(pose.neck), 0)
  rotateBone(source, DIRECTOR_MANNEQUIN_BONE_NAMES.head, degrees(pose.headY), 0, degrees(pose.headX))
  rotateBone(source, DIRECTOR_MANNEQUIN_BONE_NAMES.leftShoulder, 0, 0, -degrees(pose.leftShoulder))
  rotateBone(source, DIRECTOR_MANNEQUIN_BONE_NAMES.rightShoulder, 0, 0, -degrees(pose.rightShoulder))
  rotateBone(source, DIRECTOR_MANNEQUIN_BONE_NAMES.leftElbow, 0, 0, -degrees(pose.leftElbow))
  rotateBone(source, DIRECTOR_MANNEQUIN_BONE_NAMES.rightElbow, 0, 0, -degrees(pose.rightElbow))
  rotateBone(source, DIRECTOR_MANNEQUIN_BONE_NAMES.leftWrist, 0, 0, degrees(pose.leftWrist))
  rotateBone(source, DIRECTOR_MANNEQUIN_BONE_NAMES.rightWrist, 0, 0, degrees(pose.rightWrist))
  rotateBone(source, DIRECTOR_MANNEQUIN_BONE_NAMES.leftHip, 0, 0, degrees(pose.leftHip))
  rotateBone(source, DIRECTOR_MANNEQUIN_BONE_NAMES.rightHip, 0, 0, degrees(pose.rightHip))
  rotateBone(source, DIRECTOR_MANNEQUIN_BONE_NAMES.leftKnee, 0, 0, -degrees(pose.leftKnee))
  rotateBone(source, DIRECTOR_MANNEQUIN_BONE_NAMES.rightKnee, 0, 0, -degrees(pose.rightKnee))
  rotateBone(source, DIRECTOR_MANNEQUIN_BONE_NAMES.leftAnkle, 0, 0, degrees(pose.leftAnkle))
  rotateBone(source, DIRECTOR_MANNEQUIN_BONE_NAMES.rightAnkle, 0, 0, degrees(pose.rightAnkle))
  applyDirectorHandPose(source, pose)
  source.updateMatrixWorld(true)
}

export function isolateDirectorMannequinMaterials(
  source: THREE.Object3D,
  color: string,
  selected: boolean
): void {
  source.traverse((child) => {
    child.frustumCulled = false
    if (!(child instanceof THREE.Mesh)) return
    child.geometry = child.geometry.clone()
    const materials = Array.isArray(child.material) ? child.material : [child.material]
    const isolated = materials.map((material) => {
      const clone = material.clone()
      const fields = clone as unknown as Record<string, unknown>
      Object.entries(fields).forEach(([key, value]) => {
        if (value instanceof THREE.Texture) fields[key] = value.clone()
      })
      if (clone instanceof THREE.MeshStandardMaterial && clone.name !== 'SK_Mannequin_M_UE4Man_ChestLogo') {
        clone.color.set(color)
        clone.roughness = 0.68
        clone.metalness = 0.04
        clone.emissive.set(selected ? '#162b38' : '#000000')
        clone.emissiveIntensity = selected ? 0.28 : 0
      }
      clone.needsUpdate = true
      return clone
    })
    child.material = Array.isArray(child.material) ? isolated : isolated[0]
    child.castShadow = true
    child.receiveShadow = true
  })
}

function normalizeDirectorMannequin(source: THREE.Object3D): void {
  source.updateMatrixWorld(true)
  const bounds = new THREE.Box3().setFromObject(source)
  if (bounds.isEmpty()) return
  const size = bounds.getSize(new THREE.Vector3())
  if (!Number.isFinite(size.y) || size.y <= 0) return
  const scale = PREVIZ_HUMAN_HEIGHT_METERS / size.y
  source.scale.multiplyScalar(scale)
  source.updateMatrixWorld(true)
  const normalizedBounds = new THREE.Box3().setFromObject(source)
  const center = normalizedBounds.getCenter(new THREE.Vector3())
  source.position.set(-center.x, -normalizedBounds.min.y, -center.z)
}

export function alignDirectorMannequinToGround(source: THREE.Object3D): void {
  const rootX = source.position.x
  const rootZ = source.position.z
  source.updateMatrixWorld(true)
  const bounds = new THREE.Box3().setFromObject(source, true)
  if (bounds.isEmpty() || !Number.isFinite(bounds.min.y)) return
  source.position.set(rootX, source.position.y - bounds.min.y, rootZ)
  source.updateMatrixWorld(true)
}

function rotateBone(source: THREE.Object3D, name: string, x: number, y: number, z: number): void {
  const bone = source.getObjectByName(name)
  if (!bone) return
  bone.quaternion.multiply(new THREE.Quaternion().setFromEuler(new THREE.Euler(x, y, z)))
}

async function loadDirectorMannequinSource(url: string): Promise<THREE.Object3D> {
  try {
    const gltf = await new GLTFLoader().loadAsync(url)
    return gltf.scene
  } catch {
    throw new Error('内置 UE 人体模型无法加载')
  }
}

function degrees(value = 0): number {
  return THREE.MathUtils.degToRad(value)
}
