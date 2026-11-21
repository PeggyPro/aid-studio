import type { PrevizAsset, PrevizNode } from "@/types/previz";
import { PREVIZ_GLB_MAX_BYTES } from "./limits";

export type GlbObject = { id: string; name: string; index: number; parentId: string | null; depth: number; mesh: boolean; bone: boolean };
export type GlbObjectDocument = {
  nodes?: { name?: string; mesh?: number; camera?: number; children?: number[]; extras?: { hf_id?: unknown }; skin?: number; matrix?:number[]; translation?:number[]; rotation?:number[]; scale?:number[] }[];
  scenes?: { nodes?: number[] }[]; scene?: number; skins?: { joints?: number[] }[];
  animations?:{name?:string}[];
};
const cache = new WeakMap<PrevizAsset, { dataUrl: string; objects: GlbObject[] }>();
const catalogCache=new Map<string,GlbObject[]>();let cachedBytes=0;

/** Identity is scoped by the project instance, never by a display name or a mesh index. */
export function glbObjectsFromDocument(document: GlbObjectDocument): GlbObject[] {
  const nodes = document.nodes || [];
  if (nodes.length > 5000) throw new Error("模型内部对象超过编辑限制");
  const counts = new Map<string, number>();
  for (const node of nodes) if (typeof node.extras?.hf_id === "string") counts.set(node.extras.hf_id, (counts.get(node.extras.hf_id) || 0) + 1);
  const joints = new Set((document.skins || []).flatMap(skin => skin.joints || []));
  const seen = new Set<number>(), objects: GlbObject[] = [];
  const pending = [...(document.scenes?.[document.scene ?? 0]?.nodes || [])].reverse().map(index => ({ index, parentId: null as string | null, depth: 0 }));
  while (pending.length) {
    const { index, parentId, depth } = pending.pop()!;
    if (!Number.isInteger(index) || index < 0 || index >= nodes.length || seen.has(index)) throw new Error("模型对象层级存在循环、重复挂载或无效引用");
    seen.add(index);
    const node = nodes[index], id = node.extras?.hf_id;
    const valid = typeof id === "string" && Boolean(id.trim()) && id.length <= 200 && counts.get(id) === 1;
    if (valid) objects.push({ id, name: node.name?.slice(0,120) || `对象 ${index + 1}`, index, parentId, depth, mesh: node.mesh !== undefined, bone: joints.has(index) });
    for (const child of [...(node.children || [])].reverse()) pending.push({ index: child, parentId: valid ? id : parentId, depth: valid ? depth + 1 : depth });
  }
  return objects;
}

/** Decode the JSON chunk only, once per immutable asset; do not keep a second mesh buffer. */
export function glbObjectsForAsset(asset?: PrevizAsset): GlbObject[] {
  if (!asset || asset.kind !== "GLB") return [];
  const previous = cache.get(asset); if (previous?.dataUrl === asset.dataUrl) return previous.objects;
  const encoded = asset.dataUrl.slice(asset.dataUrl.indexOf(",") + 1);
  const header = Uint8Array.from(atob(encoded.slice(0,28)), c => c.charCodeAt(0));
  if (header.length < 20) throw new Error("模型对象头无效");
  const view = new DataView(header.buffer), length = view.getUint32(12,true), total = view.getUint32(8,true);
  if (view.getUint32(0,true) !== 0x46546c67 || view.getUint32(4,true) !== 2 || view.getUint32(16,true) !== 0x4e4f534a || total > PREVIZ_GLB_MAX_BYTES || length + 20 > total) throw new Error("模型对象目录无效");
  const prefix=encoded.slice(0,Math.ceil((20+length)/3)*4),known=catalogCache.get(prefix);
  if(known){cache.set(asset,{dataUrl:asset.dataUrl,objects:known});return known;}
  const bytes = Uint8Array.from(atob(prefix), c => c.charCodeAt(0));
  const objects = glbObjectsFromDocument(JSON.parse(new TextDecoder().decode(bytes.subarray(20,20+length))));
  if(prefix.length<=1024*1024){while(cachedBytes+prefix.length>4*1024*1024||catalogCache.size>=32){const first=catalogCache.keys().next().value!;cachedBytes-=first.length;catalogCache.delete(first);}catalogCache.set(prefix,objects);cachedBytes+=prefix.length;}
  cache.set(asset, { dataUrl: asset.dataUrl, objects }); return objects;
}

export function glbObjectsForNode(node: PrevizNode, asset?: PrevizAsset): GlbObject[] {
  const objects=glbObjectsForAsset(asset);if(!node.assetObjectIds)return objects;
  const selected=new Set(node.assetObjectIds), byId=new Map(objects.map(o=>[o.id,o]));
  return objects.filter(object=>{
    let current: GlbObject | undefined=object;
    while(current){if(selected.has(current.id))return true;current=current.parentId?byId.get(current.parentId):undefined;}
    return false;
  }).map(object=>({...object,parentId: selected.has(object.id)?null:object.parentId}));
}
