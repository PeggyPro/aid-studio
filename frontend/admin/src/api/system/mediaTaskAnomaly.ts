import { getToken } from '@/utils/auth';
import { request } from '@/utils/request';
import { invalidateSharedReads, sharedReadRequest } from '@/utils/sharedReadRequest';
import { createSingleFlight } from '@/utils/singleFlight';

export type EntityId = number | string;

export type MediaTaskAnomalyStatus =
  | 'OBSERVING'
  | 'PENDING'
  | 'WAITING'
  | 'COMPENSATION_PENDING'
  | 'CLOSED'
  | string;

export interface MediaTaskAnomalyLatest {
  id: EntityId;
  taskId: EntityId;
  mediaType?: string | null;
  anomalyType?: string | null;
  detectedAt?: string | null;
}

export interface MediaTaskAnomalySummary {
  pendingCount: number;
  longestWaitingSeconds: number;
  latestAnomaly?: MediaTaskAnomalyLatest | null;
  adminRefreshSeconds: number;
  updateTime?: string | null;
}

export interface MediaTaskAnomalyConfig {
  enabled: boolean;
  anomalyThresholdSeconds: number;
  autoRecheckIntervalSeconds: number;
  adminRefreshSeconds: number;
}

export interface MediaTaskAnomalyRow {
  id: EntityId;
  taskId: EntityId;
  userId?: EntityId | null;
  userName?: string | null;
  projectId?: EntityId | null;
  nodeId?: string | null;
  bizType?: string | null;
  mediaType?: string | null;
  providerCode?: string | null;
  modelCode?: string | null;
  taskStatus?: string | null;
  anomalyType?: string | null;
  anomalyStatus?: MediaTaskAnomalyStatus | null;
  anomalyReason?: string | null;
  waitingSeconds?: number | null;
  detectedAt?: string | null;
  lastCheckedAt?: string | null;
  nextReviewTime?: string | null;
  lastCheckMessage?: string | null;
  handlingReason?: string | null;
  handler?: string | null;
  handledAt?: string | null;
  billingStatus?: string | null;
  frozenAmount?: number | string | null;
  actualCost?: number | string | null;
  billingTraceId?: string | null;
  compensationPending?: boolean | number | null;
  providerTaskId?: string | null;
  upstreamAcceptTime?: string | null;
  lastProgressTime?: string | null;
  terminalTime?: string | null;
  lastProviderStatus?: string | null;
  resolutionType?: string | null;
  audits?: MediaTaskAnomalyAudit[];
  updateTime?: string | null;
}

export interface MediaTaskAnomalyAudit {
  id: EntityId;
  action?: string | null;
  operatorId?: EntityId | null;
  operatorName?: string | null;
  reason?: string | null;
  beforeStatus?: string | null;
  afterStatus?: string | null;
  createTime?: string | null;
}

export interface MediaTaskAnomalyListQuery extends Record<string, unknown> {
  pageNum: number;
  pageSize: number;
  taskId?: EntityId;
  userId?: EntityId;
  mediaType?: string;
  anomalyType?: string;
  anomalyStatus?: string;
  detectedStartTime?: string;
  detectedEndTime?: string;
}

export interface ContinueWaitInput {
  reason: string;
  nextReviewTime: string;
}

export interface ConfirmFailureInput {
  reason: string;
  confirmed: true;
}

const API_PREFIX = '/system/media/task/anomaly';
export const MEDIA_TASK_ANOMALY_ROUTE = '/task/anomaly';
export const MEDIA_TASK_ANOMALY_PERMISSIONS = Object.freeze({
  list: 'system:media:task:anomaly:list',
  recheck: 'system:media:task:anomaly:recheck',
  continueWait: 'system:media:task:anomaly:continue-wait',
  confirmFailure: 'system:media:task:anomaly:confirm-failure',
  config: 'system:media:task:anomaly:config'
});

const DEFAULT_REFRESH_SECONDS = 60;

interface SummaryPending {
  generation: number;
  promise: Promise<MediaTaskAnomalySummary>;
}

let summaryGeneration = 0;
let currentToken = getToken();
let sessionGeneration = 0;
const summaryCache = new Map<string, { expiresAt: number; value: MediaTaskAnomalySummary }>();
const summaryPending = new Map<string, SummaryPending>();
const summaryQueuedRefreshes = new Map<string, Promise<MediaTaskAnomalySummary>>();
const runMutation = createSingleFlight<string>();

function sessionKey() {
  const token = getToken();
  if (token !== currentToken) {
    currentToken = token;
    sessionGeneration++;
    summaryCache.clear();
  }
  return String(sessionGeneration);
}

function refreshSeconds(value: unknown) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return DEFAULT_REFRESH_SECONDS;
  return Math.max(10, Math.round(parsed));
}

