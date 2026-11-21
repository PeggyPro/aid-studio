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
import {createResourceExecutor} from './resource-remote-executor.mjs';

const packageRoot=path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const inside=(root,file)=>file===root||file.startsWith(root+path.sep);
const fail=code=>{throw new BuildError(code,'原生隔离执行配置或回执未通过验证');};
const integer=(value,min,max)=>Number.isSafeInteger(value)&&value>=min&&value<=max;

function systemctlResult(args,env) {
  return new Promise(resolve=>{
    const child=spawn('/usr/bin/systemctl',['--user',...args],{env,stdio:['ignore','pipe','ignore']});
    const timer=setTimeout(()=>child.kill('SIGKILL'),8000);let failed=false,output='';
    child.stdout.on('data',chunk=>{output=(output+chunk.toString()).slice(0,1024);});
    child.on('error',()=>{failed=true;});child.on('close',code=>{clearTimeout(timer);resolve({code:failed?-1:code,output:output.trim()});});
  });
}
async function systemctl(args,env){return (await systemctlResult(args,env)).code;}

export async function importOutputs(archive,output,maximum) {
  const source=await fs.open(archive,'r');let position=0,count=0,total=0;
  const read=async size=>{const data=Buffer.alloc(size);let offset=0;while(offset<size){const r=await source.read(data,offset,size-offset,position);if(!r.bytesRead)fail('NATIVE_EXPORT_TRUNCATED');offset+=r.bytesRead;position+=r.bytesRead;}return data;};
  try {
    if((await read(11)).toString()!=='AIDNATIVE1\n')fail('NATIVE_EXPORT_INVALID');
    for(;;){
      const length=(await read(4)).readUInt32BE();if(length===0)break;
      if(length>2048||++count>1024)fail('NATIVE_EXPORT_LIMIT');
      const item=JSON.parse((await read(length)).toString('utf8'));
      if(typeof item.name!=='string'||item.name.length>512||/[\\\0\r\n:]/.test(item.name)||path.posix.isAbsolute(item.name)
        ||item.name.split('/').some(part=>!part||part==='.'||part==='..')||!integer(item.bytes,0,maximum))fail('NATIVE_EXPORT_INVALID');
      total+=item.bytes;if(total>maximum)fail('NATIVE_EXPORT_LIMIT');
      const destination=path.join(output,item.name);await fs.mkdir(path.dirname(destination),{recursive:true});
      if(!inside(output,await fs.realpath(path.dirname(destination))))fail('NATIVE_EXPORT_PATH_INVALID');
      const target=await fs.open(destination,'wx',0o600);
      try{let remaining=item.bytes;while(remaining){const data=await read(Math.min(65536,remaining));await target.writeFile(data);remaining-=data.length;}await target.sync();}
      finally{await target.close();}
    }
    if(position!==(await source.stat()).size)fail('NATIVE_EXPORT_TRAILING_DATA');
  } finally {await source.close();}
}

