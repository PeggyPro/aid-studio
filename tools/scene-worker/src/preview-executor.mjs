import {previewLocalArtifact} from './preview.mjs';

/** Operator-owned preview adapter. Connection settings and executable paths never come from the Agent. */
export function createPreviewExecutor({blenderExecutable,localDevelopmentEnabled}) {
  return async (job,{glbPath,executionRoot,signal}) => {
    const rendered=await previewLocalArtifact({glbPath,sha256:job.artifactSha256,options:job.options,
      editorOverlay:job.editorOverlay,editorTransform:job.editorTransform,editorPlayback:job.editorPlayback},
      {executionRoot,blenderExecutable,localDevelopmentEnabled,signal});
    const {outputDirectory,imagePath,...metadata}=rendered;
    return {outputDirectory,result:{...metadata,jobId:job.jobId,projectId:job.projectId,buildJobId:job.buildJobId,
      sourceSha256:job.sourceSha256,artifactSha256:job.artifactSha256,
      previewArtifact:{name:'preview.png',sizeBytes:rendered.sizeBytes,sha256:rendered.sha256}}};
  };
}
