import { request } from '@/utils/request';
import type { ApiResponse } from '@/types/common';

export type SkillId = string | number;

export interface SkillRequestOptions {
  /** 只绕过已完成结果的短时缓存；同业务键的进行中请求仍会合并。 */
  force?: boolean;
}

function createRequestCoordinator(ttlMs: number) {
  const pending = new Map<string, { promise: Promise<unknown>; revision: number }>();
  const completed = new Map<string, { expiresAt: number; value: unknown }>();
  const revisions = new Map<string, number>();

  const prune = () => {
    const now = Date.now();
    for (const [key, cached] of completed) {
      if (cached.expiresAt <= now) {
        completed.delete(key);
        if (!pending.has(key)) revisions.delete(key);
      }
    }
    while (completed.size > 200) {
      const oldestKey = completed.keys().next().value as string | undefined;
      if (oldestKey == null) break;
      completed.delete(oldestKey);
      if (!pending.has(oldestKey)) revisions.delete(oldestKey);
    }
  };

  const run = <T>(key: string, operation: () => Promise<T>, options?: SkillRequestOptions): Promise<T> => {
    prune();
    const inFlight = pending.get(key);
    if (inFlight) {
      return inFlight.promise as Promise<T>;
    }

    const cached = completed.get(key);
    if (!options?.force && cached && cached.expiresAt > Date.now()) {
      return Promise.resolve(cached.value as T);
    }
    if (cached) completed.delete(key);

    const revision = revisions.get(key) || 0;
    const promise = operation();
    pending.set(key, { promise, revision });
    promise.then((value) => {
      if ((revisions.get(key) || 0) === revision) {
        completed.set(key, { expiresAt: Date.now() + ttlMs, value });
      }
    }, () => {
      completed.delete(key);
    });
    const clearPending = () => {
      if (pending.get(key)?.promise === promise) {
        pending.delete(key);
        if (!completed.has(key)) revisions.delete(key);
      }
    };
    promise.then(clearPending, clearPending);
    return promise;
  };

  const invalidate = (predicate?: (key: string) => boolean) => {
    const keys = new Set([...completed.keys(), ...pending.keys()]);
    for (const key of keys) {
      if (!predicate || predicate(key)) {
        completed.delete(key);
        if (pending.has(key)) revisions.set(key, (revisions.get(key) || 0) + 1);
        else revisions.delete(key);
      }
    }
  };

  const settle = async (predicate?: (key: string) => boolean) => {
    const active = [...pending.entries()]
      .filter(([key]) => !predicate || predicate(key))
      .map(([, slot]) => slot.promise);
    if (active.length) await Promise.allSettled(active);
  };

  return { run, invalidate, settle };
}

function normalizedJson(value: unknown) {
  return JSON.stringify(value);
}

const readRequests = createRequestCoordinator(800);
const mutationInflight = new Map<string, { fingerprint: string; promise: Promise<ApiResponse<unknown>> }>();

function skillReadPredicate(skillId?: SkillId) {
  return (key: string) => skillId == null || key.startsWith('skills:')
    || key.startsWith('dependencies:')
    || key.startsWith('version:')
    || key === 'models'
    || key.includes(`:skill:${skillId}:`);
}

function invalidateSkillReads(skillId?: SkillId) {
  readRequests.invalidate(skillReadPredicate(skillId));
}

function coordinatedMutation<T>(
  resourceKey: string,
  fingerprint: string,
  operation: () => Promise<ApiResponse<T>>,
  skillId?: SkillId
) {
  const existing = mutationInflight.get(resourceKey);
  if (existing) {
    return existing.fingerprint === fingerprint
      ? existing.promise as Promise<ApiResponse<T>>
      : Promise.reject(new Error('同一 Skill 正在执行其他修改，请稍后重试'));
  }
  const pending = operation().then(async (response) => {
    await readRequests.settle(skillReadPredicate(skillId));
    invalidateSkillReads(skillId);
    return response;
  }).finally(() => {
    if (mutationInflight.get(resourceKey)?.promise === pending) mutationInflight.delete(resourceKey);
  });
  mutationInflight.set(resourceKey, { fingerprint, promise: pending });
  return pending;
}

