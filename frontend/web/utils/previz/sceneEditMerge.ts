import type {PrevizScene} from '@/types/previz';
import {validateScene} from './core';

const same=(a:unknown,b:unknown)=>JSON.stringify(a)===JSON.stringify(b);
const object=(v:unknown):v is Record<string,unknown>=>Boolean(v&&typeof v==='object'&&!Array.isArray(v));
/** Apply a published edit while retaining independent local edits. No missing node is resurrected. */
export function mergePublishedSceneEdit(base:PrevizScene,current:PrevizScene,published:PrevizScene):PrevizScene {
 function merge(before:unknown,local:unknown,remote:unknown,path:string):unknown {
  if(same(before,remote))return local;
  if(same(local,before)||same(local,remote))return remote;
  if(before===undefined&&Array.isArray(local)&&Array.isArray(remote)&&(path.endsWith('.keys')||path.endsWith('.tracks')))before=[];
  if(Array.isArray(before)&&Array.isArray(local)&&Array.isArray(remote)){
   if(before.every(v=>typeof v==='number')&&local.every(v=>typeof v==='number')&&remote.every(v=>typeof v==='number')&&before.length===local.length&&before.length===remote.length)
    return remote.map((v,i)=>merge(before[i],local[i],v,`${path}[${i}]`));
   const key=(v:unknown)=>object(v)?path.endsWith('.tracks')?JSON.stringify([v.nodeId,v.objectId??null,v.channel]):path.endsWith('.keys')?String(v.frame):typeof v.id==='string'?v.id:null:null;
   if([...before,...local,...remote].every(v=>key(v)!==null)){
    const b=new Map(before.map(v=>[key(v),v])),l=new Map(local.map(v=>[key(v),v])),r=new Map(remote.map(v=>[key(v),v]));
    const result:unknown[]=[];
    for(const item of local){const id=key(item)!;if(!r.has(id)){if(b.has(id)){if(!same(b.get(id),item))throw new Error('服务器与本机同时修改了已移除的对象，请核对工程');continue;}result.push(item);continue;}
     const prior=b.get(id),published=r.get(id);
     if(path.endsWith('.nodes')&&object(item)&&object(prior)&&!same(prior,published)&&(item.locked&&!prior.locked||item.parentId!==prior.parentId||item.assetId!==prior.assetId))throw new Error('对象已锁定、重新分组或替换，服务器编辑未覆盖本机工作副本');
     const updated=merge(prior,item,published,`${path}[${id}]`);result.push(object(updated)&&object(item)&&path.endsWith('.tracks')?{...updated,id:item.id}:updated);
    }
    for(const item of remote){const id=key(item)!;if(!l.has(id)&&!b.has(id))result.push(item);}
    return path.endsWith('.keys')?result.sort((a,b)=>Number((a as {frame:number}).frame)-Number((b as {frame:number}).frame)):result;
   }
  }
  if(object(local)&&object(remote)&&(object(before)||before===undefined)){
   const prior=object(before)?before:{};const result:Record<string,unknown>={};
   for(const key of new Set([...Object.keys(prior),...Object.keys(local),...Object.keys(remote)])){
    if(key==='id'&&path.includes('.tracks[')){result[key]=local[key]??remote[key];continue;}
    const value=merge(prior[key],local[key],remote[key],`${path}.${key}`);if(value!==undefined)result[key]=value;
   }return result;
  }
  if(typeof before==='number'&&typeof local==='number'&&typeof remote==='number'){
   if(Math.abs(local-before)<=1e-6)return remote;if(Math.abs(remote-before)<=1e-6||Math.abs(local-remote)<=1e-6)return local;
  }
  throw new Error('服务器与本机同时修改了同一属性；本机草稿保留，请先核对工程版本');
 }
 const result={...merge(base,current,published,'scene') as PrevizScene};
 const ids=new Set(result.nodes.map(n=>n.id));result.timeline={...result.timeline,tracks:result.timeline.tracks.filter(t=>ids.has(t.nodeId))};
 validateScene(result);return result;
}
