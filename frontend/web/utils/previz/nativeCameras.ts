import type { PrevizScene } from "@/types/previz";
import { PREVIZ_GLB_MAX_BYTES } from "./limits";
import { glbObjectsForNode } from "./glbObjects";
type NativeCamera = { hfId: string; name: string; projection: "perspective" | "orthographic" };
const cache = new Map<string, NativeCamera[]>();
let cacheBytes = 0;

/** Decode only the JSON chunk, not mesh buffers. Cache is bounded independently of project size. */
export function nativeCameraMetadata(dataUrl: string): NativeCamera[] {
  const encoded = dataUrl.slice(dataUrl.indexOf(",") + 1);
  const header = Uint8Array.from(atob(encoded.slice(0,28)), character => character.charCodeAt(0));
  if(header.length<20)throw new Error("模型相机元数据头无效");
  const view = new DataView(header.buffer), length = view.getUint32(12,true), total = view.getUint32(8,true);
  if(view.getUint32(0,true)!==0x46546c67||view.getUint32(4,true)!==2||view.getUint32(16,true)!==0x4e4f534a
    ||total>PREVIZ_GLB_MAX_BYTES||length+20>total)throw new Error("模型相机元数据无效");
  const prefix = encoded.slice(0,Math.ceil((20+length)/3)*4);
  const prior = cache.get(prefix);if(prior)return prior;
  const bytes = Uint8Array.from(atob(prefix),character=>character.charCodeAt(0));
  const document = JSON.parse(new TextDecoder().decode(bytes.subarray(20,20+length)));
  const result=nativeCamerasFromDocument(document);
  if(prefix.length<=512*1024){
    while(cacheBytes+prefix.length>4*1024*1024||cache.size>=32){const first=cache.keys().next().value as string;cacheBytes-=first.length;cache.delete(first);}
    cache.set(prefix,result);cacheBytes+=prefix.length;
  }
  return result;
}
export function nativeCamerasFromDocument(document: {nodes?:unknown[];scenes?:{nodes?:number[]}[];scene?:number;cameras?:{type?:string}[]}):NativeCamera[] {
  const nodes = document.nodes as {name?:string;camera?:number;children?:number[];extras?:{hf_id?:unknown}}[] | undefined;
  if(!Array.isArray(nodes)||nodes.length>5000)return [];
  const ids = new Map<string,number>();
  const result: NativeCamera[] = [];
  const reachable=new Set<number>(),pending=[...(document.scenes?.[document.scene??0]?.nodes||[])];
  while(pending.length){const index=pending.pop();if(typeof index!=='number'||!Number.isInteger(index)||index<0||index>=nodes.length||reachable.has(index))continue;reachable.add(index);pending.push(...(nodes[index].children||[]));}
  for(const index of reachable){const id=nodes[index].extras?.hf_id;if(typeof id==='string')ids.set(id,(ids.get(id)||0)+1);}
  for(const [index,node] of nodes.entries()){
    if(!reachable.has(index))continue;
    const id=node.extras?.hf_id, camera=Number.isInteger(node.camera)?document.cameras?.[node.camera!]:null;
    if(typeof id!=='string'||!id.trim()||id.length>200||ids.get(id)!==1||(camera?.type!=='perspective'&&camera?.type!=='orthographic'))continue;
    result.push({hfId:id,name:typeof node.name==='string'&&node.name.trim()?node.name.slice(0,120):id,projection:camera.type});
  }
  return result;
}
export function nativeCameraId(nodeId:string,hfId:string){return `@native/${encodeURIComponent(nodeId)}/${encodeURIComponent(hfId)}`;}
export function nativeCameraOwner(id:string){
  if(!id.startsWith('@native/'))return undefined;
  const pieces=id.split('/');if(pieces.length!==3)return undefined;
  try{return decodeURIComponent(pieces[1]);}catch{return undefined;}
}
/** The canvas observation view can cover the timeline without authoring a camera. */
export const PREVIZ_DIRECTOR_VIEW_ID = 'director-camera-observer'

export function cameraChoices(scene:PrevizScene){
  const choices:{id:string;name:string;sourceNodeId?:string;hfId?:string}[]=scene.nodes.filter(node=>node.kind==='CAMERA').map(({id,name})=>({id,name}));
  const assets=new Map(scene.assets.map(asset=>[asset.id,asset]));
  for(const node of scene.nodes){
    const asset=node.kind==='GLB'?assets.get(node.assetId||''):undefined;if(!asset)continue;
    let cameras: NativeCamera[];try{cameras=nativeCameraMetadata(asset.dataUrl);}catch{continue;}
    const visible=node.assetObjectIds?new Set(glbObjectsForNode(node,asset).map(o=>o.id)):null;
    for(const camera of cameras)if(!visible||visible.has(camera.hfId))choices.push({id:nativeCameraId(node.id,camera.hfId),name:`${node.name} / ${camera.name}`,sourceNodeId:node.id,hfId:camera.hfId});
  }
  if (scene.directorLayer && !choices.some(choice => choice.id === PREVIZ_DIRECTOR_VIEW_ID)) choices.push({ id: PREVIZ_DIRECTOR_VIEW_ID, name: "导演视角" });
  if(new Set(choices.map(choice=>choice.id)).size!==choices.length)throw new Error("机位标识冲突");
  return choices;
}
