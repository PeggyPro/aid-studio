import fs from 'node:fs/promises';
import os from 'node:os';

const safe=value=>Number(value>BigInt(Number.MAX_SAFE_INTEGER)?BigInt(Number.MAX_SAFE_INTEGER):value);
async function cgroupValue(name){try{return (await fs.readFile('/sys/fs/cgroup/'+name,'utf8')).trim();}catch{return null;}}

/** Observations are not reservations or a promise of dedicated host resources. */
export async function observeComputeResources(executionRoot,executor){
 let cpuMillis=os.availableParallelism()*1000,memoryBytes=os.totalmem(),freeMemoryBytes=os.freemem();
 let cpuSource='PROCESS_VISIBLE',memorySource='HOST_VISIBLE';
 if(process.platform==='linux'){
  const cpu=await cgroupValue('cpu.max');
  if(cpu&&/^\d+ \d+$/.test(cpu)){const [quota,period]=cpu.split(' ').map(Number);if(period>0&&quota>0){cpuMillis=Math.max(1,Math.floor(Math.min(cpuMillis,quota/period*1000)));cpuSource='CGROUP_LIMIT';}}
  const memory=await cgroupValue('memory.max'),used=await cgroupValue('memory.current');
  if(memory&&/^\d+$/.test(memory)){
   memoryBytes=Math.min(memoryBytes,safe(BigInt(memory)));memorySource='CGROUP_LIMIT';
   if(used&&/^\d+$/.test(used))freeMemoryBytes=Math.min(freeMemoryBytes,Math.max(0,memoryBytes-safe(BigInt(used))));
  }
 }
 const disk=await fs.statfs(executionRoot,{bigint:true});
 const enforcement=executor.resourceLimits;
 return {cpuMillis,memoryBytes,freeMemoryBytes:Math.min(memoryBytes,freeMemoryBytes),
  scratchTotalBytes:safe(disk.blocks*disk.bsize),scratchFreeBytes:safe(disk.bavail*disk.bsize),cpuSource,memorySource,
  resourceLimitsVerified:executor.securityMode==='VERIFIED_ISOLATION'&&enforcement?.cpu===true&&enforcement?.memory===true&&enforcement?.disk===true,
  platform:process.platform,architecture:process.arch,nodeVersion:process.version};
}
