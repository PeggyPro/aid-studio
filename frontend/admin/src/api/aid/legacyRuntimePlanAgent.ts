import { request } from '@/utils/request';
import type { ApiResponse } from '@/types/common';
import type { RegisteredToolOption, SkillTextModelOption } from './skill';

const LEGACY_FAMILY_HEADERS = Object.freeze({ 'X-Aid-Runtime-Agent-Family': 'LEGACY_PLAN' });
const LEGACY_AGENT_BASE = '/aid/runtime-plan-agent';

export type RuntimeId = string | number;

export interface RuntimeAgentSkillOption {
  id: RuntimeId;
  skillCode: string;
  name: string;
  currentVersionId: RuntimeId;
  currentExecutorType?: string;
  currentModelCode?: string;
}

export interface RuntimeAgentSummary {
  id: RuntimeId;
  agentCode: string;
  name: string;
  description?: string;
  iconUrl?: string;
  visibility?: 'PUBLIC' | 'PRIVATE';
  agentFamily?: 'LEGACY_PLAN';
  status: '0' | '1';
  delFlag?: '0' | '1';
  currentVersionId?: RuntimeId | null;
  updateTime?: string;
}

export interface AgentPageQuery {
  pageNum: number;
  pageSize: number;
  keyword?: string;
  status?: '0' | '1';
}

export interface RuntimeAgentStep {
  stepKey: string;
  kind: 'MODEL' | 'TOOL' | 'SKILL';
  toolCode?: string;
  skillCode?: string;
  instruction?: string;
}

export interface RuntimeAgentDraftPayload {
  agentId: RuntimeId;
  baseVersionId?: RuntimeId;
  executorType: 'MODEL_PLAN' | 'SKILL_ORCHESTRATOR';
  delegatedSkillCode?: string;
  outputKind: 'TEXT' | 'IMAGE' | 'VIDEO';
  instruction: string;
  defaultModelCode: string;
  selectableModelCodes: string[];
  allowedSkillCodes: string[];
  allowedToolCodes: string[];
  maxSteps: number;
  maxTaskCount: number;
  maxDurationSeconds: number;
  maxEstimatedTokens: number;
  clarificationEnabled: boolean;
  steps: RuntimeAgentStep[];
}

export interface RuntimeAgentDraft extends RuntimeAgentDraftPayload {
  draftId?: RuntimeId;
  draftDigest?: string;
  baseVersionCode?: string;
  updateTime?: string;
}

export interface RuntimeAgentVersion extends RuntimeAgentDraftPayload {
  id: RuntimeId;
  versionCode: string;
  packageDigest: string;
  current?: boolean;
  status?: string;
  createBy?: string;
  createTime?: string;
  definitionJson?: string;
}

export interface RuntimeAgentValidation {
  valid: boolean;
  draftDigest?: string;
  errors: { field: string; message: string }[];
  warnings: { field: string; message: string }[];
}

export interface RuntimeAgentRun {
  id: number | string;
  agentId: number | string;
  agentVersionId: number | string;
  memberId?: number | string;
  versionId?: number | string;
  userId: number | string;
  modelCode?: string;
  outputKind?: 'TEXT' | 'IMAGE' | 'VIDEO';
  mediaTaskId?: number | string;
  status: string;
  stage?: string;
  inputJson?: string;
  outputJson?: string;
  inputPreview?: unknown;
  resultPreview?: unknown;
  errorMessage?: string;
  errorDetails?: { errorCode?: string; field?: string; reason?: string;
    allowedValues?: string[]; retryable?: boolean };
  actualCost?: string | number;
  billingStatus?: string;
  task?: {
    taskId?: number | string;
    mediaType?: 'TEXT' | 'IMAGE' | 'VIDEO' | 'AUDIO';
    status?: string;
    ossUrl?: string;
    actualCost?: string | number;
    billingStatus?: string;
  };
  startedAt?: string;
  createTime?: string;
  finishedAt?: string;
  steps?: { id?: RuntimeId; stepKey: string; stepKind?: string; status: string; modelCode?: string; toolCode?: string;
    skillCode?: string; skillVersionId?: number | string; mediaTaskId?: number | string;
    billingStatus?: string; actualCost?: string | number;
    errorMessage?: string }[];
}

interface RuntimeTryRunReceiptBase {
  accepted: true;
  requestClientRequestId: string;
}

export type GenericRuntimeTryRunReceipt = (RuntimeTryRunReceiptBase & {
  runType: 'GENERIC_RUNTIME';
  recoveryRequired: false;
} & RuntimeAgentRun) | (RuntimeTryRunReceiptBase & {
  runType: 'GENERIC_RUNTIME';
  recoveryRequired: true;
  id: RuntimeId;
  clientRequestId: string;
});

