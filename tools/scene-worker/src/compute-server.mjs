import fs from 'node:fs/promises';
import {createReadStream} from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import https from 'node:https';
import {createHash} from 'node:crypto';
import {REMOTE_PROTOCOL,createComputeVerifier} from './remote-protocol.mjs';
import {validateJob,DEFAULT_LIMITS} from './contract.mjs';
import {readSnapshot} from './snapshot.mjs';
import {createJobLedger} from './job-ledger.mjs';
import {RESOURCE_REQUEST_BYTES,validateResourceJob,decodeResourceBytes} from './resource-remote-contract.mjs';
import {observeComputeResources} from './resource-metrics.mjs';
import {createManagedCapacity} from './managed-capacity.mjs';
import {archivedResultDigest} from './archive-receipt.mjs';
import {releaseArchivedInputs} from './execution-scratch.mjs';

/** The caller supplies an operator-owned executor. Production refuses an unverified process adapter. */
export async function createComputeServer({workerId,secret,stateRoot,executionRoot,executor,engines=['threejs','blender'],
  concurrency=1,localAcceptance=false,tls,limits=DEFAULT_LIMITS,jobLedger,httpsLoopbackIngress}) {
  if(!path.isAbsolute(stateRoot)||!path.isAbsolute(executionRoot)||!Number.isInteger(concurrency)||concurrency<1||concurrency>4
      ||typeof executor?.execute!=='function'||!Array.isArray(engines)||!engines.length||new Set(engines).size!==engines.length||engines.some(e=>!['threejs','blender'].includes(e)))throw Error('INVALID_WORKER_PROFILE');
  let ingress=false;
  if(httpsLoopbackIngress!==undefined){
    const origin=new URL(httpsLoopbackIngress);
    if(localAcceptance||origin.protocol!=='https:'||origin.username||origin.password||origin.pathname!=='/'||origin.search||origin.hash
        ||['localhost','127.0.0.1','[::1]'].includes(origin.hostname))throw Error('HTTPS_INGRESS_ORIGIN_INVALID');
    ingress=true;
  }
  if(!localAcceptance&&((!ingress&&(!tls?.key||!tls?.cert))||executor.securityMode!=='VERIFIED_ISOLATION'))throw Error('VERIFIED_REMOTE_ISOLATION_REQUIRED');
  await fs.mkdir(stateRoot,{recursive:true});await fs.mkdir(executionRoot,{recursive:true});
  if((await fs.lstat(stateRoot)).isSymbolicLink()||(await fs.lstat(executionRoot)).isSymbolicLink())throw Error('INVALID_WORKER_ROOT');
  const root=await fs.realpath(stateRoot),outputRoot=await fs.realpath(executionRoot),verify=createComputeVerifier({workerId,secret,maxBytes:RESOURCE_REQUEST_BYTES});
  const queued=[],running=new Map(),uncertain=new Map(),work=new Set();let active=0,closed=false;
  const taskRoutes=[...engines.map(engine=>({engine,taskKind:'build'})),
    ...(typeof executor.preview==='function'?[{engine:executor.previewEngine||'blender',taskKind:'preview'}]:[]),
    ...(typeof executor.resource==='function'?[{engine:executor.resourceEngine||'utility',taskKind:'resource'}]:[])];
  const identity=body=>{
    if(!body||!['jobId','projectId'].every(k=>typeof body[k]==='string'&&/^[A-Za-z0-9_-]{1,100}$/.test(body[k])))throw Error('INVALID_JOB_ID');
    return createHash('sha256').update(body.projectId+':'+body.jobId).digest('hex');
  };
  const folderFor=body=>path.join(root,identity(body));
  const ledger=jobLedger||createJobLedger(),load=ledger.read,save=ledger.write;
  const capacity=await createManagedCapacity({root,routes:taskRoutes,localAcceptance,unmanagedConcurrency:concurrency,
    observe:()=>observeComputeResources(outputRoot,executor)});
  async function admitInput(body){
    // Queue inputs live outside per-execution sandboxes. They must not consume the
    // administrator's disk floor or scratch already promised to accepted jobs.
    const committed=capacity.committedScratchBytes();
    if(committed===Number.MAX_SAFE_INTEGER){const error=Error('COMPUTE_CAPACITY_UNCONFIRMED');error.code='COMPUTE_CAPACITY_UNCONFIRMED';throw error;}
    const required=BigInt(Buffer.byteLength(JSON.stringify(body))+262144)
      +BigInt(capacity.configuration?.limits.minimumFreeBytes??0)+BigInt(committed);
    for(const directory of new Set([root,outputRoot])){
      const disk=await fs.statfs(directory,{bigint:true});
      if(disk.bavail*disk.bsize<required){const error=Error('COMPUTE_STORAGE_FULL');error.code='COMPUTE_STORAGE_FULL';throw error;}
    }
  }
  let submissions=Promise.resolve();
  function submitBounded(kind,body,submitter){
    const operation=submissions.then(async()=>{
      const job=body?.job,key=identity(job),config=capacity.configuration;
      if(!localAcceptance&&!config)throw Error('COMPUTE_CONFIG_REQUIRED');
      let existing=false;try{await fs.lstat(folderFor(job));existing=true;}catch(error){if(error.code!=='ENOENT')throw error;}
      if(config&&!existing){
        const taskKind=kind==='PREVIEW'?'preview':kind==='RESOURCE'?'resource':'build';
        const engine=taskKind==='build'?job.engine:taskRoutes.find(route=>route.taskKind===taskKind)?.engine;
        if(!config.slots.some(slot=>slot.engine===engine&&slot.taskKind===taskKind))throw Error('COMPUTE_TASK_NOT_CONFIGURED');
        if((config.limits.engineConcurrent[engine]??config.limits.maxConcurrent)===0||(config.limits.taskConcurrent[taskKind]??config.limits.maxConcurrent)===0)throw Error('COMPUTE_TASK_DISABLED');
        const observed=await observeComputeResources(outputRoot,executor);
        const reservation=capacity.reserve(key,engine,taskKind,observed.scratchFreeBytes);
        if(reservation===null&&queued.length>=config.queueLimit)throw Error('COMPUTE_QUEUE_FULL');
      }
      try{const result=await submitter(body);if(['FAILED','CANCELLED','BUILT'].includes(result.state)){capacity.release(key);pump();}return result;}
      catch(error){
        // No ledger means no submission. Otherwise retain the place until its state is reconciled.
        try{await fs.lstat(folderFor(job));}catch(missing){if(missing.code==='ENOENT'){capacity.release(key);pump();}}
        throw error;
      }
    });submissions=operation.catch(()=>{});return operation;
  }
  // A restart must account for work whose terminal receipt has not been established.
  for(const name of await fs.readdir(root))if(/^[a-f0-9]{64}$/.test(name)){
    try{
      const folder=path.join(root,name),state=ledger.recoverTerminal?await ledger.recoverTerminal(folder):await load(folder);
      if(['RUNNING','PREPARING','UNKNOWN'].includes(state.state))capacity.restore(name,state.reservation);
      else if(state.state==='QUEUED'){
        try{await fs.lstat(path.join(folder,'execution-claim'));capacity.restore(name,state.reservation);}catch(error){if(error.code!=='ENOENT')throw error;}
      }
    }catch{capacity.restore(name,null);}
  }
  async function reconcilePersistence(key,folder) {
    const pending=uncertain.get(key);
    const saved=pending?.terminal??pending?.queued;
    if(!saved||pending.nextRetryAt>Date.now())return;
    pending.nextRetryAt=Date.now()+1000;
    try{await save(folder,saved);uncertain.delete(key);capacity.release(key);pump();}catch{/* Only retry receipt persistence, never execution. */}
  }
  const publicState=state=>({protocol:REMOTE_PROTOCOL,workerId,jobId:state.jobId,projectId:state.projectId,state:state.state,
    revision:state.revision,errorCode:state.errorCode,...(state.result?{result:state.result}:{}),retrySubmissionAllowed:false});
  const unresolvedState=(key,state)=>({...state,state:'UNKNOWN',errorCode:uncertain.get(key)?.phase==='EXECUTION_UNCONFIRMED'
    ?uncertain.get(key).errorCode:'JOB_LEDGER_UNCONFIRMED'});
  async function rejectIntake(key,folder,state,error){
    const terminal={...state,state:'FAILED',revision:state.revision+1,errorCode:error.code||(/^[A-Z_]{1,80}$/.test(error.message)?error.message:'INPUT_PREPARATION_FAILED')};
    try{await save(folder,terminal);return publicState(terminal);}
    catch{uncertain.set(key,{phase:'PERSISTING_TERMINAL',terminal});return publicState({...state,state:'UNKNOWN',errorCode:'JOB_LEDGER_UNCONFIRMED'});}
  }
  function acknowledgeArchive(body){
    const operation=submissions.then(async()=>{
      const key=identity(body),folder=folderFor(body),state=await load(folder);
      if(state.projectId!==body.projectId||state.jobId!==body.jobId)throw Error('JOB_SCOPE_MISMATCH');
      if(state.state!=='BUILT'||running.has(key)||uncertain.has(key))throw Error('ARCHIVE_RESULT_NOT_CONFIRMED');
      if(!/^[a-f0-9]{64}$/.test(body.resultDigest??'')||body.resultDigest!==archivedResultDigest(state.result))throw Error('ARCHIVE_RESULT_MISMATCH');
      const archiveFolder=path.join(folder,'archive');await fs.mkdir(archiveFolder,{recursive:true});
      if((await fs.lstat(archiveFolder)).isSymbolicLink())throw Error('ARCHIVE_RECEIPT_INVALID');
      let receipt;try{receipt=await load(archiveFolder);}catch(error){if(error.code!=='ENOENT')throw error;}
      if(!receipt){
        receipt={jobId:state.jobId,projectId:state.projectId,state:'ACKNOWLEDGED',revision:1,
          archive:state.archive??{resultDigest:body.resultDigest,acknowledgedAt:new Date().toISOString(),inputCleanup:'PENDING'}};
        // Do not remove anything unless the acknowledgement is durably visible.
        // Keep the maintenance receipt small; never duplicate the full result.
        await save(archiveFolder,receipt);
      }
      if(receipt.projectId!==state.projectId||receipt.jobId!==state.jobId||receipt.archive.resultDigest!==body.resultDigest)throw Error('ARCHIVE_RECEIPT_CONFLICT');
      if(receipt.archive.inputCleanup!=='RELEASED'){
        const removed=await releaseArchivedInputs(root,folder);
        receipt={...receipt,revision:receipt.revision+1,archive:{...receipt.archive,inputCleanup:'RELEASED',removed}};await save(archiveFolder,receipt);
      }
      return {protocol:REMOTE_PROTOCOL,workerId,projectId:state.projectId,jobId:state.jobId,...receipt.archive};
    });submissions=operation.catch(()=>{});return operation;
  }
  async function execute(item) {
    const controller=new AbortController();running.set(item.key,controller);active++;
    const timeout=item.reservation?setTimeout(()=>controller.abort(),item.reservation.timeoutSeconds*1000):null;
    let state={jobId:item.job.jobId,projectId:item.job.projectId,state:'UNKNOWN',revision:0};
    let phase='PREPARING';
    try{
      state=await load(item.folder);
      if(state.state!=='QUEUED')return;
      // An exclusive claim survives crashes. A restarted worker never silently reexecutes it.
      await fs.mkdir(path.join(item.folder,'execution-claim'));
      state={...state,state:'RUNNING',revision:state.revision+1,...(item.reservation?{reservation:item.reservation}:{})};await save(item.folder,state);
      phase='EXECUTING';
      const context={sourceDirectory:path.join(item.folder,'source'),executionRoot:outputRoot,signal:controller.signal,limits,
        ...(item.reservation?{resourceReservation:item.reservation}:{})};
      const built=item.kind==='PREVIEW'
        ? await executor.preview(item.job,{...context,glbPath:item.glbPath})
        :item.kind==='RESOURCE'?await executor.resource(item.job,{...context,resourceDirectory:path.join(item.folder,'resource')})
        : await executor.execute(item.job,context);
      phase='VALIDATING_RESULT';
      const location=await fs.realpath(built.outputDirectory),relative=path.relative(outputRoot,location);
      if(relative.startsWith('..')||path.isAbsolute(relative)||built.result?.projectId!==state.projectId||built.result?.jobId!==state.jobId
          ||built.result?.sourceSha256!==item.job.sourceSha256)throw Error('WORKER_RESULT_IDENTITY_INVALID');
      phase='PERSISTING_RESULT';
      state={...state,state:'BUILT',revision:state.revision+1,result:built.result,outputDirectory:location,
        ...(built.scratchCleanup?{scratchCleanup:built.scratchCleanup}:{})};await save(item.folder,state);
    }catch(error){
      if(phase==='EXECUTING'&&error.outcomeUnknown===true) {
        uncertain.set(item.key,{phase:'EXECUTION_UNCONFIRMED',errorCode:error.code||'WORKER_EXECUTION_UNCONFIRMED'});
      }else if(phase==='EXECUTING') {
        state={...state,state:controller.signal.aborted?'CANCELLED':'FAILED',revision:state.revision+1,errorCode:error.code||'WORKER_EXECUTION_FAILED'};
        try{await save(item.folder,state);}catch(persistence){uncertain.set(item.key,{phase:'PERSISTING_TERMINAL',terminal:state,errorCode:persistence.code||'JOB_LEDGER_UNAVAILABLE'});}
      } else uncertain.set(item.key,{phase,...(phase==='PERSISTING_RESULT'?{terminal:state}:{}),errorCode:error.code||'JOB_LEDGER_UNAVAILABLE'});
    }finally{if(timeout)clearTimeout(timeout);if(!uncertain.has(item.key))capacity.release(item.key);running.delete(item.key);active--;pump();}
  }
  let pumping=false,pumpRequested=false;
  function pump(){
    if(closed)return;if(pumping){pumpRequested=true;return;}pumping=true;
    void (async()=>{
      const resources=capacity.configuration?await observeComputeResources(outputRoot,executor):null;
      for(let index=0;!closed&&index<queued.length&&active<(capacity.configuration?.limits.maxConcurrent??concurrency);){
        const item=queued[index],taskKind=item.kind==='PREVIEW'?'preview':item.kind==='RESOURCE'?'resource':'build';
        const engine=taskKind==='build'?item.job.engine:taskRoutes.find(route=>route.taskKind===taskKind)?.engine;
        const reservation=capacity.reservation(item.key)??capacity.reserve(item.key,engine,taskKind,resources?.scratchFreeBytes);
        if(reservation===null){index++;continue;}
        queued.splice(index,1);item.reservation=reservation;
        const task=execute(item);work.add(task);void task.catch(()=>{}).finally(()=>work.delete(task));
      }
    })().catch(()=>{/* Failed resource observation never grants a new slot. */}).finally(()=>{pumping=false;if(pumpRequested){pumpRequested=false;pump();}});
  }
  async function restoreQueued(state,key,folder) {
    if(closed||state.state!=='QUEUED'||!state.job||running.has(key)||queued.some(item=>item.key===key)||uncertain.has(key))return;
    // A persisted execution claim means a previous process may have started work. Never resubmit it.
    try{await fs.lstat(path.join(folder,'execution-claim'));return;}catch(error){if(error.code!=='ENOENT')throw error;}
    const job=state.job;if(job.jobId!==state.jobId||job.projectId!==state.projectId||identity(job)!==key)throw Error('JOB_SCOPE_MISMATCH');
    const item={key,folder,job,kind:state.kind};
    if(state.kind==='RESOURCE'){
      validateResourceJob(job);if(typeof executor.resource!=='function')return;
    }else if(state.kind==='PREVIEW'){
      if(typeof executor.preview!=='function')return;
      const build=await load(folderFor({jobId:job.buildJobId,projectId:job.projectId}));
      if(build.state!=='BUILT'||build.result?.sourceSha256!==job.sourceSha256||build.result?.artifact?.sha256!==job.artifactSha256)throw Error('PREVIEW_SOURCE_CONFLICT');
      const directory=await fs.realpath(build.outputDirectory),relative=path.relative(outputRoot,directory);
      if(relative.startsWith('..')||path.isAbsolute(relative))throw Error('PREVIEW_SOURCE_CONFLICT');
      item.glbPath=path.join(directory,'scene.glb');
    }else{validateJob(job);if(!engines.includes(job.engine))return;}
    // Recheck after filesystem awaits; concurrent status reads share one queue entry.
    if(!closed&&!running.has(key)&&!queued.some(item=>item.key===key)){queued.push(item);pump();}
  }
  async function submitResource(body) {
    if(typeof executor.resource!=='function')throw Error('RESOURCE_EXECUTOR_UNAVAILABLE');
    if(!body||Object.keys(body).some(k=>!['job','contentBase64','references'].includes(k)))throw Error('RESOURCE_REQUEST_INVALID');
    const job=validateResourceJob(body.job),key=identity(job),folder=folderFor(job);
    const bytes=decodeResourceBytes(body.contentBase64,job.sourceSha256,32*1024*1024),references=body.references??[];
    if(!Array.isArray(references)||references.length!==(job.references??[]).length)throw Error('RESOURCE_REFERENCE_INVALID');
    let total=0;const decoded=[];
    for(let i=0;i<references.length;i++){
      const expected=job.references[i],ref=references[i];
      if(!ref||ref.url!==expected.url||ref.sha256!==expected.sha256||Object.keys(ref).some(k=>!['url','sha256','contentBase64'].includes(k)))throw Error('RESOURCE_REFERENCE_INVALID');
      const data=decodeResourceBytes(ref.contentBase64,ref.sha256,10*1024*1024);if((total+=data.length)>16*1024*1024)throw Error('RESOURCE_REFERENCE_INVALID');
      decoded.push({sha256:ref.sha256,data});
    }
    const requestDigest=createHash('sha256').update(JSON.stringify({kind:'RESOURCE',job})).digest('hex');
    try{await fs.mkdir(folder);}catch(error){
      if(error.code!=='EEXIST')throw error;
      const state=await load(folder);if(state.requestDigest!==requestDigest)throw Error('JOB_IDEMPOTENCY_CONFLICT');
      return publicState(uncertain.has(key)?unresolvedState(key,state):state);
    }
    let state={jobId:job.jobId,projectId:job.projectId,requestDigest,kind:'RESOURCE',state:'PREPARING',revision:1};await save(folder,state);
    let validated=false;
    try{
      await admitInput(body);
      const resource=path.join(folder,'resource');await fs.mkdir(resource);await fs.writeFile(path.join(resource,'input.bin'),bytes,{flag:'wx'});
      const written=new Set();for(const ref of decoded)if(!written.has(ref.sha256)){await fs.writeFile(path.join(resource,ref.sha256+'.bin'),ref.data,{flag:'wx'});written.add(ref.sha256);}
      validated=true;state={...state,job,state:'QUEUED',revision:2};await save(folder,state);queued.push({key,folder,job,kind:'RESOURCE'});pump();return publicState(state);
    }catch(error){
      if(validated){uncertain.set(key,{phase:'PERSISTING_QUEUE',queued:state,errorCode:error.code||'JOB_LEDGER_UNAVAILABLE'});return publicState({...state,state:'UNKNOWN',errorCode:'JOB_LEDGER_UNCONFIRMED'});}
      return rejectIntake(key,folder,state,error);
    }
  }
  async function submitPreview(body) {
    const {job}=body||{}; identity(job);
    const key=identity(job),folder=folderFor(job),requestDigest=createHash('sha256').update(JSON.stringify({kind:'PREVIEW',job})).digest('hex');
    try{await fs.mkdir(folder);}catch(error){
      if(error.code!=='EEXIST')throw error;
      const state=await load(folder);if(state.requestDigest!==requestDigest)throw Error('JOB_IDEMPOTENCY_CONFLICT');
      return publicState(uncertain.has(key)?unresolvedState(key,state):state);
    }
    let state={jobId:job.jobId,projectId:job.projectId,requestDigest,kind:'PREVIEW',state:'PREPARING',revision:1};await save(folder,state);
    let directory;
    try {
      if(typeof executor.preview!=='function')throw Error('PREVIEW_UNAVAILABLE');
      if(Object.keys(job).some(key=>!['jobId','projectId','buildJobId','sourceSha256','artifactSha256','options','editorOverlay','editorTransform','editorPlayback'].includes(key))
        ||typeof job.buildJobId!=='string'||!/^[A-Za-z0-9_-]{1,100}$/.test(job.buildJobId)
        ||job.jobId===job.buildJobId||!['sourceSha256','artifactSha256'].every(key=>/^[a-f0-9]{64}$/.test(job[key]||''))
        ||JSON.stringify(job).length>240000)throw Error('INVALID_PREVIEW_REQUEST');
      // Validate the original build within the preview's durable intake so a rejected input has a receipt.
      const build=await load(folderFor({jobId:job.buildJobId,projectId:job.projectId})).catch(error=>{
        if(error.code==='ENOENT')throw Error('PREVIEW_SOURCE_UNAVAILABLE');
        throw error;
      });
      if(build.state!=='BUILT'||build.projectId!==job.projectId||build.result?.sourceSha256!==job.sourceSha256
        ||build.result?.artifact?.sha256!==job.artifactSha256)throw Error('PREVIEW_SOURCE_CONFLICT');
      directory=await fs.realpath(build.outputDirectory);
      const relative=path.relative(outputRoot,directory);
      if(relative.startsWith('..')||path.isAbsolute(relative))throw Error('PREVIEW_SOURCE_CONFLICT');
      await admitInput(body);
    } catch(error) {
      return rejectIntake(key,folder,state,error);
    }
    state={...state,job,state:'QUEUED',revision:2};
    try{await save(folder,state);}catch(error){uncertain.set(key,{phase:'PERSISTING_QUEUE',queued:state,errorCode:error.code||'JOB_LEDGER_UNAVAILABLE'});return publicState({...state,state:'UNKNOWN',errorCode:'JOB_LEDGER_UNCONFIRMED'});}
    queued.push({key,folder,job,kind:'PREVIEW',glbPath:path.join(directory,'scene.glb')});pump();return publicState(state);
  }
  async function submit(body) {
    const job=validateJob(body.job);if(!engines.includes(job.engine))throw Error('ENGINE_UNAVAILABLE');
    const key=identity(job),folder=folderFor(job),requestDigest=createHash('sha256').update(JSON.stringify(body)).digest('hex');
    try{await fs.mkdir(folder);}catch(error){
      if(error.code!=='EEXIST')throw error;
      const state=await load(folder);
      if(state.requestDigest!==requestDigest)throw Error('JOB_IDEMPOTENCY_CONFLICT');
      return publicState(uncertain.has(key)?unresolvedState(key,state):state);
    }
    let state={jobId:job.jobId,projectId:job.projectId,requestDigest,state:'PREPARING',revision:1};await save(folder,state);
    let sourceValidated=false;
    try{
      await admitInput(body);
      const files=body.snapshot?.files;
      if(!Array.isArray(files)||!files.length||files.length>limits.files)throw Error('SOURCE_LIMIT');
      const source=path.join(folder,'source');await fs.mkdir(source);let total=0;const names=new Set();
      for(const item of files){
        const name=item.name;
        if(typeof name!=='string'||name.length>240||name.includes('\\')||name.includes(':')||path.posix.isAbsolute(name)
          ||name.split('/').some(part=>!part||part==='.'||part==='..'||/[. ]$/.test(part)||/^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(part))
          ||names.has(name.toLowerCase())||typeof item.contentBase64!=='string'||!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(item.contentBase64))throw Error('SOURCE_PATH_INVALID');
        names.add(name.toLowerCase());const bytes=Buffer.from(item.contentBase64,'base64');total+=bytes.length;
        if(bytes.length>limits.fileBytes||total>limits.sourceBytes)throw Error('SOURCE_LIMIT');
        const file=path.join(source,...name.split('/'));await fs.mkdir(path.dirname(file),{recursive:true});await fs.writeFile(file,bytes,{flag:'wx'});
      }
      const snapshot=await readSnapshot(source,limits);if(snapshot.sha256!==job.sourceSha256)throw Error('SOURCE_VERSION_CONFLICT');
      sourceValidated=true;
      state={...state,job,state:'QUEUED',revision:2};await save(folder,state);queued.push({key,folder,job});pump();return publicState(state);
    }catch(error){
      if(sourceValidated){uncertain.set(key,{phase:'PERSISTING_QUEUE',queued:state,errorCode:error.code||'JOB_LEDGER_UNAVAILABLE'});return publicState({...state,state:'UNKNOWN',errorCode:'JOB_LEDGER_UNCONFIRMED'});}
      state={...state,state:'FAILED',revision:2,errorCode:error.code||(/^[A-Z_]{1,80}$/.test(error.message)?error.message:'SOURCE_VALIDATION_FAILED')};
      try{await save(folder,state);}catch{uncertain.set(key,{phase:'PERSISTING_TERMINAL',terminal:state});return publicState({...state,state:'UNKNOWN',errorCode:'JOB_LEDGER_UNCONFIRMED'});}
      return publicState(state);
    }
  }
  const handler=async(request,response)=>{
    try{
      const rawAuthorization=request.headers['x-aid-compute-auth'],signature=request.headers['x-aid-compute-signature'];
      let authorization;
      try{
        if(typeof rawAuthorization!=='string'||rawAuthorization.length>4096||!/^[A-Za-z0-9_-]+$/.test(rawAuthorization)
            ||typeof signature!=='string'||!/^[a-f0-9]{64}$/.test(signature))throw Error('Invalid authentication envelope');
        authorization=JSON.parse(Buffer.from(rawAuthorization,'base64url').toString('utf8'));
      }catch{const error=new Error('COMPUTE_AUTH_REJECTED');error.code='COMPUTE_AUTH_REJECTED';throw error;}
      const maximum=request.url==='/v1/resources'?RESOURCE_REQUEST_BYTES:12*1024*1024;
      const chunks=[];let bytes=0;for await(const chunk of request){bytes+=chunk.length;if(bytes>maximum)throw Error('REQUEST_LIMIT');chunks.push(chunk);}
      const body=verify({method:request.method,path:request.url,payload:Buffer.concat(chunks),authorization,signature});
      let result;
      if(request.url==='/v1/capabilities')result={protocol:REMOTE_PROTOCOL,workerId,engines,executionEngines:[...new Set(taskRoutes.map(route=>route.engine))],taskRoutes,taskKinds:[...new Set(taskRoutes.map(route=>route.taskKind))],preview:typeof executor.preview==='function',resources:typeof executor.resource==='function',concurrency:capacity.configuration?.limits.maxConcurrent??concurrency,limits,securityMode:executor.securityMode,activeJobs:active,managedConfiguration:capacity.status(),resourcesObserved:await observeComputeResources(outputRoot,executor)};
      else if(request.url==='/v1/configuration/apply'){result={protocol:REMOTE_PROTOCOL,workerId,...await capacity.apply(body)};pump();}
      else if(request.url==='/v1/configuration/status')result={protocol:REMOTE_PROTOCOL,workerId,...capacity.status()};
      else if(request.url==='/v1/jobs/archive')result=await acknowledgeArchive(body);
      else if(request.url==='/v1/jobs')result=await submitBounded('BUILD',body,submit);
      else if(request.url==='/v1/previews')result=await submitBounded('PREVIEW',body,submitPreview);
      else if(request.url==='/v1/resources')result=await submitBounded('RESOURCE',body,submitResource);
      else if(request.url==='/v1/jobs/artifact') {
        const state=await load(folderFor(body));
        if(state.state!=='BUILT'||state.projectId!==body.projectId||state.jobId!==body.jobId)throw Error('ARTIFACT_NOT_READY');
        const metadata=state.kind==='RESOURCE'&&/^(preview|page-[0-9]{1,3})\.png$/.test(body.name)?state.result.previews?.find(item=>item.name===body.name)
          :body.name==='preview.png'&&state.kind==='PREVIEW'?state.result.previewArtifact
          :body.name==='scene.glb'&&!['PREVIEW','RESOURCE'].includes(state.kind)?{name:'scene.glb',sizeBytes:state.result.artifact.glbBytes,sha256:state.result.artifact.sha256}
          :body.name==='scene.blend'&&!['PREVIEW','RESOURCE'].includes(state.kind)?state.result.nativeArtifacts?.find(item=>item.name==='scene.blend'):null;
        if(!metadata||metadata.name!==body.name||!Number.isSafeInteger(body.offset)||body.offset<0||body.offset>=metadata.sizeBytes
          ||!Number.isSafeInteger(body.length)||body.length<1||body.length>262144)throw Error('ARTIFACT_RANGE_INVALID');
        const directory=await fs.realpath(state.outputDirectory),relative=path.relative(outputRoot,directory),file=path.join(directory,metadata.name);
        const stat=await fs.lstat(file);
        if(relative.startsWith('..')||path.isAbsolute(relative)||stat.isSymbolicLink()||!stat.isFile()||stat.size!==metadata.sizeBytes)throw Error('ARTIFACT_INVALID');
        const handle=await fs.open(file,'r');
        try {
          const bytes=Buffer.alloc(Math.min(body.length,metadata.sizeBytes-body.offset));
          const {bytesRead}=await handle.read(bytes,0,bytes.length,body.offset);
          if(bytesRead!==bytes.length)throw Error('ARTIFACT_TRUNCATED');
          result={protocol:REMOTE_PROTOCOL,workerId,jobId:state.jobId,projectId:state.projectId,...metadata,offset:body.offset,
            chunkSha256:createHash('sha256').update(bytes).digest('hex'),contentBase64:bytes.toString('base64')};
        }finally{await handle.close();}
      }
      else if(['/v1/jobs/status','/v1/jobs/cancel'].includes(request.url)) {
        const folder=folderFor(body),key=identity(body);await reconcilePersistence(key,folder);let state=await load(folder);
        if(!running.has(key)&&!uncertain.has(key)&&ledger.recoverTerminal) {
          try{state=await ledger.recoverTerminal(folder);if(['BUILT','FAILED','CANCELLED'].includes(state.state)){capacity.release(key);pump();}}catch{state={...state,state:'UNKNOWN',errorCode:'JOB_LEDGER_UNCONFIRMED'};}
        }
        if(state.projectId!==body.projectId||state.jobId!==body.jobId)throw Error('JOB_SCOPE_MISMATCH');
        if(request.url==='/v1/jobs/status')await restoreQueued(state,key,folder);
        if(request.url==='/v1/jobs/cancel'){
          if(state.revision!==body.expectedRevision)throw Error('JOB_REVISION_CONFLICT');
          const controller=running.get(key);
          if(controller){controller.abort();result={...publicState(state),state:'CANCELLING'};}
          else if(state.state==='QUEUED'){state={...state,state:'CANCELLED',revision:state.revision+1};await save(folder,state);const index=queued.findIndex(item=>item.key===key);if(index>=0)queued.splice(index,1);capacity.release(key);pump();}
        }
        if(!result){
          // No live owner means this process cannot claim that a prior running task failed.
          if(uncertain.has(key)||['RUNNING','PREPARING'].includes(state.state)&&!running.has(key)
              ||state.state==='QUEUED'&&!running.has(key)&&!queued.some(item=>item.key===key)
              ||state.state==='FAILED'&&state.result?.state==='BUILT')state=unresolvedState(key,state);
          result=publicState(state);
        }
      }else{response.writeHead(404);response.end('{}');return;}
      response.setHeader('content-type','application/json');response.end(JSON.stringify(result));
    }catch(error){response.writeHead(error.code==='COMPUTE_AUTH_REJECTED'?401:409);response.end(JSON.stringify({protocol:REMOTE_PROTOCOL,workerId,errorCode:error.code||String(error.message).replace(/[^A-Z0-9_]/g,'').slice(0,80)}));}
  };
  // An explicitly configured HTTPS ingress may reach only a loopback HTTP listener.
  // Clients still require normal HTTPS certificate validation and every route requires the original HMAC.
  const server=localAcceptance||ingress?http.createServer(handler):https.createServer(tls,handler);
  server.requestTimeout=30000;server.headersTimeout=10000;
  return {server,listen:port=>new Promise((resolve,reject)=>{
      const failed=error=>{server.removeListener('listening',ready);reject(error);};
      const ready=()=>{server.removeListener('error',failed);resolve();};
      server.once('error',failed);server.once('listening',ready);
      server.listen(port,localAcceptance||ingress?'127.0.0.1':'0.0.0.0');
    }),
    close:async()=>{closed=true;for(const controller of running.values())controller.abort();await new Promise(resolve=>server.close(resolve));await Promise.allSettled([...work]);},
    localAcceptance};
}
