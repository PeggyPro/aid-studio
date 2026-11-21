import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { buildLocalDevelopment } from './build.mjs';

// Java launches this entry with an operator-owned profile and immutable request
// file. Do not expose it as an unauthenticated HTTP server.
const [profilePath, requestPath] = process.argv.slice(2);
const boundedJson = async (file, limit) => {
  if (!file || !path.isAbsolute(file)) throw new Error('ABSOLUTE_PATH_REQUIRED');
  const stat=await fs.lstat(file);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size>limit) throw new Error('INVALID_INPUT_FILE');
  return JSON.parse(await fs.readFile(file,'utf8'));
};
let receiptPath;
let requestDigest;
try {
  const profile=await boundedJson(profilePath,16384);
  if (profile.mode!=='LOCAL_DEVELOPMENT_PROCESS' || profile.enabled!==true)
    throw new Error('LOCAL_EXECUTION_DISABLED');
  for(const key of ['sourceRoot','executionRoot','blenderExecutable'])
    if(typeof profile[key]!=='string' || !path.isAbsolute(profile[key])) throw new Error('INVALID_LOCAL_PROFILE');
  const request=await boundedJson(requestPath,32768);
  // Resolve source from server-owned project/version, never a model-supplied path.
  const {validateJob}=await import('./contract.mjs');
  validateJob(request);
  requestDigest=createHash('sha256').update(JSON.stringify(Object.fromEntries(Object.entries(request).sort(([a],[b])=>a.localeCompare(b))))).digest('hex');
  const receipts=path.join(profile.executionRoot,'.receipts');
  await fs.mkdir(receipts,{recursive:true});
  const control=path.join(receipts,request.jobId);
  try { await fs.mkdir(control); }
  catch(error) {
    if(error.code!=='EEXIST') throw error;
    let prior;
    try { prior=await boundedJson(path.join(control,'result.json'),1048576); }
    catch { process.stdout.write(JSON.stringify({type:'error',code:'WORKER_RECEIPT_PENDING',state:'UNKNOWN'})+'\n'); process.exit(2); }
    if(prior.requestDigest!==requestDigest) throw new Error('JOB_IDEMPOTENCY_CONFLICT');
    process.stdout.write(JSON.stringify(prior.event)+'\n');
    process.exit(prior.event.type==='result'?0:1);
  }
  receiptPath=path.join(control,'result.json');
  const sourceRoot=await fs.realpath(profile.sourceRoot);
  const source=await fs.realpath(path.join(sourceRoot,request.projectId,'versions',request.sourceVersionId));
  const relative=path.relative(sourceRoot,source);
  if(!relative || relative.startsWith('..') || path.isAbsolute(relative)) throw new Error('SOURCE_PATH_ESCAPE');
  const controller=new AbortController();
  process.once('SIGINT',()=>controller.abort());
  process.once('SIGTERM',()=>controller.abort());
  const result=await buildLocalDevelopment(request,{sourceDirectory:source,
    executionRoot:profile.executionRoot,blenderExecutable:profile.blenderExecutable,
    localDevelopmentEnabled:true,signal:controller.signal,
    onStage:event=>process.stdout.write(JSON.stringify({type:'stage',...event})+'\n')});
  const event={type:'result',...result};
  await persist(event);
  process.stdout.write(JSON.stringify(event)+'\n');
} catch(error) {
  // Detailed source diagnostics stay in the task log, not process stdout.
  const event={type:'error',code:error.code ?? (['JOB_IDEMPOTENCY_CONFLICT','LOCAL_EXECUTION_DISABLED'].includes(error.message)?error.message:'LOCAL_WORKER_REJECTED'),
    outputDirectory:error.outputDirectory ?? null};
  if(receiptPath) await persist(event);
  process.stdout.write(JSON.stringify(event)+'\n');
  process.exitCode=1;
}

async function persist(event) {
  const pending=receiptPath+'.pending';
  const handle=await fs.open(pending,'wx');
  try { await handle.writeFile(JSON.stringify({requestDigest,event})); await handle.sync(); }
  finally { await handle.close(); }
  await fs.rename(pending,receiptPath);
}
