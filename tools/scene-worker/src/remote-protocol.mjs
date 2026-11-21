import {createHash,createHmac,randomBytes,timingSafeEqual} from 'node:crypto';
import http from 'node:http';
import https from 'node:https';
import {BuildError} from './errors.mjs';

export const REMOTE_PROTOCOL='aid.scene-compute/1';
const canonical=value=>{
  if(value===null||typeof value==='string'||typeof value==='boolean')return JSON.stringify(value);
  if(typeof value==='number'&&Number.isFinite(value))return JSON.stringify(value);
  if(Array.isArray(value))return '['+value.map(canonical).join(',')+']';
  if(value&&typeof value==='object'&&Object.getPrototypeOf(value)===Object.prototype)
    return '{'+Object.keys(value).sort().map(key=>JSON.stringify(key)+':'+canonical(value[key])).join(',')+'}';
  throw new BuildError('INVALID_COMPUTE_PAYLOAD','计算请求包含不可序列化内容');
};
function keyBytes(secret){const key=Buffer.isBuffer(secret)?secret:Buffer.from(secret||'','base64');if(key.length<32)throw new BuildError('COMPUTE_KEY_INVALID','计算节点凭证配置无效');return key;}
export function signComputeRequest({method,path,workerId,body,secret,now=Date.now(),nonce=randomBytes(24).toString('hex')}) {
  if(!['GET','POST'].includes(method)||!/^\/v1\/[a-z0-9/-]+$/.test(path)||!/^[-a-zA-Z0-9_]{1,100}$/.test(workerId))throw new BuildError('INVALID_COMPUTE_REQUEST','计算路由无效');
  const payload=Buffer.from(canonical(body));
  const digest=createHash('sha256').update(payload).digest('hex');
  const authorization={protocol:REMOTE_PROTOCOL,workerId,issuedAt:now,expiresAt:now+60000,nonce,method,path,digest};
  const signature=createHmac('sha256',keyBytes(secret)).update(canonical(authorization)).digest('hex');
  return {payload,authorization,signature};
}
/** One verifier belongs to one worker credential. Nonces are bounded and expire; retries use a new nonce and the same job id. */
export function createComputeVerifier({workerId,secret,now=()=>Date.now(),maxBytes=12*1024*1024}) {
  const nonces=new Map(),key=keyBytes(secret);
  return ({method,path,payload,authorization,signature})=>{
    const reject=()=>{throw new BuildError('COMPUTE_AUTH_REJECTED','计算请求认证失败');};
    if(!Buffer.isBuffer(payload)||payload.length>maxBytes||!authorization||typeof signature!=='string'||!/^[a-f0-9]{64}$/.test(signature))reject();
    const time=now(),a=authorization;
    if(a.protocol!==REMOTE_PROTOCOL||a.workerId!==workerId||a.method!==method||a.path!==path
      ||!Number.isSafeInteger(a.issuedAt)||!Number.isSafeInteger(a.expiresAt)||a.issuedAt>time+10000||a.expiresAt<time
      ||a.expiresAt-a.issuedAt>60000||a.expiresAt<=a.issuedAt||typeof a.nonce!=='string'||!/^[a-f0-9]{48}$/.test(a.nonce)
      ||a.digest!==createHash('sha256').update(payload).digest('hex'))reject();
    const expected=createHmac('sha256',key).update(canonical(a)).digest();
    if(!timingSafeEqual(expected,Buffer.from(signature,'hex')))reject();
    for(const [nonce,expiry] of nonces)if(expiry<time)nonces.delete(nonce);
    if(nonces.has(a.nonce)||nonces.size>=10000)reject();
    let body;try{body=JSON.parse(payload.toString('utf8'));if(canonical(body)!==payload.toString('utf8'))reject();}catch{reject();}
    nonces.set(a.nonce,a.expiresAt);return body;
  };
}

