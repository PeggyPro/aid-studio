import NodeCaCertificate from './NodeCaCertificate';
import SceneAssistantSetting from './SceneAssistantSetting';
import React, { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Col, Descriptions, Drawer, Form, Input, InputNumber, Modal, Row, Select, Space, Steps, Table, Tag, Typography, message } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import Auth from '@/components/Auth';
import SectionTitle from '@/components/SectionTitle';
import { listComputeNodes, readComputeNode, saveComputeNode, probeComputeNode, activateComputeNode, changeComputeHealth, type ComputeNode, type ComputeConfiguration, type ComputeProbeResult } from '@/api/aid/compute';

const MiB = 1048576;
const engineOptions = [{label:'Three.js',value:'threejs'},{label:'Blender',value:'blender'}];
const defaults: ComputeConfiguration = {
  name:'',endpoint:'',poolCode:'default',credentialRef:null,engines:['threejs','blender'],
  limits:{maxConcurrent:1,engineConcurrent:{threejs:1,blender:1},taskConcurrent:{},
    capacity:{cpuMillis:1000,memoryBytes:2048*MiB,scratchBytes:4096*MiB},minimumFreeBytes:2048*MiB},
  executionTimeoutSeconds:600,queueLimit:20,storageAlias:'jobs',
};
type Rule = {code:string; maximum:number};
type SlotValues={engine:string;taskKind:string;cpuCores:number;memoryMiB:number;scratchMiB:number;timeoutSeconds:number};
type Values = {tlsCaPem?:string|null;name:string;endpoint:string;poolCode:string;credentialRef?:string;engines:string[];maxConcurrent:number;
  cpuCores:number;memoryMiB:number;scratchMiB:number;minimumFreeMiB:number;executionTimeoutSeconds:number;queueLimit:number;storageAlias:string;
  engineRules:Rule[];taskRules:Rule[];probeWorkerId?:string;probeSecret?:string;slots:SlotValues[]};
const toValues=(c:ComputeConfiguration):Values=>({...c,maxConcurrent:c.limits.maxConcurrent,cpuCores:c.limits.capacity.cpuMillis/1000,
  memoryMiB:c.limits.capacity.memoryBytes/MiB,scratchMiB:c.limits.capacity.scratchBytes/MiB,minimumFreeMiB:c.limits.minimumFreeBytes/MiB,
  credentialRef:c.credentialRef||undefined,engineRules:Object.entries(c.limits.engineConcurrent).map(([code,maximum])=>({code,maximum})),
  taskRules:Object.entries(c.limits.taskConcurrent).map(([code,maximum])=>({code,maximum})),
  slots:(c.slots||[]).map(slot=>({engine:slot.engine,taskKind:slot.taskKind,cpuCores:slot.resources.cpuMillis/1000,memoryMiB:slot.resources.memoryBytes/MiB,scratchMiB:slot.resources.scratchBytes/MiB,timeoutSeconds:slot.timeoutSeconds}))});
