import { getToken } from '@/utils/auth';
import { request } from '@/utils/request';
import type { ApiResponse } from '@/types/common';
import type { SkillTextModelOption } from './skill';

export type RuntimeId = string | number;
export type RuntimeAgentProtocolVersion = 'assistant-loop/native-v1' | 'assistant-loop/v1';

const ASSISTANT_FAMILY_HEADERS = Object.freeze({ 'X-Aid-Runtime-Agent-Family': 'ASSISTANT_LOOP' });

export const RUNTIME_AGENT_PERMISSIONS = Object.freeze({
  list: 'aid:assistant:agent:list',
  query: 'aid:assistant:agent:query',
  edit: 'aid:assistant:agent:edit',
  publish: 'aid:assistant:agent:publish',
  activate: 'aid:assistant:agent:activate'
});

export interface RuntimeAgentSummary {
  id: RuntimeId;
  agentCode: string;
  name: string;
  description?: string;
  iconUrl?: string;
  visibility: 'PUBLIC' | 'PRIVATE';
  agentFamily: 'ASSISTANT_LOOP';
  currentVersionId?: RuntimeId | null;
  status: '0' | '1';
  runtimeAvailability: 'AVAILABLE' | 'UNAVAILABLE';
  runtimeUnavailableReason?: string | null;
  updateTime?: string;
}

export interface RuntimeAgentStatusView {
  id: RuntimeId;
  status: '0' | '1';
  updateTime?: string;
}

export interface RuntimeAgentSkillBinding {
  skillId: RuntimeId;
  skillVersionId: RuntimeId;
  skillCode: string;
  packageDigest: string;
}

export interface RuntimeAgentToolBinding {
  toolCode: string;
  contractVersion: string;
  schemaDigest: string;
}

export interface RuntimeAgentResponsePolicy {
  mode: 'NATIVE_TOOL_STREAM' | 'STRICT_LOCAL_JSON' | 'NATIVE_JSON_SCHEMA';
  maxRepairAttempts: number;
}

export interface RuntimeAgentPackageResource {
  resourceKey: string;
  filePath: string;
  mimeType: string;
  content: string;
  routeJson: string;
}

export interface RuntimeAgentDraftPayload {
  agentId: RuntimeId;
  packageVersionCode?: string | null;
  baseVersionId?: RuntimeId | null;
  mainMarkdown?: string;
  systemPrompt?: string;
  resources?: RuntimeAgentPackageResource[];
  instruction: string;
  executorType: 'ASSISTANT_LOOP';
  protocolVersion: RuntimeAgentProtocolVersion;
  modelCapabilityCode: 'text.chat';
  defaultModelCode: string;
  selectableModelCodes: string[];
  temperatureBasisPoints: number;
  maxTurns: number;
  maxToolCalls: number;
  checkpointPolicy: 'TURN_BOUNDARY' | 'TOOL_BOUNDARY';
  inputSchemaJson: string;
  outputSchemaJson: string;
  responsePolicy: RuntimeAgentResponsePolicy;
  skillBindings: RuntimeAgentSkillBinding[];
  toolBindings: RuntimeAgentToolBinding[];
}

export interface RuntimeAgentDraft extends RuntimeAgentDraftPayload {
  draftId?: RuntimeId | null;
  draftDigest: string;
  agentCode: string;
  updateTime?: string | null;
}

export interface RuntimeAgentVersion extends RuntimeAgentDraftPayload {
  id: RuntimeId;
  versionCode: string;
  publishStatus: string;
  packageDigest: string;
  status: string;
  current: boolean;
  runtimeAvailability: 'AVAILABLE' | 'UNAVAILABLE';
  runtimeUnavailableReason?: string | null;
  createBy?: string;
  createTime?: string;
}

export interface RuntimeAgentValidation {
  valid: boolean;
  draftDigest: string;
  errors: { field: string; message: string }[];
  warnings: { field: string; message: string }[];
}

export interface RuntimeAgentSkillOption {
  skillId: RuntimeId;
  skillVersionId: RuntimeId;
  skillCode: string;
  name: string;
  versionCode: string;
  packageDigest: string;
  invocationScope?: string;
}

export interface RuntimeAgentToolOption {
  code: string;
  contractVersion: string;
  schemaDigest: string;
  name: string;
  description: string;
  sideEffectLevel: string;
  billable: boolean;
  requiredCapabilities: string[];
}

export interface RuntimeAgentModuleBinding {
  moduleCode: string;
  expectedAgentCode: string;
  agentId?: RuntimeId | null;
  activeVersionId?: RuntimeId | null;
  bindingRevision: number;
  state: 'UNBOUND' | 'BOUND';
  runtimeAvailability: 'AVAILABLE' | 'UNAVAILABLE';
  runtimeUnavailableReason?: string | null;
}

let epoch = 0;
let activeToken = getToken();
const reads = new Map<string, Promise<ApiResponse<unknown>>>();
const cache = new Map<string, { expiresAt: number; value: ApiResponse<unknown> }>();
const mutations = new Map<string, Promise<ApiResponse<unknown>>>();

function guardToken() {
  const token = getToken();
  if (token !== activeToken) {
    activeToken = token;
    epoch += 1;
    reads.clear(); cache.clear(); mutations.clear();
  }
}

