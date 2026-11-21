import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash,randomUUID} from 'node:crypto';

/** Publish immutable revisions without replacing an open Windows file. A hard link
 * atomically exposes an already-fsynced file and refuses an existing revision. */
export function createJobLedger({io=fs}={}) {
  const digest=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
  async function readFile(file,legacy=false) {
    const stat=await io.lstat(file);
    if(!stat.isFile()||stat.isSymbolicLink()||stat.size>1048576)throw Error('INVALID_JOB_STATE');
    const value=JSON.parse(await io.readFile(file,'utf8'));
    if(legacy)return value;
    if(!value?.state||value.sha256!==digest(value.state))throw Error('JOB_STATE_CORRUPT');
    return value.state;
  }
  async function read(folder) {
    const revisions=(await io.readdir(folder)).filter(name=>/^state-\d{20}\.json$/.test(name)).sort();
    return revisions.length?readFile(path.join(folder,revisions.at(-1))):readFile(path.join(folder,'state.json'),true);
  }
  async function write(folder,state) {
    if(!Number.isSafeInteger(state.revision)||state.revision<1)throw Error('JOB_REVISION_INVALID');
    let current;
    try{current=await read(folder);}catch(error){if(error.code!=='ENOENT')throw error;}
    if(current?.revision===state.revision&&digest(current)===digest(state))return;
    if((current?.revision||0)!==state.revision-1)throw Error('JOB_REVISION_CONFLICT');
    const pending=path.join(folder,randomUUID()+'.pending');
    const target=path.join(folder,`state-${String(state.revision).padStart(20,'0')}.json`);
    const handle=await io.open(pending,'wx');
    try{await handle.writeFile(JSON.stringify({sha256:digest(state),state}));await handle.sync();}finally{await handle.close();}
    let published=false;
    try{
      for(let attempt=0;;attempt++) {
        try{await io.link(pending,target);published=true;break;}
        catch(error){
          if(error.code==='EEXIST') {
            if(digest(await readFile(target))!==digest(state))throw Error('JOB_REVISION_CONFLICT');
            published=true;break;
          }
          if(!['EPERM','EACCES','EBUSY'].includes(error.code)||attempt>=5)throw error;
          await new Promise(resolve=>setTimeout(resolve,20*(attempt+1)));
        }
      }
    }finally{
      // Failure evidence stays available when publication was not confirmed.
      // Cleanup failure must never reverse an already published terminal result.
      if(published)await io.unlink(pending).catch(()=>{});
    }
  }
  async function recoverTerminal(folder) {
    const current=await read(folder);
    if(current.state!=='RUNNING')return current;
    const candidates=[];
    for(const name of (await io.readdir(folder)).filter(name=>/^[a-f0-9-]{36}\.pending$/.test(name))) {
      let candidate;
      try{candidate=await readFile(path.join(folder,name));}catch{continue;}
      if(candidate.revision===current.revision+1&&['BUILT','FAILED','CANCELLED'].includes(candidate.state)
          &&candidate.jobId===current.jobId&&candidate.projectId===current.projectId
          &&candidate.requestDigest===current.requestDigest)candidates.push(candidate);
    }
    if(!candidates.length)return current;
    if(new Set(candidates.map(digest)).size!==1)throw Error('JOB_TERMINAL_CONFLICT');
    // Only publish a previously validated terminal receipt. Never invoke the engine.
    await write(folder,candidates[0]);
    return candidates[0];
  }
  return {read,write,recoverTerminal};
}