export interface SkillListQuery {
  skillId?: SkillId;
  includeDeleted?: boolean;
  pageNum: number;
  pageSize: number;
  keyword?: string;
  status?: '0' | '1';
  ownerType?: 'PLATFORM' | 'USER';
  ownerUserId?: SkillId;
}

export interface AdminSkillSummary {
  id: SkillId;
  skillCode: string;
  name: string;
  description?: string;
  capabilityDescription?: string;
  iconUrl?: string;
  ownerType: string;
  ownerUserId?: SkillId | null;
  visibility: string;
  invocationScope: string;
  currentVersionId?: SkillId | null;
  status: '0' | '1';
  delFlag: '0' | '1';
  modelCode: string;
  reasoningPolicy: 'DISABLED' | 'OPTIONAL' | 'REQUIRED';
  cacheToDisk?: boolean;
  moderationReason?: string;
  moderatedBy?: string;
  moderatedAt?: string;
  updateTime?: string;
}

export interface SkillIdentitySavePayload {
  id: SkillId;
  name: string;
  description?: string;
  capabilityDescription?: string;
  iconUrl?: string;
  visibility: 'PRIVATE' | 'PUBLIC';
  invocationScope: 'ENTRYPOINT' | 'INTERNAL';
  cacheToDisk?: boolean;
  status: '0' | '1';
}

export interface SkillCreatePayload {
  skillCode: string;
  name: string;
  description?: string;
  iconUrl?: string;
  visibility?: 'PRIVATE' | 'PUBLIC';
  invocationScope?: 'ENTRYPOINT' | 'INTERNAL';
  templateVersionId?: SkillId;
}

export interface SkillRunItem {
  id: SkillId;
  userId: SkillId;
  skillId: SkillId;
  skillVersionId: SkillId;
  projectId?: SkillId | null;
  episodeId?: SkillId | null;
  skillConfigHash: string;
  modelCode: string;
  invokeSource: string;
  clientRequestId: string;
  generation: number;
  status: string;
  stage?: string;
  actionMode?: string;
  qualityMode?: string;
  clientRequestDigest?: string;
  executionSnapshotDigest?: string;
  resolvedConfigDigest?: string;
  rootRunId?: SkillId;
  parentRunId?: SkillId;
  inputJson?: string;
  outputJson?: string;
  errorMessage?: string;
  startedAt?: string;
  finishedAt?: string;
  tasks: SkillRunTaskItem[];
}

export interface SkillRunTaskItem {
  stepId: SkillId;
  stepSeq: number;
  stepKey: string;
  stepExecutionId: string;
  skillId: SkillId;
  skillVersionId: SkillId;
  actionMode: string;
  workflowAttempt: number;
  orchestrationStatus: string;
  mediaTaskId?: SkillId;
  mediaStatus?: string;
  billingStatus?: string;
  /** Decimal string returned by the audit API; do not coerce to IEEE-754 number. */
  actualCost?: string;
}

export type SkillRunSummary = Omit<SkillRunItem,
  'clientRequestDigest' | 'executionSnapshotDigest' | 'resolvedConfigDigest' | 'rootRunId'
  | 'parentRunId' | 'inputJson' | 'outputJson' | 'errorMessage' | 'tasks'> & {
  durationMillis?: number;
};

export interface SkillRunQuery {
  pageNum: number;
  pageSize: number;
  skillId?: SkillId;
  userId?: SkillId;
  status?: string;
}

export interface SkillTextModelOption {
  modelCode: string;
  modelName?: string;
  modelType?: string;
  capabilityJson?: string;
  capability?: Record<string, unknown>;
  capabilities?: { code: string; label?: string; enabled?: boolean }[];
  modelLogo?: string;
  providerName?: string;
  providerLogo?: string;
  billing?: Record<string, unknown>;
  status?: string;
  delFlag?: string;
  available: boolean;
  unavailableReason?: string;
}

export interface SkillDraftResource {
  resourceKey: string;
  filePath: string;
  resourceType: string;
  mimeType: string;
  content: string;
  routeJson: string;
}

export interface SkillDraftRelation {
  relationKey: string;
  childSkillId: SkillId;
  childVersionId: SkillId;
  requiredFlag: boolean;
}

