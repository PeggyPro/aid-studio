import * as THREE from 'three';

/** 与编辑端的固定UE三节指骨契约一致，Finger0为拇指，末端Nub不参与旋转。 */
export const FROZEN_HAND_BONES = {
  leftThumb: ['Bip001_L_Finger0_011', 'Bones_L_Finger01_012', 'Bones_L_Finger02_013'],
  rightThumb: ['Bip001_R_Finger0_035', 'Bones_R_Finger01_036', 'Bones_R_Finger02_037'],
  leftFingers: [
    'Bones_L_Finger1_015',
    'Bones_L_Finger11_016',
    'Bones_L_Finger12_017',
    'Bones_L_Finger2_019',
    'Bones_L_Finger21_020',
    'Bones_L_Finger22_021',
    'Bones_L_Finger3_023',
    'Bones_L_Finger31_024',
    'Bones_L_Finger32_025',
    'Bones_L_Finger4_027',
    'Bones_L_Finger41_028',
    'Bones_L_Finger42_029'
  ],
  rightFingers: [
    'Bones_R_Finger1_039',
    'Bones_R_Finger11_040',
    'Bones_R_Finger12_041',
    'Bones_R_Finger2_043',
    'Bones_R_Finger21_044',
    'Bones_R_Finger22_045',
    'Bones_R_Finger3_047',
    'Bones_R_Finger31_048',
    'Bones_R_Finger32_049',
    'Bones_R_Finger4_051',
    'Bones_R_Finger41_052',
    'Bones_R_Finger42_053'
  ]
} as const;

export function applyFrozenHandPose(root: THREE.Object3D, pose: Record<string, number>) {
  for (const [field, names] of Object.entries(FROZEN_HAND_BONES)) {
    const value = pose[field] ?? 0;
    if (!Number.isFinite(value)) throw new Error(`固定手部姿态字段无效：${field}`);
    if (!value) continue;
    const bones = names.map((name) => {
      const bone = root.getObjectByName(name);
      if (!(bone instanceof THREE.Bone)) throw new Error(`固定模型缺少手部姿态骨骼：${name}`);
      return bone;
    });
    const rotation = new THREE.Quaternion().setFromEuler(new THREE.Euler(0, 0, THREE.MathUtils.degToRad(value)));
    bones.forEach((bone) => bone.quaternion.multiply(rotation));
  }
}
