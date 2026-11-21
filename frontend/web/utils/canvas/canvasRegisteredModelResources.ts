import type { CanvasModelUploadResult } from '@/types/canvas-api'
import { unwrap } from '@/utils/business/shared'
import { canvasApiReadPost } from './canvasApiClient'

export type CanvasRegisteredModelResource = Pick<CanvasModelUploadResult, 'resourceId' | 'workspaceId' | 'bundle'>

/** Read existing roots, including unsaved references and disabled library versions. Never registers a resource. */
export async function canvasRegisteredModelResourcesRead(workspaceId: string, resourceIds: string[]): Promise<CanvasRegisteredModelResource[]> {
  const ids = [...new Set(resourceIds)].sort()
  if (!ids.length) return []
  if (ids.length > 100 || ![workspaceId, ...ids].every(id => /^[1-9]\d{0,18}$/.test(id)))
    throw new Error('模型资源读取参数无效')
  const rows = await canvasApiReadPost<CanvasRegisteredModelResource[]>('/api/user/canvas/model/resources/read', { workspaceId, resourceIds: ids }).then(unwrap)
  if (!Array.isArray(rows) || rows.length !== ids.length || rows.some(row => !row || typeof row !== 'object')
    || new Set(rows.map(row => String(row.resourceId))).size !== ids.length
    || rows.some(row => String(row.workspaceId) !== workspaceId || !ids.includes(String(row.resourceId))))
    throw new Error('模型资源读取回执不完整，请重试')
  return rows
}
