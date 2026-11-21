import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
import {spawn} from 'node:child_process';
import {createServer} from 'node:net';
import {BuildError} from './errors.mjs';
import {runProcessDirect,withProcessIsolation} from './process.mjs';
import {buildLocalDevelopment} from './build.mjs';
import {createPreviewExecutor} from './preview-executor.mjs';
import {importOutputs} from './native-isolation.mjs';

const packageRoot=path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const inside=(root,file)=>file===root||file.startsWith(root+path.sep);
const fail=code=>{throw new BuildError(code,'容器隔离配置或执行回执未通过验证');};
const integer=(n,min,max)=>Number.isSafeInteger(n)&&n>=min&&n<=max;
const executable='/usr/local/bin/docker',endpoint='unix:///var/run/docker.sock';
const hostEnv={PATH:'/usr/local/bin:/usr/bin:/bin',HOME:'/tmp',GOMAXPROCS:'2'};
async function docker(args,allowFailure=false){
 const result=await new Promise((resolve,reject)=>{
  const child=spawn(executable,['--host',endpoint,...args],{env:hostEnv,stdio:['ignore','pipe','pipe']});
  let output='',overflow=false;const timer=setTimeout(()=>child.kill('SIGKILL'),20000);
  child.stdout.on('data',b=>{if(output.length+b.length>131072){overflow=true;child.kill('SIGKILL');}else output+=b.toString();});
  child.stderr.resume();child.on('error',reject);child.on('close',code=>{clearTimeout(timer);resolve({code,output,overflow});});
 });
 if(!allowFailure&&(result.code!==0||result.overflow))fail('CONTAINER_CONTROL_FAILED');
 return result;
}
export async function createContainerLauncher(profile){
 const c=profile.containerIsolation,executionRoot=await fs.realpath(profile.executionRoot);
 if(process.platform!=='linux'||!c||!/^sha256:[a-f0-9]{64}$/.test(c.image??'')
   ||!integer(c.uid,1000,60000)||!integer(c.gid,1000,60000)||!integer(c.cpuMillis,100,64000)
   ||!integer(c.memoryBytes,268435456,64*1024**3)||!integer(c.scratchBytes,50331648,16*1024**3)
   ||!integer(c.tasksMax,16,1024)||!Array.isArray(c.runtimeRoots)||c.runtimeRoots.length>16||!Array.isArray(c.executables))fail('CONTAINER_CONFIGURATION_INVALID');
 if(process.getuid()!==c.uid)fail('CONTAINER_CONTROLLER_UID_INVALID');
 const protectedPaths=[profile.stateRoot,profile.executionRoot,profile.credentialFile,profile.tlsKeyFile].filter(Boolean).map(x=>path.resolve(x));
 const roots=[packageRoot];
 for(const root of c.runtimeRoots){
  if(typeof root!=='string'||!path.isAbsolute(root)||root.split('/').filter(Boolean).length<2||/[,\r\n\0]/.test(root)
    ||await fs.realpath(root)!==root)fail('CONTAINER_RUNTIME_ROOT_INVALID');
  roots.push(root);
 }
 for(const root of roots)if(protectedPaths.some(p=>inside(root,p)||inside(p,root)))fail('CONTAINER_RUNTIME_ROOT_UNSAFE');
 const executables=new Set();for(const value of c.executables){
  if(typeof value!=='string'||!path.isAbsolute(value))fail('CONTAINER_EXECUTABLE_INVALID');
  const real=await fs.realpath(value);if(!inside('/usr',real)&&!roots.some(r=>inside(r,real)))fail('CONTAINER_EXECUTABLE_UNMOUNTED');executables.add(real);
 }
 const environments=new Map();
 for(const [binary,env] of Object.entries(c.environments??{})){
  const real=await fs.realpath(binary);if(!executables.has(real)||!env||typeof env!=='object'||Array.isArray(env))fail('CONTAINER_ENVIRONMENT_INVALID');
  for(const [key,value] of Object.entries(env)){
   if(!['LD_LIBRARY_PATH','BLENDER_SYSTEM_RESOURCES','BLENDER_SYSTEM_SCRIPTS','BLENDER_SYSTEM_DATAFILES','BLENDER_SYSTEM_PYTHON'].includes(key)||typeof value!=='string'||value.length>4096)fail('CONTAINER_ENVIRONMENT_INVALID');
   for(const p of value.split(':')){const resolved=await fs.realpath(p);if(!path.isAbsolute(p)||!inside('/usr',resolved)&&!roots.some(r=>inside(r,resolved)))fail('CONTAINER_ENVIRONMENT_UNMOUNTED');}
  }environments.set(real,{...env});
 }
 const info=JSON.parse((await docker(['info','--format','{{json .}}'])).output);
 if(info.OSType!=='linux'||!info.SecurityOptions?.some(x=>/^name=seccomp(?:,|$)/.test(x)))fail('CONTAINER_SECCOMP_REQUIRED');
 if(JSON.parse((await docker(['image','inspect',c.image])).output)[0].Id!==c.image)fail('CONTAINER_IMAGE_MISMATCH');
 return async function launch(command,args,options,reservation){
  const binary=await fs.realpath(command);if(!executables.has(binary))fail('CONTAINER_EXECUTABLE_UNAPPROVED');
  const cwd=await fs.realpath(options.cwd),rel=path.relative(executionRoot,cwd);
  if(!rel||rel.startsWith('..')||path.isAbsolute(rel)||/[,\r\n\0]/.test(cwd))fail('CONTAINER_JOB_SCOPE_INVALID');
  const folder=path.join(executionRoot,rel.split(path.sep)[0]),output=await fs.realpath(path.dirname(options.logFile));
  if(!inside(folder,output)||await fs.realpath(folder)!==folder)fail('CONTAINER_JOB_SCOPE_INVALID');
  const r=reservation?.resources??c;
  for(const key of ['cpuMillis','memoryBytes','scratchBytes'])if(!integer(r[key],1,c[key]))fail('CONTAINER_SLOT_LIMIT_INVALID');
  const scratchBytes=Math.floor(Math.min(r.scratchBytes/3,r.memoryBytes/2,512*1024**2));if(scratchBytes<16*1024**2)fail('CONTAINER_SLOT_LIMIT_INVALID');
  const control=await fs.mkdtemp(path.join(executionRoot,'.container-control-')),file=path.join(control,'control.json'),archive=path.join(control,'export.bin');
  const name='aid-scene-'+randomUUID(),readonly=path.basename(cwd)==='input'?cwd:null;
  const env={PATH:'/usr/local/bin:/usr/bin:/bin',...(environments.get(binary)??{})};
  for(const key of ['CLASSPATH','PYTHONNOUSERSITE','PYTHONUTF8'])if(typeof options.env?.[key]==='string')env[key]=options.env[key];
  if(!integer(options.timeoutMs,1,86400000))fail('CONTAINER_TIMEOUT_INVALID');
  await fs.writeFile(file,JSON.stringify({folder,output,cwd,readonlyInput:readonly,scratchBytes,timeoutMs:options.timeoutMs+1000,threads:Math.max(1,Math.ceil(r.cpuMillis/1000)),argv:[binary,...args],env,logFile:options.logFile}),{flag:'wx',mode:0o600});
  const create=['create','--name',name,'--pull','never','--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges',
   '--user',`${c.uid}:${c.gid}`,'--memory',String(r.memoryBytes),'--memory-swap',String(r.memoryBytes),'--cpus',String(r.cpuMillis/1000),
   '--pids-limit',String(c.tasksMax),'--cgroupns','private','--init','--log-driver','none','--workdir','/',
   '--mount',`type=bind,source=${folder},target=/aid-seed,readonly`,'--mount',`type=bind,source=${file},target=/aid-control.json,readonly`,
   '--tmpfs',`${folder}:rw,noexec,nosuid,nodev,size=${scratchBytes},uid=${c.uid},gid=${c.gid},mode=0700`,
   '--tmpfs','/tmp:rw,noexec,nosuid,nodev,size=1048576,mode=1777'];
  for(const root of [...new Set(roots)])create.push('--mount',`type=bind,source=${root},target=${root},readonly`);
  if(readonly)create.push('--mount',`type=bind,source=${readonly},target=${readonly},readonly`);
  create.push('--entrypoint','/usr/bin/python3',c.image,path.join(packageRoot,'src/native-supervisor.py'));
  const kill=()=>{docker(['kill',name],true).catch(()=>{});};
  try{
   await docker(create);
   const observed=JSON.parse((await docker(['inspect',name])).output)[0],h=observed.HostConfig;
   if(h.Memory!==r.memoryBytes||h.MemorySwap!==r.memoryBytes||h.NanoCpus!==r.cpuMillis*1000000||h.PidsLimit!==c.tasksMax
     ||h.NetworkMode!=='none'||!h.ReadonlyRootfs||h.Privileged||!h.CapDrop?.includes('ALL')||observed.Config.User!==`${c.uid}:${c.gid}`
     ||!h.SecurityOpt?.some(x=>x.startsWith('no-new-privileges')))fail('CONTAINER_LIMITS_NOT_APPLIED');
   await runProcessDirect(executable,['--host',endpoint,'start','--attach',name],{...options,env:hostEnv,exportPipe:{file:archive,maxBytes:scratchBytes+1024*2052+32,fromStdout:true},onTerminate:kill});
   await importOutputs(archive,output,scratchBytes);
  }catch(error){
   const state=await docker(['inspect','--format','{{json .State}}',name],true);
   if(!options.signal?.aborted&&state.code===0&&JSON.parse(state.output).OOMKilled)throw new BuildError('EXECUTION_MEMORY_LIMIT','任务超过内存上限，隔离容器已终止');
   throw error;
  }finally{
   await docker(['rm','--force',name],true);
   const remaining=await docker(['ps','--all','--filter','name=^/'+name+'$','--format','{{.ID}}'],true);
   if(remaining.code!==0||remaining.output.trim()){const error=new BuildError('WORKER_TERMINATION_UNCONFIRMED','隔离容器终止状态待核实');error.outcomeUnknown=true;throw error;}
   await fs.rm(control,{recursive:true,force:true});
  }
 };
}
export async function createExecutor(profile){
 const launch=await createContainerLauncher(profile),folder=await fs.mkdtemp(path.join(await fs.realpath(profile.executionRoot),'container-probe-'));
 const listener=createServer(),canary=path.join(profile.stateRoot,'.container-probe-'+randomUUID());
 try{
  await new Promise((resolve,reject)=>{listener.once('error',reject);listener.listen(0,'127.0.0.1',resolve);});await fs.writeFile(canary,'private-probe',{flag:'wx',mode:0o600});
  await launch('/usr/bin/python3',['-c','import pathlib,socket;assert not pathlib.Path('+JSON.stringify(canary)+').exists();assert not pathlib.Path("/var/run/docker.sock").exists();s=socket.socket();s.settimeout(1);assert s.connect_ex(("127.0.0.1",'+listener.address().port+'))!=0'],{cwd:folder,env:{},timeoutMs:20000,logFile:path.join(folder,'probe.log'),logBytes:65536});
  for(const [i,binary,args] of [[0,process.execPath,['--version']],[1,profile.blenderExecutable,['--background','--factory-startup','--disable-autoexec','--python-expr','import bpy;print(bpy.app.version_string)']]])
   await launch(binary,args,{cwd:folder,env:{},timeoutMs:30000,logFile:path.join(folder,'runtime-'+i+'.log'),logBytes:65536});
 }finally{if(listener.listening)await new Promise(resolve=>listener.close(resolve));await fs.rm(canary,{force:true});await fs.rm(folder,{recursive:true,force:true});}
 const scoped=(operation,job,context)=>withProcessIsolation((command,args,options)=>launch(command,args,options,context.resourceReservation),()=>operation(job,context));
 const preview=createPreviewExecutor({blenderExecutable:profile.blenderExecutable,localDevelopmentEnabled:true});
 return {securityMode:'VERIFIED_ISOLATION',resourceLimits:{cpu:true,memory:true,disk:true},
  execute:(job,context)=>scoped(async(job,context)=>{const result=await buildLocalDevelopment(job,{...context,blenderExecutable:profile.blenderExecutable,localDevelopmentEnabled:true});result.result.executionMode='CONTAINER_ISOLATED';return result;},job,context),
  preview:(job,context)=>scoped(preview,job,context)};
}
