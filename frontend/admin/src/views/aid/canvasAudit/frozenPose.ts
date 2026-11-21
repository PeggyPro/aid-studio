import * as THREE from 'three';
import { applyFrozenHandPose, FROZEN_HAND_BONES } from './frozenHandPose';

const restPoses = new WeakMap<
  THREE.Object3D,
  { y: number; bones: Map<THREE.Bone, { position: THREE.Vector3; quaternion: THREE.Quaternion; scale: THREE.Vector3 }> }
>();

// The fixed UE retopology skeleton uses Z for limb pitch, X for yaw and Y for roll.
const bones: Record<string, [string, number, number]> = {
  spine: ['Bip001_Pelvis_03', 2, -1],
  chest: ['Bip001_Spine1_05', 0, 1],
  neck: ['Bip001_Neck_06', 1, 1],
  headY: ['Bip001_Head_055', 0, 1],
  headX: ['Bip001_Head_055', 2, 1],
  leftShoulder: ['Bip001_L_UpperArm_08', 2, -1],
  rightShoulder: ['Bip001_R_UpperArm_032', 2, -1],
  leftElbow: ['Bip001_L_Forearm_09', 2, -1],
  rightElbow: ['Bip001_R_Forearm_033', 2, -1],
  leftWrist: ['Bip001_L_Hand_010', 2, 1],
  rightWrist: ['Bip001_R_Hand_034', 2, 1],
  leftHip: ['Bip001_L_Thigh_057', 2, 1],
  rightHip: ['Bip001_R_Thigh_061', 2, 1],
  leftKnee: ['Bip001_L_Calf_058', 2, -1],
  rightKnee: ['Bip001_R_Calf_062', 2, -1],
  leftAnkle: ['Bip001_L_Foot_059', 2, 1],
  rightAnkle: ['Bip001_R_Foot_063', 2, 1]
};
export function applyFrozenPose(root: THREE.Object3D, pose: Record<string, number> = {}) {
  let rest = restPoses.get(root);
  if (!rest) {
    rest = { y: root.position.y, bones: new Map() };
    root.traverse((object) => {
      if (object instanceof THREE.Bone)
        rest!.bones.set(object, {
          position: object.position.clone(),
          quaternion: object.quaternion.clone(),
          scale: object.scale.clone()
        });
    });
    restPoses.set(root, rest);
  }
  for (const [field, value] of Object.entries(pose))
    if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error(`固定姿态字段无效：${field}`);
  root.position.y = rest.y;
  rest.bones.forEach((value, bone) => {
    bone.position.copy(value.position);
    bone.quaternion.copy(value.quaternion);
    bone.scale.copy(value.scale);
  });
  const rotate = (name: string, axis: number, degrees: number) => {
    const bone = root.getObjectByName(name);
    if (!(bone instanceof THREE.Bone)) throw new Error(`固定模型缺少姿态所需骨骼：${name}`);
    const angles: [number, number, number] = [0, 0, 0];
    angles[axis] = THREE.MathUtils.degToRad(degrees);
    bone.quaternion.multiply(new THREE.Quaternion().setFromEuler(new THREE.Euler(...angles)));
  };
  if (root.getObjectByName('Bip001_L_UpperArm_08') instanceof THREE.Bone) {
    rotate('Bip001_L_UpperArm_08', 1, 25);
    rotate('Bip001_R_UpperArm_032', 1, -25);
    rotate('Bip001_L_Forearm_09', 2, 25);
    rotate('Bip001_R_Forearm_033', 2, 25);
  }
  if (pose.headX || pose.headY) {
    const head = root.getObjectByName('Bip001_Head_055');
    if (!(head instanceof THREE.Bone)) throw new Error('固定模型缺少头部姿态骨骼');
    head.quaternion.multiply(
      new THREE.Quaternion().setFromEuler(
        new THREE.Euler(THREE.MathUtils.degToRad(pose.headY ?? 0), 0, THREE.MathUtils.degToRad(pose.headX ?? 0))
      )
    );
  }
  for (const [field, value] of Object.entries(pose)) {
    if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error(`固定姿态字段无效：${field}`);
    if (!value) continue;
    if (field === 'headX' || field === 'headY' || field in FROZEN_HAND_BONES) continue;
    if (field === 'rootHeight') {
      root.position.y += value;
      continue;
    }
    const mapping = bones[field];
    if (mapping) {
      rotate(mapping[0], mapping[1], value * mapping[2]);
      continue;
    }
    throw new Error(`姿态字段尚不能忠实还原：${field}`);
  }
  applyFrozenHandPose(root, pose);
  root.updateMatrixWorld(true);
}
