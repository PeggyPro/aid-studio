import type { DirectorSceneData } from '@/types/studio'
import { packThreePrevizAssets, unpackThreePrevizAssets } from './threePrevizDocument'

export interface ThreePrevizLocalRecord {
  key: string
  revision: number
  scene: DirectorSceneData
  savedAt: string
  assetPool?: string[]
  preparedScene?: Record<string, unknown>
  canvasBase?: string
}

export class ThreePrevizLocalConflict extends Error {
  constructor() { super('另一窗口已保存此3D预演。当前编辑仍保留，请导出工程后重新打开核对。') }
}

export function threePrevizStorageKey(owner: string, scope: string, sceneId: string) {
  if (!owner || !scope || !sceneId) throw new Error('3D预演账号或画布身份尚未就绪')
  return JSON.stringify([owner, scope, sceneId])
}

function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open('aid-three-previz-v1', 1)
    request.onupgradeneeded = () => request.result.createObjectStore('scenes', { keyPath: 'key' })
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(new Error('无法打开3D预演本机工程库'))
    request.onblocked = () => reject(new Error('3D预演工程库被旧窗口占用，请关闭后重试'))
  })
}

export async function readThreePrevizLocal(key: string): Promise<ThreePrevizLocalRecord | undefined> {
  const db = await open()
  return new Promise((resolve, reject) => {
    const transaction = db.transaction('scenes', 'readonly')
    const request = transaction.objectStore('scenes').get(key)
    transaction.oncomplete = () => {
      db.close()
      try {
        const record = request.result as ThreePrevizLocalRecord | undefined
        resolve(record ? { ...record, scene: unpackThreePrevizAssets(record.scene, record.assetPool), assetPool: undefined } : undefined)
      } catch (error) { reject(error) }
    }
    transaction.onerror = () => { db.close(); reject(new Error('3D预演本机工程读取失败')) }
  })
}

/** Native IndexedDB CAS; two windows cannot silently overwrite one another. */
export async function saveThreePrevizLocal(key: string, scene: DirectorSceneData, expectedRevision: number, preparedScene?: Record<string, unknown>, canvasBase?: string): Promise<ThreePrevizLocalRecord> {
  const db = await open()
  return new Promise((resolve, reject) => {
    const transaction = db.transaction('scenes', 'readwrite')
    const store = transaction.objectStore('scenes')
    const request = store.get(key)
    let result: ThreePrevizLocalRecord
    let error: Error | undefined
    request.onsuccess = () => {
      const previous = request.result as ThreePrevizLocalRecord | undefined
      if ((previous?.revision ?? 0) !== expectedRevision) { error = new ThreePrevizLocalConflict(); transaction.abort(); return }
      result = { key, revision: expectedRevision + 1, scene: structuredClone(scene), savedAt: new Date().toISOString(), ...(preparedScene ? { preparedScene } : {}), ...(canvasBase ? { canvasBase } : {}) }
      store.put({ ...result, ...packThreePrevizAssets(scene) })
    }
    transaction.oncomplete = () => { db.close(); resolve(result) }
    transaction.onerror = transaction.onabort = () => { db.close(); reject(error ?? new Error('3D预演本机保存失败，请导出工程保留编辑')) }
  })
}


/** Remove only the recovery copy that this save confirmed, never a newer copy. */
export async function deleteThreePrevizLocal(key: string, revision: number, savedAt: string): Promise<void> {
  const db = await open()
  return new Promise((resolve, reject) => {
    const transaction = db.transaction('scenes', 'readwrite')
    const store = transaction.objectStore('scenes')
    const request = store.get(key)
    request.onsuccess = () => {
      const current = request.result as ThreePrevizLocalRecord | undefined
      if (current?.revision === revision && current.savedAt === savedAt) store.delete(key)
    }
    transaction.oncomplete = () => { db.close(); resolve() }
    transaction.onerror = transaction.onabort = () => { db.close(); reject(new Error('3D预演恢复副本清理失败')) }
  })
}
