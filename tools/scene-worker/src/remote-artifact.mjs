import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash,randomUUID} from 'node:crypto';

/** Stream bounded authenticated chunks to an operator-owned local directory. Publish only after full verification. */
export async function downloadComputeArtifact(client,{jobId,projectId,name,sizeBytes,sha256},directory,{signal}={}) {
  const image=typeof name==='string'&&/^(preview|page-[0-9]{1,3})\.png$/.test(name);
  if(!image&&!['scene.glb','scene.blend'].includes(name)||!Number.isSafeInteger(sizeBytes)||sizeBytes<1||sizeBytes>(image?16:128)*1024*1024
      ||!/^[a-f0-9]{64}$/.test(sha256)||!path.isAbsolute(directory))throw Error('ARTIFACT_METADATA_INVALID');
  await fs.mkdir(directory,{recursive:true});
  if((await fs.lstat(directory)).isSymbolicLink())throw Error('ARTIFACT_DIRECTORY_INVALID');
  const root=await fs.realpath(directory),target=path.join(root,name),pending=path.join(root,randomUUID()+'.download');
  const handle=await fs.open(pending,'wx'),digest=createHash('sha256');
  try{
    for(let offset=0;offset<sizeBytes;) {
      const length=Math.min(262144,sizeBytes-offset),chunk=await client.artifactChunk(jobId,projectId,name,offset,length,{signal});
      if(chunk.jobId!==jobId||chunk.projectId!==projectId||chunk.name!==name||chunk.sizeBytes!==sizeBytes
          ||chunk.sha256!==sha256||chunk.offset!==offset||typeof chunk.contentBase64!=='string')throw Error('ARTIFACT_SCOPE_MISMATCH');
      const bytes=Buffer.from(chunk.contentBase64,'base64');
      if(bytes.length!==length||bytes.toString('base64')!==chunk.contentBase64||createHash('sha256').update(bytes).digest('hex')!==chunk.chunkSha256)throw Error('ARTIFACT_CHUNK_INVALID');
      await handle.writeFile(bytes);digest.update(bytes);offset+=bytes.length;
    }
    if(digest.digest('hex')!==sha256)throw Error('ARTIFACT_HASH_MISMATCH');
    await handle.sync();
  }catch(error){await handle.close();await fs.unlink(pending).catch(()=>{});throw error;}
  await handle.close();
  try{await fs.link(pending,target);}catch(error){
    if(error.code!=='EEXIST')throw error;
    const stat=await fs.lstat(target);
    if(stat.isSymbolicLink()||!stat.isFile()||stat.size!==sizeBytes)throw Error('ARTIFACT_TARGET_CONFLICT');
    const {createReadStream}=await import('node:fs');const hash=createHash('sha256');
    for await(const chunk of createReadStream(target))hash.update(chunk);
    if(hash.digest('hex')!==sha256)throw Error('ARTIFACT_TARGET_CONFLICT');
  }finally{await fs.unlink(pending).catch(()=>{});}
  return target;
}
