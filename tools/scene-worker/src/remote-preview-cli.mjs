import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash,randomUUID} from 'node:crypto';
import {RemoteComputeClient} from './remote-protocol.mjs';
import {downloadComputeArtifact} from './remote-artifact.mjs';
import {confirmRemoteArchive} from './remote-archive.mjs';

const [profileFile,requestFile,operation]=process.argv.slice(2);
async function read(file,max) {
  if(!file||!path.isAbsolute(file))throw Error('ABSOLUTE_PATH_REQUIRED');
  const stat=await fs.lstat(file);
  if(!stat.isFile()||stat.isSymbolicLink()||stat.size>max)throw Error('INVALID_INPUT_FILE');
  return JSON.parse(await fs.readFile(file,'utf8'));
}
async function publish(file,value) {
  const pending=file+'.'+randomUUID()+'.pending',handle=await fs.open(pending,'wx');
  try{await handle.writeFile(JSON.stringify(value));await handle.sync();}finally{await handle.close();}
  try{await fs.link(pending,file);}catch(error){
    if(error.code!=='EEXIST'||JSON.stringify(await read(file,1048576))!==JSON.stringify(value))throw error;
  }finally{await fs.unlink(pending).catch(()=>{});}
}
try {
  const profile=await read(profileFile,98304),{job}=await read(requestFile,250000);
  if(profile.mode!=='REMOTE_COMPUTE'||profile.enabled!==true)throw Error('REMOTE_EXECUTION_DISABLED');
  const credential=await read(profile.credentialFile,4096),client=new RemoteComputeClient({endpoint:profile.endpoint,workerId:profile.workerId,tlsCaPem:profile.tlsCaPem,
    secret:credential.secret,localAcceptance:profile.localAcceptance===true});
  const directory=await fs.realpath(path.dirname(requestFile)),claim=path.join(directory,'remote-preview-claim.json'),receipt=path.join(directory,'remote-preview-result.json');
  const digest=createHash('sha256').update(JSON.stringify(job)).digest('hex');
  let prior;try{prior=await read(receipt,1048576);}catch(error){if(error.code!=='ENOENT')throw error;}
  const archive=async value=>{if(value.event.type==='result'&&value.remoteResult)await confirmRemoteArchive(client,{jobId:job.jobId,projectId:job.projectId,result:value.remoteResult,receiptFile:receipt,outputDirectory:directory,artifacts:[value.remoteResult.previewArtifact]});};
  if(prior){if(prior.digest!==digest)throw Error('JOB_IDEMPOTENCY_CONFLICT');await archive(prior);process.stdout.write(JSON.stringify(prior.event)+'\n');process.exit(prior.event.type==='result'?0:1);}
  let first=false;
  if(operation==='--lookup-only'){
    if((await read(claim,4096)).digest!==digest)throw Error('JOB_IDEMPOTENCY_CONFLICT');
  }else try{const handle=await fs.open(claim,'wx');try{await handle.writeFile(JSON.stringify({digest}));await handle.sync();first=true;}finally{await handle.close();}}
  catch(error){if(error.code!=='EEXIST')throw error;if((await read(claim,4096)).digest!==digest)throw Error('JOB_IDEMPOTENCY_CONFLICT');}
  if(first)await client.request('POST','/v1/previews',{job});
  const deadline=Date.now()+(operation==='--lookup-only'?0:120000);let state;
  do{state=await client.status(job.jobId,job.projectId);if(['BUILT','FAILED','CANCELLED','UNKNOWN'].includes(state.state))break;
    await new Promise(resolve=>setTimeout(resolve,500));}while(Date.now()<deadline);
  let event;
  if(state.state==='BUILT'){
    const result=state.result;
    if(result.jobId!==job.jobId||result.projectId!==job.projectId||result.buildJobId!==job.buildJobId
      ||result.sourceSha256!==job.sourceSha256||result.artifactSha256!==job.artifactSha256
      ||result.previewArtifact?.name!=='preview.png')throw Error('WORKER_RESULT_IDENTITY_INVALID');
    const imagePath=await downloadComputeArtifact(client,{jobId:job.jobId,projectId:job.projectId,...result.previewArtifact},directory);
    event={type:'result',result:{...result,imagePath,outputDirectory:directory}};
  }else if(['FAILED','CANCELLED'].includes(state.state))event={type:'error',state:state.state,code:state.errorCode||'REMOTE_PREVIEW_FAILED'};
  else throw Error('REMOTE_RESULT_PENDING');
  const saved={digest,event,...(event.type==='result'?{remoteResult:state.result}:{})};
  await publish(receipt,saved);await archive(saved);process.stdout.write(JSON.stringify(event)+'\n');process.exitCode=event.type==='result'?0:1;
}catch(error){
  process.stdout.write(JSON.stringify({type:'error',state:'UNKNOWN',code:error.code||(/^[A-Z_]+$/.test(error.message)?error.message:'REMOTE_PREVIEW_UNCONFIRMED')})+'\n');process.exitCode=2;
}
