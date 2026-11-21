import fs from 'node:fs/promises';
import path from 'node:path';

export async function readOperatorFile(file,limit=16384,privateFile=false) {
 if(typeof file!=='string'||!path.isAbsolute(file))throw Error('PROFILE_ABSOLUTE_PATH_REQUIRED');
 const stat=await fs.lstat(file);
 if(!stat.isFile()||stat.isSymbolicLink()||stat.size<1||stat.size>limit)throw Error('PROFILE_FILE_INVALID');
 if(privateFile&&process.platform!=='win32'&&(stat.mode&0o077))throw Error('CREDENTIAL_PERMISSIONS_INVALID');
 return fs.readFile(file);
}
export function validateWorkerProfile(profile) {
 if(!profile||profile.enabled!==true||!['LOCAL_ACCEPTANCE','ISOLATED_COMPUTE'].includes(profile.mode))throw Error('WORKER_DISABLED');
 const local=profile.mode==='LOCAL_ACCEPTANCE';
 if(typeof profile.workerId!=='string'||!/^[A-Za-z0-9_-]{1,100}$/.test(profile.workerId))throw Error('WORKER_ID_INVALID');
 if(!Number.isInteger(profile.port)||profile.port<(local?0:1)||profile.port>65535)throw Error('WORKER_PORT_INVALID');
 if(!Number.isInteger(profile.concurrency)||profile.concurrency<1||profile.concurrency>4)throw Error('WORKER_CONCURRENCY_INVALID');
 for(const key of ['stateRoot','executionRoot','credentialFile'])if(typeof profile[key]!=='string'||!path.isAbsolute(profile[key]))throw Error('WORKER_PATH_INVALID');
 const a=path.resolve(profile.stateRoot),b=path.resolve(profile.executionRoot);
 if(a===b||!path.relative(a,b).startsWith('..')&&!path.isAbsolute(path.relative(a,b))||!path.relative(b,a).startsWith('..')&&!path.isAbsolute(path.relative(b,a)))throw Error('WORKER_ROOTS_OVERLAP');
 const credential=path.resolve(profile.credentialFile);
 for(const root of [a,b]){const rel=path.relative(root,credential);if(!rel.startsWith('..')&&!path.isAbsolute(rel))throw Error('CREDENTIAL_IN_JOB_ROOT');}
 if(local){if(typeof profile.blenderExecutable!=='string'||!path.isAbsolute(profile.blenderExecutable))throw Error('BLENDER_PATH_INVALID');}
 else{
  if(typeof profile.executorModule!=='string'||!path.isAbsolute(profile.executorModule))throw Error('ISOLATION_CONFIGURATION_REQUIRED');
  if(profile.transport==='HTTPS_LOOPBACK_INGRESS'){
   let origin;try{origin=new URL(profile.httpsIngressOrigin);}catch{throw Error('HTTPS_INGRESS_ORIGIN_REQUIRED');}
   if(origin.protocol!=='https:'||origin.username||origin.password||origin.pathname!=='/'||origin.search||origin.hash
       ||['localhost','127.0.0.1','[::1]'].includes(origin.hostname))throw Error('HTTPS_INGRESS_ORIGIN_INVALID');
  }else{
   if(profile.transport!==undefined&&profile.transport!=='DIRECT_TLS')throw Error('WORKER_TRANSPORT_INVALID');
   for(const key of ['tlsKeyFile','tlsCertFile'])if(typeof profile[key]!=='string'||!path.isAbsolute(profile[key]))throw Error('ISOLATION_CONFIGURATION_REQUIRED');
  }
  if(!/^[a-f0-9]{64}$/.test(profile.executorSha256??''))throw Error('ISOLATION_ADAPTER_UNPINNED');
 }
 return profile;
}
export async function loadWorkerProfile(file) {
 return validateWorkerProfile(JSON.parse((await readOperatorFile(file)).toString('utf8')));
}
