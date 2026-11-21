import {
  canvasApiAuthHeaders,
  canvasApiBaseUrl,
  resolveCanvasApiUrl,
} from './canvasApiClient'
import type { CanvasResource, CanvasSceneDocument } from '@/types/canvas-api'
import { canvasModelingResources } from './canvasSceneModeling'

export const canvasResourceDisplayUrl = (resource: CanvasResource) =>
  resource.displayUrl || canvasPublicationFileUrl(resource.resourceUrl)
export function isProtectedCanvasPublicationFile(url: string): boolean {
  const target = new URL(canvasPublicationFileUrl(url), window.location.href),
    base = new URL(canvasApiBaseUrl() || '/', window.location.href)
  return (
    target.origin === base.origin &&
    target.pathname.startsWith(
      base.pathname.replace(/\/$/, '') + '/api/user/canvas/publication/',
    )
  )
}

export function canvasPublicationFileUrl(url: string): string {
  return /^(?:\/api\/(?:public|user)\/canvas\/|\/profile\/)/.test(url)
    ? resolveCanvasApiUrl(url)
    : url
}

/** Credentials go only to protected publication APIs on the configured gateway, never to object storage. */
export async function fetchCanvasPublicationFile(
  url: string,
  signal?: AbortSignal,
): Promise<Blob> {
  const resolved = canvasPublicationFileUrl(url)
  const base = new URL(canvasApiBaseUrl() || '/', window.location.href)
  const target = new URL(resolved, window.location.href)
  const prefix =
    base.pathname.replace(/\/$/, '') + '/api/user/canvas/publication/'
  const protectedApi =
    target.origin === base.origin && target.pathname.startsWith(prefix)
  const response = await fetch(resolved, {
    signal,
    credentials: 'omit',
    headers: protectedApi ? canvasApiAuthHeaders() : undefined,
  })
  if (!response.ok) throw new Error(`素材读取失败（${response.status}）`)
  return response.blob()
}

/** Keep authenticated display URLs local to one workspace and revoke them when its lifetime ends. */
export function createCanvasResourceAccess() {
  const urls = new Map<string, Promise<string>>(),
    sceneBytes = new Map<string, Promise<string>>(),
    controller = new AbortController()
  return {
    async prepare(resources: CanvasResource[], document?: CanvasSceneDocument): Promise<CanvasResource[]> {
      const modeling = canvasModelingResources(document)
      for (const id of sceneBytes.keys()) if (!modeling.has(id)) sceneBytes.delete(id)
      const result: CanvasResource[] = []
      for (let resource of resources) {
        const id = String(resource.resourceId)
        if (modeling.has(id)) {
          let bytes = sceneBytes.get(id)
          if (!bytes) {
            bytes = fetchCanvasPublicationFile(resource.resourceUrl, controller.signal).then(blob => {
              controller.signal.throwIfAborted()
              const mime = modeling.get(id) || resource.mimeType || blob.type
              return new Promise<string>((resolve, reject) => {
                const reader = new FileReader()
                reader.onload = () => resolve(String(reader.result))
                reader.onerror = () => reject(reader.error)
                reader.readAsDataURL(new Blob([blob], { type: mime }))
              })
            })
            sceneBytes.set(id, bytes)
            void bytes.catch(() => { if (sceneBytes.get(id) === bytes) sceneBytes.delete(id) })
          }
          const sceneDataUrl = await bytes
          resource = { ...resource, sceneDataUrl }
          if (resource.mediaType === 'IMAGE') {
            result.push({ ...resource, displayUrl: sceneDataUrl })
            continue
          }
        }
        if (
          !['IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT'].includes(
            resource.mediaType,
          ) ||
          !isProtectedCanvasPublicationFile(resource.resourceUrl)
        ) {
          result.push(resource)
          continue
        }
        const key = resource.resourceId + ':' + resource.resourceUrl
        let request = urls.get(key)
        if (!request) {
          request = fetchCanvasPublicationFile(
            resource.resourceUrl,
            controller.signal,
          ).then((blob) => {
            controller.signal.throwIfAborted()
            return URL.createObjectURL(blob)
          })
          urls.set(key, request)
          void request.catch(() => urls.delete(key))
        }
        result.push({ ...resource, displayUrl: await request })
      }
      return result
    },
    dispose() {
      controller.abort()
      for (const request of urls.values())
        void request.then((url) => URL.revokeObjectURL(url)).catch(() => {})
      urls.clear()
      sceneBytes.clear()
    },
  }
}
