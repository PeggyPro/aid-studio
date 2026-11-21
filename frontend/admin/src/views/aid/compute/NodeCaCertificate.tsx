import React, { useEffect, useRef, useState } from 'react';
import { Alert, Button, Space, Typography, Upload, theme } from 'antd';
import { DeleteOutlined, UploadOutlined } from '@ant-design/icons';
import { inspectComputeCertificate, type ComputeCertificateBundle } from '@/api/aid/compute';

export default function NodeCaCertificate({value,onChange,onBusyChange,disabled=false}:{
  value?:string|null;onChange?:(pem:string)=>void;onBusyChange?:(busy:boolean)=>void;disabled?:boolean;
}) {
  const {token}=theme.useToken();
  const [bundle,setBundle]=useState<ComputeCertificateBundle|null>(null),[failure,setFailure]=useState(''),[busy,setBusy]=useState(false);
  const epoch=useRef(0),verified=useRef(''),flight=useRef(false),busyCallback=useRef(onBusyChange);
  busyCallback.current=onBusyChange;
  const setLoading=(loading:boolean)=>{flight.current=loading;setBusy(loading);busyCallback.current?.(loading);};
  useEffect(()=>{
    const current=++epoch.current;
    if(!value){verified.current='';setBundle(null);setFailure('');setLoading(false);return;}
    if(verified.current===value){setLoading(false);return;}
    setLoading(true);
    inspectComputeCertificate(value).then(result=>{
      if(current!==epoch.current)return;verified.current=value;setBundle(result.data);setFailure('');
    }).catch(error=>{if(current===epoch.current){setBundle(null);setFailure(error?.msg||error?.message||'证书读取失败，请重新上传');}})
      .finally(()=>{if(current===epoch.current)setLoading(false);});
    return()=>{epoch.current++;};
  },[value]);
  useEffect(()=>()=>{epoch.current++;busyCallback.current?.(false);},[]);
  const upload=async(file:File)=>{
    if(flight.current||disabled)return false;
    const current=++epoch.current;setLoading(true);setFailure('');
    try {
      if(file.size>65536)throw Error('证书文件不能超过64 KiB');
      const pem=await file.text();
      if(pem.includes('PRIVATE KEY'))throw Error('请上传CA公共证书，不要上传私钥');
      if(!pem.includes('-----BEGIN CERTIFICATE-----'))throw Error('仅支持PEM格式的CA公共证书');
      const result=await inspectComputeCertificate(pem);
      if(current!==epoch.current)return false;
      verified.current=result.data.pem||'';setBundle(result.data);onChange?.(verified.current);
    } catch(error:any){if(current===epoch.current)setFailure((error?.msg||error?.message||'证书校验失败')+(value?'；原证书仍保留':''));}
    finally {if(current===epoch.current)setLoading(false);}
    return false;
  };
  return <Space direction="vertical" size={8} style={{width:'100%'}}>
    <Typography.Text type="secondary">公有证书通常无需上传。使用自签名或私有CA时，上传CA公共证书，仅对当前节点生效，无需重启服务。</Typography.Text>
    <Space wrap>
      <Upload accept=".crt,.pem,.cer" maxCount={1} showUploadList={false} disabled={disabled||busy} beforeUpload={upload}>
        <Button icon={<UploadOutlined/>} loading={busy} disabled={disabled}>{value?'替换CA证书':'上传CA证书'}</Button>
      </Upload>
      {value&&<Button icon={<DeleteOutlined/>} disabled={disabled||busy} onClick={()=>{epoch.current++;verified.current='';setBundle(null);setFailure('');onChange?.('');}}>移除证书</Button>}
    </Space>
    {!value&&<Typography.Text type="secondary">当前使用系统信任的证书。支持PEM格式 .crt / .pem / .cer，最多64 KiB。</Typography.Text>}
    {failure&&<Alert type="error" showIcon role="alert" message={failure}/>}
    {bundle?.certificates.map(cert=><div key={cert.sha256} style={{padding:12,border:`1px solid ${token.colorBorder}`,borderRadius:8,minWidth:0}}>
      <Typography.Paragraph ellipsis={{rows:2,tooltip:cert.subject}} style={{marginBottom:4}}>{cert.subject}</Typography.Paragraph>
      <Typography.Paragraph type="secondary" style={{marginBottom:4}}>有效期：{new Date(cert.notBefore).toLocaleDateString()} — {new Date(cert.notAfter).toLocaleDateString()}</Typography.Paragraph>
      <Typography.Text style={{display:'block',overflowWrap:'anywhere',fontFamily:'monospace',fontSize:12}}>SHA-256：{cert.sha256.match(/.{2}/g)?.join(':')}</Typography.Text>
    </div>)}
  </Space>;
}
