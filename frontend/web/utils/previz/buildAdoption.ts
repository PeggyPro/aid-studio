import { Euler, Matrix4, Quaternion, Vector3 } from "three";
import type { PrevizAsset, PrevizCommand, PrevizNode, PrevizScene, PrevizTransform, Vec3 } from "@/types/previz";
import { copy, createNode } from "./core";
import { glbObjectsFromDocument, type GlbObjectDocument } from "./glbObjects";
import { assessGlbReplacement } from "./adoptionSafety";
import { cameraChoices } from "./nativeCameras";

export type PrevizBuildOperation = {
  version: 1; kind: "ADD" | "MODIFY" | "REPLACE_SCOPE" | "REBUILD_SCENE";
  editorVersion: string; sceneDigest: string; targetNodeIds: string[]; outputObjectIds: string[];
  baseNodes?: Partial<PrevizNode>[];
};
export class PrevizAdoptionConflict extends Error {}
const same = (a: unknown,b: unknown) => JSON.stringify(a) === JSON.stringify(b);
const close=(a:number,b:number)=>Math.abs(a-b)<=1e-5;
const matrix = (t: PrevizTransform) => new Matrix4().compose(new Vector3(...t.position),new Quaternion().setFromEuler(new Euler(...t.rotation)),new Vector3(...t.scale));
function transform(value: Matrix4): PrevizTransform {
  const p=new Vector3(),q=new Quaternion(),s=new Vector3();value.decompose(p,q,s);
  const check=new Matrix4().compose(p,q,s);
  if(value.elements.some((n,i)=>!Number.isFinite(n)||Math.abs(n-check.elements[i])>1e-5)||s.toArray().some(n=>n<=0||n>100))throw new Error("对象坐标含剪切或无效缩放，不能安全采用");
  const r=new Euler().setFromQuaternion(q);return {position:p.toArray(),rotation:[r.x,r.y,r.z],scale:s.toArray()};
}

export function sourceObjectWorldTransforms(document: GlbObjectDocument, ids: string[]) {
  const nodes=document.nodes || [], catalog=glbObjectsFromDocument(document), byId=new Map(catalog.map(o=>[o.id,o]));
  const parents=new Map<number,number>(); nodes.forEach((node,index)=>(node.children||[]).forEach(child=>parents.set(child,index)));
  const cache=new Map<number,Matrix4>();
  const world=(index:number):Matrix4=>{
    const prior=cache.get(index);if(prior)return prior;
    const node=nodes[index],local=node.matrix ? new Matrix4().fromArray(node.matrix) : new Matrix4().compose(new Vector3().fromArray(node.translation||[0,0,0]),new Quaternion().fromArray(node.rotation||[0,0,0,1]),new Vector3().fromArray(node.scale||[1,1,1]));
    const parent=parents.get(index),result=parent===undefined?local:world(parent).clone().multiply(local);cache.set(index,result);return result;
  };
  const selected=new Set(ids), result=new Map<string,PrevizTransform>();
  for(const id of ids){
    const item=byId.get(id);if(!item)throw new Error("构建结果缺少约定的对象，结果已保留且未采用");
    for(let parent=item.parentId;parent;parent=byId.get(parent)?.parentId||null)if(selected.has(parent))throw new Error("采用范围同时包含组合及其零件，会造成重复，未采用");
    result.set(id,transform(world(item.index)));
  }
  return result;
}

/** Three-way property merge: independent manual edits survive; same-property conflicts stay explicit. */
export function mergeAdoptionTransform(base: PrevizTransform,current: PrevizTransform,generated: PrevizTransform): PrevizTransform {
  const result=copy(generated);
  for(const key of ["position","rotation","scale"] as const){
    if(key!=="rotation"){
      result[key]=generated[key].map((value,axis)=>{
        if(close(current[key][axis],base[key][axis]))return value;
        if(!close(value,base[key][axis])&&!close(value,current[key][axis]))throw new PrevizAdoptionConflict("生成期间同一对象的同一坐标也被手动修改，请核对后选择采用方式");
        return current[key][axis];
      }) as Vec3;
    }else{
      const orientation=(v:Vec3)=>new Quaternion().setFromEuler(new Euler(...v));
      if(orientation(current.rotation).angleTo(orientation(base.rotation))>1e-5){
        if(orientation(generated.rotation).angleTo(orientation(base.rotation))>1e-5&&orientation(current.rotation).angleTo(orientation(generated.rotation))>1e-5)throw new PrevizAdoptionConflict("生成与手动编辑同时改变了对象方向，请核对采用方式");
        result.rotation=copy(current.rotation);
      }
    }
  }
  return result;
}