function read<T>(key: string, url: string, data: unknown, force = false): Promise<ApiResponse<T>> {
  guardToken();
  const cacheKey = `${epoch}:${key}:${JSON.stringify(data)}`;
  const active = reads.get(cacheKey);
  if (active) return active as Promise<ApiResponse<T>>;
  const stored = cache.get(cacheKey);
  if (!force && stored && stored.expiresAt > Date.now()) return Promise.resolve(stored.value as ApiResponse<T>);
  const task = request<T>({ url, method: 'post', data, headers: ASSISTANT_FAMILY_HEADERS }).then((response) => {
    if (activeToken === getToken()) cache.set(cacheKey, { expiresAt: Date.now() + 800, value: response });
    return response;
  }).finally(() => { if (reads.get(cacheKey) === task) reads.delete(cacheKey); });
  reads.set(cacheKey, task as Promise<ApiResponse<unknown>>);
  return task;
}

function mutate<T>(key: string, url: string, data: unknown): Promise<ApiResponse<T>> {
  guardToken();
  const mutationKey = `${key}:${JSON.stringify(data)}`;
  const active = mutations.get(mutationKey);
  if (active) return active as Promise<ApiResponse<T>>;
  const task = request<T>({ url, method: 'post', data, headers: ASSISTANT_FAMILY_HEADERS }).then((response) => {
    epoch += 1; cache.clear(); return response;
  }).finally(() => { if (mutations.get(mutationKey) === task) mutations.delete(mutationKey); });
  mutations.set(mutationKey, task as Promise<ApiResponse<unknown>>);
  return task;
}

export function listRuntimeAgents(data: { pageNum: number; pageSize: number; keyword?: string; status?: '0' | '1' }, force = false) {
  return read<RuntimeAgentSummary[]>('agents', '/aid/runtime-agent/list', data, force);
}

export function createRuntimeAgent(data: { agentCode: string; name: string; description?: string; visibility: 'PUBLIC' | 'PRIVATE' }) {
  return mutate<RuntimeAgentSummary>('agent:create', '/aid/runtime-agent/create', data);
}

export function listRuntimeAgentModuleBindings(force = false) {
  return read<RuntimeAgentModuleBinding[]>('module-bindings', '/aid/runtime-agent/module-binding/list', {}, force);
}

export function activateRuntimeAgentModuleBinding(data: {
  moduleCode: RuntimeAgentModuleBinding['moduleCode'];
  agentId: RuntimeId;
  versionId: RuntimeId;
  expectedBindingRevision: number;
}) {
  return mutate<RuntimeAgentModuleBinding>(`module:${data.moduleCode}:activate`,
    '/aid/runtime-agent/module-binding/activate', data);
}

export function editRuntimeAgentIdentity(data: Pick<RuntimeAgentSummary, 'id' | 'name' | 'description' | 'iconUrl' | 'visibility'>) {
  return mutate<void>(`agent:${data.id}:identity`, '/aid/runtime-agent/identity/edit', data);
}

export function setRuntimeAgentStatus(id: RuntimeId, status: '0' | '1') {
  return mutate<RuntimeAgentStatusView>(`agent:${id}:status`, '/aid/runtime-agent/status', { id, status });
}

export function getRuntimeAgentDraft(agentId: RuntimeId, baseVersionId?: RuntimeId, force = false) {
  return read<RuntimeAgentDraft>(`agent:${agentId}:draft`, '/aid/runtime-agent/draft/detail', { agentId, baseVersionId }, force);
}

export function saveRuntimeAgentDraft(data: RuntimeAgentDraftPayload & { draftId?: RuntimeId | null; draftDigest?: string }) {
  return mutate<RuntimeAgentDraft>(`agent:${data.agentId}:draft`, '/aid/runtime-agent/draft/save', data);
}

export function validateRuntimeAgentDraft(data: RuntimeAgentDraftPayload) {
  return read<RuntimeAgentValidation>(`agent:${data.agentId}:validate`, '/aid/runtime-agent/draft/validate', data, true);
}

export function publishRuntimeAgentDraft(draftId: RuntimeId, draftDigest: string, versionCode: string) {
  return mutate<RuntimeAgentVersion>(`draft:${draftId}:publish`, '/aid/runtime-agent/draft/publish', { draftId, draftDigest, versionCode });
}

export function listRuntimeAgentVersions(agentId: RuntimeId, pageNum: number, pageSize: number, force = false) {
  return read<RuntimeAgentVersion[]>(`agent:${agentId}:versions`, '/aid/runtime-agent/version/list', { agentId, pageNum, pageSize }, force);
}

export function getRuntimeAgentVersion(id: RuntimeId, force = false) {
  return read<RuntimeAgentVersion>(`version:${id}`, '/aid/runtime-agent/version/detail', { id }, force);
}

export function activateRuntimeAgentVersion(agentId: RuntimeId, versionId: RuntimeId, expectedCurrentVersionId?: RuntimeId | null) {
  return mutate<void>(`agent:${agentId}:activate`, '/aid/runtime-agent/version/activate', { agentId, versionId, expectedCurrentVersionId });
}

export function listRuntimeAgentModels() {
  return read<SkillTextModelOption[]>('models', '/aid/runtime-agent/model/options', {});
}

export function listRuntimeAgentSkills() {
  return read<RuntimeAgentSkillOption[]>('skills', '/aid/runtime-agent/skill/options', {});
}

export function listRuntimeAgentTools() {
  return read<RuntimeAgentToolOption[]>('tools', '/aid/runtime-agent/tool/options', {});
}