/** This launcher mounts only software and this job's immutable inputs. All generated writes remain in a bounded tmpfs. */
export async function createNativeLauncher(profile) {
  if(process.platform!=='linux')fail('NATIVE_LINUX_REQUIRED');
  const config=profile.nativeIsolation,executionRoot=await fs.realpath(profile.executionRoot);
  if(!config||!Array.isArray(config.runtimeRoots)||config.runtimeRoots.length>16||!Array.isArray(config.executables)
      ||!integer(config.cpuMillis,100,32000)||!integer(config.memoryBytes,268435456,64*1024**3)
      ||!integer(config.scratchBytes,50331648,16*1024**3)||!integer(config.tasksMax,16,1024))fail('NATIVE_LIMITS_REQUIRED');
  const runtimeRoots=[packageRoot];
  for(const root of config.runtimeRoots){
    if(typeof root!=='string'||!path.isAbsolute(root)||root.split('/').filter(Boolean).length<2)fail('NATIVE_RUNTIME_ROOT_INVALID');
    const real=await fs.realpath(root);if(real!==root||!inside(root,real))fail('NATIVE_RUNTIME_ROOT_INVALID');
    runtimeRoots.push(real);
  }
  const protectedPaths=[profile.stateRoot,profile.executionRoot,profile.credentialFile,profile.tlsKeyFile].filter(Boolean).map(value=>path.resolve(value));
  for(const root of runtimeRoots)if(protectedPaths.some(file=>inside(root,file)||inside(file,root)))fail('NATIVE_RUNTIME_ROOT_UNSAFE');
  const executables=new Set();for(const value of config.executables){if(typeof value!=='string'||!path.isAbsolute(value))fail('NATIVE_EXECUTABLE_INVALID');const real=await fs.realpath(value);if(!inside('/usr',real)&&!runtimeRoots.some(root=>inside(root,real)))fail('NATIVE_EXECUTABLE_UNMOUNTED');executables.add(real);}
  const environments=new Map();
  for(const [binary,values] of Object.entries(config.environments??{})){
    const real=await fs.realpath(binary);if(!executables.has(real)||!values||Array.isArray(values)||typeof values!=='object')fail('NATIVE_ENVIRONMENT_INVALID');
    for(const [key,value] of Object.entries(values)){
      if(!['LD_LIBRARY_PATH','BLENDER_SYSTEM_RESOURCES','BLENDER_SYSTEM_SCRIPTS','BLENDER_SYSTEM_DATAFILES','BLENDER_SYSTEM_PYTHON'].includes(key)
          ||typeof value!=='string'||value.length>4096)fail('NATIVE_ENVIRONMENT_INVALID');
      for(const directory of value.split(path.delimiter)){const resolved=await fs.realpath(directory);if(!path.isAbsolute(directory)
        ||!inside('/usr',resolved)&&!runtimeRoots.some(root=>inside(root,resolved)))fail('NATIVE_ENVIRONMENT_UNMOUNTED');}
    }
    environments.set(real,{...values});
  }
  const hostEnv={PATH:'/usr/bin:/bin',XDG_RUNTIME_DIR:'/run/user/'+process.getuid(),DBUS_SESSION_BUS_ADDRESS:'unix:path=/run/user/'+process.getuid()+'/bus'};
  return async function launch(command,args,options,reservation) {
    const executable=await fs.realpath(command);if(!executables.has(executable))fail('NATIVE_EXECUTABLE_UNAPPROVED');
    const cwd=await fs.realpath(options.cwd),relative=path.relative(executionRoot,cwd);
    if(relative.startsWith('..')||path.isAbsolute(relative)||!relative)fail('NATIVE_JOB_SCOPE_INVALID');
    const folder=path.join(executionRoot,relative.split(path.sep)[0]),output=await fs.realpath(path.dirname(options.logFile));
    if(!inside(folder,output)||await fs.realpath(folder)!==folder)fail('NATIVE_JOB_SCOPE_INVALID');
    const resources=reservation?.resources??config;
    for(const key of ['cpuMillis','memoryBytes','scratchBytes'])if(!integer(resources[key],1,config[key]))fail('NATIVE_SLOT_LIMIT_INVALID');
    const scratchBytes=Math.floor(Math.min(resources.scratchBytes/3,resources.memoryBytes/2,512*1024**2));
    if(scratchBytes<16*1024**2)fail('NATIVE_SLOT_LIMIT_INVALID');
    const control=await fs.mkdtemp(path.join(executionRoot,'.native-control-')),unit='aid-scene-'+randomUUID().replaceAll('-','')+'.service';
    const archive=path.join(control,'export.bin'),controlFile=path.join(control,'control.json');
    const readonlyInput=path.basename(cwd)==='input'?cwd:null,env={PATH:'/usr/local/bin:/usr/bin:/bin',...(environments.get(executable)??{})};
    for(const key of ['CLASSPATH','PYTHONNOUSERSITE','PYTHONUTF8'])if(typeof options.env?.[key]==='string')env[key]=options.env[key];
    await fs.writeFile(controlFile,JSON.stringify({folder,output,cwd,readonlyInput,scratchBytes,threads:Math.max(1,Math.ceil(resources.cpuMillis/1000)),
      argv:[executable,...args],env,logFile:options.logFile}),{flag:'wx',mode:0o600});
    const bwrap=['/usr/bin/bwrap','--unshare-all','--unshare-user','--disable-userns','--die-with-parent','--new-session','--cap-drop','ALL',
      '--ro-bind','/usr','/usr','--symlink','usr/lib','/lib','--symlink','usr/lib64','/lib64','--symlink','usr/bin','/bin',
      '--dir','/proc','--dev','/dev','--remount-ro','/dev','--ro-bind',folder,'/aid-seed',
      '--ro-bind',controlFile,'/aid-control.json'];
    for(const directory of ['/etc/fonts','/etc/ld.so.cache']){try{await fs.lstat(directory);bwrap.push('--ro-bind',directory,directory);}catch(error){if(error.code!=='ENOENT')throw error;}}
    for(const root of [...new Set(runtimeRoots)])if(!inside('/usr',root))bwrap.push('--ro-bind',root,root);
    bwrap.push('--size',String(scratchBytes),'--tmpfs',folder);
    if(readonlyInput)bwrap.push('--ro-bind',readonlyInput,readonlyInput);
    bwrap.push('--symlink',path.join(folder,'.native-tmp'),'/tmp','--remount-ro','/','--clearenv','/usr/bin/python3',path.join(packageRoot,'src/native-supervisor.py'));
    const limits=['--property=MemoryMax='+resources.memoryBytes,'--property=MemorySwapMax=0','--property=OOMPolicy=kill','--property=TasksMax='+config.tasksMax,
      '--property=CPUQuota='+(resources.cpuMillis/10)+'%','--property=RuntimeMaxSec='+Math.max(1,Math.ceil(options.timeoutMs/1000)),
      '--property=KillMode=control-group','--property=NoNewPrivileges=yes'];
    const kill=()=>{const child=spawn('/usr/bin/systemctl',['--user','kill','--kill-whom=all','--signal=KILL',unit],{env:hostEnv,stdio:'ignore'});child.on('error',()=>{});};
    try {
      await runProcessDirect('/usr/bin/systemd-run',['--user','--quiet','--wait','--pipe','--unit='+unit,...limits,...bwrap],
        {...options,env:hostEnv,exportPipe:{file:archive,maxBytes:scratchBytes+1024*2052+32,fromStdout:true},onTerminate:kill});
      await importOutputs(archive,output,scratchBytes);
    } catch(error) {
      const result=await systemctlResult(['show','--property=Result','--value',unit],hostEnv);
      if(!options.signal?.aborted&&result.code===0&&result.output==='oom-kill')throw new BuildError('EXECUTION_MEMORY_LIMIT','任务超过已配置的内存上限，隔离进程已终止');
      throw error;
    } finally {
      kill();
      await systemctl(['stop',unit],hostEnv);
      const status=await systemctl(['is-active','--quiet',unit],hostEnv);
      if(![3,4].includes(status)){
        const error=new BuildError('WORKER_TERMINATION_UNCONFIRMED','隔离任务终止状态待核实，不会重放任务');error.outcomeUnknown=true;throw error;
      }
      await systemctl(['reset-failed',unit],hostEnv);
      await fs.rm(control,{recursive:true,force:true});
    }
  };
}

