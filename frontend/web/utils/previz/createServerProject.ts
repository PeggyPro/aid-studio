import type {PrevizProject} from '@/types/previz';
import {createPrevizWorkspaceApi} from './remoteWorkspace';
import {createProject} from './core';
import {packProjectDocument,unpackProjectDocument} from './projectDocument';
import {assistantDigest} from '@/utils/assistant/assistantRequestCoordinator';

/** Stable creation identity survives a lost save response; existing documents are never overwritten. */
export async function createServerProject(project:PrevizProject,guard:()=>void):Promise<PrevizProject> {
 const api=createPrevizWorkspaceApi();guard();
 const metadata=await api.create(project.title,`previz-local-${project.id}`);guard();
 const document=await packProjectDocument(project,metadata.id,api,guard,()=>{});guard();
 const current=await api.documentCurrent(metadata.id);guard();
 let version=current.documentVersion;
 if(version){
  const existing=await api.documentRead(metadata.id,version);guard();
  if(assistantDigest(existing.document)!==assistantDigest(document))throw new Error('此创建操作已有不同的服务器场景，请从项目列表打开核对；未覆盖已有内容');
 }else{
  if(current.currentSourceVersion)throw new Error('项目已经开始生成，请从项目列表打开；未覆盖源码');
  const saved=await api.documentSave(metadata.id,null,null,document);guard();version=saved.documentVersion;
 }
 return {...project,remoteWorkspaceId:metadata.id,remoteDocumentVersion:version,remoteSourceVersion:current.currentSourceVersion,remoteRole:metadata.accessRole??'OWNER'};
}

/** Resolve a canonical server-project URL on a new device without relying on IndexedDB IDs. */
export async function readServerProject(projectId:string,guard:()=>void):Promise<PrevizProject> {
 const api=createPrevizWorkspaceApi();
 const metadata=await api.detail(projectId);guard();
 const head=await api.documentCurrent(projectId);guard();
 let project=createProject(metadata.title);
 if(head.documentVersion){
  const result=await api.documentRead(projectId,head.documentVersion);guard();
  const content=await unpackProjectDocument(result.document,projectId,api,guard,()=>{});guard();
  project={...project,...content,remoteDocumentVersion:result.documentVersion,remoteSourceVersion:result.sourceVersion};
 }
 return {...project,remoteWorkspaceId:projectId,remoteRole:metadata.accessRole};
}