export interface SkillPackagePayload {
  skillId: SkillId;
  baseVersionId?: SkillId;
  skillMarkdown: string;
  modelCode: string;
  defaultModelCode: string;
  /** Immutable model configuration text; round-trip unchanged so provider-specific keys survive. */
  modelConfigJson?: string;
  selectableModelCodes: string[];
  systemPrompt: string;
  inputSchemaJson: string;
  outputSchemaJson: string;
  definitionJson?: string;
  maxOutputTokens: number;
  contextWindowTokens: number;
  safetyMarginTokens: number;
  resources: SkillDraftResource[];
  relations: SkillDraftRelation[];
}

export interface SkillDraftDetail extends SkillPackagePayload {
  draftId?: SkillId;
  baseVersionCode?: string;
  skillCode: string;
  executorType: string;
  invocationScope: string;
  draftDigest?: string;
  updateTime?: string;
}

export interface SkillVersionSummary {
  id: SkillId;
  skillId: SkillId;
  versionCode: string;
  publishStatus: string;
  packageDigest: string;
  status: '0' | '1';
  current: boolean;
  createBy?: string;
  createTime?: string;
}

export interface SkillVersionListQuery {
  skillId: SkillId;
  pageNum: number;
  pageSize: number;
}

export interface SkillVersionResource extends SkillDraftResource {
  id: SkillId;
  objectKey: string;
  contentDigest: string;
  sizeBytes: number;
}

export interface SkillVersionRelation extends SkillDraftRelation {
  id: SkillId;
  childSkillCode: string;
  childVersionCode: string;
}

export interface SkillVersionDetail {
  id: SkillId;
  skillId: SkillId;
  skillCode: string;
  versionCode: string;
  visibility: string;
  invocationScope: string;
  publishStatus: string;
  executorType: string;
  modelCode: string;
  modelConfigJson?: string;
  defaultModelCode: string;
  selectableModelCodes: string[];
  packageDigest: string;
  manifestJson: string;
  inputSchemaJson: string;
  outputSchemaJson: string;
  systemPrompt: string;
  skillMarkdown: string;
  definitionJson?: string;
  maxOutputTokens: number;
  contextWindowTokens: number;
  safetyMarginTokens: number;
  status: '0' | '1';
  current: boolean;
  createBy?: string;
  createTime?: string;
  resources: SkillVersionResource[];
  relations: SkillVersionRelation[];
}

export interface SkillValidationIssue {
  field: string;
  message: string;
}

export interface SkillValidationResult {
  valid: boolean;
  draftDigest?: string;
  errors: SkillValidationIssue[];
  warnings: SkillValidationIssue[];
}

export interface SkillDependencyVersionOption {
  id: SkillId;
  versionCode: string;
  current: boolean;
}

export interface SkillDependencyOption {
  skillId: SkillId;
  skillCode: string;
  name: string;
  currentVersionId?: SkillId | null;
}

export interface SkillDependencyListQuery {
  skillId: SkillId;
  pageNum: number;
  pageSize: number;
  keyword?: string;
}

export interface SkillDependencyVersionListQuery {
  parentSkillId: SkillId;
  childSkillId: SkillId;
  pageNum: number;
  pageSize: number;
  keyword?: string;
}

export interface SkillDependencyLabel {
  childSkillId: SkillId;
  childSkillCode: string;
  childSkillName: string;
  childVersionId: SkillId;
  childVersionCode: string;
  current: boolean;
}

export interface SkillDraftSavePayload extends SkillPackagePayload {
  draftId?: SkillId;
  draftDigest?: string;
}

export function listSkills(data: SkillListQuery, options?: SkillRequestOptions) {
  const requestKey = `skills:${JSON.stringify([
    data.pageNum,
    data.pageSize,
    data.keyword || '',
    data.status || '',
    data.skillId == null ? '' : String(data.skillId), data.includeDeleted,
    data.ownerType || '',
    data.ownerUserId == null ? '' : String(data.ownerUserId)
  ])}`;
  return readRequests.run(requestKey,
    () => request<AdminSkillSummary[]>({ url: '/aid/skill/list', method: 'post', data }), options);
}

export function editSkillIdentity(data: SkillIdentitySavePayload) {
  return coordinatedMutation(`skill:${data.id}`, `identity-edit:${normalizedJson(data)}`,
    () => request({ url: '/aid/skill/identity/edit', method: 'post', data }), data.id);
}