export async function createExecutor(profile) {
  const launch=await createNativeLauncher(profile);
  // A real namespace/cgroup probe must pass before the HTTP worker advertises enforcement.
  const folder=await fs.mkdtemp(path.join(await fs.realpath(profile.executionRoot),'native-probe-'));
  const listener=createServer(),canary=path.join(profile.stateRoot,'.native-probe-'+randomUUID());
  try{
    await new Promise((resolve,reject)=>{listener.once('error',reject);listener.listen(0,'127.0.0.1',resolve);});
    await fs.writeFile(canary,'probe',{flag:'wx',mode:0o600});
    await launch('/usr/bin/python3',['-c','import pathlib,socket;assert not pathlib.Path("/proc/1/environ").exists();assert not pathlib.Path('+JSON.stringify(canary)+').exists();s=socket.socket();s.settimeout(1);assert s.connect_ex(("127.0.0.1",'+listener.address().port+'))!=0'],
      {cwd:folder,env:{},timeoutMs:15000,logFile:path.join(folder,'probe.log'),logBytes:65536});
    const probes=[[process.execPath,['--version']],[profile.blenderExecutable,['--background','--factory-startup','--disable-autoexec','--python-expr','import bpy; print(bpy.app.version_string)']]];
    const resource=profile.nativeIsolation.resourceConfiguration;
    if(resource){
      if(!Array.isArray(resource.documentClasspath)||!resource.documentClasspath.length)fail('NATIVE_DOCUMENT_CLASSPATH_REQUIRED');
      for(const executable of [resource.javaExecutable,resource.ffmpegExecutable,resource.ffprobeExecutable,resource.pythonExecutable])if(typeof executable!=='string'||!path.isAbsolute(executable))fail('NATIVE_RESOURCE_EXECUTABLE_REQUIRED');
      probes.push([resource.javaExecutable,['-version']],[resource.ffmpegExecutable,['-version']],[resource.ffprobeExecutable,['-version']],[resource.pythonExecutable,['-c','import PIL,fontTools']]);
    }
    for(let index=0;index<probes.length;index++)await launch(probes[index][0],probes[index][1],
      {cwd:folder,env:{},timeoutMs:30000,logFile:path.join(folder,'runtime-'+index+'.log'),logBytes:65536});
  }
  finally{if(listener.listening)await new Promise(resolve=>listener.close(resolve));await fs.rm(canary,{force:true});await fs.rm(folder,{recursive:true,force:true});}
  const scoped=(operation,job,context)=>withProcessIsolation((command,args,options)=>launch(command,args,options,context.resourceReservation),()=>operation(job,context));
  const preview=createPreviewExecutor({blenderExecutable:profile.blenderExecutable,localDevelopmentEnabled:true});
  const executor={securityMode:'VERIFIED_ISOLATION',resourceLimits:{cpu:true,memory:true,disk:true},
    execute:(job,context)=>scoped(async(job,context)=>{const built=await buildLocalDevelopment(job,{...context,blenderExecutable:profile.blenderExecutable,localDevelopmentEnabled:true});built.result.executionMode='NATIVE_ISOLATED';return built;},job,context),
    preview:(job,context)=>scoped(preview,job,context)};
  if(profile.nativeIsolation.resourceConfiguration){const resource=createResourceExecutor(profile.nativeIsolation.resourceConfiguration);executor.resource=(job,context)=>scoped(resource,job,context);}
  return executor;
}
