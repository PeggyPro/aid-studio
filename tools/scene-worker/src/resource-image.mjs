import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {runProcess} from './process.mjs';
import {BuildError} from './errors.mjs';

const digest=bytes=>createHash('sha256').update(bytes).digest('hex');
const MAX_INPUT=32*1024*1024,MAX_PREVIEW=16*1024*1024;

/** Operator configured decoder. No executable or disk path can come from model arguments. */
export async function inspectLocalResourceImage({bytes,sha256},{executionRoot,pythonExecutable,localDevelopmentEnabled,signal}) {
  return inspectLocalVisualResource({bytes,sha256,kind:'image'},{executionRoot,pythonExecutable,localDevelopmentEnabled,signal});
}
export async function inspectLocalResourceFont({bytes,sha256},configuration) {
  return inspectLocalVisualResource({bytes,sha256,kind:'font'},configuration);
}
export async function inspectLocalResourceSvg(input,configuration) {
  return inspectLocalVisualResource({...input,kind:'svg'},configuration);
}
async function inspectLocalVisualResource({bytes,sha256,kind,baseUrl,resources=new Map()},{executionRoot,pythonExecutable,nodeExecutable=process.execPath,localDevelopmentEnabled,signal}) {
  if(localDevelopmentEnabled!==true)throw new BuildError('LOCAL_EXECUTION_DISABLED','本地资源解析未启用');
  if(!Buffer.isBuffer(bytes)||bytes.length===0||bytes.length>(kind==='svg'?10*1024*1024:MAX_INPUT)||!/^[a-f0-9]{64}$/.test(sha256||'')||digest(bytes)!==sha256)
    throw new BuildError('RESOURCE_INPUT_INVALID','资源内容或指纹无效');
  const executable=kind==='svg'?nodeExecutable:pythonExecutable;
  if(!path.isAbsolute(executionRoot||'')||!path.isAbsolute(executable||''))throw new BuildError('RESOURCE_CONFIG_INVALID','资源执行目录和执行器必须由运行配置指定');
  const root=await fs.realpath(executionRoot),rootStat=await fs.lstat(executionRoot);
  if(rootStat.isSymbolicLink()||!rootStat.isDirectory())throw new BuildError('RESOURCE_DIRECTORY_INVALID','资源执行目录无效');
  if(signal?.aborted)throw new BuildError('CANCELLED','任务已取消');
  const job=await fs.mkdtemp(path.join(root,`resource-${kind}-`)),temporary=path.join(job,'tmp');await fs.mkdir(temporary);
  await fs.writeFile(path.join(job,'input.bin'),bytes,{flag:'wx'});
  if(kind==='svg'){
    if(baseUrl!==undefined&&(typeof baseUrl!=='string'||baseUrl.length>8192))throw new BuildError('RESOURCE_REFERENCE_INVALID','资源基础地址无效');
    if(!(resources instanceof Map)||resources.size>16)throw new BuildError('RESOURCE_REFERENCE_INVALID','资源引用数量无效');
    const folder=path.join(job,'resources');await fs.mkdir(folder);const written=new Set(),images=[];let total=0;
    for(const [url,data] of resources){if(typeof url!=='string'||url.length>8192||!Buffer.isBuffer(data)||data.length>10*1024*1024||(total+=data.length)>16*1024*1024)throw new BuildError('RESOURCE_REFERENCE_INVALID','资源引用数据无效');const sha=digest(data);if(!written.has(sha)){await fs.writeFile(path.join(folder,sha+'.bin'),data,{flag:'wx'});written.add(sha)}images.push({url,sha256:sha})}
    await fs.writeFile(path.join(job,'resource-map.json'),JSON.stringify({baseUrl,images}),{flag:'wx'});
  }
  const environment={TEMP:temporary,TMP:temporary,TMPDIR:temporary,PYTHONDONTWRITEBYTECODE:'1',PYTHONNOUSERSITE:'1',PYTHONSAFEPATH:'1',PYTHONUTF8:'1'};
  if(process.platform==='win32'){
    environment.SystemRoot=process.env.SystemRoot||'C:\\Windows';environment.WINDIR=environment.SystemRoot;
    environment.PATH=path.dirname(executable)+';'+path.join(environment.SystemRoot,'System32');
  }
  const args=kind==='svg'?['--max-old-space-size=256',fileURLToPath(new URL('./resource-svg-cli.mjs',import.meta.url)),job]:[fileURLToPath(new URL('./resource-image-inspector.py',import.meta.url)),job,kind];
  try{await runProcess(executable,args,{
    cwd:job,env:environment,timeoutMs:30000,logFile:path.join(job,'decoder.log'),logBytes:65536,signal});
  }catch(error){if(error?.code==='SOURCE_EXECUTION_FAILED')throw new BuildError('RESOURCE_IMAGE_DECODE_FAILED','资源预览解析失败');throw error;}
  const receiptPath=path.join(job,'inspection.json'),imagePath=path.join(job,'preview.png');
  for(const [file,limit] of [[receiptPath,65536],[imagePath,MAX_PREVIEW]]){
    const stat=await fs.lstat(file);if(stat.isSymbolicLink()||!stat.isFile()||stat.size===0||stat.size>limit||await fs.realpath(file)!==file)
      throw new BuildError('RESOURCE_RECEIPT_INVALID','资源解析回执无效');
  }
  const metadata=JSON.parse(await fs.readFile(receiptPath,'utf8')),png=await fs.readFile(imagePath);
  if(png.length<24||!png.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10]))||png.toString('ascii',12,16)!=='IHDR'
    ||metadata.sourceSha256!==sha256||metadata.sourceBytes!==bytes.length||metadata.previewSha256!==digest(png)||metadata.previewBytes!==png.length
    ||metadata.previewWidth!==png.readUInt32BE(16)||metadata.previewHeight!==png.readUInt32BE(20)
    ||metadata.previewWidth<1||metadata.previewHeight<1||metadata.previewWidth>2048||metadata.previewHeight>2048)
    throw new BuildError('RESOURCE_RECEIPT_INVALID','资源图片指纹或尺寸不匹配');
  return {outputDirectory:job,imagePath,metadata};
}
