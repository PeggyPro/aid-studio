import { request } from '@/utils/request';
import { getToken } from '@/utils/auth';
import type { ApiResponse } from '@/types/common';

export type NotificationId = string;
export type NotificationVersion = string;
export type NotificationStatus = 'DRAFT' | 'SCHEDULED' | 'PUBLISHED' | 'WITHDRAWN';
export type NotificationRecipientType = 'ALL' | 'USERS';
export type NotificationContentFormat = 'MARKDOWN' | 'PLAIN_TEXT';

export const NOTIFICATION_PERMISSIONS = {
  list: 'system:notification:list',
  save: 'system:notification:save',
  publish: 'system:notification:publish',
  withdraw: 'system:notification:withdraw',
  cancelSchedule: 'system:notification:cancelSchedule',
  recipients: 'system:notification:recipients'
} as const;

export interface NotificationSummary {
  id: NotificationId;
  version: NotificationVersion;
  title: string;
  summary?: string | null;
  status: NotificationStatus;
  recipientType: NotificationRecipientType;
  recipientCount: number;
  scheduledTime?: string | null;
  publishTime?: string | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface NotificationDetail extends NotificationSummary {
  content: string;
  contentFormat: NotificationContentFormat;
  recipientUserIds: NotificationId[];
}

export interface NotificationListQuery {
  pageNum: number;
  pageSize: number;
  status?: NotificationStatus;
  keyword?: string;
}

export interface NotificationListResponse extends ApiResponse<NotificationSummary[]> {
  total: number;
  pageNum: number;
  pageSize: number;
}

export interface NotificationDetailRequest {
  id: NotificationId;
}

export interface NotificationSaveRequest {
  id?: NotificationId;
  expectedVersion?: NotificationVersion;
  title: string;
  summary?: string;
  content: string;
  contentFormat: NotificationContentFormat;
  recipientType: NotificationRecipientType;
  recipientUserIds: NotificationId[];
  scheduledTime?: string | null;
  clientRequestId: string;
}

export interface NotificationActionRequest {
  id: NotificationId;
  expectedVersion: NotificationVersion;
  clientRequestId: string;
}

export interface NotificationRecipient {
  userId: NotificationId;
  userName: string;
  nickName?: string | null;
  avatar?: string | null;
  status: '0' | '1' | string;
  createTime?: string | null;
}

export interface NotificationRecipientQuery {
  keyword?: string;
  pageNum: number;
  pageSize: number;
}

export interface NotificationRecipientResponse extends ApiResponse<NotificationRecipient[]> {
  total: number;
  pageNum: number;
  pageSize: number;
}

interface PendingRead {
  generation: number;
  promise: Promise<ApiResponse<unknown>>;
}

const API_PREFIX = '/aid/notification';
const READ_CACHE_MS = 1000;
const pendingReads = new Map<string, PendingRead>();
const queuedRefreshes = new Map<string, Promise<ApiResponse<unknown>>>();
const recentReads = new Map<string, { expiresAt: number; value: ApiResponse<unknown> }>();
const pendingWrites = new Map<string, Promise<ApiResponse<unknown>>>();
let readGeneration = 0;
let sessionGeneration = 0;
let sessionToken = getToken();

function ordered(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(ordered);
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.keys(value as Record<string, unknown>)
        .sort()
        .map((key) => [key, ordered((value as Record<string, unknown>)[key])])
    );
  }
  return value;
}

function stableJson(value: unknown) {
  return JSON.stringify(ordered(value));
}

function syncSession() {
  const token = getToken();
  if (token !== sessionToken) {
    sessionToken = token;
    sessionGeneration += 1;
    recentReads.clear();
  }
  return token;
}

function postRead<TResponse extends ApiResponse<unknown>>(
  path: string,
  data: object,
  force = false
): Promise<TResponse> {
  const token = syncSession();
  const session = sessionGeneration;
  const generation = readGeneration;
  const key = `${path}:${session}:${stableJson(data)}`;
  const running = pendingReads.get(key);

  if (running) {
    if (running.generation === generation) return running.promise as Promise<TResponse>;

    const refreshKey = `${key}:${generation}`;
    const queued = queuedRefreshes.get(refreshKey);
    if (queued) return queued as Promise<TResponse>;
    const refresh = running.promise
      .catch(() => undefined)
      .then(() => {
        if (token !== getToken()) throw new Error('登录状态已变更，请重新加载');
        return postRead<TResponse>(path, data, true);
      })
      .finally(() => {
        if (queuedRefreshes.get(refreshKey) === refresh) queuedRefreshes.delete(refreshKey);
      });
    queuedRefreshes.set(refreshKey, refresh);
    return refresh;
  }

  const cached = recentReads.get(key);
  if (!force && cached && cached.expiresAt > Date.now()) {
    return Promise.resolve(cached.value as TResponse);
  }
  for (const [cacheKey, entry] of recentReads) {
    if (entry.expiresAt <= Date.now()) recentReads.delete(cacheKey);
  }

  const entry: PendingRead = { generation, promise: Promise.resolve({} as ApiResponse<unknown>) };
  const promise = request({
    url: `${API_PREFIX}/${path}`,
    method: 'post',
    data,
    // 本模块已按业务键合并查询；避免全局 POST 写请求防重把 recipients 等读取误判为重复提交。
    headers: { repeatSubmit: false }
  })
    .then((response) => {
      if (generation === readGeneration && session === sessionGeneration && token === getToken()) {
        recentReads.set(key, { expiresAt: Date.now() + READ_CACHE_MS, value: response });
      }
      return response;
    })
    .finally(() => {
      if (pendingReads.get(key) === entry) pendingReads.delete(key);
    });
  entry.promise = promise;
  pendingReads.set(key, entry);
  return promise as Promise<TResponse>;
}

function invalidateNotificationReads() {
  readGeneration += 1;
  recentReads.clear();
}

function postWrite<T>(path: string, data: object): Promise<ApiResponse<T>> {
  syncSession();
  const key = `${path}:${sessionGeneration}:${stableJson(data)}`;
  const running = pendingWrites.get(key);
  if (running) return running as Promise<ApiResponse<T>>;

  const promise = request<T>({
    url: `${API_PREFIX}/${path}`,
    method: 'post',
    data,
    // 写请求由同内容 singleflight 与服务端 clientRequestId 幂等共同治理，允许未知结果按原请求重试。
    headers: { repeatSubmit: false },
    suppressConflictToast: true,
    preserveBusinessError: true
  })
    .then((response) => {
      invalidateNotificationReads();
      return response;
    })
    .finally(() => {
      if (pendingWrites.get(key) === promise) pendingWrites.delete(key);
    });
  pendingWrites.set(key, promise);
  return promise;
}

export function listNotifications(data: NotificationListQuery, force = false) {
  return postRead<NotificationListResponse>('list', data, force);
}

export function getNotificationDetail(data: NotificationDetailRequest, force = false) {
  return postRead<ApiResponse<NotificationDetail>>('detail', data, force);
}

export function saveNotification(data: NotificationSaveRequest) {
  return postWrite<NotificationDetail>('save', data);
}

export function publishNotification(data: NotificationActionRequest) {
  return postWrite<NotificationSummary>('publish', data);
}

export function withdrawNotification(data: NotificationActionRequest) {
  return postWrite<NotificationSummary>('withdraw', data);
}

export function cancelScheduledNotification(data: NotificationActionRequest) {
  return postWrite<NotificationSummary>('cancel-schedule', data);
}

export function listNotificationRecipients(data: NotificationRecipientQuery, force = false) {
  return postRead<NotificationRecipientResponse>('recipients', data, force);
}
