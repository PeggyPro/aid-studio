import fs from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { validateJob, DEFAULT_LIMITS } from './contract.mjs';
import { BuildError } from './errors.mjs';
import { readSnapshot, materializeSnapshot } from './snapshot.mjs';
import { inspectGlb } from './glb.mjs';
import { runProcess } from './process.mjs';
import { releaseExecutionScratch } from './execution-scratch.mjs';

const here=path.dirname(fileURLToPath(import.meta.url));

/** Local adapter for explicitly trusted fixtures only. Remote/model-generated
 * source must use a sandbox adapter; process spawning alone is not isolation. */
export async function buildTrustedFixture(job, options) {
  if (options.trustedFixture !== true) throw new BuildError('SANDBOX_REQUIRED','测试夹具执行必须明确授权');
  return buildLocalProcess(job, options, 'TRUSTED_LOCAL_FIXTURE');
}

/** Explicit local development mode. Set only by the operator's local profile,
 * never by a job payload, model argument, or HTTP request. Not a security sandbox. */
export async function buildLocalDevelopment(job, options) {
  if (options.localDevelopmentEnabled !== true)
    throw new BuildError('LOCAL_EXECUTION_DISABLED', '本地开发执行未启用');
  return buildLocalProcess(job, options, 'LOCAL_DEVELOPMENT_PROCESS');
}

