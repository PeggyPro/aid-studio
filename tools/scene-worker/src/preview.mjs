import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {runProcess} from './process.mjs';
import {BuildError} from './errors.mjs';

/** Paths and executables are host-owned, not accepted as tool arguments. */
export async function previewLocalArtifact({glbPath,sha256,options={},editorOverlay={},editorTransform,editorPlayback}, {executionRoot,blenderExecutable,localDevelopmentEnabled,signal}) {
  if(localDevelopmentEnabled!==true)throw new BuildError('LOCAL_EXECUTION_DISABLED','本地预览未启用');
  const {width=640,height=360,time=0,animationIndex=editorPlayback?.clipIndex??0,view='isometric',cameraId,inspectIds=[],exposureEV=0}=options;
  if(![width,height].every(v=>Number.isInteger(v)&&v>=64&&v<=2048)||!Number.isFinite(time)||time<0
    ||!Number.isInteger(animationIndex)||animationIndex<0||!['front','side','top','isometric'].includes(view)
    ||!Array.isArray(inspectIds)||inspectIds.length>32||inspectIds.some(id=>typeof id!=='string'||!id||id.length>200)
    ||cameraId!==undefined&&(typeof cameraId!=='string'||!cameraId||cameraId.length>200)
    ||!Number.isFinite(exposureEV)||exposureEV < -16||exposureEV>8)
    throw new BuildError('INVALID_PREVIEW_REQUEST','预览参数无效');
  const stat=await fs.lstat(glbPath);
  if(!stat.isFile()||stat.isSymbolicLink()||stat.size>32*1024**2)throw new BuildError('INVALID_ARTIFACT','预览模型无效');
  const bytes=await fs.readFile(glbPath);
  if(createHash('sha256').update(bytes).digest('hex')!==sha256)throw new BuildError('ARTIFACT_CHANGED','模型指纹已变化');
  if(bytes.length<20||bytes.readUInt32LE(0)!==0x46546c67||bytes.readUInt32LE(8)!==bytes.length)throw new BuildError('INVALID_ARTIFACT','GLB头无效');
  const json=JSON.parse(bytes.subarray(20,20+bytes.readUInt32LE(12)).toString('utf8'));
  let resolvedCameraId=cameraId;
  if(cameraId!==undefined){
    const nodes=json.nodes||[],byId=nodes.filter(node=>node.extras?.hf_id===cameraId);
    const matches=byId.length?byId:nodes.filter(node=>node.camera!==undefined&&node.name===cameraId);
    if(matches.length!==1||matches[0].camera===undefined||typeof matches[0].extras?.hf_id!=='string')
      throw new BuildError(matches.length>1?'PREVIEW_CAMERA_AMBIGUOUS':'PREVIEW_CAMERA_NOT_FOUND','请使用场景相机的唯一标识或唯一名称');
    resolvedCameraId=matches[0].extras.hf_id;
  }
  if((json.buffers||[]).some(item=>item.uri)||(json.images||[]).some(item=>item.uri))throw new BuildError('INVALID_ARTIFACT','预览只接受内嵌模型');
  const animationNames=(json.animations||[]).map((item,index)=>item.name||`Animation_${index}`);
  if(editorPlayback!==undefined&&(!editorPlayback||!Number.isInteger(editorPlayback.clipIndex)||editorPlayback.clipIndex<0
      ||editorPlayback.clipIndex>=animationNames.length||typeof editorPlayback.loop!=='boolean'
      ||editorPlayback.clipName!==undefined&&editorPlayback.clipName!==animationNames[editorPlayback.clipIndex]))throw new BuildError('INVALID_EDITOR_PLAYBACK','已保存动画片段与模型不一致');
  if(animationNames.length?animationIndex>=animationNames.length:animationIndex!==0)throw new BuildError('INVALID_ANIMATION','动画索引无效');
  await fs.mkdir(executionRoot,{recursive:true});
  const folder=await fs.mkdtemp(path.join(await fs.realpath(executionRoot),'preview-'));
  const input=path.join(folder,'scene.glb'),output=path.join(folder,'preview.png'),metadata=path.join(folder,'preview.json');
  await fs.writeFile(input,bytes,{flag:'wx'});
  const request=path.join(folder,'request.json');
  if(!editorOverlay||Array.isArray(editorOverlay)||Object.keys(editorOverlay).length>5000||JSON.stringify(editorOverlay).length>200000)
    throw new BuildError('INVALID_EDITOR_OVERLAY','编辑叠加层无效');
  if(editorTransform!==undefined&&(!editorTransform||!['position','rotation','scale'].every(key=>Array.isArray(editorTransform[key])&&editorTransform[key].length===3
      &&editorTransform[key].every(v=>Number.isFinite(v)&&Math.abs(v)<=1000000&&(key!=='scale'||v>0)))))throw new BuildError('INVALID_EDITOR_TRANSFORM','模型编辑变换无效');
  await fs.writeFile(request,JSON.stringify({input,output,metadata,width,height,time,animationIndex,animationNames,view,cameraId:resolvedCameraId,inspectIds,exposureEV,editorOverlay,editorTransform,
    loopAnimation:editorPlayback?.clipIndex===animationIndex&&editorPlayback.loop===true}));
  const env={};for(const key of ['PATH','SystemRoot','WINDIR','COMSPEC','PATHEXT'])if(process.env[key])env[key]=process.env[key];
  Object.assign(env,{TEMP:folder,TMP:folder,TMPDIR:folder,USERPROFILE:folder,PYTHONNOUSERSITE:'1',PYTHONUTF8:'1'});
  await runProcess(blenderExecutable,['--background','--factory-startup','--disable-autoexec','--threads','2','--python-exit-code','1','--python',path.join(path.dirname(fileURLToPath(import.meta.url)),'preview-runner.py'),'--',request],
    {cwd:folder,env,timeoutMs:300000,logFile:path.join(folder,'execution.log'),logBytes:262144,signal});
  const image=await fs.readFile(output);
  if(image.length>16*1024**2||image.subarray(0,8).toString('hex')!=='89504e470d0a1a0a')throw new BuildError('INVALID_PREVIEW_RESULT','预览图无效');
  return {outputDirectory:folder,imagePath:output,sha256:createHash('sha256').update(image).digest('hex'),sizeBytes:image.length,
    ...JSON.parse(await fs.readFile(metadata,'utf8'))};
}