export interface RegisteredToolOption {
  code?: string;
  toolCode: string;
  name: string;
  description?: string;
  enabled?: boolean;
}

export interface SkillPublicationSettings {
  allowUserPublicSkills: boolean;
}

export function createSkill(data: SkillCreatePayload) {
  return coordinatedMutation('skill:create', `create:${normalizedJson(data)}`,
    () => request<AdminSkillSummary>({ url: '/aid/skill/create', method: 'post', data }));
}

export function getSkillPublicationSettings(options?: SkillRequestOptions) {
  return readRequests.run('skill:publication:settings',
    () => request<SkillPublicationSettings>({
      url: '/aid/skill/publication/settings', method: 'post', data: {}
    }), options);
}

export function saveSkillPublicationSettings(data: SkillPublicationSettings) {
  return coordinatedMutation('skill:publication:settings', `settings:${normalizedJson(data)}`,
    () => request<void>({
      url: '/aid/skill/publication/settings/save', method: 'post', data
    }));
}

export function unpublishUserSkill(id: SkillId, reason: string) {
  const data = { id, reason };
  return coordinatedMutation(`skill:${id}`, `moderation-unpublish:${normalizedJson(data)}`,
    () => request({ url: '/aid/skill/moderation/unpublish', method: 'post', data }), id);
}

export function updateSkillStatus(id: SkillId, status: '0' | '1') {
  return coordinatedMutation(`skill:${id}`, `status:${status}`,
    () => request({ url: '/aid/skill/status', method: 'post', data: { id, status } }), id);
}

export function removeSkill(id: SkillId) {
  return coordinatedMutation(`skill:${id}`, 'remove',
    () => request({ url: '/aid/skill/remove', method: 'post', data: { id },
      suppressConflictToast: true }), id);
}

export function restoreSkill(id: SkillId) {
  return coordinatedMutation(`skill:${id}`, 'restore',
    () => request({ url: '/aid/skill/restore', method: 'post', data: { id } }), id);
}

export function listSkillTextModels(options?: SkillRequestOptions) {
  return readRequests.run('models',
    () => request<SkillTextModelOption[]>({ url: '/aid/skill/model/options', method: 'post', data: {} }), options);
}

export function listRegisteredToolOptions(options?: SkillRequestOptions) {
  return readRequests.run('skill:tool:options',
    () => request<RegisteredToolOption[]>({ url: '/aid/runtime-plan-agent/tool/options', method: 'post', data: {},
      headers: { 'X-Aid-Runtime-Agent-Family': 'LEGACY_PLAN' } }), options);
}

export function listSkillRuns(data: SkillRunQuery, options?: SkillRequestOptions) {
  const requestKey = `runs:skill:${data.skillId ?? 'all'}:${JSON.stringify([
    data.pageNum, data.pageSize, data.userId ?? '', data.status || ''
  ])}`;
  return readRequests.run(requestKey,
    () => request<SkillRunSummary[]>({ url: '/aid/skill/run/list', method: 'post', data }), options);
}

export function getSkillRun(id: SkillId, options?: SkillRequestOptions) {
  return readRequests.run(`run:${id}`,
    () => request<SkillRunItem>({ url: '/aid/skill/run/detail', method: 'post', data: { id } }), options);
}

export function listSkillRuntimeRuns(data: { skillId?: SkillId; userId?: SkillId; status?: string;
  pageNum: number; pageSize: number }, options?: SkillRequestOptions) {
  return readRequests.run(`runtime-runs:skill:${normalizedJson(data)}`,
    () => request<import('./legacyRuntimePlanAgent').RuntimeAgentRun[]>({
      url: '/aid/skill/runtime-run/list', method: 'post', data }), options);
}

export function getSkillRuntimeRun(runId: SkillId, options?: SkillRequestOptions) {
  return readRequests.run(`runtime-run:${runId}`,
    () => request<import('./legacyRuntimePlanAgent').RuntimeAgentRun>({
      url: '/aid/skill/runtime-run/detail', method: 'post', data: { runId } }), options);
}

export function listSkillVersions(data: SkillVersionListQuery, options?: SkillRequestOptions) {
  const requestKey = `versions:skill:${data.skillId}:page:${data.pageNum}:size:${data.pageSize}`;
  return readRequests.run(requestKey,
    () => request<SkillVersionSummary[]>({
      url: '/aid/skill/version/list', method: 'post', data
    }).then((response) => response as ApiResponse<SkillVersionSummary[]> & {
      currentVersionId: SkillId | null;
    }), options);
}