async function buildLocalProcess(job, { sourceDirectory, executionRoot, blenderExecutable,
  signal, limits = DEFAULT_LIMITS, onStage = () => {} }, executionMode) {
  validateJob(job);
  for(const key of Object.keys(DEFAULT_LIMITS))
    if(!Number.isSafeInteger(limits[key]) || limits[key]<1) throw new BuildError('INVALID_LIMITS',`${key} 限额无效`);
  const snapshot=await readSnapshot(sourceDirectory,limits);
  if(snapshot.sha256!==job.sourceSha256) throw new BuildError('SOURCE_VERSION_CONFLICT','源码版本已变化');
  if(!snapshot.files.some(f=>f.name===job.entry)) throw new BuildError('SOURCE_ENTRY_NOT_FOUND','源码入口不存在');
  if(signal?.aborted) throw new BuildError('CANCELLED','任务已取消');
  await fs.mkdir(executionRoot,{recursive:true});
  const root=await fs.realpath(executionRoot);
  const folder=await fs.mkdtemp(path.join(root,`${job.jobId}-`));
  const input=path.join(folder,'input'), output=path.join(folder,'output');
  await materializeSnapshot(snapshot,input); await fs.mkdir(output);
  const started=Date.now(); const logFile=path.join(output,'execution.log');
  const resultFile=path.join(output,'result.json');
  const notify=stage=>onStage({jobId:job.jobId,stage});
  const env={};
  for(const key of ['PATH','SystemRoot','WINDIR','COMSPEC','PATHEXT']) if(process.env[key]) env[key]=process.env[key];
  const temp=path.join(folder,'temp'); await fs.mkdir(temp);
  Object.assign(env,{TEMP:temp,TMP:temp,TMPDIR:temp,HOME:temp,USERPROFILE:temp,PYTHONNOUSERSITE:'1',PYTHONUTF8:'1'});
  const glb=path.join(output,'scene.glb');
  try {
    notify('BUILDING');
    const command=job.engine==='threejs'?process.execPath:blenderExecutable;
    if(!command) throw new BuildError('ENGINE_UNAVAILABLE','本机未配置对应引擎');
    const args=job.engine==='threejs'
      ? [path.join(here,'three-runner.mjs'),path.join(input,job.entry),glb]
      : ['--background','--factory-startup','--disable-autoexec','--threads','2','--python-exit-code','1',
         '--python',path.join(here,'blender-runner.py'),'--',path.join(input,job.entry),glb,String(job.keepBlend===true)];
    await runProcess(command,args,{cwd:input,env,timeoutMs:limits.timeoutMs,logBytes:limits.logBytes,logFile,signal});
    notify('VALIDATING');
    const stats=await inspectGlb(glb,limits);
    const metadataPath=`${glb}.metadata.json`;
    const metadataStat=await fs.lstat(metadataPath);
    if(!metadataStat.isFile() || metadataStat.isSymbolicLink() || metadataStat.size>limits.logBytes)
      throw new BuildError('INVALID_ENGINE_RECEIPT','引擎回执无效');
    const metadata=JSON.parse(await fs.readFile(metadataPath,'utf8'));
    if(metadata.engine!==job.engine || typeof metadata.engineVersion!=='string' || metadata.engineVersion.length>100
      || !Array.isArray(metadata.warnings) || metadata.warnings.length>1000)
      throw new BuildError('INVALID_ENGINE_RECEIPT','引擎回执不匹配');
    const warnings=metadata.warnings.map(warning=>{
      if(!warning || typeof warning.code!=='string' || warning.code.length>100 || typeof warning.objectId!=='string'
        || warning.objectId.length>200 || typeof warning.type!=='string' || warning.type.length>100)
        throw new BuildError('INVALID_ENGINE_RECEIPT','导出告警格式无效');
      return {code:warning.code,objectId:warning.objectId,type:warning.type};
    });
    const previewSettings=metadata.previewSettings;
    if(previewSettings!=null && (job.engine!=='blender' || previewSettings.lightingUnits!=='PHOTOMETRIC'
      || !Number.isFinite(previewSettings.exposure) || previewSettings.exposure<2**-16 || previewSettings.exposure>2**8
      || !Number.isFinite(previewSettings.sourceExposureEV)))
      throw new BuildError('INVALID_ENGINE_RECEIPT','预览曝光信息无效');
    const digest=createHash('sha256').update(await fs.readFile(glb)).digest('hex');
    const nativeArtifacts=[];
    if(job.engine==='blender' && job.keepBlend===true) {
      const nativePath=path.join(output,'scene.blend');
      const nativeStat=await fs.lstat(nativePath);
      if(!nativeStat.isFile() || nativeStat.isSymbolicLink() || nativeStat.size<1 || nativeStat.size>limits.nativeProjectBytes)
        throw new BuildError('ARTIFACT_LIMIT','Blender工程无效或超过文件限额');
      const nativeHash=createHash('sha256');
      for await (const chunk of createReadStream(nativePath)) nativeHash.update(chunk);
      nativeArtifacts.push({name:'scene.blend',mimeType:'application/x-blender',sizeBytes:nativeStat.size,sha256:nativeHash.digest('hex')});
    }
    const result={protocol:job.protocol,jobId:job.jobId,projectId:job.projectId,sceneId:job.sceneId,
      sourceVersionId:job.sourceVersionId,sourceSha256:job.sourceSha256,state:'BUILT',
      artifact:{name:'scene.glb',sha256:digest,...stats},engine:job.engine,engineVersion:metadata.engineVersion,
      warnings,nativeArtifacts,...(previewSettings?{previewSettings}:{}),durationMs:Date.now()-started,
      previewState:'NOT_REQUESTED',executionMode};
    await fs.writeFile(resultFile,JSON.stringify(result,null,2));
    // Only successful, fully validated executions reach this point. A cleanup
    // failure must not turn an existing build result into a generation failure.
    let scratchCleanup;
    try{scratchCleanup={state:'RELEASED',...await releaseExecutionScratch(root,folder)};}
    catch(error){scratchCleanup={state:'PENDING',code:error.code||'SCRATCH_CLEANUP_FAILED'};}
    notify('BUILT'); return {result,outputDirectory:output,scratchCleanup};
  } catch(error) {
    const failure=error instanceof BuildError?error:new BuildError('WORKER_INTERNAL_ERROR','执行器内部错误',{details:error.message});
    await fs.writeFile(resultFile,JSON.stringify({protocol:job.protocol,jobId:job.jobId,
      state:failure.outcomeUnknown===true?'UNKNOWN':failure.code==='CANCELLED'?'CANCELLED':'FAILED',code:failure.code,message:failure.message,
      retryable:failure.retryable,details:failure.details,durationMs:Date.now()-started},null,2));
    failure.outputDirectory=output; throw failure;
  }
}
