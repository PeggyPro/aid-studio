"use client";
import { createRandomUuid } from '@/utils/clientId'
import {useCallback,useEffect,useMemo,useRef,useState} from 'react';
import {Modal} from 'antd';
import {ArrowUpOutlined, DeleteOutlined, FolderOpenOutlined, PlusOutlined, ReloadOutlined, SearchOutlined, UploadOutlined} from '@ant-design/icons';
import {BoxMark, Choice, Tool} from './PrevizControls';
import {PrevizProjectDialog} from './PrevizProjectDialog';
import {createPrevizWorkspaceApi} from '@/utils/previz/remoteWorkspace';
import {readServerProject} from '@/utils/previz/createServerProject';
import {assistantErrorMessage} from '@/utils/assistant/assistantRequestCoordinator';
import {usePrevizAssistantIdentity} from './usePrevizAssistantIdentity';
import type {PrevizProject} from '@/types/previz';
import {InfiniteScrollLoadFooter} from '@/components/common/InfiniteScrollLoadFooter';
import {waitInfiniteScrollAppendDelay} from '@/utils/infiniteScrollDelay';

type Api=ReturnType<typeof createPrevizWorkspaceApi>;
export function PrevizServerProjects({onOpen,embedded=false,workingCopies=[],onCreate,onImport,creating=false}:{onOpen:(project:PrevizProject)=>Promise<void>;embedded?:boolean;workingCopies?:PrevizProject[];onCreate?:()=>void;onImport?:()=>void;creating?:boolean}) {
 const identity=usePrevizAssistantIdentity(),api=useMemo(()=>createPrevizWorkspaceApi(),[identity.identity]);
 const [open,setOpen]=useState(false),[busy,setBusy]=useState(false),[message,setMessage]=useState(''),[error,setError]=useState('');
 const [trash,setTrash]=useState(false),[modal,modalContext]=Modal.useModal();
 const [search,setSearch]=useState(''),[sort,setSort]=useState('recent');
 const [loadingMore,setLoadingMore]=useState(false),[appendFrom,setAppendFrom]=useState<number|null>(null);
 const sentinel=useRef<HTMLDivElement>(null),pageGeneration=useRef(0),appendFlight=useRef<symbol|null>(null);
 const pendingLifecycle=useRef(new Map<string,string>());
 const [items,setItems]=useState<Awaited<ReturnType<Api['projectPage']>>['items']>([]),[cursor,setCursor]=useState<string>();
 const lock=useRef<{identity:string;token:symbol}|null>(null),alive=useRef(true),account=useRef(identity.identity);account.current=identity.identity;
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[]);
 const action=useCallback(async(work:(guard:()=>void)=>Promise<void>)=>{
  if(lock.current?.identity===identity.identity)return;const scope=identity.identity,token=Symbol();lock.current={identity:scope,token};setBusy(true);setError('');
  const guard=()=>{if(!alive.current||account.current!==scope)throw new Error('账号或页面已变化');};
  try{await work(guard);}catch(e){if(alive.current&&account.current===scope)setError(assistantErrorMessage(e));}
  finally{if(lock.current?.token===token){lock.current=null;if(alive.current&&account.current===scope)setBusy(false);}}
 },[identity.identity]);
 const list=useCallback(()=>action(async guard=>{
  ++pageGeneration.current;setAppendFrom(null);
  const page=await (trash?api.projectTrashPage():api.projectPage());guard();
  setItems(page.items);setCursor(page.nextCursor);setMessage('');
 }),[action,api,trash]);
 const append=useCallback(async()=>{
  if(!cursor||appendFlight.current||lock.current||error)return;
  const token=Symbol(),scope=identity.identity,generation=pageGeneration.current,started=Date.now();
  appendFlight.current=token;setLoadingMore(true);
  const valid=()=>alive.current&&account.current===scope&&pageGeneration.current===generation;
  try{
   const before=cursor;
   const page=await (trash?api.projectTrashPage(before):api.projectPage(before));
   await waitInfiniteScrollAppendDelay(started);
   if(!valid())return;
   if(page.nextCursor===before)throw new Error('项目分页状态未更新，请刷新项目列表后重试');
   setAppendFrom(items.length);setItems(old=>[...new Map([...old,...page.items].map(item=>[item.id,item])).values()]);
   setCursor(page.nextCursor);
  }catch(cause){if(valid())setError(assistantErrorMessage(cause));}
  finally{if(appendFlight.current===token){appendFlight.current=null;if(alive.current)setLoadingMore(false);}}
 },[api,cursor,error,identity.identity,items.length,trash]);
 useEffect(()=>{++pageGeneration.current;appendFlight.current=null;setItems([]);setCursor(undefined);setError('');setBusy(false);setLoadingMore(false);pendingLifecycle.current.clear();},[identity.identity]);
 useEffect(()=>{if((embedded||open)&&identity.actorId)void list();},[embedded,open,identity.actorId,list]);
 useEffect(()=>{
  const target=sentinel.current;if(!target||!cursor||busy||loadingMore||error||!identity.actorId||(!embedded&&!open))return;
  const observer=new IntersectionObserver(entries=>{if(entries.some(entry=>entry.isIntersecting))void append();},{rootMargin:'120px 0px'});
  observer.observe(target);return()=>observer.disconnect();
 },[append,busy,cursor,embedded,error,identity.actorId,loadingMore,open]);
 const load=(id:string)=>action(async guard=>{
  const project=await readServerProject(id,guard);guard();
  await onOpen(project);guard();setOpen(false);
 });
 const change=(item:(typeof items)[number],operation:'DELETE'|'RESTORE')=>action(async guard=>{
  ++pageGeneration.current;
  const key=[item.id,operation,item.revision].join(':');let request=pendingLifecycle.current.get(key);
  if(!request){request=createRandomUuid();pendingLifecycle.current.set(key,request);}
  const result=await api.projectLifecycle(item.id,operation,String(item.revision),request);guard();pendingLifecycle.current.delete(key);
  const page=await (trash?api.projectTrashPage():api.projectPage());guard();setItems(page.items);setCursor(page.nextCursor);
  setMessage(result.deleted?'项目已移入回收站，已保存文件保留，可恢复。':'项目已恢复，原场景、源码和成员设置保留。');
 });
 const remove=(item:(typeof items)[number])=>modal.confirm({title:`将“${item.title}”移入回收站？`,content:'成员将无法访问该项目，已保存文件仍保留，可以恢复。有运行中或待核实任务时暂时不能移入。',okText:'移入回收站',cancelText:'取消',onOk:()=>change(item,'DELETE')});
 const visible=items.filter(item=>item.title.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()));
 if(sort==='name')visible.sort((a,b)=>a.title.localeCompare(b.title,'zh-CN'));
 const copies=new Map(workingCopies.slice().sort((a,b)=>a.updatedAt.localeCompare(b.updatedAt)).map(project=>[project.remoteWorkspaceId,project]));
 const appendedIds=new Set(appendFrom===null?[]:items.slice(appendFrom).map(item=>item.id));
 const content=<>
  {modalContext}
  <header className="pv-project-library__header"><div><h2>{trash?'项目回收站':embedded?'服务器工程':'我的 3D预演'}</h2><p>{trash?'已保存文件保留，随时恢复':embedded?'选择要迁入的建模工程':'从上次的场景，继续创作'}</p></div>
   <div className="pv-project-library-tools">
   {onImport&&!trash?<button type="button" className="pv-secondary" disabled={busy||creating} onClick={onImport}><UploadOutlined/> 导入工程</button>:null}
   <button type="button" className="pv-secondary" disabled={busy||loadingMore||!identity.actorId} onClick={()=>{++pageGeneration.current;setTrash(value=>!value);setItems([]);setSearch('');setCursor(undefined);setMessage('');}}>{trash?'返回我的项目':'回收站'}</button>
   <Tool label="刷新项目" disabled={busy||!identity.actorId} onClick={()=>void list()} icon={<ReloadOutlined spin={busy}/>}/>
   </div>
  </header>
  {items.length?<div className="pv-project-filter"><label className="pv-project-search"><SearchOutlined/><input aria-label="搜索已加载的项目" placeholder="搜索项目" value={search} onChange={event=>setSearch(event.target.value)}/></label><Choice label="项目排序" value={sort} onChange={setSort} items={[{key:'recent',label:'最近更新'},{key:'name',label:'按名称'}]}/></div>:null}
  {!identity.actorId?<div className="pv-empty"><FolderOpenOutlined/><h3>{identity.connected?'正在验证账号…':'登录后继续创作'}</h3><p>{identity.error||'账号验证完成后读取项目。'}</p>{identity.connected&&identity.error?<button className="pv-secondary" onClick={identity.retry}>重试验证</button>:null}</div>:null}
  {error?<p className="pv-inline-error" role="alert">{error}</p>:null}
  {message?<p role="status">{message}</p>:null}
  {identity.actorId&&!items.length&&!error?<div className="pv-library-notice" role="status">{busy?'正在读取项目…':trash?'回收站为空':'还没有项目。描述一个场景，或从空白空间开始。'}</div>:null}
  {search&&!visible.length?<p className="pv-library-notice" role="status">已加载的项目中没有匹配结果{cursor?'，可以继续加载更多项目。':'。'}</p>:null}
  <div className="pv-project-grid">
   {!trash&&onCreate?<button type="button" className="pv-create-project" disabled={busy||creating||!identity.actorId} onClick={onCreate}><span className="pv-create-project__art"><PlusOutlined/></span><strong>新建 3D预演</strong><span>从空白场景开始 <ArrowUpOutlined/></span></button>:null}
   {visible.map(item=>{const copy=copies.get(item.id),thumbnail=copy?.remoteDocumentVersion===item.documentVersion?copy?.thumbnail:undefined;return <article className={`pv-server-project-card-wrap${appendedIds.has(item.id)?' is-appended':''}`} key={item.id}>
    <button className="pv-server-project-card" type="button" aria-label={`打开项目 ${item.title}`} disabled={busy||trash} onClick={()=>void load(item.id)}>
     <span className="pv-project-cover">{thumbnail?<img src={thumbnail} alt="" loading="lazy"/>:<span className="pv-project-cover__empty"><BoxMark/><span>{item.documentVersion?'场景预演':'空白场景'}</span></span>}<span className="pv-project-open"><ArrowUpOutlined/></span></span>
     <span className="pv-project-card-copy"><strong>{item.title}</strong><span>{trash?'可恢复':item.documentVersion?'已保存场景':'空白项目'}<span>{item.role==='OWNER'?'我的项目':item.role==='EDITOR'?'可编辑':'只读'}</span></span></span>
    </button>
    {item.role==='OWNER'?<div className="pv-server-project-actions"><Tool label={`${trash?'恢复项目':'移入回收站'} ${item.title}`} disabled={busy} onClick={()=>trash?void change(item,'RESTORE'):remove(item)} icon={trash?<ReloadOutlined/>:<DeleteOutlined/>}/></div>:null}
   </article>})}
  </div>
  <div ref={sentinel} className="pv-project-load-sentinel" aria-hidden="true" />
  <InfiniteScrollLoadFooter loading={loadingMore} hasMore={Boolean(cursor)} hasItems={items.length>0} endText="已加载全部项目"/>
 </>;
 const library=<section className="pv-project-library" aria-label="服务器项目">{content}</section>;
 if(embedded)return library;
 return <><button type="button" className="pv-secondary" onClick={()=>{setOpen(true);}}>服务器项目</button>
  <PrevizProjectDialog title="服务器项目" open={open} onCancel={()=>{if(!busy)setOpen(false);}} closable={!busy} mask={{closable:!busy}} keyboard={!busy}>{library}</PrevizProjectDialog></>;
}