function rules(rows:Rule[],maximum:number,engines?:string[]) {
  const result:Record<string,number>={};
  for(const row of rows||[]){
    if(!row?.code||!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$/.test(row.code)||Object.prototype.hasOwnProperty.call(result,row.code))throw Error('分类编码不能为空、重复或包含特殊字符');
    if(!Number.isInteger(row.maximum)||row.maximum<0||row.maximum>maximum)throw Error('分类并发不能超过单机总并发，0表示不接此类任务');
    if(engines&&!engines.includes(row.code))throw Error('并发规则包含未选择的引擎');result[row.code]=row.maximum;
  }return result;
}
function quantity(value:number,multiplier:number){const result=value*multiplier;if(!Number.isSafeInteger(result)||result<0)throw Error('资源数量必须能换算为有效的整数计量单位');return result;}
function configuration(v:Values):ComputeConfiguration {const result:ComputeConfiguration={tlsCaPem:v.tlsCaPem||null,name:v.name.trim(),endpoint:v.endpoint.trim(),poolCode:v.poolCode.trim(),credentialRef:v.credentialRef?.trim()||null,
  engines:v.engines,storageAlias:v.storageAlias.trim(),executionTimeoutSeconds:v.executionTimeoutSeconds,queueLimit:v.queueLimit,
  limits:{maxConcurrent:v.maxConcurrent,engineConcurrent:rules(v.engineRules,v.maxConcurrent,v.engines),taskConcurrent:rules(v.taskRules,v.maxConcurrent),
    capacity:{cpuMillis:quantity(v.cpuCores,1000),memoryBytes:quantity(v.memoryMiB,MiB),scratchBytes:quantity(v.scratchMiB,MiB)},minimumFreeBytes:quantity(v.minimumFreeMiB,MiB)},
  slots:(v.slots||[]).map(slot=>({engine:slot.engine,taskKind:slot.taskKind,resources:{cpuMillis:quantity(slot.cpuCores,1000),memoryBytes:quantity(slot.memoryMiB,MiB),scratchBytes:quantity(slot.scratchMiB,MiB)},timeoutSeconds:slot.timeoutSeconds}))};
  const keys=new Set<string>();for(const slot of result.slots||[]){const key=JSON.stringify([slot.engine,slot.taskKind]);
    if(!v.engines.includes(slot.engine)||!slot.taskKind||keys.has(key))throw Error('槽位的引擎或任务类型无效，不能重复配置相同规格');keys.add(key);
    if(slot.resources.cpuMillis<=0||slot.resources.memoryBytes<=0||slot.resources.scratchBytes<=0||slot.resources.cpuMillis>result.limits.capacity.cpuMillis||slot.resources.memoryBytes>result.limits.capacity.memoryBytes||slot.resources.scratchBytes>result.limits.capacity.scratchBytes)throw Error('单个槽位资源不能超过机器配置额度');
    if(slot.timeoutSeconds<1||slot.timeoutSeconds>v.executionTimeoutSeconds)throw Error('槽位超时不能超过节点执行超时');
  }return result;}
const required=[{required:true,message:'请填写此项'}];
const codeRules=[...required,{pattern:/^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$/,message:'使用字母、数字、点、下划线或短横线'}];

