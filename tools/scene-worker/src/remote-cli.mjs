import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash,randomUUID} from 'node:crypto';
import {RemoteComputeClient} from './remote-protocol.mjs';
import {downloadComputeArtifact} from './remote-artifact.mjs';
import {readSnapshot} from './snapshot.mjs';
import {validateJob} from './contract.mjs';
import {confirmRemoteArchive} from './remote-archive.mjs';

// Same trusted request/receipt interface as local-cli; no model-controlled connection settings.
const [profilePath,requestPath,operation]=process.argv.slice(2);
async function json(file,limit){
  if(!file||!path.isAbsolute(file))throw Error('ABSOLUTE_PATH_REQUIRED');
  const stat=await fs.lstat(file);
  if(!stat.isFile()||stat.isSymbolicLink()||stat.size>limit)throw Error('INVALID_INPUT_FILE');
  return JSON.parse(await fs.readFile(file,'utf8'));
}
async function publish(file,value){
  const pending=file+'.'+randomUUID()+'.pending',handle=await fs.open(pending,'wx');
  try{await handle.writeFile(JSON.stringify(value));await handle.sync();}finally{await handle.close();}
  try{await fs.link(pending,file);}catch(error){
    if(error.code!=='EEXIST'||JSON.stringify(await json(file,1048576))!==JSON.stringify(value))throw error;
  }finally{await fs.unlink(pending).catch(()=>{});}
}
try{
  const profile=await json(profilePath,98304),request=validateJob(await json(requestPath,32768));
  if(profile.mode!=='REMOTE_COMPUTE'||profile.enabled!==true||!path.isAbsolute(profile.sourceRoot)||!path.isAbsolute(profile.executionRoot))throw Error('REMOTE_EXECUTION_DISABLED');
  const secret=await json(profile.credentialFile,4096);
  const client=new RemoteComputeClient({endpoint:profile.endpoint,workerId:profile.workerId,tlsCaPem:profile.tlsCaPem,secret:secret.secret,localAcceptance:profile.localAcceptance===true});
  const requestDigest=createHash('sha256').update(JSON.stringify(Object.fromEntries(Object.entries(request).sort(([a],[b])=>a.localeCompare(b))))).digest('hex');
  const control=path.join(profile.executionRoot,'.receipts',request.jobId);await fs.mkdir(control,{recursive:true});
  if((await fs.lstat(control)).isSymbolicLink())throw Error('INVALID_RECEIPT_ROOT');
  const receipt=path.join(control,'result.json'),claim=path.join(control,'remote-request.json');
  let prior;try{prior=await json(receipt,1048576);}catch(error){if(error.code!=='ENOENT')throw error;}
  const archive=async event=>{if(event.type==='result')await confirmRemoteArchive(client,{jobId:request.jobId,projectId:request.projectId,result:event.result,receiptFile:receipt,outputDirectory:event.outputDirectory,artifacts:[{name:'scene.glb',sizeBytes:event.result.artifact.glbBytes,sha256:event.result.artifact.sha256},...(event.result.nativeArtifacts||[])]});};
  if(prior){if(prior.requestDigest!==requestDigest)throw Error('JOB_IDEMPOTENCY_CONFLICT');await archive(prior.event);process.stdout.write(JSON.stringify(prior.event)+'\n');process.exit(prior.event.type==='result'?0:1);}
  let first=false;
  if(operation==='--lookup-only'){
    if((await json(claim,4096)).requestDigest!==requestDigest)throw Error('JOB_IDEMPOTENCY_CONFLICT');
  }else try{const handle=await fs.open(claim,'wx');try{await handle.writeFile(JSON.stringify({requestDigest}));await handle.sync();first=true;}finally{await handle.close();}}
  catch(error){if(error.code!=='EEXIST')throw error;if((await json(claim,4096)).requestDigest!==requestDigest)throw Error('JOB_IDEMPOTENCY_CONFLICT');}
  if(first){
    const root=await fs.realpath(profile.sourceRoot),source=await fs.realpath(path.join(root,request.projectId,'versions',request.sourceVersionId)),relative=path.relative(root,source);
    if(!relative||relative.startsWith('..')||path.isAbsolute(relative))throw Error('SOURCE_PATH_ESCAPE');
    const snapshot=await readSnapshot(source);
    if(snapshot.sha256!==request.sourceSha256)throw Error('SOURCE_VERSION_CONFLICT');
    await client.submit(request,{files:snapshot.files.map(file=>({name:file.name,contentBase64:file.data.toString('base64')}))});
  }
  const deadline=Date.now()+(operation==='--lookup-only'?0:(Number.isInteger(profile.waitMs)?Math.min(120000,Math.max(1000,profile.waitMs)):120000));
  let state;
  do{state=await client.status(request.jobId,request.projectId);if(['BUILT','FAILED','CANCELLED','UNKNOWN'].includes(state.state))break;await new Promise(resolve=>setTimeout(resolve,500));}while(Date.now()<deadline);
  if(!['BUILT','FAILED','CANCELLED'].includes(state.state))throw Error('REMOTE_RESULT_PENDING');
  let event;
  if(state.state==='BUILT'){
    const result=state.result;
    if(result.jobId!==request.jobId||result.projectId!==request.projectId||result.sceneId!==request.sceneId||result.sourceSha256!==request.sourceSha256||result.engine!==request.engine)throw Error('WORKER_RESULT_IDENTITY_INVALID');
    const outputDirectory=path.join(profile.executionRoot,'remote-'+request.jobId);await fs.mkdir(outputDirectory,{recursive:true});
    await downloadComputeArtifact(client,{jobId:request.jobId,projectId:request.projectId,name:'scene.glb',sizeBytes:result.artifact.glbBytes,sha256:result.artifact.sha256},outputDirectory);
    for(const native of result.nativeArtifacts||[])await downloadComputeArtifact(client,{jobId:request.jobId,projectId:request.projectId,...native},outputDirectory);
    await publish(path.join(outputDirectory,'result.json'),result);
    event={type:'result',result,outputDirectory};
  }else event={type:'error',code:state.errorCode||'REMOTE_EXECUTION_FAILED',state:state.state};
  await publish(receipt,{requestDigest,event});await archive(event);process.stdout.write(JSON.stringify(event)+'\n');process.exitCode=event.type==='result'?0:1;
}catch(error){
  // A communication or local publication failure is not proof of failed generation.
  process.stdout.write(JSON.stringify({type:'error',state:'UNKNOWN',code:error.code||(/^[A-Z_]+$/.test(error.message)?error.message:'REMOTE_RECEIPT_UNAVAILABLE')})+'\n');process.exitCode=2;
}
