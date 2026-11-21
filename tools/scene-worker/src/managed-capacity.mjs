import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {createJobLedger} from './job-ledger.mjs';

const quantities=['cpuMillis','memoryBytes','scratchBytes'];
const fail=code=>{throw Error(code);};
const integer=(value,min=0,max=Number.MAX_SAFE_INTEGER)=>Number.isSafeInteger(value)&&value>=min&&value<=max;
const key=value=>typeof value==='string'&&/^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$/.test(value);
const canonical=value=>JSON.stringify(value,(key,item)=>key==='engines'&&Array.isArray(item)?[...item].sort():item&&typeof item==='object'&&!Array.isArray(item)?Object.fromEntries(Object.keys(item).sort().map(k=>[k,item[k]])):item);
const digest=value=>createHash('sha256').update(canonical(value)).digest('hex');

/** Durable configuration CAS and single-worker admission. Reservations survive uncertain receipts. */
export async function createManagedCapacity({root,routes,observe,localAcceptance,unmanagedConcurrency=1,ledger=createJobLedger()}) {
  const folder=path.join(root,'managed-configuration');await fs.mkdir(folder,{recursive:true});
  let current=null,serial=Promise.resolve();const occupied=new Map();
  try{current=await ledger.read(folder);}catch(error){if(error.code!=='ENOENT')throw error;}
  function validate(input,resources){
    const c=input?.configuration,l=c?.limits;
    if(!key(input?.nodeKey)||!integer(input?.configVersion,1)||!integer(input?.expectedVersion)||!c||!l
        ||!integer(l.maxConcurrent,1,10000)||!integer(c.queueLimit,0,100000)||!integer(l.minimumFreeBytes)
        ||!Array.isArray(c.engines)||!c.engines.length||new Set(c.engines).size!==c.engines.length
        ||!Array.isArray(c.slots)||!c.slots.length||c.slots.length>500
        ||!integer(c.executionTimeoutSeconds,1,86400))fail('COMPUTE_CONFIG_INVALID');
    for(const field of quantities)if(!integer(l.capacity?.[field],1))fail('COMPUTE_CONFIG_INVALID');
    for(const category of ['engineConcurrent','taskConcurrent']){
      const values=l[category];if(!values||Array.isArray(values)||typeof values!=='object'||Object.keys(values).length>100)fail('COMPUTE_CONFIG_INVALID');
      for(const [name,count] of Object.entries(values))if(!key(name)||!integer(count,0,l.maxConcurrent))fail('COMPUTE_CONFIG_INVALID');
    }
    if(c.engines.some(engine=>!routes.some(route=>route.engine===engine)))fail('COMPUTE_CONFIG_ROUTE_INVALID');
    const seen=new Set();
    for(const slot of c.slots){
      const route=JSON.stringify([slot.engine,slot.taskKind]);
      if(seen.has(route)||!c.engines.includes(slot.engine)||!routes.some(r=>r.engine===slot.engine&&r.taskKind===slot.taskKind))fail('COMPUTE_CONFIG_ROUTE_INVALID');
      seen.add(route);
      for(const field of quantities)if(!integer(slot.resources?.[field],1)||slot.resources[field]>l.capacity[field])fail('COMPUTE_CONFIG_SLOT_INVALID');
      if(!integer(slot.timeoutSeconds,1,c.executionTimeoutSeconds))fail('COMPUTE_CONFIG_SLOT_INVALID');
    }
    if(resources){
      if(l.capacity.cpuMillis>resources.cpuMillis||l.capacity.memoryBytes>resources.memoryBytes
          ||l.minimumFreeBytes>resources.scratchTotalBytes||l.capacity.scratchBytes>resources.scratchTotalBytes-l.minimumFreeBytes)fail('COMPUTE_CONFIG_EXCEEDS_MACHINE');
      if(!localAcceptance&&!resources.resourceLimitsVerified)fail('COMPUTE_RESOURCE_ENFORCEMENT_REQUIRED');
    }
  }
  if(current)validate({...current,expectedVersion:0},await observe());
  function status(){return {nodeKey:current?.nodeKey??null,configVersion:current?.configVersion??0,
    configurationDigest:current?digest(current.configuration):null,reservedJobs:occupied.size,managed:!!current};}
  async function apply(input){
    // Serialize concurrent administration requests across asynchronous durable writes.
    const operation=serial.then(async()=>{
      validate(input,await observe());const hash=digest(input.configuration);
      if(current&&current.nodeKey!==input.nodeKey)fail('COMPUTE_NODE_BINDING_CONFLICT');
      if(current?.configVersion===input.configVersion){if(digest(current.configuration)!==hash)fail('COMPUTE_CONFIG_VERSION_CONFLICT');return status();}
      if(input.expectedVersion!==(current?.configVersion??0)||input.configVersion<=(current?.configVersion??0))fail('COMPUTE_CONFIG_VERSION_CONFLICT');
      const next={nodeKey:input.nodeKey,configVersion:input.configVersion,configuration:input.configuration,
        configurationDigest:hash,revision:(current?.revision??0)+1};
      await ledger.write(folder,next);current=next;return status();
    });serial=operation.catch(()=>{});return operation;
  }
  function restore(id,reservation){occupied.set(id,reservation||{unresolved:true});}
  function reserve(id,engine,taskKind,freeDiskBytes){
    if(occupied.has(id))return null;
    if(!current){if(occupied.size>=unmanagedConcurrency)return null;occupied.set(id,{unresolved:true});return undefined;}
    const c=current.configuration,l=c.limits,slot=c.slots.find(s=>s.engine===engine&&s.taskKind===taskKind);
    if(!slot||occupied.size>=l.maxConcurrent)return null;
    const totals={cpuMillis:0,memoryBytes:0,scratchBytes:0};let engineCount=0,taskCount=0;
    for(const reservation of occupied.values()){
      if(reservation.unresolved)return null;
      if(reservation.engine===engine)engineCount++;
      if(reservation.taskKind===taskKind)taskCount++;
      for(const field of quantities){totals[field]+=reservation.resources[field];if(!integer(totals[field]))return null;}
    }
    if(engineCount>=(l.engineConcurrent[engine]??l.maxConcurrent)||taskCount>=(l.taskConcurrent[taskKind]??l.maxConcurrent))return null;
    for(const field of quantities)if(slot.resources[field]>l.capacity[field]-totals[field])return null;
    if(!integer(freeDiskBytes)||freeDiskBytes<l.minimumFreeBytes||totals.scratchBytes+slot.resources.scratchBytes>freeDiskBytes-l.minimumFreeBytes)return null;
    const reservation=structuredClone({...slot,nodeKey:current.nodeKey,configVersion:current.configVersion});occupied.set(id,reservation);return reservation;
  }
  function committedScratchBytes(){
    if(!current)return 0;let total=0;
    for(const reservation of occupied.values()){
      if(reservation.unresolved)return Number.MAX_SAFE_INTEGER;
      total+=reservation.resources.scratchBytes;if(!Number.isSafeInteger(total))return Number.MAX_SAFE_INTEGER;
    }
    return total;
  }
  return {apply,status,restore,reserve,committedScratchBytes,reservation:id=>occupied.get(id),release:id=>occupied.delete(id),
    get configuration(){return current?.configuration??null;}};
}