export class RemoteComputeClient {
  constructor({endpoint,workerId,secret,localAcceptance=false,timeoutMs=30000,maxResponseBytes=1024*1024,tlsCaPem=null}) {
    const url=new URL(endpoint);
    if(url.username||url.password||url.search||url.hash||url.pathname!=='/'
      ||url.protocol!=='https:'&&!(localAcceptance&&url.protocol==='http:'&&['127.0.0.1','[::1]'].includes(url.hostname)))
      throw new BuildError('COMPUTE_TLS_REQUIRED','独立计算节点必须使用HTTPS；本地验收仅允许回环地址');
    if(!Number.isSafeInteger(timeoutMs)||timeoutMs<1||timeoutMs>120000||!Number.isSafeInteger(maxResponseBytes)||maxResponseBytes<1||maxResponseBytes>16*1024*1024)
      throw new BuildError('COMPUTE_LIMIT_INVALID','计算连接限额无效');
    if(tlsCaPem!=null&&tlsCaPem!=='') {
      if(typeof tlsCaPem!=='string'||Buffer.byteLength(tlsCaPem)>65536||tlsCaPem.includes('PRIVATE KEY')||!tlsCaPem.includes('-----BEGIN CERTIFICATE-----'))
        throw new BuildError('COMPUTE_CA_INVALID','节点CA公共证书配置无效');
    }
    this.tlsCaPem=tlsCaPem||undefined;
    this.endpoint=url;this.workerId=workerId;this.secret=keyBytes(secret);this.timeoutMs=timeoutMs;this.maxResponseBytes=maxResponseBytes;
  }
  async request(method,path,body,{signal}={}) {
    const signed=signComputeRequest({method,path,body,workerId:this.workerId,secret:this.secret});
    const transport=this.endpoint.protocol==='https:'?https:http;
    return new Promise((resolve,reject)=>{
      // Native transport uses normal TLS validation, no redirect or environment proxy discovery.
      const request=transport.request(new URL(path,this.endpoint),{method,signal,timeout:this.timeoutMs,ca:this.tlsCaPem,
        headers:{'content-type':'application/json','content-length':signed.payload.length,
          'x-aid-compute-auth':Buffer.from(JSON.stringify(signed.authorization)).toString('base64url'),'x-aid-compute-signature':signed.signature}},response=>{
        let total=0;const chunks=[];
        response.on('data',chunk=>{total+=chunk.length;if(total>this.maxResponseBytes){request.destroy(new BuildError('COMPUTE_RESPONSE_LIMIT','计算节点响应超限'));return;}chunks.push(chunk);});
        response.on('error',()=>reject(new BuildError('COMPUTE_RESPONSE_UNKNOWN','计算响应中断，应核对原任务状态')));
        response.on('end',()=>{
          if(response.statusCode<200||response.statusCode>=300)return reject(new BuildError(response.statusCode>=500?'COMPUTE_RESPONSE_UNKNOWN':'COMPUTE_HTTP_REJECTED',`计算节点响应 ${response.statusCode}`));
          try {const value=JSON.parse(Buffer.concat(chunks).toString('utf8'));if(value.protocol!==REMOTE_PROTOCOL||value.workerId!==this.workerId)throw Error();resolve(value);}
          catch{reject(new BuildError('COMPUTE_RESPONSE_INVALID','计算节点回执身份无效'));}
        });
      });
      request.on('timeout',()=>request.destroy(new BuildError('COMPUTE_RESPONSE_UNKNOWN','计算连接超时，应查询原任务，不得直接重复提交')));
      request.on('error',error=>reject(error instanceof BuildError?error:new BuildError('COMPUTE_RESPONSE_UNKNOWN','计算连接中断，应查询原任务，不得直接重复提交')));request.end(signed.payload);
    });
  }
  submit(job,snapshot,options){return this.request('POST','/v1/jobs',{job,snapshot},options);}
  status(jobId,projectId,options){return this.request('POST','/v1/jobs/status',{jobId,projectId},options);}
  cancel(jobId,projectId,expectedRevision,options){return this.request('POST','/v1/jobs/cancel',{jobId,projectId,expectedRevision},options);}
  capabilities(options){return this.request('POST','/v1/capabilities',{},options);}
  artifactChunk(jobId,projectId,name,offset,length,options){return this.request('POST','/v1/jobs/artifact',{jobId,projectId,name,offset,length},options);}
}