function normalizeSummary(response: any): MediaTaskAnomalySummary {
  const data = response?.data || {};
  const configuredRefresh = data.adminRefreshSeconds ?? data.config?.adminRefreshSeconds;
  return {
    pendingCount: Math.max(0, Number(data.pendingCount) || 0),
    longestWaitingSeconds: Math.max(0, Number(data.longestWaitingSeconds) || 0),
    latestAnomaly: data.latestAnomaly || null,
    adminRefreshSeconds: refreshSeconds(configuredRefresh),
    updateTime: data.updateTime || null
  };
}

/**
 * 三处入口共用的异常任务摘要读取。
 * force 只绕过结果缓存；同账号同业务键已有请求时始终共用该请求。
 */
export function getMediaTaskAnomalySummary(force = false): Promise<MediaTaskAnomalySummary> {
  const key = sessionKey();
  const generation = summaryGeneration;
  const running = summaryPending.get(key);
  if (running) {
    if (running.generation === generation) return running.promise;

    const refreshKey = `${key}:${generation}`;
    const queued = summaryQueuedRefreshes.get(refreshKey);
    if (queued) return queued;
    const refresh = running.promise
      .catch(() => undefined)
      .then(() => {
        if (key !== sessionKey()) throw new Error('登录状态已变更，请重新加载');
        return getMediaTaskAnomalySummary(true);
      })
      .finally(() => {
        if (summaryQueuedRefreshes.get(refreshKey) === refresh) {
          summaryQueuedRefreshes.delete(refreshKey);
        }
      });
    summaryQueuedRefreshes.set(refreshKey, refresh);
    return refresh;
  }

  const cached = summaryCache.get(key);
  if (!force && cached && cached.expiresAt > Date.now()) return Promise.resolve(cached.value);
  if (cached && cached.expiresAt <= Date.now()) summaryCache.delete(key);

  const entry: SummaryPending = {
    generation,
    promise: Promise.resolve({
      pendingCount: 0,
      longestWaitingSeconds: 0,
      latestAnomaly: null,
      adminRefreshSeconds: DEFAULT_REFRESH_SECONDS
    })
  };
  const promise = request({ url: `${API_PREFIX}/summary`, method: 'get' })
    .then((response) => {
      const value = normalizeSummary(response);
      if (generation === summaryGeneration && key === sessionKey()) {
        summaryCache.set(key, {
          expiresAt: Date.now() + value.adminRefreshSeconds * 1000,
          value
        });
      }
      return value;
    })
    .finally(() => {
      if (summaryPending.get(key) === entry) summaryPending.delete(key);
    });
  entry.promise = promise;
  summaryPending.set(key, entry);
  return promise;
}

export function invalidateMediaTaskAnomalySummary() {
  summaryGeneration++;
  summaryCache.clear();
}

export function listMediaTaskAnomalies(query: MediaTaskAnomalyListQuery, force = false) {
  return sharedReadRequest(`${API_PREFIX}/list`, query, force);
}

export function getMediaTaskAnomaly(id: EntityId, force = false) {
  return sharedReadRequest(`${API_PREFIX}/${id}`, undefined, force);
}

export function getMediaTaskAnomalyConfig(force = false) {
  return sharedReadRequest(`${API_PREFIX}/config`, undefined, force);
}

function mutationKey(action: string, id: EntityId) {
  return `${sessionKey()}:${action}:${String(id)}`;
}

async function mutate(id: EntityId, action: string, data?: object) {
  return runMutation(mutationKey(action, id), async () => {
    const result = await request({
      url: `${API_PREFIX}/${id}/${action}`,
      method: 'post',
      data
    });
    invalidateSharedReads(API_PREFIX);
    invalidateMediaTaskAnomalySummary();
    return result;
  });
}

export function recheckMediaTaskAnomaly(id: EntityId) {
  return mutate(id, 'recheck');
}

export function recheckMediaTaskById(taskId: string, data: { reason: string }) {
  return runMutation(`${sessionKey()}:task-recheck:${taskId}:${JSON.stringify(data)}`, async () => {
    const result = await request({ url: `${API_PREFIX}/task/${taskId}/recheck`, method: 'post', data });
    invalidateSharedReads(API_PREFIX);
    invalidateMediaTaskAnomalySummary();
    return result;
  });
}

export function continueWaitingMediaTaskAnomaly(id: EntityId, data: ContinueWaitInput) {
  return mutate(id, 'continue-wait', data);
}

export function confirmMediaTaskAnomalyFailure(id: EntityId, data: ConfirmFailureInput) {
  return mutate(id, 'confirm-failure', data);
}

export function updateMediaTaskAnomalyConfig(data: MediaTaskAnomalyConfig) {
  return runMutation(`${sessionKey()}:config:update`, async () => {
    const result = await request({
      url: `${API_PREFIX}/config`,
      method: 'put',
      data
    });
    invalidateSharedReads(API_PREFIX);
    invalidateMediaTaskAnomalySummary();
    return result;
  });
}
