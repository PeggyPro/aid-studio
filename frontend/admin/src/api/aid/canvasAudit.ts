import { request } from '@/utils/request';
import { getToken } from '@/utils/auth';

export type AuditId = string | number;
export type ReviewStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';
export type ReviewAction = 'APPROVE' | 'REJECT' | 'OFFLINE';
export interface CanvasAuditRow {
  publicationId: AuditId;
  projectId: AuditId;
  projectName?: string;
  projectDesc?: string;
  reviewStatus: ReviewStatus;
  reviewReason?: string;
  stateVersion: AuditId;
  videoUrl?: string;
  coverUrl?: string;
  allowCopy?: boolean;
  isPublic?: boolean;
  visibility?: 'PUBLIC' | 'PRIVATE' | 'ADMIN_OFFLINE';
  requestedAllowCopy?: boolean;
  isDerivative?: boolean;
  sourcePublicationId?: AuditId;
  rootPublicationId?: AuditId;
  [key: string]: any;
}
export interface CanvasAuditQuery {
  pageNum: number;
  pageSize: number;
  keyword?: string;
  reviewStatus?: ReviewStatus;
}
export interface CanvasResource {
  resourceId: AuditId;
  resourceUrl?: string;
  resourceType?: string;
  mediaType?: string;
  name?: string;
  bundle?: {
    format: string;
    rootPath: string;
    files: { path: string; resourceId: AuditId; resourceUrl?: string }[];
    references?: { ownerPath?: string; uri: string; path: string }[];
  };
  [key: string]: any;
}
export interface CanvasAuditDetail extends CanvasAuditRow {
  canvas?: Record<string, any>;
  auditRecords?: Record<string, any>[];
  source?: Record<string, any>;
  publishedVersion?: Record<string, any>;
  sourceComparison?: Record<string, any>;
  publishedComparison?: Record<string, any>;
}
const pending = new Map<string, Promise<any>>();
const recent = new Map<string, { value: any; until: number }>();
let session = getToken();
let generation = 0;
const canonical = (value: any): any =>
  Array.isArray(value)
    ? value.map(canonical)
    : value && typeof value === 'object'
      ? Object.fromEntries(
          Object.keys(value)
            .sort()
            .map((key) => [key, canonical(value[key])])
        )
      : value;
function identity() {
  const token = getToken();
  if (session !== token) {
    session = token;
    recent.clear();
    generation++;
  }
  return token;
}
function read<T>(operation: string, data: object, force = false): Promise<T> {
  const token = identity();
  const key = `${generation}:${operation}:${JSON.stringify(canonical(data))}`;
  if (pending.has(key)) return pending.get(key)!;
  const cached = recent.get(key);
  if (!force && cached && cached.until > Date.now()) return Promise.resolve(cached.value);
  const current = generation;
  for (const [expiredKey, value] of recent) if (value.until <= Date.now()) recent.delete(expiredKey);
  const promise = request<T>({ url: `/aid/audit/canvas/${operation}`, method: 'post', data, errorFeedback: 'caller' })
    .then((res) => {
      if (token !== getToken()) throw new Error('登录身份已变化，请重新查询');
      if (current === generation) recent.set(key, { value: res.data, until: Date.now() + 800 });
      return res.data as T;
    })
    .finally(() => pending.delete(key));
  pending.set(key, promise);
  return promise;
}
export const listCanvasAudit = (query: CanvasAuditQuery, force = false) =>
  read<{ items: CanvasAuditRow[]; total: number; cleanupPending?: number }>('list', query, force);
export const getCanvasAudit = (publicationId: AuditId, force = false) =>
  read<CanvasAuditDetail>('detail', { publicationId }, force);
export function actCanvasAudit(data: {
  publicationId: AuditId;
  expectedVersion: AuditId;
  action: ReviewAction;
  reason?: string;
}) {
  identity();
  const key = `${generation}:write:${JSON.stringify(canonical(data))}`;
  if (pending.has(key)) return pending.get(key)!;
  const token = getToken();
  const promise = request({
    url: `/aid/audit/canvas/${data.action === 'OFFLINE' ? 'offline' : 'review'}`,
    method: 'post',
    data,
    errorFeedback: 'caller'
  })
    .then((res) => {
      if (token !== getToken()) throw new Error('登录身份已变化，请重新查询');
      return res.data;
    })
    .finally(() => {
      recent.clear();
      generation++;
      pending.delete(key);
    });
  pending.set(key, promise);
  return promise;
}

/** Only the authenticated audit gateway can receive the login token. */
export function auditMediaPath(publicationId: AuditId, resourceId: AuditId) {
  return `/aid/audit/canvas/media?${new URLSearchParams({ publicationId: String(publicationId), resourceId: String(resourceId) })}`;
}
const mediaPending = new Map<string, Promise<Blob>>();
export function fetchAuditMedia(path: string): Promise<Blob> {
  const base = new URL(import.meta.env.VITE_APP_BASE_API || '/', window.location.origin);
  const raw = new URL(path, window.location.origin);
  if (raw.origin !== window.location.origin || !/^\/(?:[^?#]*\/)?aid\/audit\/canvas\/media$/.test(raw.pathname))
    return Promise.reject(new Error('资源地址不属于审核网关'));
  const gateway = raw.pathname.slice(raw.pathname.indexOf('/aid/audit/canvas/media'));
  const url = new URL(`${base.pathname.replace(/\/$/, '')}${gateway}${raw.search}`, base.origin);
  if (url.origin !== window.location.origin) return Promise.reject(new Error('审核网关必须与后台同源'));
  const token = getToken();
  if (!token) return Promise.reject(new Error('登录已失效'));
  const key = `${token}:${url.href}`;
  if (mediaPending.has(key)) return mediaPending.get(key)!;
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), 60000);
  const promise = fetch(url, {
    headers: { Authorization: `Bearer ${token}` },
    redirect: 'error',
    credentials: 'same-origin',
    signal: controller.signal
  })
    .then(async (res) => {
      if (!res.ok || res.headers.get('content-type')?.includes('application/json'))
        throw new Error(`审核资源加载失败（${res.status}）`);
      const blob = await res.blob();
      if (!blob.size) throw new Error('审核资源为空');
      if (token !== getToken()) throw new Error('登录身份已变化');
      return blob;
    })
    .catch((error) => {
      if (controller.signal.aborted) throw new Error('审核资源读取超时，请重新查询');
      throw error;
    })
    .finally(() => {
      clearTimeout(timer);
      mediaPending.delete(key);
    });
  mediaPending.set(key, promise);
  return promise;
}