export type DelegatedSkillTryRunReceipt = (RuntimeTryRunReceiptBase & {
  runType: 'SKILL_ORCHESTRATOR';
  recoveryRequired: false;
  legacyRunId: RuntimeId;
  runId: RuntimeId;
  status: string;
  stage?: string;
  skillCode?: string;
  skillVersionId?: RuntimeId;
  modelCode?: string;
  errorMessage?: string;
  tasks?: unknown[];
}) | (RuntimeTryRunReceiptBase & {
  runType: 'SKILL_ORCHESTRATOR';
  recoveryRequired: true;
  legacyRunId: RuntimeId;
  runId: RuntimeId;
  clientRequestId?: string;
});

/** Exact response union for paid draft trials; limited receipts are not complete Run views. */
export type RuntimeTryRunReceipt = GenericRuntimeTryRunReceipt | DelegatedSkillTryRunReceipt;

export type RuntimeTryRunRecordTarget = {
  kind: 'GENERIC_RUNTIME';
  memberType: 'SKILL' | 'AGENT';
  runId: RuntimeId;
} | {
  kind: 'SKILL_ORCHESTRATOR';
  runId: RuntimeId;
};

const reads = new Map<string, Promise<ApiResponse<RuntimeAgentSummary[]>>>();
const readCache = new Map<string, { value: unknown; expiry: number }>();
let readEpoch = 0;
export function listRuntimeAgents(data: AgentPageQuery, force = false): Promise<ApiResponse<RuntimeAgentSummary[]>> {
  const key = `${readEpoch}:${JSON.stringify(data)}`;
  const active = reads.get(key);
  if (active) return active;
  const stored = readCache.get(key);
  if (!force && stored && stored.expiry > Date.now())
    return Promise.resolve(stored.value as ApiResponse<RuntimeAgentSummary[]>);
  const task = request<RuntimeAgentSummary[]>({ url: `${LEGACY_AGENT_BASE}/list`, method: 'post', data,
    headers: LEGACY_FAMILY_HEADERS })
    .then((response) => { readCache.set(key, { value: response, expiry: Date.now() + 800 }); return response; })
    .finally(() => { if (reads.get(key) === task) reads.delete(key); });
  reads.set(key, task);
  return task;
}

const otherReads = new Map<string, Promise<unknown>>();
function read<T>(key: string, url: string, data: unknown, force = false): Promise<ApiResponse<T>> {
  const requestKey = `${readEpoch}:${key}`;
  const active = otherReads.get(requestKey);
  if (active) return active as Promise<ApiResponse<T>>;
  const stored = readCache.get(requestKey);
  if (!force && stored && stored.expiry > Date.now())
    return Promise.resolve(stored.value as ApiResponse<T>);
  const task = request<T>({ url, method: 'post', data, headers: LEGACY_FAMILY_HEADERS }).then((response) => {
    readCache.set(requestKey, { value: response, expiry: Date.now() + 800 });
    return response;
  }).finally(() => {
    if (otherReads.get(requestKey) === task) otherReads.delete(requestKey);
  });
  otherReads.set(requestKey, task);
  return task;
}

const mutations = new Map<string, Promise<unknown>>();
function mutate<T>(key: string, url: string, data: unknown,
  suppressConflictToast = false): Promise<ApiResponse<T>> {
  const requestKey = `${key}:${JSON.stringify(data)}`;
  const active = mutations.get(requestKey);
  if (active) return active as Promise<ApiResponse<T>>;
  const task = request<T>({ url, method: 'post', data, suppressConflictToast,
    headers: LEGACY_FAMILY_HEADERS }).then((response) => {
    readEpoch += 1;
    readCache.clear();
    return response;
  }).finally(() => {
    if (mutations.get(requestKey) === task) mutations.delete(requestKey);
  });
  mutations.set(requestKey, task);
  return task;
}

export function createRuntimeAgent(data: Pick<RuntimeAgentSummary, 'agentCode' | 'name'> & {
  description?: string; iconUrl?: string; visibility?: 'PUBLIC' | 'PRIVATE'; templateVersionId?: RuntimeId;
}) {
  return mutate<RuntimeAgentSummary>(`agent:create:${data.agentCode}`, `${LEGACY_AGENT_BASE}/create`, data);
}

