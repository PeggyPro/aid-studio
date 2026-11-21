/**
 * 合并同时进行的同键请求；请求结束后再次调用仍会正常发起新请求。
 * 适用于动态路由重新挂载等场景，避免同一列表查询并发发送两次。
 */
export function createSingleFlight<TKey>() {
  const pending = new Map<TKey, Promise<unknown>>();

  return function run<TResult>(key: TKey, operation: () => Promise<TResult>): Promise<TResult> {
    const existing = pending.get(key);
    if (existing) return existing as Promise<TResult>;

    const promise = operation();
    pending.set(key, promise);

    const clear = () => {
      if (pending.get(key) === promise) pending.delete(key);
    };
    promise.then(clear, clear);

    return promise;
  };
}
