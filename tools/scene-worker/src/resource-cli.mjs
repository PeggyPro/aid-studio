import fs from 'node:fs/promises';
import path from 'node:path';
import {inspectResource} from './resource-dispatch.mjs';
import {inspectRemoteResource} from './resource-remote.mjs';

// Private process boundary. The Java caller owns both files; no model-provided disk paths.
async function readJson(file,limit){
  if(!path.isAbsolute(file||''))throw new Error('RESOURCE_CONFIG_INVALID');
  const stat=await fs.lstat(file);if(stat.isSymbolicLink()||!stat.isFile()||stat.size>limit)throw new Error('RESOURCE_INPUT_INVALID');
  return JSON.parse(await fs.readFile(file,'utf8'));
}
try {
  if(process.argv.length!==4)throw new Error('RESOURCE_INPUT_INVALID');
  const profile=await readJson(process.argv[2],98304),request=await readJson(process.argv[3],160*1024);
  if(!['LOCAL_DEVELOPMENT_PROCESS','REMOTE_COMPUTE'].includes(profile.mode)||profile.enabled!==true)throw new Error('LOCAL_EXECUTION_DISABLED');
  if(!path.isAbsolute(profile.intakeRoot||'')||!path.isAbsolute(profile.executionRoot||''))throw new Error('RESOURCE_CONFIG_INVALID');
  const intake=await fs.realpath(profile.intakeRoot);
  if((await fs.lstat(profile.intakeRoot)).isSymbolicLink())throw new Error('RESOURCE_DIRECTORY_INVALID');
  if(typeof request.inputId!=='string'||!/^[a-f0-9]{64}$/.test(request.inputId)||request.sha256!==request.inputId)throw new Error('RESOURCE_INPUT_INVALID');
  const file=path.join(intake,request.inputId+'.bin'),stat=await fs.lstat(file);
  if(stat.isSymbolicLink()||!stat.isFile()||stat.size<1||stat.size>32*1024*1024||await fs.realpath(file)!==file)throw new Error('RESOURCE_INPUT_INVALID');
  const bytes=await fs.readFile(file),controller=new AbortController();
  process.once('SIGINT',()=>controller.abort());process.once('SIGTERM',()=>controller.abort());
  const config={...profile,localDevelopmentEnabled:true,signal:controller.signal},input={bytes,sha256:request.sha256,format:request.format,pages:request.pages};
  if(request.format==='svg'){
    const references=request.references??[];if(!Array.isArray(references)||references.length>16)throw new Error('RESOURCE_REFERENCE_INVALID');
    const resources=new Map();let total=0;
    for(const ref of references){
      if(!ref||typeof ref.url!=='string'||ref.url.length>8192||!/^[a-f0-9]{64}$/.test(ref.sha256||'')||resources.has(ref.url))throw new Error('RESOURCE_REFERENCE_INVALID');
      const reference=path.join(intake,ref.sha256+'.bin'),info=await fs.lstat(reference);
      if(info.isSymbolicLink()||!info.isFile()||info.size<1||info.size>10*1024*1024||(total+=info.size)>16*1024*1024||await fs.realpath(reference)!==reference)throw new Error('RESOURCE_REFERENCE_INVALID');
      const data=await fs.readFile(reference);const {createHash}=await import('node:crypto');if(createHash('sha256').update(data).digest('hex')!==ref.sha256)throw new Error('RESOURCE_REFERENCE_INVALID');resources.set(ref.url,data);
    }
    input.baseUrl=request.baseUrl;input.resources=resources;
  }
  const result=profile.mode==='REMOTE_COMPUTE'
    ?await inspectRemoteResource({...input,projectId:request.projectId,videoWindow:request.videoWindow},config)
    :await inspectResource({...input,startSeconds:request.videoWindow?.startSeconds,durationSeconds:request.videoWindow?.durationSeconds,frameCount:request.videoWindow?.frameCount},config);
  process.stdout.write(JSON.stringify({type:'result',...result})+'\n');
} catch(error) {
  const candidate=error.code||error.message;
  process.stdout.write(JSON.stringify({type:'error',code:/^(RESOURCE_[A-Z_]+|LOCAL_EXECUTION_DISABLED|CANCELLED|EXECUTION_TIMEOUT|WORKER_START_UNAVAILABLE)$/.test(candidate)?candidate:'RESOURCE_EXECUTION_FAILED'})+'\n');
  process.exitCode=1;
}