export function scopedBuildCommands(scene: PrevizScene,asset: PrevizAsset,document: GlbObjectDocument,operation: PrevizBuildOperation,baselineNodes: Partial<PrevizNode>[] = operation.baseNodes || []) {
  if(operation.version!==1||!Array.isArray(operation.targetNodeIds)||!Array.isArray(operation.outputObjectIds)
      ||new Set(operation.targetNodeIds).size!==operation.targetNodeIds.length||new Set(operation.outputObjectIds).size!==operation.outputObjectIds.length)throw new Error("构建采用契约无效");
  const current=new Map(scene.nodes.map(node=>[node.id,node])),baseline=new Map<string,Partial<PrevizNode>>(baselineNodes.flatMap(node=>node.id?[[node.id,node] as [string,Partial<PrevizNode>]]:[]));
  const targets=operation.targetNodeIds.map(id=>{
    const node=current.get(id),base=baseline.get(id);
    if(!node||!base||node.locked)throw new PrevizAdoptionConflict("原目标已删除、锁定或缺少生成基线，结果未覆盖当前场景");
    const origin=(base as Partial<PrevizNode>&{buildOrigin?:PrevizAsset["buildOrigin"]}).buildOrigin;
    const actual=scene.assets.find(a=>a.id===node.assetId)?.buildOrigin;
    if(origin&&(!actual||actual.projectId!==origin.projectId||actual.sha256!==origin.sha256||actual.sourceVersionId!==origin.sourceVersionId))throw new PrevizAdoptionConflict("目标模型已被其他构建替换，旧结果不能覆盖新版本");
    if(node.parentId!==(base.parentId??null))throw new PrevizAdoptionConflict("生成期间对象层级发生变化，未自动覆盖");
    return node;
  });
  const commands:PrevizCommand[]=[];
  if(operation.kind==="MODIFY"){
    if(targets.length!==1||targets[0].kind!=="GLB")throw new Error("修改操作需要唯一生成模型");
    const safety=assessGlbReplacement(scene,targets[0].id,document);
    if(!safety.canRetain)throw new PrevizAdoptionConflict(safety.reason);
    const oldId=targets[0].assetId,retained=scene.nodes.some(n=>n.id!==targets[0].id&&n.assetId===oldId)?scene.assets:scene.assets.filter(a=>a.id!==oldId);
    commands.push({type:"assets.replace",assets:[...retained,asset]},{type:"node.update",id:targets[0].id,patch:{assetId:asset.id}});
    return {commands,nodeIds:[targets[0].id]};
  }
  const roots=operation.outputObjectIds.length?operation.outputObjectIds:operation.kind==='REBUILD_SCENE'?glbObjectsFromDocument(document).filter(o=>!o.parentId).map(o=>o.id):[];
  if(!roots.length)throw new Error("新增或重建须明确交付对象范围，不能自动叠入完整场景");
  if(operation.kind==="ADD"&&targets.length)throw new Error("新增操作不能删除或替换原对象");
  if(!["ADD","REPLACE_SCOPE","REBUILD_SCENE"].includes(operation.kind))throw new Error("不支持的采用操作");
  const catalog=glbObjectsFromDocument(document),poses=sourceObjectWorldTransforms(document,roots);
  const selectedTargets=new Set(targets.map(n=>n.id));
  // Every existing target needs one unambiguous replacement; no name matching or implicit deletion.
  const bindings=new Map<string,PrevizNode>();
  for(const target of targets){
    const root=roots.includes(target.id)?target.id:targets.length===1&&roots.length===1?roots[0]:undefined;
    if(!root){if(operation.kind!=="REBUILD_SCENE")throw new Error("生成对象与替换范围无法逐一对应，原对象保持不变");continue;}
    if(bindings.has(root))throw new Error("多个目标不能指向同一个生成对象");
    bindings.set(root,target);
  }
  if(targets.some(n=>n.parentId&&selectedTargets.has(n.parentId)))throw new Error("替换范围不能同时包含父对象及其子对象");
  if(operation.kind==="REBUILD_SCENE"){
    for(const node of scene.nodes){
      if(["CAMERA","LIGHT"].includes(node.kind))continue;
      const base=baseline.get(node.id);
      if(!base)throw new PrevizAdoptionConflict("生成期间新增了对象，重建结果未覆盖这些手动修改");
      for(const key of Object.keys(base) as (keyof PrevizNode)[]){
        if(key==="buildOrigin" as keyof PrevizNode)continue;
        if(!same(node[key],base[key]))throw new PrevizAdoptionConflict("生成期间场景对象已被修改，请核对重建范围");
      }
    }
    for(const target of targets)if(![...bindings.values()].some(n=>n.id===target.id))commands.push({type:"node.remove",id:target.id});
  }
  const parentWorld=(id:string|null,map:Map<string,Partial<PrevizNode>>):Matrix4=>{
    if(!id)return new Matrix4();const n=map.get(id);if(!n?.localTransform)throw new Error("对象父级基线不可用");
    return parentWorld(n.parentId??null,map).multiply(matrix(n.localTransform));
  };
  const nodeIds:string[]=[];
  for(const root of roots){
    const target=bindings.get(root),pose=poses.get(root)!;
    const node=target?copy(target):createNode("GLB",catalog.find(o=>o.id===root)!.name);
    if(target){
      const base=baseline.get(target.id)!;
      if(!base.localTransform)throw new Error("对象变换基线缺失");
      const generated=transform(parentWorld(base.parentId??null,baseline).invert().multiply(matrix(pose)));
      node.localTransform=mergeAdoptionTransform(base.localTransform,target.localTransform,generated);
      if(target.assetOverrides&&Object.keys(target.assetOverrides).length)throw new PrevizAdoptionConflict("范围替换包含内部手动编辑，请使用模型续改保留这些编辑");
      if(target.kind==='GLB'&&!assessGlbReplacement(scene,target.id,document).canRetain)throw new PrevizAdoptionConflict('范围替换不能丢失已有对象动画或机位，请使用模型续改或明确重置内部编辑');
      if(base.color&&target.color!==base.color)node.assetOverrides={[root]:{color:target.color}};
    }else node.localTransform=pose;
    node.kind="GLB";delete node.action;delete node.motionFacing;
    node.assetId=asset.id;node.assetObjectIds=[root];node.assetPivotId=root;
    if(!node.assetPlayback&&document.animations?.length){const clip=document.animations[0];node.assetPlayback={clipIndex:0,loop:false,...(typeof clip.name==='string'&&clip.name.length<=200?{clipName:clip.name}:{})};}
    commands.push(target?{type:"node.update",id:target.id,patch:node}:{type:"node.add",node});nodeIds.push(node.id);
  }
  const futureNodes=new Map(scene.nodes.map(node=>[node.id,node]));
  for(const command of commands){
    if(command.type==='node.remove'){
      const removed=new Set([command.id]);let count=0;while(count!==removed.size){count=removed.size;for(const node of futureNodes.values())if(node.parentId&&removed.has(node.parentId))removed.add(node.id);}
      for(const id of removed)futureNodes.delete(id);
    }else if(command.type==='node.update'){const previous=futureNodes.get(command.id);if(previous)futureNodes.set(command.id,{...previous,...command.patch});}
    else if(command.type==='node.add')futureNodes.set(command.node.id,command.node);
  }
  const usedAssets=new Set([...futureNodes.values()].map(node=>node.assetId).filter(Boolean));
  const replacedAssets=new Set(targets.map(node=>node.assetId).filter(Boolean));
  const assets = [...scene.assets.filter(value => !replacedAssets.has(value.id) || usedAssets.has(value.id)), asset];
  commands.unshift({ type: "assets.replace", assets });
  if (operation.kind === "REBUILD_SCENE" && scene.shots.length) {
    const nodes = [...futureNodes.values()];
    const nextCameras = cameraChoices({ ...scene, nodes, assets });
    const nextCameraIds = new Set(nextCameras.map(camera => camera.id));
    if (scene.shots.some(shot => !nextCameraIds.has(shot.cameraId))) {
      const oldCameras = new Map(cameraChoices(scene).map(camera => [camera.id, camera]));
      const generatedOwners = new Set(nodes.filter(node => node.assetId === asset.id).map(node => node.id));
      const generatedCameras = nextCameras.filter(camera => camera.sourceNodeId && generatedOwners.has(camera.sourceNodeId));
      const shots = scene.shots.map(shot => {
        if (nextCameraIds.has(shot.cameraId)) return shot;
        const oldCamera = oldCameras.get(shot.cameraId);
        const matches = generatedCameras.filter(camera => oldCamera?.hfId && camera.hfId === oldCamera.hfId);
        if (matches.length !== 1) throw new PrevizAdoptionConflict(`新场景缺少镜头「${shot.name}」对应的唯一机位，当前镜头与生成产物均已保留`);
        return { ...shot, cameraId: matches[0].id };
      });
      commands.unshift({ type: "timeline.replace", timeline: scene.timeline, shots });
    }
  }
  return {commands,nodeIds};
}
