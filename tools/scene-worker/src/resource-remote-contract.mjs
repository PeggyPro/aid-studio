import {createHash} from 'node:crypto';
export const RESOURCE_REQUEST_BYTES=68*1024*1024;
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const formats=new Set(['pdf','pptx','ppt','docx','doc','html','md','font','svg','image','psd','video']);
export function validateResourceJob(job) {
  if(!job||Object.keys(job).some(k=>!['jobId','projectId','sourceSha256','format','pages','videoWindow','baseUrl','references'].includes(k))||
    !['jobId','projectId'].every(k=>typeof job[k]==='string'&&/^[A-Za-z0-9_-]{1,100}$/.test(job[k]))||
    !/^[a-f0-9]{64}$/.test(job.sourceSha256||'')||!formats.has(job.format))throw Error('RESOURCE_REQUEST_INVALID');
  if(job.pages!==undefined&&(!Array.isArray(job.pages)||!job.pages.length||job.pages.length>8||new Set(job.pages).size!==job.pages.length||job.pages.some(p=>!Number.isInteger(p)||p<0||p>=500)||job.format==='video'))throw Error('RESOURCE_DOCUMENT_PAGE_SELECTION_INVALID');
  if(job.videoWindow!==undefined){
    const w=job.videoWindow;
    if(job.format!=='video'||!w||Object.keys(w).some(k=>!['startSeconds','durationSeconds','frameCount'].includes(k))||
      !Number.isFinite(w.startSeconds)||w.startSeconds<0||!Number.isFinite(w.durationSeconds)||w.durationSeconds<=0||w.durationSeconds>60||!Number.isInteger(w.frameCount)||w.frameCount<1||w.frameCount>8)throw Error('RESOURCE_MEDIA_WINDOW_INVALID');
  }
  if(job.baseUrl!==undefined){let u;try{u=new URL(job.baseUrl);}catch{throw Error('RESOURCE_URL_INVALID');}if(!['https:','http:'].includes(u.protocol)||u.username||u.password||job.baseUrl.length>8192)throw Error('RESOURCE_URL_INVALID');}
  const refs=job.references??[];
  if(!Array.isArray(refs)||refs.length>16||refs.length&&job.format!=='svg'||new Set(refs.map(r=>r?.url)).size!==refs.length)throw Error('RESOURCE_REFERENCE_INVALID');
  for(const ref of refs)if(!ref||Object.keys(ref).some(k=>!['url','sha256'].includes(k))||typeof ref.url!=='string'||ref.url.length>8192||!/^[a-f0-9]{64}$/.test(ref.sha256||''))throw Error('RESOURCE_REFERENCE_INVALID');
  return job;
}
export function decodeResourceBytes(value,sha256,maxBytes){
  if(typeof value!=='string'||!value.length||value.length>Math.ceil(maxBytes/3)*4||/[^A-Za-z0-9+/=]/.test(value))throw Error('RESOURCE_INPUT_INVALID');
  const bytes=Buffer.from(value,'base64');
  if(!bytes.length||bytes.length>maxBytes||bytes.toString('base64')!==value||hash(bytes)!==sha256)throw Error('RESOURCE_INPUT_INVALID');
  return bytes;
}
