import { request } from './request';
import { getToken } from './auth';

interface PendingRead {
  generation: number;
  promise: Promise<any>;
}

const pending = new Map<string, PendingRead>();
const queuedRefreshes = new Map<string, Promise<any>>();
const recent = new Map<string, { expires: number; value: any }>();
let sessionToken = getToken();
let sessionGeneration = 0;
let invalidationGeneration = 0;
const ordered = (value: any): any => Array.isArray(value) ? value.map(ordered) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, ordered(value[key])])) : value;

/** 同一业务读取共用在途请求；强制刷新仅忽略短时结果缓存。 */
export function sharedReadRequest(url: string, params?: Record<string, unknown>, force = false) {
  const token = getToken();
  if (token !== sessionToken) {
    sessionToken = token; sessionGeneration++; recent.clear();
  }
  const generation = invalidationGeneration;
  const session = sessionGeneration;
  const key = `${url}:${session}:${JSON.stringify(ordered(params || {}))}`;
  const running = pending.get(key);
  if (running) {
    // 同一代次始终共用当前请求，force 也不能绕过进行中的业务请求。
    if (running.generation === generation) return running.promise;

    // 写操作已使旧读取失效：先等待旧读取结束，再合并为唯一一次权威刷新。
    const refreshKey = `${key}:${generation}`;
    const queued = queuedRefreshes.get(refreshKey);
    if (queued) return queued;
    const refresh = running.promise
      .catch(() => undefined)
      .then(() => {
        if (token !== getToken()) throw new Error('登录状态已变更，请重新加载');
        return sharedReadRequest(url, params, true);
      })
      .finally(() => {
        if (queuedRefreshes.get(refreshKey) === refresh) queuedRefreshes.delete(refreshKey);
      });
    queuedRefreshes.set(refreshKey, refresh);
    return refresh;
  }
  const cached = recent.get(key);
  if (!force && cached && cached.expires > Date.now()) return Promise.resolve(cached.value);
  for (const [oldKey, old] of recent) if (old.expires <= Date.now()) recent.delete(oldKey);
  const entry: PendingRead = { generation, promise: Promise.resolve(undefined) };
  const promise = request({ url, method: 'get', params }).then((value) => {
    if (generation === invalidationGeneration && session === sessionGeneration && token === getToken())
      recent.set(key, { expires: Date.now() + 1000, value });
    return value;
  }).finally(() => {
    if (pending.get(key) === entry) pending.delete(key);
  });
  entry.promise = promise;
  pending.set(key, entry);
  return promise;
}

export function invalidateSharedReads(prefix: string) {
  invalidationGeneration++;
  for (const key of recent.keys()) if (key.startsWith(prefix)) recent.delete(key);
}
