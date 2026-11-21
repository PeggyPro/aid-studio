import * as THREE from "three";
import type { PrevizNode } from "@/types/previz";

type Base = { object: THREE.Object3D; visible: boolean; position: THREE.Vector3; rotation: THREE.Quaternion; scale: THREE.Vector3 };
type Patch = NonNullable<PrevizNode["assetOverrides"]>[string];
type State = { objects: Map<string, Base>; knownIds: Set<string>; owned: THREE.Material[]; colors: Map<THREE.Mesh, { material: THREE.Material; color?: THREE.Color }[]>; applied: Map<string, Patch>; filtered: Map<THREE.Object3D,boolean> };
const states = new WeakMap<THREE.Object3D, State>();
const quaternion = new THREE.Quaternion(), euler = new THREE.Euler(), vector = new THREE.Vector3();

function capture(root: THREE.Object3D): State {
  const existing = states.get(root); if (existing) return existing;
  const state: State = { objects: new Map(), knownIds: new Set(), owned: [], colors: new Map(), applied: new Map(), filtered: new Map() };
  const ambiguous = new Set<string>();
  root.traverse(object => {
    if (object === root) return;
    const id = object.userData?.hf_id;
    if (typeof id !== "string" || !id.trim()) return;
    if (ambiguous.has(id)) return;
    if (state.objects.has(id)) { state.objects.delete(id); ambiguous.add(id); return; }
    state.objects.set(id, { object, visible: object.visible, position: object.position.clone(), rotation: object.quaternion.clone(), scale: object.scale.clone() });
  });
  state.knownIds=new Set(state.objects.keys());states.set(root, state); return state;
}

export function initializeGlbObjectOverrides(root: THREE.Object3D,knownIds?:Set<string>) { const state=capture(root);if(knownIds)state.knownIds=knownIds; }
export function glbObject(root: THREE.Object3D, id: string) { return capture(root).objects.get(id)?.object; }
export function pickGlbObjectId(root:THREE.Object3D,hit:THREE.Object3D,part=false) {
  const candidates:THREE.Object3D[]=[];
  for(let object:THREE.Object3D|null=hit;object&&object!==root;object=object.parent)if(typeof object.userData.hf_id==="string"&&glbObject(root,object.userData.hf_id))candidates.push(object);
  if(part)return candidates[0]?.userData.hf_id as string|undefined;
  const declared=candidates.find(object=>object.userData.aid_role==="object");
  if(declared)return declared.userData.hf_id as string;
  const child=root.children.length===1?root.children[0]:undefined;
  const container=child&&(child.userData.aid_role==="scene"||child.children.some(object=>object instanceof THREE.Camera||object instanceof THREE.Light))?child:root;
  return candidates.find(object=>object.parent===container)?.userData.hf_id as string|undefined;
}
export function captureGlbObjectTransformBase(root: THREE.Object3D, id: string) {
  const state = capture(root), base = state.objects.get(id);
  if (base && !state.applied.has(id)) { base.position.copy(base.object.position); base.rotation.copy(base.object.quaternion); base.scale.copy(base.object.scale); }
}
export function resetGlbObjectSubset(root: THREE.Object3D) {
  const state=states.get(root);if(!state)return;
  for(const [object,visible] of state.filtered)object.visible=visible;
  state.filtered.clear();
}
export function applyGlbObjectSubset(root: THREE.Object3D, ids?: string[]) {
  if(!ids)return;
  const state=capture(root), allowed=new Set<THREE.Object3D>([root]);
  for(const id of ids){
    const object=state.objects.get(id)?.object;if(!object)throw new Error("增量模型缺少约定的对象，未采用生成结果");
    object.traverse(child=>allowed.add(child));
    for(let parent=object.parent;parent;parent=parent.parent)allowed.add(parent);
  }
  for(const object of [...allowed]) if(object instanceof THREE.SkinnedMesh) for(const bone of object.skeleton.bones) {
    for(let current: THREE.Object3D | null=bone;current;current=current.parent)allowed.add(current);
  }
  // Keep graph nodes for skin bindings; only render selected subtrees. No destructive mesh split.
  root.traverse(object=>{if(!allowed.has(object)){state.filtered.set(object,object.visible);object.visible=false;}});
}
export function glbObjectTransformDelta(root: THREE.Object3D, id: string, transform: import("@/types/previz").PrevizTransform): Patch {
  const base = capture(root).objects.get(id);
  if (!base) throw new Error("所选内部对象已不存在");
  const rotation = base.rotation.clone().invert().multiply(new THREE.Quaternion().setFromEuler(new THREE.Euler(...transform.rotation)));
  const angles = new THREE.Euler().setFromQuaternion(rotation);
  return { positionOffset: new THREE.Vector3(...transform.position).sub(base.position).toArray(),
    rotationOffset: [angles.x, angles.y, angles.z], scaleMultiplier: new THREE.Vector3(...transform.scale).divide(base.scale).toArray() };
}
export function validateGlbObjectOverrides(root: THREE.Object3D, overrides: PrevizNode["assetOverrides"]) {
  const state = capture(root);
  for (const id of Object.keys(overrides || {})) if (!state.knownIds.has(id))
    throw new Error("模型版本已变化，内部对象编辑无法匹配，请核对后再采用");
}

