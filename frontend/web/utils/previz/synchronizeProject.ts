import type {PrevizProject} from '@/types/previz';
import {createPrevizWorkspaceApi} from './remoteWorkspace';
import {packProjectDocument,unpackProjectDocument,type ProjectDocument} from './projectDocument';
import {assistantDigest} from '@/utils/assistant/assistantRequestCoordinator';

export class PrevizSourceHeadChangedError extends Error {
 constructor(readonly projectId:string,readonly attemptedSourceVersion:string|null){
  super('服务器源码版本已变化，请先核对生成结果；本地修改已保留');
 }
}

function containsHistory(history: ProjectDocument['versions'], base: ProjectDocument['versions']) {
 return base.length <= history.length && base.every((entry,index)=>assistantDigest(entry)===assistantDigest(history[index]));
}

/** Scene and source versions are checked together. An unknown save response is reconciled by content. */
export async function synchronizeProject(snapshot:PrevizProject,guard:()=>void,onProgress:(message:string)=>void=()=>{}) {
 const api=createPrevizWorkspaceApi(),projectId=snapshot.remoteWorkspaceId;
 if(!projectId)throw new Error('此工作副本尚未关联服务器，请导出工程包，再从首页导入为项目');
 guard();const metadata=await api.detail(projectId,true);guard();
 if(metadata.accessRole==='VIEWER')throw new Error('此项目为只读权限，本地修改已保留');
 const current=await api.documentCurrent(projectId);guard();
 if(snapshot.remoteSourceVersion!==undefined&&snapshot.remoteSourceVersion!==current.currentSourceVersion)throw new PrevizSourceHeadChangedError(projectId,snapshot.remoteSourceVersion);
 const document=await packProjectDocument(snapshot,projectId,api,guard,onProgress);guard();
 let version=current.documentVersion;
 let versions: PrevizProject['versions'] | undefined;
 let write=true;
 if(version!==(snapshot.remoteDocumentVersion??null)){
  if(!version)throw new Error('服务器场景版本已变化，请重新载入核对');
  const existing=await api.documentRead(projectId,version);guard();
  write=assistantDigest(existing.document)!==assistantDigest(document);
  if(write){
   const conflict=()=>new Error('服务器已有其他保存版本，请先导出本机备份，再返回项目列表重新打开核对；本地修改已保留');
   if(!snapshot.remoteDocumentVersion)throw conflict();
   const linked=await api.documentRead(projectId,snapshot.remoteDocumentVersion);guard();
   const head=existing.document as ProjectDocument;
   const base=linked.document as ProjectDocument;
   const {versions:localHistory,...localScene}=document;
   const {versions:remoteHistory,...remoteScene}=head;
   const {versions:baseHistory,...baseScene}=base;
   const historyOnly=existing.sourceVersion===linked.sourceVersion
     && assistantDigest(remoteScene)===assistantDigest(baseScene)
     && containsHistory(remoteHistory,baseHistory);
   if(!historyOnly)throw conflict();
   if(!containsHistory(localHistory,remoteHistory)){
    if(!containsHistory(remoteHistory,localHistory) || assistantDigest(remoteScene)!==assistantDigest(localScene))throw conflict();
    versions=(await unpackProjectDocument(head,projectId,api,guard,onProgress,snapshot.scene.assets)).versions;
    write=false;
   }
  }
 }
 if(write){
  const saved=await api.documentSave(projectId,version,current.currentSourceVersion,document);guard();version=saved.documentVersion;
 }
 return {remoteWorkspaceId:projectId,remoteRole:metadata.accessRole,remoteDocumentVersion:version??undefined,remoteSourceVersion:current.currentSourceVersion,...(versions?{versions}:{})};
}
