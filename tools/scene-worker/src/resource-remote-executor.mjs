import fs from 'node:fs/promises';
import path from 'node:path';
import {inspectResource} from './resource-dispatch.mjs';
import {validateResourceJob} from './resource-remote-contract.mjs';

// The compute host supplies configuration; the signed request cannot select binaries.
export function createResourceExecutor(configuration) {
  return async (job,context)=>{
    validateResourceJob(job);
    const bytes=await fs.readFile(path.join(context.resourceDirectory,'input.bin')),resources=new Map();
    for(const ref of job.references??[])resources.set(ref.url,await fs.readFile(path.join(context.resourceDirectory,ref.sha256+'.bin')));
    const inspected=await inspectResource({bytes,sha256:job.sourceSha256,format:job.format,pages:job.pages,baseUrl:job.baseUrl,resources,...job.videoWindow},
      {...configuration,executionRoot:context.executionRoot,signal:context.signal});
    const metadata=inspected.metadata,previews=(inspected.previews??(inspected.imagePath?[{file:'preview.png',sha256:metadata.previewSha256,sizeBytes:metadata.previewBytes}]:[]))
      .map(({file,page,timeSeconds,sha256,sizeBytes})=>({name:file,...(page===undefined?{}:{page}),...(timeSeconds===undefined?{}:{timeSeconds}),sha256,sizeBytes}));
    return {outputDirectory:inspected.outputDirectory,result:{jobId:job.jobId,projectId:job.projectId,sourceSha256:job.sourceSha256,metadata,previews,executionMode:'REMOTE_COMPUTE'}};
  };
}
