import { request } from '@/utils/request';
import { getToken } from '@/utils/auth';

export type StorageState = 'UNCONFIGURED' | 'UNSYNCED' | 'READY';
export const STORAGE_STATUS_CHANGED = 'aid:storage-status-changed';
let cache: { token: string; state: StorageState; time: number } | undefined;
const pending = new Map<string, Promise<StorageState>>();

/** force 仅跳过短时结果缓存，始终合并相同登录身份的进行中请求。 */
export function getStorageStatus(force = false): Promise<StorageState> {
  const token = getToken() || '';
  const running = pending.get(token);
  if (running) return running;
  if (!force && cache?.token === token && Date.now() - cache.time < 15000) {
    return Promise.resolve(cache.state);
  }
  const task = request({ url: '/oss/config/status', method: 'get' }).then((res: any) => {
    const state = res.data?.state;
    if (!['UNCONFIGURED', 'UNSYNCED', 'READY'].includes(state)) {
      throw new Error('文件存储配置状态暂未确认');
    }
    cache = { token, state, time: Date.now() };
    return state as StorageState;
  }).finally(() => pending.delete(token));
  pending.set(token, task);
  return task;
}

export function notifyStorageStatusChanged() {
  cache = undefined;
  const emit = () => {
    cache = undefined;
    window.dispatchEvent(new Event(STORAGE_STATUS_CHANGED));
  };
  // 保存期间已有的读取可能返回旧快照；先合并其请求，完成后再统一读取权威状态。
  const running = pending.get(getToken() || '');
  if (running) void running.finally(emit).catch(() => {});
  else emit();
}
