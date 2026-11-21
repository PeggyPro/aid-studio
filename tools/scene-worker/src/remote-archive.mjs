import fs from 'node:fs/promises';
import {createReadStream} from 'node:fs';
import path from 'node:path';
import {createHash,randomUUID} from 'node:crypto';
import {archivedResultDigest} from './archive-receipt.mjs';

/** Archive maintenance is independent of the already durable generation result.
 * An old/unreachable worker leaves a pending marker, never a failed generation. */
export async function confirmRemoteArchive(client,{jobId,projectId,result,receiptFile,outputDirectory,artifacts}){
  if(!path.isAbsolute(receiptFile||'')||!path.isAbsolute(outputDirectory||'')||!result||!Array.isArray(artifacts))return {inputCleanup:'PENDING',errorCode:'LOCAL_ARCHIVE_INVALID'};
  const resultDigest=archivedResultDigest(result),marker=receiptFile+'.archive.json';
  let status;
  try{
    const prior=JSON.parse(await fs.readFile(marker,'utf8').catch(error=>{if(error.code==='ENOENT')return 'null';throw error;}));
    if(prior?.resultDigest===resultDigest&&prior.inputCleanup==='RELEASED')return prior;
    const receipt=await fs.lstat(receiptFile),directory=await fs.lstat(outputDirectory);
    if(!receipt.isFile()||receipt.isSymbolicLink()||!directory.isDirectory()||directory.isSymbolicLink())throw Error('LOCAL_ARCHIVE_INVALID');
    let total=0;
    for(const artifact of artifacts){
      if(!/^(scene\.(glb|blend)|preview\.png|page-[0-9]{1,3}\.png)$/.test(artifact.name)||!Number.isSafeInteger(artifact.sizeBytes)||artifact.sizeBytes<1||artifact.sizeBytes>128*1024**2||!/^([a-f0-9]{64})$/.test(artifact.sha256))throw Error('LOCAL_ARCHIVE_INVALID');
      total+=artifact.sizeBytes;if(total>256*1024**2)throw Error('LOCAL_ARCHIVE_INVALID');
      const file=path.join(outputDirectory,artifact.name),stat=await fs.lstat(file);
      if(!stat.isFile()||stat.isSymbolicLink()||stat.size!==artifact.sizeBytes)throw Error('LOCAL_ARCHIVE_INVALID');
      const hash=createHash('sha256');for await(const chunk of createReadStream(file))hash.update(chunk);
      if(hash.digest('hex')!==artifact.sha256)throw Error('LOCAL_ARCHIVE_INVALID');
    }
    const response=await client.request('POST','/v1/jobs/archive',{jobId,projectId,resultDigest},{signal:AbortSignal.timeout(5000)});
    if(response.jobId!==jobId||response.projectId!==projectId||response.resultDigest!==resultDigest||response.inputCleanup!=='RELEASED')throw Error('ARCHIVE_CONFIRMATION_PENDING');
    status={resultDigest,inputCleanup:'RELEASED'};
  }catch(error){status={resultDigest,inputCleanup:'PENDING',errorCode:/^[A-Z_]{1,80}$/.test(error.message)?error.message:'ARCHIVE_CONFIRMATION_PENDING'};}
  // Sidecar failures are recoverable maintenance work, not generation failures.
  const target=status.inputCleanup==='RELEASED'?marker:receiptFile+'.archive-pending.json';
  const temporary=target+'.'+randomUUID()+'.pending';
  try{
    const handle=await fs.open(temporary,'wx');try{await handle.writeFile(JSON.stringify(status));await handle.sync();}finally{await handle.close();}
    if(status.inputCleanup==='RELEASED'){
      try{await fs.link(temporary,target);}catch(error){if(error.code!=='EEXIST')throw error;}
      await fs.unlink(receiptFile+'.archive-pending.json').catch(()=>{});
    }else await fs.rename(temporary,target);
  }catch{}finally{await fs.unlink(temporary).catch(()=>{});}
  return status;
}
