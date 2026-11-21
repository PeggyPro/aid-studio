import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {runProcess} from './process.mjs';
import {BuildError} from './errors.mjs';

const digest=bytes=>createHash('sha256').update(bytes).digest('hex');
const formats=new Set(['pdf','pptx','ppt','docx','doc','html','md']);

/** All executable/classpath values come from operator configuration, never model arguments. */
export async function inspectLocalResourceDocument({bytes,sha256,format,pages},
  {executionRoot,javaExecutable,documentClasspath,localDevelopmentEnabled,signal}) {
  if(localDevelopmentEnabled!==true)throw new BuildError('LOCAL_EXECUTION_DISABLED','本地资源解析未启用');
  if(!Buffer.isBuffer(bytes)||bytes.length===0||bytes.length>10*1024*1024||digest(bytes)!==sha256||!formats.has(format))
    throw new BuildError('RESOURCE_INPUT_INVALID','文档内容或格式无效');
  if(pages!==undefined&&(!['pdf','pptx','ppt'].includes(format)||!Array.isArray(pages)||pages.length<1||pages.length>8
    ||new Set(pages).size!==pages.length||pages.some(page=>!Number.isInteger(page)||page<0||page>=500)))
    throw new BuildError('RESOURCE_DOCUMENT_PAGE_SELECTION_INVALID','文档页码无效');
  if(!path.isAbsolute(executionRoot||'')||!path.isAbsolute(javaExecutable||'')||!Array.isArray(documentClasspath)
    ||documentClasspath.length===0||documentClasspath.some(entry=>typeof entry!=='string'||!path.isAbsolute(entry)||/[\r\n";]/.test(entry)))
    throw new BuildError('RESOURCE_CONFIG_INVALID','文档执行器必须由运行配置指定');
  const root=await fs.realpath(executionRoot),stat=await fs.lstat(executionRoot);
  if(stat.isSymbolicLink()||!stat.isDirectory())throw new BuildError('RESOURCE_DIRECTORY_INVALID','资源执行目录无效');
  if(signal?.aborted)throw new BuildError('CANCELLED','任务已取消');
  const job=await fs.mkdtemp(path.join(root,'resource-document-')),temporary=path.join(job,'tmp');
  await fs.mkdir(temporary);await fs.mkdir(path.join(temporary,'fontcache'));await fs.writeFile(path.join(job,'input.bin'),bytes,{flag:'wx'});
  // CLASSPATH avoids Windows command-line length limits and platform-dependent argument-file encoding.
  const env={TEMP:temporary,TMP:temporary,TMPDIR:temporary,CLASSPATH:documentClasspath.join(path.delimiter)};
  if(process.platform==='win32'){env.SystemRoot=process.env.SystemRoot||'C:\\Windows';env.WINDIR=env.SystemRoot;env.PATH=path.dirname(javaExecutable)+';'+path.join(env.SystemRoot,'System32');}
  const args=['-Xmx256m','-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-Djava.io.tmpdir='+temporary,
    '-Dpdfbox.fontcache='+path.join(temporary,'fontcache'),'com.aid.previz.workspace.PrevizResourceDocumentMain',job,format];
  if(pages!==undefined)args.push(pages.join(','));
  try{await runProcess(javaExecutable,args,{cwd:job,env,timeoutMs:45000,logFile:path.join(job,'decoder.log'),logBytes:65536,signal});}
  catch(error){if(error?.code==='SOURCE_EXECUTION_FAILED')throw new BuildError('RESOURCE_DOCUMENT_DECODE_FAILED','文档解析失败');throw error;}
  async function readOutput(name,maximum){
    const file=path.join(job,name),info=await fs.lstat(file);
    if(info.isSymbolicLink()||!info.isFile()||info.size<1||info.size>maximum||await fs.realpath(file)!==file)
      throw new BuildError('RESOURCE_RECEIPT_INVALID','文档回执无效');
    return fs.readFile(file);
  }
  const metadata=JSON.parse((await readOutput('inspection.json',256*1024)).toString('utf8'));
  if(metadata.sourceSha256!==sha256||metadata.sourceBytes!==bytes.length||!Array.isArray(metadata.previews)||metadata.previews.length>8
    ||!Array.isArray(metadata.parts)||metadata.contentType!==format)
    throw new BuildError('RESOURCE_RECEIPT_INVALID','文档内容指纹不匹配');
  const seen=new Set(),previews=[];
  for(const preview of metadata.previews){
    if(!Number.isInteger(preview.page)||preview.page<0||preview.page>=500||seen.has(preview.page)||preview.file!==`page-${preview.page}.png`)
      throw new BuildError('RESOURCE_RECEIPT_INVALID','文档预览页码无效');
    seen.add(preview.page);const png=await readOutput(preview.file,16*1024*1024);
    if(png.length<24||!png.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10]))||png.toString('ascii',12,16)!=='IHDR'
      ||png.readUInt32BE(16)<1||png.readUInt32BE(20)<1||Math.max(png.readUInt32BE(16),png.readUInt32BE(20))>2048
      ||digest(png)!==preview.sha256||png.length!==preview.sizeBytes)
      throw new BuildError('RESOURCE_RECEIPT_INVALID','文档预览指纹无效');
    previews.push({...preview,imagePath:path.join(job,preview.file)});
  }
  if(pages!==undefined&&JSON.stringify([...seen])!==JSON.stringify(pages))throw new BuildError('RESOURCE_RECEIPT_INVALID','文档预览页与请求不一致');
  return {outputDirectory:job,metadata,previews};
}