export function updateRuntimeAgentIdentity(data: Pick<RuntimeAgentSummary,
  'id' | 'name' | 'description' | 'iconUrl' | 'visibility'>) {
  return mutate<RuntimeAgentSummary>(`agent:${data.id}:identity`, `${LEGACY_AGENT_BASE}/identity/edit`, data);
}

export function getRuntimeAgent(id: RuntimeId) {
  return read<RuntimeAgentSummary>(`agent:${id}`, `${LEGACY_AGENT_BASE}/detail`, { id });
}

export function listRuntimeAgentModelOptions() {
  return read<SkillTextModelOption[]>('agent:model:options', `${LEGACY_AGENT_BASE}/model/options`, {});
}

export function listRuntimeAgentSkillOptions() {
  return read<RuntimeAgentSkillOption[]>('agent:skill:options', `${LEGACY_AGENT_BASE}/skill/options`, {});
}

export function listRuntimeAgentToolOptions() {
  return read<RegisteredToolOption[]>('agent:tool:options', `${LEGACY_AGENT_BASE}/tool/options`, {});
}

export function getRuntimeAgentDraft(agentId: RuntimeId, baseVersionId?: RuntimeId, force = false) {
  return read<RuntimeAgentDraft>(`agent:${agentId}:draft:${baseVersionId ?? 'current'}`,
    `${LEGACY_AGENT_BASE}/draft/detail`, { agentId, baseVersionId }, force);
}

export function saveRuntimeAgentDraft(data: RuntimeAgentDraftPayload & { draftId?: RuntimeId; draftDigest?: string }) {
  return mutate<RuntimeAgentDraft>(`agent:${data.agentId}:draft`, `${LEGACY_AGENT_BASE}/draft/save`, data);
}

export function validateRuntimeAgentDraft(data: RuntimeAgentDraftPayload) {
  return read<RuntimeAgentValidation>(`agent:${data.agentId}:validate:${JSON.stringify(data)}`,
    `${LEGACY_AGENT_BASE}/draft/validate`, data, true);
}

export function publishRuntimeAgentDraft(draftId: RuntimeId, draftDigest: string, versionCode: string) {
  return mutate<RuntimeAgentVersion>(`agent:draft:${draftId}:publish`,
    `${LEGACY_AGENT_BASE}/draft/publish`, { draftId, draftDigest, versionCode });
}

export function listRuntimeAgentVersions(agentId: RuntimeId, pageNum: number, pageSize: number) {
  return read<RuntimeAgentVersion[]>(`agent:${agentId}:versions:${pageNum}:${pageSize}`,
    `${LEGACY_AGENT_BASE}/version/list`, { agentId, pageNum, pageSize });
}

export function getRuntimeAgentVersion(id: RuntimeId) {
  return read<RuntimeAgentVersion>(`agent:version:${id}`, `${LEGACY_AGENT_BASE}/version/detail`, { id })
    .then((response) => {
      let definition: Partial<RuntimeAgentDraftPayload> = {};
      try { definition = JSON.parse(response.data.definitionJson || '{}'); } catch { /* read-only detail remains visible */ }
      return { ...response, data: { ...response.data, ...definition,
        selectableModelCodes: definition.selectableModelCodes || [],
        allowedSkillCodes: definition.allowedSkillCodes || [],
        allowedToolCodes: definition.allowedToolCodes || [],
        steps: definition.steps || [] } };
    });
}

export function activateRuntimeAgentVersion(agentId: RuntimeId, versionId: RuntimeId,
  expectedCurrentVersionId: RuntimeId | null) {
  return mutate(`agent:${agentId}:activate`, `${LEGACY_AGENT_BASE}/version/activate`,
    { agentId, versionId, expectedCurrentVersionId });
}

export function setRuntimeAgentStatus(id: RuntimeId, status: '0' | '1') {
  return mutate(`agent:${id}:status`, `${LEGACY_AGENT_BASE}/status`, { id, status });
}

export function removeRuntimeAgent(id: RuntimeId) {
  return mutate(`agent:${id}:remove`, `${LEGACY_AGENT_BASE}/remove`, { id }, true);
}

export function listRuntimeAgentRuns(data: { agentId?: RuntimeId; userId?: RuntimeId; status?: string;
  pageNum: number; pageSize: number }) {
  return read<RuntimeAgentRun[]>(`agent:runs:${JSON.stringify(data)}`,
    `${LEGACY_AGENT_BASE}/run/list`, data);
}

export function getRuntimeAgentRun(id: number | string) {
  return read<RuntimeAgentRun>(`agent:run:${id}`, `${LEGACY_AGENT_BASE}/run/detail`, { id });
}