export default function ComputeNodesPage(){
  const [rows,setRows]=useState<ComputeNode[]>([]),[total,setTotal]=useState(0),[loading,setLoading]=useState(false);
  const [query,setQuery]=useState({pageNum:1,pageSize:10,keyword:''}),[keyword,setKeyword]=useState(''),[epoch,setEpoch]=useState(0);
  const [open,setOpen]=useState(false),[editing,setEditing]=useState<ComputeNode|null>(null),[detail,setDetail]=useState<ComputeNode|null>(null);
  const [saving,setSaving]=useState(false),[failure,setFailure]=useState('');const [form]=Form.useForm<Values>();
  const [stage,setStage]=useState(0),[checking,setChecking]=useState(false),[probe,setProbe]=useState<ComputeProbeResult|null>(null);
  const [certificateBusy,setCertificateBusy]=useState(false);
  const probeFlight=useRef(false);
  const [activating,setActivating]=useState<string|null>(null);const activationFlight=useRef(false);
  const [activationTarget,setActivationTarget]=useState<ComputeNode|null>(null),[activationFailure,setActivationFailure]=useState('');
  const [healthBusy,setHealthBusy]=useState<string|null>(null);const healthFlight=useRef(false);
  const healthAction=async(row:ComputeNode,operation:'health'|'maintenance')=>{
    if(healthFlight.current)return;healthFlight.current=true;setHealthBusy(row.nodeKey);
    try{await changeComputeHealth(operation,{nodeKey:row.nodeKey,...(operation==='maintenance'?{enabled:row.state!=='MAINTENANCE'}:{})});
      message.success(operation==='health'?'节点健康检查通过':row.state==='MAINTENANCE'?'已退出维护，请检查健康后接单':'已进入维护，运行任务继续保留');}
    catch(error:any){message.error(error?.msg||error?.message||'操作未确认，请刷新检查');}
    finally{healthFlight.current=false;if(alive.current){setHealthBusy(null);setEpoch(n=>n+1);}}
  };
  const activate=(row:ComputeNode)=>{if(activationFlight.current)return;setActivationFailure('');setActivationTarget(row);};
  const confirmActivation=async()=>{const row=activationTarget;if(!row||activationFlight.current)return;
    activationFlight.current=true;setActivating(row.nodeKey);setActivationFailure('');
    try{await activateComputeNode({nodeKey:row.nodeKey,expectedRevision:row.revision});if(alive.current){setActivationTarget(null);setEpoch(n=>n+1);message.success('计算节点已确认配置生效');}}
    catch(error:any){if(alive.current)setActivationFailure(error?.msg||error?.message||'启用未确认，再次操作将先核对节点版本');}
    finally{activationFlight.current=false;if(alive.current)setActivating(null);}
  };
  const flight=useRef(false),requestId=useRef(''),alive=useRef(true),generation=useRef(0);
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[]);
  useEffect(()=>{const current=++generation.current;setLoading(true);
    listComputeNodes(query,epoch>0).then((result:any)=>{if(alive.current&&current===generation.current){setRows(result.rows||[]);setTotal(result.total||0);}})
      .catch(()=>{if(alive.current&&current===generation.current)message.error('计算节点读取失败，请重试');})
      .finally(()=>{if(alive.current&&current===generation.current)setLoading(false);});
  },[query,epoch]);
  const edit=async(row?:ComputeNode)=>{try{const current=row?(await readComputeNode(row.nodeKey) as any).data:null;
    if(!alive.current)return;setEditing(current);requestId.current=crypto.randomUUID();setFailure('');setStage(0);setProbe(null);
    form.setFieldsValue({...toValues(current?.configuration||defaults),probeWorkerId:current?.workerId||'',probeSecret:''});setOpen(true);
  }catch{message.error('节点配置读取失败');}};
  const checkConnection=async()=>{
    if(probeFlight.current||certificateBusy)return;probeFlight.current=true;setChecking(true);setFailure('');
    try{
      const v=await form.validateFields(['name','endpoint','probeWorkerId','probeSecret','tlsCaPem']);
      const result:any=await probeComputeNode({endpoint:v.endpoint.trim(),workerId:v.probeWorkerId?.trim()||'',secret:v.probeSecret||undefined,nodeKey:editing?.nodeKey,tlsCaPem:v.tlsCaPem||''});
      if(!Array.isArray(result.data?.routes)||!result.data.routes.length)throw Error('计算节点缺少执行器信息，请更新Worker后重新检测');
      if(!alive.current)return;setProbe(result.data);form.setFieldValue('probeSecret','');
      if(!editing){form.setFieldValue('engines',result.data.engines);form.setFieldValue('engineRules',result.data.engines.map((code:string)=>({code,maximum:1})));
        form.setFieldValue('slots',result.data.routes.map((route:{engine:string;taskKind:string})=>({...route,cpuCores:1,memoryMiB:1024,scratchMiB:1024,timeoutSeconds:600})));}
      setStage(1);
    }catch(error:any){if(alive.current&&!error?.errorFields)setFailure(error?.msg||error?.message||'连接检测失败，请检查计算服务是否在线');}
    finally{probeFlight.current=false;if(alive.current)setChecking(false);}
  };
  const advance=async()=>{if(stage===0){await checkConnection();return;}
    if(stage===1){try{configuration(await form.validateFields());setFailure('');setStage(2);}catch(error:any){if(!error?.errorFields)setFailure(error?.message||'请核对资源配置');}return;}
    await save();};
  const save=async()=>{if(flight.current)return;flight.current=true;setSaving(true);setFailure('');
    try{if(!probe)throw Error('请先检测计算节点连接');const v=await form.validateFields();await saveComputeNode({nodeKey:editing?.nodeKey||null,expectedRevision:editing?.revision||null,clientRequestId:requestId.current,configuration:configuration(v),probeId:probe.proofId});
      if(alive.current){setOpen(false);setEpoch(n=>n+1);message.success('配置草稿已保存，验证并启用后才影响新任务');}
    }catch(error:any){if(alive.current&&!error?.errorFields)setFailure(error?.msg||error?.message||'保存未确认，请保留当前内容并重试');}
    finally{flight.current=false;if(alive.current)setSaving(false);}};
  const number=(name:keyof Values,label:string,min:number,max:number,unit:string,step=1)=><Col xs={24} md={12} key={name}><Form.Item name={name} label={label} rules={required}><InputNumber min={min} max={max} step={step} addonAfter={unit} style={{width:'100%'}}/></Form.Item></Col>;
  const ruleList=(name:'engineRules'|'taskRules',label:string)=><Form.List name={name}>{(fields,{add,remove})=><Card size="small" title={label} style={{marginBottom:16}}>
    {fields.map(field=><Row gutter={12} key={field.key} align="middle"><Col xs={12} md={13}><Form.Item name={[field.name,'code']} rules={codeRules} label="分类编码"><Input placeholder={name==='engineRules'?'threejs':'build'}/></Form.Item></Col>
      <Col xs={9} md={8}><Form.Item name={[field.name,'maximum']} dependencies={['maxConcurrent']} rules={[...required,{validator:(_,value)=>Number.isInteger(value)&&value>=0&&value<=form.getFieldValue('maxConcurrent')?Promise.resolve():Promise.reject(new Error('分类并发不能超过单机总并发，0表示不接此类任务'))}]} label="并发上限"><InputNumber min={0} max={10000} style={{width:'100%'}}/></Form.Item></Col>
      <Col span={3}><Button aria-label={`删除${label}第${field.name+1}项`} icon={<DeleteOutlined/>} onClick={()=>remove(field.name)}/></Col></Row>)}
    <Button icon={<PlusOutlined/>} onClick={()=>add({code:'',maximum:1})}>添加限制</Button>
  </Card>}</Form.List>;
  return <div className="app-container"><Space direction="vertical" size="middle" style={{width:'100%'}}>
    <SceneAssistantSetting refreshKey={epoch} />
    <Card><Space direction="vertical" size="middle" style={{width:'100%'}}>
    <SectionTitle title="计算节点"/>
    <Typography.Paragraph type="secondary">管理计算服务的连接与资源额度。配置值是调度上限，实际可用能力以节点验证和资源上报为准。</Typography.Paragraph>
    <Space wrap><Input aria-label="搜索计算节点" placeholder="节点名称" value={keyword} maxLength={100} onChange={e=>setKeyword(e.target.value)} onPressEnter={()=>setQuery({...query,pageNum:1,keyword})}/>
      <Button icon={<SearchOutlined/>} onClick={()=>setQuery({...query,pageNum:1,keyword})}>查询</Button><Button icon={<ReloadOutlined/>} loading={loading} onClick={()=>setEpoch(n=>n+1)}>刷新</Button>
      <Auth permission="aid:compute:edit"><Button type="primary" icon={<PlusOutlined/>} onClick={()=>void edit()}>新增节点</Button></Auth></Space>
    <Table rowKey="nodeKey" dataSource={rows} loading={loading} scroll={{x:980}} pagination={{current:query.pageNum,pageSize:query.pageSize,total,showSizeChanger:true,onChange:(pageNum,pageSize)=>setQuery({...query,pageNum,pageSize})}}
      columns={[
        {title:'节点名称',dataIndex:['configuration','name'],render:(name:string,row:ComputeNode)=><Button type="link" onClick={()=>setDetail(row)}>{name}</Button>},
        {title:'资源池',dataIndex:['configuration','poolCode']},
        {title:'支持引擎',render:(_,row:ComputeNode)=>row.configuration.engines.join('、')},
        {title:'总并发上限',dataIndex:['configuration','limits','maxConcurrent']},
        {title:'资源额度',render:(_,row:ComputeNode)=>`${row.configuration.limits.capacity.cpuMillis/1000} 核 / ${row.configuration.limits.capacity.memoryBytes/MiB} MiB`},
        {title:'配置状态',render:(_,row:ComputeNode)=><Space wrap><Tag color={row.state==='ACTIVE'?'green':undefined}>{row.state==='DRAFT'?'未启用':row.state==='ACTIVE'?'已启用':row.state==='MAINTENANCE'?'维护中':row.state}</Tag><span>{row.verifiedAt?'连接已验证':'待检测'} · 草稿 v{row.revision}{row.activeRevision?` / 生效 v${row.activeRevision}`:''}</span></Space>},
        {title:'运行健康',width:180,render:(_,row:ComputeNode)=><Space direction="vertical" size={0}><Tag color={row.healthStatus==='ONLINE'&&row.healthFresh?'green':row.healthStatus==='UNAVAILABLE'?'red':undefined}>{row.healthStatus==='ONLINE'?(row.healthFresh?'在线':'待复核'):row.healthStatus==='UNAVAILABLE'?'不可用':'待检查'}</Tag><span>{row.healthCheckedAt?new Date(row.healthCheckedAt).toLocaleString():'尚无健康回执'}</span></Space>},
        {title:'操作',fixed:'right',width:290,render:(_,row:ComputeNode)=><Auth permission="aid:compute:edit"><Space wrap><Button type="link" icon={<EditOutlined/>} onClick={()=>void edit(row)}>编辑</Button><Button type="link" disabled={!row.verifiedAt||row.activeRevision===row.revision} loading={activating===row.nodeKey} onClick={()=>activate(row)}>启用</Button><Button type="link" disabled={!row.activeRevision} loading={healthBusy===row.nodeKey} onClick={()=>void healthAction(row,'health')}>检查健康</Button><Button type="link" disabled={!row.activeRevision||healthBusy!==null} onClick={()=>void healthAction(row,'maintenance')}>{row.state==='MAINTENANCE'?'退出维护':'进入维护'}</Button></Space></Auth>},
      ]}/>
    </Space></Card></Space>
  <Modal title="启用计算节点配置" open={activationTarget!==null} okText="下发并启用" cancelText="取消"
    onOk={()=>void confirmActivation()} confirmLoading={activating!==null} maskClosable={activating===null}
    cancelButtonProps={{disabled:activating!==null}} onCancel={()=>{if(!activationFlight.current){setActivationTarget(null);setActivationFailure('');}}}>
    <Typography.Paragraph>将配置 v{activationTarget?.revision} 下发到 {activationTarget?.configuration.name}。收到机器确认后才标记生效；运行中任务保留原额度。</Typography.Paragraph>
    {activationFailure&&<Alert role="alert" type="error" showIcon message={activationFailure}/>}
  </Modal>
  <Modal title={<div>{editing?'编辑计算节点':'添加计算资源'}<Steps current={stage} size="small" items={[{title:'检测连接'},{title:'资源与并发'},{title:'核对配置'}]} style={{marginTop:16}}/></div>} open={open} width={820} style={{top:24}} styles={{body:{maxHeight:'calc(100dvh - 240px)',overflowY:'auto',paddingRight:8}}} onCancel={()=>{if(!saving&&!checking&&!certificateBusy){form.setFieldValue('probeSecret','');setOpen(false);}}} onOk={()=>void advance()} confirmLoading={saving||checking||certificateBusy} okText={stage===0?'检测连接并继续':stage===1?'核对配置':'保存配置草稿'} cancelButtonProps={{disabled:saving||checking||certificateBusy}} maskClosable={!saving&&!checking&&!certificateBusy}>
    <Alert type="info" showIcon message="保存草稿不会立即接单，也不会修改正在执行任务的配置。" style={{marginBottom:16}}/>
    {failure&&<Alert role="alert" type="error" showIcon message={failure} style={{marginBottom:16,position:'sticky',top:0,zIndex:2}}/>}
    <Form form={form} layout="vertical" disabled={saving||checking||certificateBusy} preserve onValuesChange={values=>{if(['endpoint','probeWorkerId','probeSecret','tlsCaPem'].some(key=>key in values)){setProbe(null);setStage(0);}}}>
      <div hidden={stage!==0}>
      <Row gutter={16}><Col xs={24} md={12}><Form.Item name="name" label="节点名称" rules={required}><Input maxLength={100}/></Form.Item></Col>
      <Col xs={24} md={12}><Form.Item name="poolCode" label="资源池编码" rules={codeRules}><Input maxLength={100}/></Form.Item></Col></Row>
      <Form.Item name="endpoint" label="计算服务地址" rules={required} extra="填写HTTPS根地址及端口，例如 https://compute.example.com:9443；本机验收可用HTTP。"><Input maxLength={500}/></Form.Item>
      {open&&<Form.Item name="tlsCaPem" label="节点CA证书（可选）"><NodeCaCertificate onBusyChange={setCertificateBusy} disabled={saving||checking}/></Form.Item>}
      <Form.Item name="probeWorkerId" label="计算服务标识" rules={codeRules} extra="填写安装Worker时生成的服务标识，检测时会核对机器身份。"><Input maxLength={100}/></Form.Item>
      <Form.Item name="probeSecret" label="认证密钥" rules={[{validator:(_,value)=>value||editing?.credentialConfigured||probe?Promise.resolve():Promise.reject(new Error('首次检测需要填写认证密钥'))}]} extra={editing?.credentialConfigured?'已保存认证信息，留空沿用；填写新密钥会重新验证。':'检测成功后加密保存，管理页面不回显密钥。'}><Input.Password autoComplete="new-password" maxLength={1024}/></Form.Item>
      </div>
      <div hidden={stage!==1}>
      {probe&&<Descriptions size="small" bordered column={2} title="机器实际探测结果" style={{marginBottom:16}} items={[
        {key:'cpu',label:'进程可用CPU上限',children:`${probe.resources.cpuMillis/1000} 核`},
        {key:'memory',label:'可见内存上限',children:`${Math.floor(probe.resources.memoryBytes/MiB)} MiB`},
        {key:'disk',label:'当前剩余磁盘',children:`${Math.floor(probe.resources.scratchFreeBytes/MiB)} MiB`},
        {key:'running',label:'当前运行任务',children:probe.activeJobs},
      ]}/>}
      <Form.Item name="storageAlias" label="执行存储卷标识" rules={codeRules} extra="由节点验证可用目录，不能通过任务内容指定系统路径。"><Input maxLength={100}/></Form.Item>
      <Form.Item name="engines" label="支持引擎" rules={required}><Select mode="tags" options={engineOptions}/></Form.Item>
      <Typography.Title level={5}>并发与资源额度</Typography.Title>
      <Typography.Paragraph type="secondary">总并发与分类限制同时生效。分类上限不能超过总并发，设为0时不接该类任务；未单独配置的类别仍受总并发和资源限制。</Typography.Paragraph>
      <Row gutter={16}>{number('maxConcurrent','单机最高并发',1,10000,'个')}{number('cpuCores','可调度CPU',0.001,100000,'核',0.5)}
        {number('memoryMiB','可调度内存',1,Number.MAX_SAFE_INTEGER/MiB,'MiB',256)}{number('scratchMiB','任务临时空间额度',1,Number.MAX_SAFE_INTEGER/MiB,'MiB',1024)}
        {number('minimumFreeMiB','磁盘最低剩余量',0,Number.MAX_SAFE_INTEGER/MiB,'MiB',1024)}{number('queueLimit','排队上限',0,100000,'个')}
        {number('executionTimeoutSeconds','执行超时',1,86400,'秒')}</Row>
      {ruleList('engineRules','分引擎并发')}{ruleList('taskRules','分任务并发')}
      <Form.List name="slots">{(fields,{add,remove})=><Card size="small" title="每个执行槽位的资源规格" extra={<Button icon={<PlusOutlined/>} onClick={()=>add({engine:form.getFieldValue('engines')?.[0],taskKind:'build',cpuCores:1,memoryMiB:1024,scratchMiB:1024,timeoutSeconds:600})}>添加规格</Button>}>
        <Typography.Paragraph type="secondary">一个任务占用一个槽位，同时预留这里配置的CPU、内存和临时空间。不同引擎、任务可设置不同规格，不平均分摊整台机器。</Typography.Paragraph>
        {fields.map(field=><Card size="small" key={field.key} style={{marginBottom:12}} title={`槽位规格 ${field.name+1}`} extra={<Button aria-label={`删除槽位规格${field.name+1}`} icon={<DeleteOutlined/>} onClick={()=>remove(field.name)}/>}>
          <Row gutter={12}><Col xs={24} md={12}><Form.Item name={[field.name,'engine']} label="引擎" rules={required}><Select options={(probe?.engines||[]).map(value=>({value,label:engineOptions.find(item=>item.value===value)?.label||value}))}/></Form.Item></Col>
          <Col xs={24} md={12}><Form.Item name={[field.name,'taskKind']} label="任务类型" rules={required}><Select options={(probe?.taskKinds||[]).map(value=>({value,label:({build:'建模构建',preview:'场景预览',resource:'素材解析'} as Record<string,string>)[value]||value}))}/></Form.Item></Col>
          <Col xs={12} md={6}><Form.Item name={[field.name,'cpuCores']} label="单槽位CPU" rules={required}><InputNumber min={0.001} step={0.5} addonAfter="核" style={{width:'100%'}}/></Form.Item></Col>
          <Col xs={12} md={6}><Form.Item name={[field.name,'memoryMiB']} label="单槽位内存" rules={required}><InputNumber min={1} step={256} addonAfter="MiB" style={{width:'100%'}}/></Form.Item></Col>
          <Col xs={12} md={6}><Form.Item name={[field.name,'scratchMiB']} label="单槽位临时空间" rules={required}><InputNumber min={1} step={256} addonAfter="MiB" style={{width:'100%'}}/></Form.Item></Col>
          <Col xs={12} md={6}><Form.Item name={[field.name,'timeoutSeconds']} label="单任务超时" rules={required}><InputNumber min={1} max={86400} addonAfter="秒" style={{width:'100%'}}/></Form.Item></Col></Row>
          <Form.Item noStyle shouldUpdate>{()=>{const slot=form.getFieldValue(['slots',field.name]) as SlotValues|undefined;
            const values=slot?[form.getFieldValue('maxConcurrent'),(form.getFieldValue('engineRules')||[]).find((r:Rule)=>r.code===slot.engine)?.maximum??form.getFieldValue('maxConcurrent'),(form.getFieldValue('taskRules')||[]).find((r:Rule)=>r.code===slot.taskKind)?.maximum??form.getFieldValue('maxConcurrent'),Math.floor(form.getFieldValue('cpuCores')/slot.cpuCores),Math.floor(form.getFieldValue('memoryMiB')/slot.memoryMiB),Math.floor(form.getFieldValue('scratchMiB')/slot.scratchMiB)]:[];
            return <Typography.Text type="secondary">按当前额度，空闲时此类任务最多容纳 {values.length&&values.every(v=>Number.isFinite(v)&&v>=0)?Math.min(...values):'—'} 个槽位；实际接单还需扣除实时占用。</Typography.Text>;
          }}</Form.Item>
        </Card>)}
      </Card>}</Form.List>
      </div>
      {stage===2&&probe&&<Space direction="vertical" style={{width:'100%'}}>
        <Descriptions bordered column={1} items={[
          {key:'name',label:'节点名称',children:form.getFieldValue('name')},{key:'endpoint',label:'服务地址',children:form.getFieldValue('endpoint')},
          {key:'worker',label:'已验证服务标识',children:probe.workerId},{key:'concurrency',label:'最高并发',children:form.getFieldValue('maxConcurrent')},
          {key:'memory',label:'内存额度',children:`${form.getFieldValue('memoryMiB')} MiB`},
          {key:'checked',label:'最近检测',children:new Date(probe.checkedAt).toLocaleString()},
        ]}/>
        <Alert type="info" showIcon message={probe.resourceLimitsVerified?'连接与资源检测已通过，配置将保存为草稿。':'节点在线；系统级资源隔离尚未验证，保存配置后不会自动启用接单。'}/>
      </Space>}
      {stage>0&&<Button style={{marginTop:12}} onClick={()=>setStage(stage-1)}>上一步</Button>}
    </Form>
  </Modal>
  <Drawer title="计算节点详情" open={!!detail} onClose={()=>setDetail(null)} width={520}>{detail&&<>
    <Descriptions column={1} bordered items={[
      {key:'name',label:'名称',children:detail.configuration.name},{key:'key',label:'节点标识',children:detail.nodeKey},
      {key:'endpoint',label:'服务地址',children:detail.configuration.endpoint},{key:'pool',label:'资源池',children:detail.configuration.poolCode},
      {key:'active',label:'生效配置',children:detail.activeRevision?`v${detail.activeRevision}`:'尚未验证启用'},
      {key:'draft',label:'草稿版本',children:`v${detail.revision}`},{key:'limit',label:'总并发上限',children:detail.configuration.limits.maxConcurrent},
      {key:'engines',label:'引擎限制',children:Object.entries(detail.configuration.limits.engineConcurrent).map(([key,value])=>`${key}: ${value}`).join('；')||'沿用总并发'},
      {key:'worker',label:'计算服务标识',children:detail.workerId||'尚未检测'},
      {key:'runtime',label:'实际资源与环境',children:detail.verification?`${detail.verification.resources.cpuMillis/1000} 核可用上限 / ${Math.floor(detail.verification.resources.memoryBytes/MiB)} MiB内存 / ${Math.floor(detail.verification.resources.scratchFreeBytes/MiB)} MiB剩余磁盘`:'等待节点验证与上报'},
      {key:'credential',label:'认证信息',children:detail.credentialConfigured?'已加密保存，不回显':'尚未保存'},
      {key:'checked',label:'最近连接检测',children:detail.verification?new Date(detail.verification.checkedAt).toLocaleString():'尚未检测'},
      {key:'slots',label:'槽位规格',children:(detail.configuration.slots||[]).map(slot=><p key={slot.engine+':'+slot.taskKind}>{slot.engine} / {slot.taskKind}：{slot.resources.cpuMillis/1000} 核，{slot.resources.memoryBytes/MiB} MiB内存，{slot.resources.scratchBytes/MiB} MiB临时空间</p>)},
    ]}/><Alert style={{marginTop:16}} type="info" message="本页配置额度不代表机器物理配置或已生效的操作系统隔离限制。"/>
  </>}</Drawer>
  </div>;
}
