import type {PrevizScene} from '@/types/previz';
import {nativeCameraId,nativeCameraOwner,nativeCamerasFromDocument} from './nativeCameras';
type GlbDocument=Parameters<typeof nativeCamerasFromDocument>[0] & {nodes?:{extras?:{hf_id?:unknown}}[];animations?:{name?:string}[]};
/** Infer only revisions of the same host-confirmed run and scene. Old hints never trigger replacement. */
export function findRunSceneReplacement(scene:PrevizScene, incoming:{projectId:string;runtimeRunId?:string;sceneId:string;engine:string;editorVersion?:string;entry?:string}) {
 if(!incoming.runtimeRunId)return undefined;
 const candidates=scene.nodes.filter(node=>{
  if(node.kind!=='GLB')return false;
  const origin=scene.assets.find(asset=>asset.id===node.assetId)?.buildOrigin;
  return origin?.projectId===incoming.projectId&&origin.runtimeRunId===incoming.runtimeRunId
   &&(origin.sceneId===incoming.sceneId||origin.entry!==undefined&&origin.entry===incoming.entry)
   &&origin.engine===incoming.engine&&origin.editorVersion===incoming.editorVersion;
 });
 if(candidates.length>1)throw new Error('同一生成场景存在多个实例，请明确选择续改目标；产物已保留');
 return candidates[0]?.id;
}
/** A replacement must preserve every live camera reference; reset only affects internal edits. */
export function assessGlbReplacement(scene:PrevizScene,targetId:string,document:GlbDocument){
 const target=scene.nodes.find(node=>node.id===targetId),ids=new Map<string,number>();
 for(const node of document.nodes||[])if(typeof node.extras?.hf_id==='string')ids.set(node.extras.hf_id,(ids.get(node.extras.hf_id)||0)+1);
 const missingEdits=[...(target?.assetObjectIds||[]),...Object.keys(target?.assetOverrides||{}),...scene.timeline.tracks.filter(t=>t.nodeId===targetId&&t.objectId).map(t=>t.objectId!)].some(id=>ids.get(id)!==1);
 const playback=target?.assetPlayback,clip=playback&&document.animations?.[playback.clipIndex];
 const changedAnimation=Boolean(playback&&(!clip||playback.clipName!==undefined&&clip.name!==playback.clipName));
 const cameras=new Set(nativeCamerasFromDocument(document).map(camera=>nativeCameraId(targetId,camera.hfId)));
 const missingCamera=scene.shots.some(shot=>nativeCameraOwner(shot.cameraId)===targetId&&!cameras.has(shot.cameraId));
 const canReplace=Boolean(target&&target.kind==='GLB'&&!target.locked&&!missingCamera);
 return {canReplace,canRetain:canReplace&&!missingEdits&&!changedAnimation,missingEdits,changedAnimation,missingCamera,
  reason:!target||target.locked?'原模型已删除或锁定。':missingCamera?'新模型缺少工程镜头正在引用的机位，请保留原模型或先调整镜头。':missingEdits||changedAnimation?'新模型中的部分对象或动画片段已变化，原内部编辑不能完整匹配。':'生成期间你修改了场景。'};
}