export function getSkillVersion(id: SkillId, options?: SkillRequestOptions) {
  return readRequests.run(`version:${id}`,
    () => request<SkillVersionDetail>({
      url: '/aid/skill/version/detail', method: 'post', data: { id }
    }), options);
}

export function getSkillDraft(skillId: SkillId, baseVersionId?: SkillId, options?: SkillRequestOptions) {
  return readRequests.run(`draft:skill:${skillId}:base:${baseVersionId ?? 'current'}`,
    () => request<SkillDraftDetail>({
      url: '/aid/skill/draft/detail', method: 'post', data: { skillId, baseVersionId }
    }), options);
}

export function listSkillDependencyOptions(data: SkillDependencyListQuery, options?: SkillRequestOptions) {
  const keyword = data.keyword?.trim() || '';
  const normalized = { ...data, keyword: keyword || undefined };
  const requestKey = `dependencies:skills:${normalizedJson(normalized)}`;
  return readRequests.run(requestKey,
    () => request<SkillDependencyOption[]>({
      url: '/aid/skill/dependency/options', method: 'post', data: normalized
    }), options);
}

export function listSkillDependencyVersionOptions(
  data: SkillDependencyVersionListQuery, options?: SkillRequestOptions
) {
  const keyword = data.keyword?.trim() || '';
  const normalized = { ...data, keyword: keyword || undefined };
  const requestKey = `dependencies:versions:${normalizedJson(normalized)}`;
  return readRequests.run(requestKey,
    () => request<SkillDependencyVersionOption[]>({
      url: '/aid/skill/dependency/version/options', method: 'post',
      data: normalized
    }), options);
}

export function getSkillDependencyLabels(
  parentSkillId: SkillId, versionIds: SkillId[], options?: SkillRequestOptions
) {
  const normalizedIds = [...new Set(versionIds)].sort((left, right) =>
    String(left).localeCompare(String(right))).slice(0, 16);
  const data = { parentSkillId, versionIds: normalizedIds };
  return readRequests.run(`dependencies:labels:${normalizedJson(data)}`,
    () => request<SkillDependencyLabel[]>({
      url: '/aid/skill/dependency/labels', method: 'post', data
    }), options);
}

export function saveSkillDraft(data: SkillDraftSavePayload) {
  const resourceKey = data.draftId ? `draft:${data.draftId}` : `skill:${data.skillId}`;
  return coordinatedMutation(resourceKey, `draft-save:${normalizedJson(data)}`,
    () => request<SkillDraftDetail>({ url: '/aid/skill/draft/save', method: 'post', data }), data.skillId);
}

export function discardSkillDraft(skillId: SkillId, draftId: SkillId, draftDigest: string) {
  const data = { draftId, draftDigest };
  return coordinatedMutation(`draft:${draftId}`, `draft-discard:${draftDigest}`,
    () => request({ url: '/aid/skill/draft/discard', method: 'post', data }), skillId);
}

export function validateSkillDraft(data: SkillPackagePayload, options?: SkillRequestOptions) {
  return readRequests.run(`draft-validate:skill:${data.skillId}:${normalizedJson(data)}`,
    () => request<SkillValidationResult>({
      url: '/aid/skill/draft/validate', method: 'post', data
    }), options);
}

export function publishSkillDraft(draftId: SkillId, draftDigest: string, versionCode: string) {
  const data = { draftId, draftDigest, versionCode };
  return coordinatedMutation(`draft:${draftId}`, `draft-publish:${draftDigest}:${versionCode}`,
    () => request<SkillVersionDetail>({ url: '/aid/skill/draft/publish', method: 'post', data }));
}

export function activateSkillVersion(
  skillId: SkillId, versionId: SkillId, expectedCurrentVersionId: SkillId | null
) {
  const data = { skillId, versionId, expectedCurrentVersionId };
  return coordinatedMutation(`skill:${skillId}`, `version-activate:${normalizedJson(data)}`,
    () => request({
      url: '/aid/skill/version/activate', method: 'post', data
    }), skillId);
}
