import fs from 'node:fs/promises';
import {previewLocalArtifact} from './preview.mjs';
const [requestFile]=process.argv.slice(2);
try {
  const stat=await fs.lstat(requestFile);
  if(!stat.isFile()||stat.isSymbolicLink()||stat.size>32768)throw Error('INVALID_PREVIEW_REQUEST');
  const request=JSON.parse(await fs.readFile(requestFile,'utf8'));
  const result=await previewLocalArtifact(request.input,request.host);
  process.stdout.write(JSON.stringify({type:'result',result})+'\n');
} catch(error) {process.stdout.write(JSON.stringify({type:'error',code:error.code||'PREVIEW_FAILED'})+'\n');process.exitCode=1;}
