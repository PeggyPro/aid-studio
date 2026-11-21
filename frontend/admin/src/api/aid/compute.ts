import { request } from '@/utils/request';
import { getToken } from '@/utils/auth';
import { sharedReadRequest, invalidateSharedReads } from '@/utils/sharedReadRequest';

export interface ComputeConfiguration {
  tlsCaPem?: string | null; name: string; endpoint: string; poolCode: string; credentialRef: string | null; engines: string[];
  limits: { maxConcurrent: number; engineConcurrent: Record<string, number>; taskConcurrent: Record<string, number>;
    capacity: { cpuMillis: number; memoryBytes: number; scratchBytes: number }; minimumFreeBytes: number };
  executionTimeoutSeconds: number; queueLimit: number; storageAlias: string;
  slots?:{engine:string;taskKind:string;resources:{cpuMillis:number;memoryBytes:number;scratchBytes:number};timeoutSeconds:number}[];
}
export interface ComputeNode {
  nodeKey: string; revision: string; activeRevision?: string | null; state: string;
  configuration: ComputeConfiguration; updatedAt: string;
  workerId?: string; credentialConfigured?: boolean; verifiedAt?: string; verification?: ComputeProbeResult;
  healthStatus?:string;healthCheckedAt?:string;healthFresh?:boolean;
}
export interface ComputeProbeResult {proofId:string;workerId:string;checkedAt:string;securityMode:string;engines:string[];taskKinds:string[];currentConcurrency:number;activeJobs:number;
  routes:{engine:string;taskKind:string}[];
  resources:{cpuMillis:number;memoryBytes:number;freeMemoryBytes:number;scratchTotalBytes:number;scratchFreeBytes:number};resourceLimitsVerified:boolean}
export interface ComputeCertificateBundle {pem:string|null;certificates:{subject:string;issuer:string;sha256:string;notBefore:string;notAfter:string}[]}
export function inspectComputeCertificate(pem:string):Promise<{data:ComputeCertificateBundle}>{
  const key=JSON.stringify([getToken(),'certificate',pem]);const pending=writes.get(key);if(pending)return pending;
  const promise=request<ComputeCertificateBundle>({url:'/aid/compute/node/certificate/inspect',method:'post',data:{pem},errorFeedback:'caller',headers:{repeatSubmit:false}}).finally(()=>writes.delete(key));
  writes.set(key,promise);return promise;
}
export function probeComputeNode(data:{endpoint:string;workerId:string;secret?:string;nodeKey?:string;tlsCaPem?:string}){
  const key=JSON.stringify([getToken(),'probe',data]);const pending=writes.get(key);if(pending)return pending;
  const promise=request<ComputeProbeResult>({url:'/aid/compute/node/probe',method:'post',data,errorFeedback:'caller',headers:{repeatSubmit:false}}).finally(()=>writes.delete(key));
  writes.set(key,promise);return promise;
}
export const listComputeNodes = (query: Record<string, unknown>, force = false) => sharedReadRequest('/aid/compute/node/list', query, force);
export const readComputeNode = (key: string) => sharedReadRequest('/aid/compute/node/' + encodeURIComponent(key), undefined, true);
const writes = new Map<string, Promise<any>>();

export interface SceneAssistantSettingView {
  enabled: boolean;
  available: boolean;
  canEnable: boolean;
  poolCode: string;
  message: string;
}

const sceneAssistantUrl = '/aid/compute/scene-assistant';
export const getSceneAssistantSetting = (): Promise<{ data: SceneAssistantSettingView }> =>
  sharedReadRequest(sceneAssistantUrl, undefined, true);

export function updateSceneAssistantSetting(enabled: boolean) {
  const key = JSON.stringify([getToken(), sceneAssistantUrl, enabled]);
  const pending = writes.get(key);
  if (pending) return pending as ReturnType<typeof updateSceneAssistantSettingRequest>;
  const promise = updateSceneAssistantSettingRequest(enabled)
    .then(result => { invalidateSharedReads(sceneAssistantUrl); return result; })
    .finally(() => writes.delete(key));
  writes.set(key, promise);
  return promise;
}

function updateSceneAssistantSettingRequest(enabled: boolean) {
  return request<SceneAssistantSettingView>({ url: sceneAssistantUrl, method: 'put', data: { enabled },
    errorFeedback: 'caller', headers: { repeatSubmit: false } });
}
export function changeComputeHealth(operation:'health'|'maintenance',data:{nodeKey:string;enabled?:boolean}){
  const key=JSON.stringify([getToken(),operation,data]);const pending=writes.get(key);if(pending)return pending;
  const promise=request({url:'/aid/compute/node/'+operation,method:'post',data,headers:{repeatSubmit:false}})
    .then(result=>{invalidateSharedReads('/aid/compute/node');return result;}).finally(()=>writes.delete(key));
  writes.set(key,promise);return promise;
}
export function activateComputeNode(data:{nodeKey:string;expectedRevision:string}) {
  const key=JSON.stringify([getToken(),'activate',data]);const pending=writes.get(key);if(pending)return pending;
  const promise=request({url:'/aid/compute/node/activate',method:'post',data,errorFeedback:'caller',headers:{repeatSubmit:false}})
    .then(result=>{invalidateSharedReads('/aid/compute/node');return result;}).finally(()=>writes.delete(key));
  writes.set(key,promise);return promise;
}
export function saveComputeNode(data: {nodeKey: string | null; expectedRevision: string | null; clientRequestId: string; configuration: ComputeConfiguration;probeId?:string}) {
  const key = JSON.stringify([getToken(), data]);
  const pending = writes.get(key); if (pending) return pending;
  const promise = request({url: '/aid/compute/node', method: 'post', data})
    .then(result => { invalidateSharedReads('/aid/compute/node'); return result; }).finally(() => writes.delete(key));
  writes.set(key, promise); return promise;
}
