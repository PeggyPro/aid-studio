import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {runProcess} from './process.mjs';
import {BuildError} from './errors.mjs';

const hash=data=>createHash('sha256').update(data).digest('hex');
const fail=(code,message)=>{throw new BuildError(code,message)};

/** Bounded video-frame inspection. Sound tracks are neither decoded nor analyzed. */
export async function inspectLocalResourceMedia({bytes,sha256,format,startSeconds=0,durationSeconds=30,frameCount=4},
  {executionRoot,ffmpegExecutable,ffprobeExecutable,localDevelopmentEnabled,signal}) {
  if(localDevelopmentEnabled!==true)fail('LOCAL_EXECUTION_DISABLED','本地媒体解析未启用');
  if(!Buffer.isBuffer(bytes)||!bytes.length||bytes.length>32*1024*1024||hash(bytes)!==sha256||format!=='video')fail('RESOURCE_INPUT_INVALID','仅支持视频画面参考');
  if(!Number.isFinite(startSeconds)||startSeconds<0||!Number.isFinite(durationSeconds)||durationSeconds<=0||durationSeconds>60||!Number.isInteger(frameCount)||frameCount<1||frameCount>8)fail('RESOURCE_MEDIA_WINDOW_INVALID','媒体分析范围无效');
  if(!ffmpegExecutable||!ffprobeExecutable)fail('RESOURCE_MEDIA_EXECUTOR_UNAVAILABLE','视频画面解析执行器尚未配置');
  for(const value of [executionRoot,ffmpegExecutable,ffprobeExecutable])if(!path.isAbsolute(value||''))fail('RESOURCE_CONFIG_INVALID','媒体执行器必须由运行配置指定');
  const root=await fs.realpath(executionRoot);if((await fs.lstat(executionRoot)).isSymbolicLink())fail('RESOURCE_DIRECTORY_INVALID','媒体执行目录无效');
  if(signal?.aborted)fail('CANCELLED','任务已取消');
  const job=await fs.mkdtemp(path.join(root,'resource-media-')),temporary=path.join(job,'tmp');await fs.mkdir(temporary);await fs.writeFile(path.join(job,'input.bin'),bytes,{flag:'wx'});
  const env={TEMP:temporary,TMP:temporary,TMPDIR:temporary};if(process.platform==='win32'){env.SystemRoot=process.env.SystemRoot||'C:\\Windows';env.WINDIR=env.SystemRoot;env.PATH=path.join(env.SystemRoot,'System32');}
  const inputOptions=['-max_alloc','67108864','-protocol_whitelist','file,pipe','-format_whitelist','mov,matroska,webm,avi,ogg,asf,mpegts,mpeg','-probesize','8388608','-analyzeduration','5000000'];
  const deadline=Date.now()+60000;
  const execute=(binary,args,name,timeoutMs=30000)=>{const remaining=deadline-Date.now();if(remaining<1)fail('RESOURCE_EXECUTION_TIMEOUT','媒体解析超时');return runProcess(binary,args,{cwd:job,env,timeoutMs:Math.min(timeoutMs,remaining),logFile:path.join(job,name+'.log'),logBytes:65536,signal});};
  await execute(ffprobeExecutable,['-v','error',...inputOptions,'-select_streams','v','-show_entries','format=duration,format_name:stream=index,codec_type,codec_name,width,height,duration','-of','json','-o','probe.json','input.bin'],'probe',15000);
  const probeFile=path.join(job,'probe.json');if((await fs.stat(probeFile)).size>65536)fail('RESOURCE_MEDIA_METADATA_LIMIT','媒体信息超限');
  const probe=JSON.parse(await fs.readFile(probeFile,'utf8')),streams=probe.streams;
  if(!Array.isArray(streams)||!streams.length||streams.length>32)fail('RESOURCE_MEDIA_STREAM_INVALID','媒体轨道无效');
  const video=streams.find(s=>s.codec_type==='video'),totalSeconds=Number(probe.format?.duration??video?.duration);
  if(!Number.isFinite(totalSeconds)||totalSeconds<=0||startSeconds>=totalSeconds||!video)fail('RESOURCE_MEDIA_WINDOW_INVALID','视频时长或分析范围无效');
  const endSeconds=Math.min(totalSeconds,startSeconds+durationSeconds),previews=[];
  if(video&&format==='video'){
    if(!Number.isInteger(video.width)||!Number.isInteger(video.height)||video.width<1||video.height<1||video.width*video.height>16_000_000)fail('RESOURCE_MEDIA_DIMENSIONS','视频尺寸超过解析上限');
    for(let i=0;i<frameCount;i++){
      const seconds=startSeconds+(endSeconds-startSeconds)*(i+.5)/frameCount,file=`page-${i}.png`;
      await execute(ffmpegExecutable,['-nostdin','-hide_banner','-v','error','-threads','2',...inputOptions,'-ss',seconds.toFixed(6),'-i','input.bin','-map',`0:${video.index}`,'-an','-sn','-dn','-frames:v','1','-vf','scale=1024:1024:force_original_aspect_ratio=decrease','-threads','1','-n',file],`frame-${i}`);
      const imagePath=path.join(job,file),info=await fs.stat(imagePath);if(info.size<24||info.size>16*1024*1024)fail('RESOURCE_PREVIEW_LIMIT','媒体预览超限');
      const png=await fs.readFile(imagePath);if(!png.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10]))||png.readUInt32BE(16)<1||png.readUInt32BE(20)<1||Math.max(png.readUInt32BE(16),png.readUInt32BE(20))>1024)fail('RESOURCE_RECEIPT_INVALID','媒体预览格式无效');
      previews.push({file,page:i,timeSeconds:seconds,sha256:hash(png),sizeBytes:png.length,imagePath});
    }
  }
  const metadata={contentType:format,sourceSha256:sha256,sourceBytes:bytes.length,totalSeconds,startSeconds,endSeconds,additionalMediaNotAnalyzed:startSeconds>0||endSeconds<totalSeconds,hasVideo:true,streams:streams.filter(s=>s.codec_type==='video'),parts:[],analysisRequired:true};
  const result={outputDirectory:job,metadata,previews};await fs.writeFile(path.join(job,'inspection.json'),JSON.stringify(result),{flag:'wx'});return result;
}
