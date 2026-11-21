import React,{useEffect,useRef,useState} from 'react';
import {Alert,Button,Checkbox,Collapse,Drawer,Select,Space,Table,Tag,message} from 'antd';
import {request} from '@/utils/request';
import {getToken} from '@/utils/auth';
import {sharedReadRequest,invalidateSharedReads} from '@/utils/sharedReadRequest';
import Auth from '@/components/Auth';
import BusinessStoragePolicyPanel from './BusinessStoragePolicyPanel';
type Ref={kind:'AGENT'|'SKILL';code:string;versionId:string};
type Descriptor={reference:Ref;name:string;packageDigest:string;defaultModelCode:string;selectableModelCodes:string[];dependencies:Ref[]};
type Binding=Ref&{providerCode:string;packageDigest:string;defaultEntry:boolean;sortOrder:number};
type Option={providerCode:string;entrypoint:Descriptor};
type View={sceneCode:string;revision:string;bindings:{binding:Binding;entrypoint?:Descriptor;available:boolean;unavailableReason?:string}[];models:any[]};
type ComputeView={sceneCode:string;poolCode:string;revision:string;pools:{code:string;enabledNodes:number;engines:string[]}[]};
const flights=new Map<string,Promise<any>>(),keyOf=(provider:string,r:Ref)=>JSON.stringify([provider,r.kind,r.code,r.versionId]);
function saveBinding(data:{sceneCode:string;expectedRevision:string;bindings:Binding[]}){
 const key=JSON.stringify([getToken(),data]),active=flights.get(key);if(active)return active;
 const promise=request({url:'/aid/funcconfig/entrypoints',method:'post',data,headers:{repeatSubmit:false}}).then(result=>{invalidateSharedReads('/aid/funcconfig/entrypoints');return result;}).finally(()=>flights.delete(key));flights.set(key,promise);return promise;
}
function saveComputePool(data:{sceneCode:string;poolCode:string;expectedRevision:string}){
 const key=JSON.stringify(['compute-pool',getToken(),data]),active=flights.get(key);if(active)return active;
 const promise=request({url:'/aid/funcconfig/entrypoints/compute-pool',method:'post',data,headers:{repeatSubmit:false}}).then(result=>{invalidateSharedReads('/aid/funcconfig/entrypoints');return result;}).finally(()=>flights.delete(key));flights.set(key,promise);return promise;
}
export default function BusinessEntrypointDrawer({scene,onClose}:{scene:{funcCode:string;funcName:string}|null;onClose:()=>void}){
 const [view,setView]=useState<View|null>(null),[options,setOptions]=useState<Option[]>([]),[bindings,setBindings]=useState<Binding[]>([]);
 const [compute,setCompute]=useState<ComputeView|null>(null),[pool,setPool]=useState(''),[poolBusy,setPoolBusy]=useState(false),[storageBusy,setStorageBusy]=useState(false);const poolPending=useRef(false);
 const [busy,setBusy]=useState(false),[loading,setLoading]=useState(false),[failure,setFailure]=useState('');const pending=useRef(false);
 useEffect(()=>{let live=true;setView(null);setCompute(null);setPool('');setFailure('');if(!scene)return;setLoading(true);
  const base='/aid/funcconfig/entrypoints/'+encodeURIComponent(scene.funcCode);
  Promise.all([sharedReadRequest(base,undefined,true),sharedReadRequest(base+'/options',undefined,true),sharedReadRequest(base+'/compute-pool',undefined,true)]).then(([data,choices,resources]:any[])=>{if(live){setView(data.data);setBindings(data.data.bindings.map((item:any)=>item.binding));setOptions(choices.data||[]);setCompute(resources.data);setPool(resources.data.poolCode||'');}})
   .catch((error:any)=>{if(live)setFailure(error?.msg||error?.message||'场景入口读取失败');}).finally(()=>{if(live)setLoading(false);});return()=>{live=false;};
 },[scene?.funcCode]);
 const save=async()=>{if(pending.current||!scene||!view)return;pending.current=true;setBusy(true);setFailure('');
  try{const result:any=await saveBinding({sceneCode:scene.funcCode,expectedRevision:view.revision,bindings});setView(result.data);setBindings(result.data.bindings.map((item:any)=>item.binding));message.success('业务场景固定入口已保存');}
  catch(error:any){setFailure(error?.msg||error?.message||'保存未确认，已保留当前选择');}finally{pending.current=false;setBusy(false);}};
 const known=new Map(options.map(option=>[keyOf(option.providerCode,option.entrypoint.reference),option]));
 const savePool=async()=>{if(poolPending.current||!scene||!compute)return;poolPending.current=true;setPoolBusy(true);setFailure('');
  try{const result:any=await saveComputePool({sceneCode:scene.funcCode,poolCode:pool,expectedRevision:compute.revision});setCompute(result.data);setPool(result.data.poolCode||'');message.success('计算资源池绑定已保存，运行中任务保留原机器');}
  catch(error:any){setFailure(error?.msg||error?.message||'资源池保存未确认，已保留当前选择');}finally{poolPending.current=false;setPoolBusy(false);}};
 for(const item of view?.bindings||[])if(item.entrypoint)known.set(keyOf(item.binding.providerCode,item.entrypoint.reference),{providerCode:item.binding.providerCode,entrypoint:item.entrypoint});
 return <Drawer title={`${scene?.funcName||''} · Agent / Skill 绑定`} width={1000} open={!!scene} onClose={()=>{if(!busy&&!poolBusy&&!storageBusy)onClose();}}
  extra={<Auth permission="aid:funcconfig:edit"><Button type="primary" loading={busy} disabled={!view||loading} onClick={()=>void save()}>保存绑定</Button></Auth>}>
  <Space direction="vertical" size="middle" style={{width:'100%'}}>
   {failure&&<Alert type="error" showIcon message={failure}/>}
   <Alert type="info" showIcon message="绑定现有固定版本，运行中任务保留原版本。模型能力、参数及SKU沿用此业务的模型配置。"/>
   {scene&&<BusinessStoragePolicyPanel sceneCode={scene.funcCode} onBusyChange={setStorageBusy}/>}
   <Space direction="vertical" style={{width:'100%'}}>
    <label htmlFor="business-compute-pool">计算资源池</label>
    <Space.Compact style={{width:'100%'}}>
     <Select id="business-compute-pool" aria-describedby="business-compute-pool-help" aria-label="计算资源池" style={{flex:1}} value={pool} disabled={poolBusy||loading||!compute} onChange={setPool}
      options={[{value:'',label:'未绑定计算资源池'},...(compute?.pools||[]).map(item=>({value:item.code,label:`${item.code} · ${item.enabledNodes} 台已启用 · ${item.engines.join(' / ')}`})),...(pool&&!compute?.pools.some(item=>item.code===pool)?[{value:pool,label:`${pool} · 当前不可用`}]:[])]}/>
     <Auth permission="aid:funcconfig:edit"><Button loading={poolBusy} disabled={!compute||loading||busy} onClick={()=>void savePool()}>保存资源池</Button></Auth>
    </Space.Compact>
    <div id="business-compute-pool-help">新任务从此池中选择健康机器并原子预留槽位；预览和恢复使用原机器。并发、单槽资源及维护状态在计算资源管理中配置。</div>
   </Space>
   {!loading&&options.length===0&&<Alert type="warning" message="当前业务没有可用执行入口，请检查模块适配器、已发布版本及业务模型配置。"/>}
   <Select mode="multiple" aria-label="选择场景Agent和Skill" placeholder="选择已发布的 Agent / Skill 固定版本" loading={loading} style={{width:'100%'}} disabled={busy||!view}
    value={bindings.map(b=>keyOf(b.providerCode,b))} options={[...known.entries()].map(([value,option])=>({value,label:`${option.entrypoint.reference.kind} · ${option.entrypoint.name} · v${option.entrypoint.reference.versionId}`}))}
    onChange={keys=>setBindings(keys.map((key,index)=>{const option=known.get(key)!;const prior=bindings.find(b=>keyOf(b.providerCode,b)===key);return prior?{...prior,sortOrder:index}:{providerCode:option.providerCode,...option.entrypoint.reference,packageDigest:option.entrypoint.packageDigest,defaultEntry:false,sortOrder:index};}))}/>
   <Table rowKey={b=>keyOf(b.providerCode,b)} dataSource={bindings} pagination={false} loading={loading} columns={[
    {title:'类型',dataIndex:'kind',render:kind=><Tag>{kind}</Tag>},{title:'固定入口',render:(_,b)=>known.get(keyOf(b.providerCode,b))?.entrypoint.name||b.code},{title:'版本',dataIndex:'versionId'},
    {title:'默认入口',render:(_,b)=><Checkbox checked={b.defaultEntry} disabled={busy} onChange={e=>setBindings(items=>items.map(item=>item.kind===b.kind?{...item,defaultEntry:keyOf(item.providerCode,item)===keyOf(b.providerCode,b)&&e.target.checked}:item))}>作为默认</Checkbox>},
    {title:'可用性',render:(_,b)=>{const item=view?.bindings.find(item=>keyOf(item.binding.providerCode,item.binding)===keyOf(b.providerCode,b));return item&&!item.available?<Tag color="red">{item.unavailableReason}</Tag>:<Tag>保存时核验</Tag>;}}
   ]}/>
   <Collapse items={(view?.models||[]).map(model=>({key:model.modelCode+':'+model.capabilityCode,label:`${model.modelName} · ${model.capabilityCode||'默认能力'}`,children:<Space direction="vertical" style={{width:'100%'}}>
    <Table rowKey={(r:any,index)=>r.name||r.key||String(index)} pagination={false} size="small" dataSource={model.parameterSchema||[]} columns={[{title:'参数',render:(_,r:any)=>r.label||r.name||r.key},{title:'字段',render:(_,r:any)=>r.name||r.key},{title:'类型',dataIndex:'type'},{title:'说明',dataIndex:'description'}]}/>
    {model.billing?.rules?.length?<><div>{model.billing.billingDesc}</div><Table rowKey={(r:any,index)=>r.skuCode||String(index)} size="small" pagination={false} scroll={{x:true}} dataSource={model.billing.rules}
     columns={(model.billing.columns||[]).map((column:any)=>({title:(column.label||column.title||column.key)+(column.unit?`（${column.unit}）`:''),dataIndex:column.key,render:(value:any)=>value==null?'—':typeof value==='object'?JSON.stringify(value):String(value)}))}/></>:<Alert type="warning" message="尚无可展示的权威计价明细，不能据此视为免费。"/>}
   </Space>}))}/>
  </Space>
 </Drawer>;
}
