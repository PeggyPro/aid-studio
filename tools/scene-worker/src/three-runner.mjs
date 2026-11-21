import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import * as THREE from 'three';
import { GLTFExporter } from 'three/addons/exporters/GLTFExporter.js';
import { prepareLightTargets } from './light-targets.mjs';

// GLTFExporter uses the browser FileReader API for binary packing. This shim
// only reads in-memory Blob values; it does not grant filesystem access.
globalThis.FileReader ??= class {
  readAsArrayBuffer(blob) { this.load(blob.arrayBuffer()); }
  readAsDataURL(blob) { this.load(blob.arrayBuffer().then(data => `data:${blob.type || 'application/octet-stream'};base64,${Buffer.from(data).toString('base64')}`)); }
  load(promise) { promise.then(value => { this.result = value; this.onload?.({target:this}); this.onloadend?.({target:this}); }, error => { this.error=error; this.onerror?.({target:this}); }); }
};

const [entry, output] = process.argv.slice(2);
const module = await import(pathToFileURL(path.resolve(entry)).href);
if (typeof module.createScene !== 'function') throw new Error('SCENE_ENTRY_INVALID: 必须导出 createScene(THREE)');
const root = await module.createScene(THREE);
if (!root?.isObject3D) throw new Error('SCENE_ENTRY_INVALID: createScene 必须返回 Object3D');
// Three creates light targets implicitly. Their identity follows the authored
// light rather than requiring the model to repeat platform helper metadata.
const helperOwners = new Map();
root.traverse(object => {
  if (!(object.isDirectionalLight || object.isSpotLight) || typeof object.userData?.hf_id !== 'string'
      || !object.userData.hf_id.trim()) return;
  const target = object.target;
  if (target?.constructor !== THREE.Object3D || target.children.length || target.userData?.hf_id != null) return;
  const owners = helperOwners.get(target) ?? [];
  owners.push(object.userData.hf_id); helperOwners.set(target, owners);
});
for (const [target, owners] of helperOwners) target.userData.hf_id = `aid_light_target:${owners.sort()[0]}`;
const ids=new Set(); const warnings=[];
root.traverse(object => {
  const id=object.userData?.hf_id;
  if (typeof id !== 'string' || !id.trim() || ids.has(id)) throw new Error('INVALID_OBJECT_ID: 对象 hf_id 缺失或重复');
  ids.add(id);
  if (object.isLight && !object.isDirectionalLight && !object.isPointLight && !object.isSpotLight)
    warnings.push({code:'LIGHT_NOT_EXPORTED',objectId:id,type:object.type});
});
for (const clip of root.animations ?? []) {
  if (!clip.isAnimationClip && !(clip instanceof THREE.AnimationClip)) throw new Error('INVALID_ANIMATION: 动画必须为 AnimationClip');
  for (const track of clip.tracks) {
    const binding=THREE.PropertyBinding.parseTrackName(track.name);
    if (!['position','quaternion','scale','morphTargetInfluences'].includes(binding.propertyName))
      throw new Error(`UNSUPPORTED_ANIMATION_CHANNEL: ${track.name}`);
  }
}
root.updateMatrixWorld(true);
warnings.push(...prepareLightTargets(root));
const buffer=await new GLTFExporter().parseAsync(root,{binary:true,onlyVisible:false,animations:root.animations ?? []});
await fs.writeFile(output,Buffer.from(buffer));
await fs.writeFile(`${output}.metadata.json`,JSON.stringify({engine:'threejs',engineVersion:THREE.REVISION,warnings}));
