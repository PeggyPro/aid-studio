import React,{useEffect,useRef,useState} from 'react';
import {Alert,Button,Checkbox,Collapse,InputNumber,Space,message} from 'antd';
import Auth from '@/components/Auth';
import {request} from '@/utils/request';
import {getToken} from '@/utils/auth';
import {sharedReadRequest,invalidateSharedReads} from '@/utils/sharedReadRequest';
type Field={key:string;name:string;unit:string;defaultValue:number;minimum:number;maximum:number};
type View={sceneCode:string;revision:string;supported:boolean;fields:{definition:Field;value:number|null;effectiveValue:number|null;invalid:boolean}[]};
const flights=new Map<string,Promise<any>>();
function save(data:{sceneCode:string;expectedRevision:string;values:Record<string,number|null>}){
 const key=JSON.stringify([getToken(),data]),active=flights.get(key);if(active)return active;
 const pending=request({url:'/aid/funcconfig/entrypoints/storage-policy',method:'post',data,headers:{repeatSubmit:false}})
  .then(value=>{invalidateSharedReads('/aid/funcconfig/entrypoints');return value;}).finally(()=>flights.delete(key));flights.set(key,pending);return pending;
}
export default function BusinessStoragePolicyPanel({sceneCode,onBusyChange}:{sceneCode:string;onBusyChange:(busy:boolean)=>void}){
 const [view,setView]=useState<View|null>(null),[values,setValues]=useState<Record<string,number|null>>({}),[busy,setBusy]=useState(false),[failure,setFailure]=useState('');
 const pending=useRef(false),generation=useRef(0);
 useEffect(()=>{const current=++generation.current;setView(null);setFailure('');
  sharedReadRequest('/aid/funcconfig/entrypoints/'+encodeURIComponent(sceneCode)+'/storage-policy',undefined,true)
   .then((result:any)=>{if(current===generation.current){setView(result.data);setValues(Object.fromEntries(result.data.fields.map((f:any)=>[f.definition.key,f.value??null])));}})
   .catch((error:any)=>{if(current===generation.current)setFailure(error?.msg||error?.message||'工作区存储配置读取失败');});
  return()=>{generation.current++;};
 },[sceneCode]);
 const commit=async()=>{if(pending.current||!view)return;
  if(view.fields.some(({definition:f})=>values[f.key]!==null&&(!Number.isSafeInteger(values[f.key])||Number(values[f.key])<f.minimum||Number(values[f.key])>f.maximum))){setFailure('请填写允许范围内的整数，或选择继承默认值');return;}
  pending.current=true;setBusy(true);onBusyChange(true);setFailure('');const current=generation.current;
  try{const result:any=await save({sceneCode,expectedRevision:view.revision,values});if(current===generation.current){setView(result.data);message.success('工作区存储配置已保存');}}
  catch(error:any){if(current===generation.current)setFailure(error?.msg||error?.message||'保存未确认，已保留填写内容');}
  finally{pending.current=false;onBusyChange(false);if(current===generation.current)setBusy(false);}
 };
 if(!view?.supported&&!failure)return null;
 return <Collapse items={[{key:'storage-policy',label:'工作区存储与容量',children:<Space direction="vertical" size="middle" style={{width:'100%'}}>
  <Alert type="info" showIcon message="配置仅限制后续新增写入。降低额度不会删除历史版本或中断已登记的上传。执行临时空间继续由计算节点槽位配置。"/>
  {failure&&<Alert type="error" showIcon message={failure}/>}
  {view?.fields.map(({definition:f,invalid})=><div key={f.key} style={{display:'grid',gridTemplateColumns:'minmax(180px,1fr) minmax(180px,1fr)',gap:8}}>
   <label htmlFor={'storage-'+f.key.replace(/\./g,'-')}>{f.name}（{f.unit}）{invalid&&<span style={{color:'var(--ant-color-error)'}}> 当前配置无效</span>}</label>
   <Space direction="vertical" size={4}>
    <Checkbox disabled={busy} checked={values[f.key]===null} onChange={event=>setValues(v=>({...v,[f.key]:event.target.checked?null:f.defaultValue}))}>继承默认值：{f.defaultValue.toLocaleString()}</Checkbox>
    <InputNumber id={'storage-'+f.key.replace(/\./g,'-')} aria-label={f.name} min={f.minimum} max={f.maximum} precision={0} value={values[f.key]} disabled={busy||values[f.key]===null} style={{width:'100%'}} onChange={value=>setValues(v=>({...v,[f.key]:value}))}/>
    <span style={{color:'var(--ant-color-text-secondary)',fontSize:12}}>范围 {f.minimum.toLocaleString()}–{f.maximum.toLocaleString()}</span>
   </Space>
  </div>)}
  <Auth permission="aid:funcconfig:edit"><Button loading={busy} disabled={!view} onClick={()=>void commit()}>保存存储配置</Button></Auth>
 </Space>}]} />;
}