/** Undo only the prior editor delta, preserving the mixer's last evaluated pose and caches. */
export function resetGlbObjectOverrides(root: THREE.Object3D, overrides: PrevizNode["assetOverrides"]) {
  if (!overrides && !states.has(root)) return;
  const state = capture(root);
  for (const [id, previous] of state.applied) {
    const base = state.objects.get(id); if (!base) continue;
    if (previous.positionOffset) base.object.position.sub(vector.fromArray(previous.positionOffset));
    if (previous.rotationOffset) base.object.quaternion.multiply(quaternion.setFromEuler(euler.set(...previous.rotationOffset)).invert());
    if (previous.scaleMultiplier) base.object.scale.divide(vector.fromArray(previous.scaleMultiplier));
    base.object.visible = base.visible;
  }
  for (const entries of state.colors.values()) for (const entry of entries) {
    const material = entry.material as THREE.Material & { color?: THREE.Color };
    if (entry.color && material.color) material.color.copy(entry.color);
  }
  state.applied.clear();
}

export function applyGlbObjectOverrides(root: THREE.Object3D, overrides: PrevizNode["assetOverrides"]) {
  if (!overrides && !states.has(root)) return;
  const state = capture(root);
  validateGlbObjectOverrides(root, overrides);
  const entries = Object.entries(overrides || {});
  const depth = (id: string) => { let n = state.objects.get(id)?.object, d = 0; while (n?.parent) { d++; n = n.parent; } return d; };
  entries.sort(([a],[b]) => depth(a)-depth(b));
  for (const [id, patch] of entries) {
    const base = state.objects.get(id);
    if (!base) continue; // An inactive source subtree retains its saved edits without mounting its graph.
    const object = base.object;
    base.position.copy(object.position); base.rotation.copy(object.quaternion); base.scale.copy(object.scale);
    if (patch.positionOffset) object.position.add(vector.fromArray(patch.positionOffset));
    if (patch.rotationOffset) object.quaternion.multiply(quaternion.setFromEuler(euler.set(...patch.rotationOffset)));
    if (patch.scaleMultiplier) object.scale.multiply(vector.fromArray(patch.scaleMultiplier));
    if (patch.visible !== undefined) object.visible = patch.visible;
    if (patch.color) object.traverse(child => {
      if (!(child instanceof THREE.Mesh)) return;
      if (!state.colors.has(child)) {
        const original = Array.isArray(child.material) ? child.material : [child.material];
        const owned = original.map(material => material.clone());
        state.owned.push(...owned);
        child.material = Array.isArray(child.material) ? owned : owned[0];
        state.colors.set(child, owned.map(material => ({ material, color: (material as THREE.Material & { color?: THREE.Color }).color?.clone() })));
      }
      for (const entry of state.colors.get(child)!) (entry.material as THREE.Material & { color?: THREE.Color }).color?.set(patch.color!);
    });
    state.applied.set(id, patch);
  }
}

export function releaseGlbObjectOverrides(root: THREE.Object3D) {
  const state = states.get(root);
  if (state) { state.owned.forEach(material => material.dispose()); states.delete(root); }
}
