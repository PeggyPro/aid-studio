import fs from 'node:fs/promises';import path from 'node:path';import {createHash} from 'node:crypto';
import {renderResourceSvg} from './resource-svg.mjs';
const digest=b=>createHash('sha256').update(b).digest('hex');
async function main(){
  const argument=process.argv[2];if(!path.isAbsolute(argument||''))throw Error('RESOURCE_JOB_INVALID');const root=await fs.realpath(argument);
  if((await fs.lstat(argument)).isSymbolicLink())throw Error('RESOURCE_JOB_INVALID');
  async function read(name,limit){const file=path.join(root,name),s=await fs.lstat(file);if(!s.isFile()||s.isSymbolicLink()||s.size>limit||await fs.realpath(file)!==file)throw Error('RESOURCE_INPUT_INVALID');return fs.readFile(file)}
  const bytes=await read('input.bin',10*1024*1024),options=JSON.parse((await read('resource-map.json',256*1024)).toString('utf8'));
  if(!Array.isArray(options.images)||options.images.length>16)throw Error('RESOURCE_REFERENCE_INVALID');const resources=new Map();let total=0;
  for(const item of options.images){if(typeof item.url!=='string'||item.url.length>8192||!/^[a-f0-9]{64}$/.test(item.sha256||'')||resources.has(item.url))throw Error('RESOURCE_REFERENCE_INVALID');const b=await read('resources/'+item.sha256+'.bin',10*1024*1024);if(digest(b)!==item.sha256||(total+=b.length)>16*1024*1024)throw Error('RESOURCE_REFERENCE_INVALID');resources.set(item.url,b)}
  const result=renderResourceSvg(bytes,{baseUrl:options.baseUrl,resources});await fs.writeFile(path.join(root,'preview.png'),result.png,{flag:'wx'});
  const metadata={...result.metadata,sourceBytes:bytes.length,previewBytes:result.png.length,previewFile:'preview.png',staticOnly:true};await fs.writeFile(path.join(root,'inspection.json'),JSON.stringify(metadata),{flag:'wx'});console.log(JSON.stringify(metadata));
}
main().catch(error=>{console.log(JSON.stringify({error:/^RESOURCE_[A-Z_]+$/.test(error.code||error.message)?error.code||error.message:'RESOURCE_SVG_DECODE_FAILED'}));process.exitCode=1});
