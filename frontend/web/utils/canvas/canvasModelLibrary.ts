import { canvasApiBaseUrl, canvasApiPost, canvasApiReadPost } from './canvasApiClient'
import { unwrap } from '@/utils/business/shared'
import type { CanvasModelUploadResult, CanvasResource } from '@/types/canvas-api'

export interface CanvasLibraryModel {
  id: string; modelId: string; versionNo: number; name: string; categoryId: string; categoryName: string
  tags: string; thumbnailPath: string; thumbnailUrl?: string; fileSize: number; metadataJson: string
}
export const canvasModelCategories = () => canvasApiReadPost<Array<{ id: string; name: string }>>('/api/user/canvas/model/categories').then(unwrap)
export const canvasModelList = (query: { pageNum: number; pageSize: number; categoryId?: string; keyword?: string }) =>
  canvasApiReadPost<CanvasLibraryModel[]>('/api/user/canvas/model/list', query).then(unwrap)
const registrations = new Map<string, Promise<CanvasModelUploadResult>>()
const identity = () => JSON.stringify([canvasApiBaseUrl(), typeof window === 'undefined' ? '' : localStorage.getItem('token') || ''])
/** Shared by both library entry points and legacy scene registration; successful signed URLs are never cached. */
function register(request: { workspaceId: string; operationId: string; id?: string; contentHash?: string }) {
  const scope = identity(), key = JSON.stringify([scope, request])
  const running = registrations.get(key)
  if (running) return running
  const promise = canvasApiPost<CanvasModelUploadResult>('/api/user/canvas/model/use', request).then(unwrap)
    .then(result => {
      if (identity() !== scope) throw new Error('登录身份或模型服务已变化，请重试原请求')
      return result
    }).finally(() => { if (registrations.get(key) === promise) registrations.delete(key) })
  registrations.set(key, promise)
  return promise
}
export const canvasModelUse = (workspaceId: string, id: string, operationId: string) => register({ workspaceId, id, operationId })
export async function canvasModelUseByHash(workspaceId: string, contentHash: string, operationId: string): Promise<CanvasResource & CanvasModelUploadResult> {
  const result = await register({ workspaceId, contentHash, operationId })
  const root = result.bundle.files.find(file => file.path === result.bundle.rootPath)
  if (String(result.workspaceId) !== workspaceId || !root?.resourceUrl || String(root.resourceId) !== String(result.resourceId)) throw new Error('模型登记回执不完整，请重试原请求')
  return { ...result, mediaType: 'MODEL', resourceUrl: root.resourceUrl }
}
