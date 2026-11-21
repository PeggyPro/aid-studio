import * as THREE from 'three';

/** Match the authoring viewport's primitive geometry, including its local torus orientation. */
export function frozenPrimitive(shape: string): THREE.BufferGeometry | undefined {
  switch (shape) {
    case 'box': return new THREE.BoxGeometry(1, 1, 1);
    case 'sphere': return new THREE.SphereGeometry(0.55, 32, 16);
    case 'cylinder': return new THREE.CylinderGeometry(0.45, 0.45, 1.2, 32);
    case 'torus': return new THREE.TorusGeometry(0.45, 0.14, 16, 48).rotateX(Math.PI / 2);
    case 'cone': return new THREE.ConeGeometry(0.5, 1.1, 32);
    case 'pyramid': return new THREE.ConeGeometry(0.55, 1.1, 4);
    case 'capsule': return new THREE.CapsuleGeometry(0.42, 0.75, 8, 16);
    case 'plane': return new THREE.PlaneGeometry(1, 1);
    default: return undefined;
  }
}
