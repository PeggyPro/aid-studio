import { spawn } from 'node:child_process';
import fs from 'node:fs/promises';
import { BuildError } from './errors.mjs';
import { AsyncLocalStorage } from 'node:async_hooks';
import { createWriteStream } from 'node:fs';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const isolation = new AsyncLocalStorage();
// Only an operator-owned executor installs this context; job payloads cannot select a launcher.
export function withProcessIsolation(launcher, operation) { return isolation.run(launcher, operation); }
export function runProcess(command, args, options) {
  const launcher = isolation.getStore();
  return launcher ? launcher(command, args, options) : runProcessDirect(command, args, options);
}

export function runProcessDirect(command, args, { cwd, env, timeoutMs, logFile, logBytes, signal, exportPipe, onTerminate }) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(new BuildError('CANCELLED','任务已取消'));
    let child;
    try { child=spawn(command,args,{cwd,env,windowsHide:true,shell:false,detached:process.platform!=='win32',stdio:['ignore','pipe','pipe',...(exportPipe&&!exportPipe.fromStdout?['pipe']:[])]}); }
    catch(error) { return reject(new BuildError('WORKER_START_UNAVAILABLE','执行器无法启动',{details:error.code})); }
    let bytes=0, tail='', stopReason=null;
    const chunks=[];
    const append=chunk=>{
      tail=(tail+chunk.toString('utf8')).slice(-12000);
      const remaining=logBytes-bytes;
      if(remaining>0) { const part=Buffer.from(chunk.subarray(0,remaining)); bytes+=part.length; chunks.push(part); }
    };
    if(!exportPipe?.fromStdout)child.stdout.on('data',append); child.stderr.on('data',append);
    function stop(reason) {
      if(stopReason) return;
      stopReason=reason;
      try { onTerminate?.(); } catch { /* The finalizer also verifies termination. */ }
      if(!child.pid) return;
      if(process.platform==='win32') {
        const killer=spawn('taskkill.exe',['/PID',String(child.pid),'/T','/F'],{windowsHide:true,stdio:'ignore'});
        killer.on('error',()=>child.kill());
        killer.on('close',code=>{if(code!==0) child.kill();});
      } else {
        try { process.kill(-child.pid,'SIGKILL'); } catch { child.kill('SIGKILL'); }
      }
    }
    const timer=setTimeout(()=>stop('EXECUTION_TIMEOUT'),timeoutMs);
    const exported=exportPipe?pipeline(exportPipe.fromStdout?child.stdout:child.stdio[3],new Transform({transform(chunk,encoding,done){
      this.total=(this.total??0)+chunk.length;
      done(this.total>exportPipe.maxBytes?new Error('NATIVE_EXPORT_LIMIT'):null,chunk);
    }}),createWriteStream(exportPipe.file,{flags:'wx',mode:0o600})).catch(()=>{stop('ARTIFACT_LIMIT');}):Promise.resolve();
    const cancel=()=>stop('CANCELLED'); signal?.addEventListener('abort',cancel,{once:true});
    let spawnError;
    child.on('error',error=>{spawnError=error;});
    child.on('close',async code=>{
      clearTimeout(timer); signal?.removeEventListener('abort',cancel);
      await exported;
      try {
        await fs.writeFile(logFile,Buffer.concat(chunks),{flag:'wx'});
      } catch(error) {
        return reject(new BuildError('LOG_WRITE_FAILED','执行日志无法保存',{details:error.code}));
      }
        if(spawnError) return reject(new BuildError('WORKER_START_UNAVAILABLE','执行器无法启动',{details:spawnError.code}));
        if(stopReason) return reject(new BuildError(stopReason,stopReason==='CANCELLED'?'任务已取消':'执行已终止，查看执行日志'));
        if(code!==0) {
          const known=tail.match(/\b(INVALID_OBJECT_ID|SCENE_ENTRY_INVALID|INVALID_ANIMATION|UNSUPPORTED_ANIMATION_CHANNEL|INVALID_LIGHT_TARGET|UNSUPPORTED_LIGHT_HIERARCHY|ANIMATION_EXPORT_LIMIT|GLB_EXPORT_FAILED)\b/);
          return reject(new BuildError(known?.[1]??'SOURCE_EXECUTION_FAILED','场景源码执行失败',{details:{exitCode:code,diagnostic:tail}}));
        }
        resolve({exitCode:code,logBytes:bytes});
    });
  });
}
