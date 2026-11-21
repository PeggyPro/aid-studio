/** Only the same File object proves the upload intent is identical; metadata cannot prove equal content. */
export function createCanvasUploadRegistry<T>() {
  const pending = new WeakMap<File, Map<string, Promise<T>>>()
  const completed = new WeakMap<File, Map<string, T>>()

  const keyFor = (workspaceId: string, mediaType: string) => JSON.stringify([workspaceId, mediaType])

  return {
    run(workspaceId: string, mediaType: string, file: File, upload: () => Promise<T>): Promise<T> {
      const key = keyFor(workspaceId, mediaType)
      const completedForFile = completed.get(file)
      if (completedForFile?.has(key)) return Promise.resolve(completedForFile.get(key) as T)
      const current = pending.get(file)?.get(key)
      if (current) return current
      const request = Promise.resolve().then(upload).then((resource) => {
        const results = completed.get(file) || new Map<string, T>()
        results.set(key, resource)
        completed.set(file, results)
        return resource
      }).finally(() => {
        pending.get(file)?.delete(key)
      })
      const requests = pending.get(file) || new Map<string, Promise<T>>()
      requests.set(key, request)
      pending.set(file, requests)
      return request
    }
  }
}
