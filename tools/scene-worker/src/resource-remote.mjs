import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash,randomUUID} from 'node:crypto';
import {RemoteComputeClient} from './remote-protocol.mjs';
import {downloadComputeArtifact} from './remote-artifact.mjs';
import {validateResourceJob} from './resource-remote-contract.mjs';
import {confirmRemoteArchive} from './remote-archive.mjs';

const hash=data=>createHash('sha256').update(data).digest('hex');
async function read(file,limit){const stat=await fs.lstat(file);if(stat.isSymbolicLink()||!stat.isFile()||stat.size>limit)throw Error('RESOURCE_CONFIG_INVALID');return JSON.parse(await fs.readFile(file,'utf8'));}
async function publish(file,value){
  const temporary=file+'.'+randomUUID()+'.pending',handle=await fs.open(temporary,'wx');
  try{await handle.writeFile(JSON.stringify(value));await handle.sync();}finally{await handle.close();}
  try{await fs.link(temporary,file);}catch(error){if(error.code!=='EEXIST'||JSON.stringify(await read(file,512*1024))!==JSON.stringify(value))throw error;}
  finally{await fs.unlink(temporary).catch(()=>{});}
}
export async function inspectRemoteResource(input,profile){
  if(profile.mode!=='REMOTE_COMPUTE'||profile.enabled!==true||!path.isAbsolute(profile.credentialFile||'')||!path.isAbsolute(profile.executionRoot||''))throw Error('RESOURCE_CONFIG_INVALID');
  if(!Buffer.isBuffer(input.bytes)||input.bytes.length>32*1024*1024||hash(input.bytes)!==input.sha256)throw Error('RESOURCE_INPUT_INVALID');
  const refs=[...(input.resources??new Map())].map(([url,bytes])=>({url,sha256:hash(bytes),contentBase64:bytes.toString('base64')}));
  const fields={projectId:input.projectId,sourceSha256:input.sha256,format:input.format,
    ...(input.pages===undefined?{}:{pages:input.pages}),...(input.baseUrl===undefined?{}:{baseUrl:input.baseUrl}),
    ...(input.videoWindow===undefined?{}:{videoWindow:input.videoWindow}),references:refs.map(({url,sha256})=>({url,sha256}))};
  const digest=hash(JSON.stringify(fields)),job=validateResourceJob({jobId:'resource-'+digest,...fields});
  const root=await fs.realpath(profile.executionRoot);if((await fs.lstat(profile.executionRoot)).isSymbolicLink())throw Error('RESOURCE_DIRECTORY_INVALID');
  const folder=path.join(root,job.jobId);await fs.mkdir(folder,{recursive:true});
  if((await fs.lstat(folder)).isSymbolicLink()||await fs.realpath(folder)!==folder)throw Error('RESOURCE_DIRECTORY_INVALID');
  const receipt=path.join(folder,'result.json');
  let client;
  const getClient=async()=>{if(!client){const credential=await read(profile.credentialFile,4096);client=new RemoteComputeClient({endpoint:profile.endpoint,workerId:profile.workerId,tlsCaPem:profile.tlsCaPem,secret:credential.secret,localAcceptance:profile.localAcceptance===true});}return client;};
  const archive=async result=>{if(result)try{await confirmRemoteArchive(await getClient(),{jobId:job.jobId,projectId:job.projectId,result,receiptFile:receipt,outputDirectory:folder,artifacts:result.previews});}catch{/* Cached results remain readable after temporary credentials expire. */}};
  try{const prior=await read(receipt,512*1024);if(prior.digest!==digest)throw Error('RESOURCE_RECEIPT_INVALID');
    const remote=prior.remoteHeader?{...prior.remoteHeader,metadata:prior.result.metadata,previews:prior.result.previews.map(({file,imagePath,...metadata})=>({name:file,...metadata}))}:prior.remoteResult;
    await archive(remote);return prior.result;}
  catch(error){if(error.code!=='ENOENT')throw error;}
  await getClient();
  let first=false;try{await fs.mkdir(path.join(folder,'submission-claim'));first=true;}catch(error){if(error.code!=='EEXIST')throw error;}
  try{
    if(first)await client.request('POST','/v1/resources',{job,contentBase64:input.bytes.toString('base64'),references:refs},{signal:profile.signal});
    const deadline=Date.now()+45000;let state;
    do{state=await client.status(job.jobId,job.projectId,{signal:profile.signal});if(['BUILT','FAILED','CANCELLED','UNKNOWN'].includes(state.state))break;
      await new Promise(resolve=>setTimeout(resolve,250));}while(Date.now()<deadline);
    if(['FAILED','CANCELLED'].includes(state.state))throw Error(/^RESOURCE_[A-Z_]+$/.test(state.errorCode)?state.errorCode:'RESOURCE_EXECUTION_FAILED');
    if(state.state!=='BUILT')throw Error('RESOURCE_REMOTE_PENDING');
    const result=state.result;
    if(result?.jobId!==job.jobId||result.projectId!==job.projectId||result.sourceSha256!==job.sourceSha256||
      result.metadata?.sourceSha256!==input.sha256||result.metadata.sourceBytes!==input.bytes.length||!Array.isArray(result.previews)||result.previews.length>8||
      new Set(result.previews.map(p=>p?.name)).size!==result.previews.length)throw Error('RESOURCE_RECEIPT_INVALID');
    const previews=[];
    for(const preview of result.previews){
      if(!/^(preview|page-[0-9]{1,3})\.png$/.test(preview.name))throw Error('RESOURCE_RECEIPT_INVALID');
      const imagePath=await downloadComputeArtifact(client,{...preview,jobId:job.jobId,projectId:job.projectId},folder,{signal:profile.signal});
      const {name,...metadata}=preview;previews.push({...metadata,file:name,imagePath});
    }
    const inspection={metadata:result.metadata,previews,outputDirectory:folder,executionMode:'REMOTE_COMPUTE'};
    // Preserve the original digest without duplicating potentially large extracted text.
    const {metadata:archivedMetadata,previews:archivedPreviews,...remoteHeader}=result;
    await publish(receipt,{digest,result:inspection,remoteHeader});await archive(result);return inspection;
  }catch(error){if(/^RESOURCE_[A-Z_]+$/.test(error.message))throw error;throw Error('RESOURCE_REMOTE_UNCONFIRMED');}
}
