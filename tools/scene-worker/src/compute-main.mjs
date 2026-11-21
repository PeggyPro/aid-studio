import {pathToFileURL} from 'node:url';
import {createHash} from 'node:crypto';
import {createComputeServer} from './compute-server.mjs';
import {loadWorkerProfile,readOperatorFile} from './worker-profile.mjs';
import {buildLocalDevelopment} from './build.mjs';
import {createPreviewExecutor} from './preview-executor.mjs';
import {createResourceExecutor} from './resource-remote-executor.mjs';

export async function startWorker(profileFile) {
 const profile=await loadWorkerProfile(profileFile),localAcceptance=profile.mode==='LOCAL_ACCEPTANCE';
 const credentials=JSON.parse((await readOperatorFile(profile.credentialFile,4096,true)).toString('utf8'));
 let executor,tls;
 if(localAcceptance){
  executor={securityMode:'LOCAL_ACCEPTANCE',execute:(job,context)=>buildLocalDevelopment(job,{...context,blenderExecutable:profile.blenderExecutable,localDevelopmentEnabled:true}),
   preview:createPreviewExecutor({blenderExecutable:profile.blenderExecutable,localDevelopmentEnabled:true})};
  if(profile.resourceProfileFile)executor.resource=createResourceExecutor(JSON.parse((await readOperatorFile(profile.resourceProfileFile)).toString('utf8')));
 }else{
  const source=await readOperatorFile(profile.executorModule,1048576);
  if(createHash('sha256').update(source).digest('hex')!==profile.executorSha256)throw Error('ISOLATION_ADAPTER_CHANGED');
  const adapter=await import(pathToFileURL(profile.executorModule).href);
  if(typeof adapter.createExecutor!=='function')throw Error('ISOLATION_ADAPTER_INVALID');
  executor=await adapter.createExecutor(profile);
  if(executor?.securityMode!=='VERIFIED_ISOLATION')throw Error('VERIFIED_REMOTE_ISOLATION_REQUIRED');
  if(profile.transport!=='HTTPS_LOOPBACK_INGRESS')tls={key:await readOperatorFile(profile.tlsKeyFile,16384,true),cert:await readOperatorFile(profile.tlsCertFile,65536)};
 }
 const worker=await createComputeServer({workerId:profile.workerId,secret:credentials.secret,stateRoot:profile.stateRoot,executionRoot:profile.executionRoot,
  concurrency:profile.concurrency,engines:profile.engines,executor,localAcceptance,tls,
  httpsLoopbackIngress:profile.transport==='HTTPS_LOOPBACK_INGRESS'?profile.httpsIngressOrigin:undefined});
 try{await worker.listen(profile.port);}catch(error){await worker.close().catch(()=>{});throw error;}
 return worker;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 try{
  const worker=await startWorker(process.argv[2]);
  process.stdout.write(JSON.stringify({state:'LISTENING',address:worker.server.address(),localAcceptance:worker.localAcceptance})+'\n');
  let stopping=false;
  const stop=async()=>{if(stopping)return;stopping=true;const deadline=setTimeout(()=>process.exit(1),40000);deadline.unref();await worker.close();clearTimeout(deadline);};
  process.once('SIGINT',stop);process.once('SIGTERM',stop);
 }catch(error){process.stderr.write(JSON.stringify({errorCode:/^[A-Z0-9_]+$/.test(error.message??'')?error.message:'WORKER_START_FAILED'})+'\n');process.exitCode=1;}
}
