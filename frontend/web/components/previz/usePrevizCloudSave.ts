"use client";
import {useCallback,useEffect,useRef,useState} from 'react';
import type {PrevizProject} from '@/types/previz';
import {mainAssistantTransport} from '@/utils/business/assistant';
import {createPrevizWorkspaceApi} from '@/utils/previz/remoteWorkspace';
import {synchronizeProject,PrevizSourceHeadChangedError} from '@/utils/previz/synchronizeProject';
import {previzErrorMessage as assistantErrorMessage} from '@/utils/previz/errorMessage';
import {previzCloudContent as content,samePrevizCloudContent as same,type PrevizCloudContent as Content} from '@/utils/previz/cloudSaveContent';

export function usePrevizCloudSave(project:PrevizProject,getProject:()=>PrevizProject,flushLocal:()=>Promise<void>,onSaved:(patch:Partial<PrevizProject>)=>void,autoSave:boolean) {
 const handlers=useRef({getProject,flushLocal,onSaved});handlers.current={getProject,flushLocal,onSaved};
 const saved=useRef(content(project)),flight=useRef<Promise<void>|null>(null),alive=useRef(true);
 const blockedSource=useRef<{projectId:string;version:string|null}|null>(null);
 const remoteUpdate=useRef(false);
 const reportedFailures=useRef(new WeakSet<object>());
 const [error,setError]=useState(''),[busy,setBusy]=useState(false),[epoch,setEpoch]=useState(0),[verified,setVerified]=useState(false);
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[]);
 useEffect(()=>{
  let current=true;const snapshot=handlers.current.getProject();
  if(!snapshot.remoteWorkspaceId)return;
  if(!autoSave){setVerified(true);return;}
  const scope=mainAssistantTransport.identityKey();
  void createPrevizWorkspaceApi().documentCurrent(snapshot.remoteWorkspaceId).then(head=>{
   if(!current||scope!==mainAssistantTransport.identityKey()||snapshot.remoteDocumentVersion!==handlers.current.getProject().remoteDocumentVersion)return;
   if(head.documentVersion!==(snapshot.remoteDocumentVersion??null))setError('服务器已有更新版本，请先导出本机备份，再返回项目列表重新打开核对；本机工作副本保留');
   setVerified(true);
  }).catch(e=>{if(current)setError(assistantErrorMessage(e));});
  return()=>{current=false;};
 },[autoSave,project.remoteWorkspaceId]);
 const flush=useCallback(async(recheck=false)=>{
  if(remoteUpdate.current)throw new Error('正在同步服务器对象编辑，请同步完成后再保存；本机草稿保留');
  if(flight.current)return flight.current;
  const scope=mainAssistantTransport.identityKey();
  const guard=()=>{if(!alive.current||scope!==mainAssistantTransport.identityKey())throw new Error('账号或页面已变化，未覆盖当前工作副本');};
  const work=async()=>{
   await handlers.current.flushLocal();guard();const snapshot=handlers.current.getProject();
   if(!snapshot.remoteWorkspaceId)return;
   if(same(saved.current,content(snapshot))&&snapshot.remoteDocumentVersion){
    if(!recheck)return;
    setBusy(true);
    const head=await createPrevizWorkspaceApi().documentCurrent(snapshot.remoteWorkspaceId);guard();
    if(snapshot.remoteSourceVersion!==undefined&&head.currentSourceVersion!==snapshot.remoteSourceVersion)throw new PrevizSourceHeadChangedError(snapshot.remoteWorkspaceId,snapshot.remoteSourceVersion);
    if(head.documentVersion===snapshot.remoteDocumentVersion){setVerified(true);setError("");return;}
   }
   if(snapshot.remoteRole==='VIEWER')throw new Error('此项目为只读权限，不能保存到服务器');
   if(!snapshot.title.trim())throw new Error('请填写工程名称后保存');
   setBusy(true);
   const patch=await synchronizeProject(snapshot,guard);guard();
   if(patch.versions && !same(content(snapshot),content(handlers.current.getProject()))) {
    throw new Error('核对历史修订期间场景已修改，请重新保存；本机草稿保留');
   }
   saved.current=content({...snapshot,...patch});setVerified(true);handlers.current.onSaved(patch);await handlers.current.flushLocal();setError("");
  };
  const promise=work().catch(e=>{
   if(alive.current){
    const current=handlers.current.getProject();
    blockedSource.current=e instanceof PrevizSourceHeadChangedError?{projectId:e.projectId,version:e.attemptedSourceVersion}:null;
    // A later adopted source may already be present. Its next save must recheck both authoritative heads.
    const moved=blockedSource.current&&current.remoteWorkspaceId===blockedSource.current.projectId
      &&current.remoteSourceVersion!==blockedSource.current.version;
    if(moved){blockedSource.current=null;setError('');}
    else {
     if(e && typeof e==='object')reportedFailures.current.add(e);
     setError(assistantErrorMessage(e));
    }
   }
   throw e;
  }).finally(()=>{flight.current=null;if(alive.current){setBusy(false);setEpoch(v=>v+1);}});
  flight.current=promise;return promise;
 },[]);
 const drain=useCallback(async(recheck=false)=>{
  await flush(recheck);
  while(alive.current&&handlers.current.getProject().remoteWorkspaceId&&!same(saved.current,content(handlers.current.getProject())))await flush();
 },[flush]);
 const changed=!same(saved.current,content(project));
 useEffect(()=>{
  const blocked=blockedSource.current;
  if(blocked&&project.remoteWorkspaceId===blocked.projectId&&project.remoteSourceVersion!==blocked.version){
   blockedSource.current=null;setError('');setEpoch(value=>value+1);
  }
 },[project.remoteWorkspaceId,project.remoteSourceVersion]);
 useEffect(()=>{
  if(!autoSave||remoteUpdate.current||!changed||error||busy||!project.remoteWorkspaceId||project.remoteRole==='VIEWER')return;
  const timer=setTimeout(()=>void flush().catch(()=>{}),1500);return()=>clearTimeout(timer);
 },[autoSave,changed,error,busy,project,epoch,flush]);
 useEffect(()=>{
  const warn=(event:BeforeUnloadEvent)=>{if(project.remoteWorkspaceId&&(autoSave&&changed||flight.current)){event.preventDefault();event.returnValue='';}};
  window.addEventListener('beforeunload',warn);return()=>window.removeEventListener('beforeunload',warn);
 },[autoSave,project.remoteWorkspaceId,changed]);
 return {flush:drain,retry:()=>drain(true),hasReportedFailure:(cause:unknown)=>Boolean(cause && typeof cause==='object' && reportedFailures.current.has(cause)),duringRemoteUpdate:async(work:()=>Promise<void>)=>{if(remoteUpdate.current)throw new Error('正在同步另一项工程编辑');remoteUpdate.current=true;try{await flight.current?.catch(()=>{});await work();}finally{remoteUpdate.current=false;setEpoch(v=>v+1);}},adoptServerSnapshot:(baseline?:Content)=>{saved.current=baseline??content(handlers.current.getProject());blockedSource.current=null;setError('');setVerified(true);setEpoch(v=>v+1);},error,busy,changed,status:!project.remoteWorkspaceId?'仅本机工作副本':project.remoteRole==='VIEWER'?'只读项目':error?'服务器保存失败 · 本机草稿保留':busy?'正在保存到服务器':!verified?'正在核对服务器版本':changed?'待同步到服务器':'已保存到服务器'};
}
