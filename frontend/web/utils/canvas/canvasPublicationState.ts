import type { CanvasPublicationState } from '@/types/canvas-api'
export function canvasPublicationStateLabel(
  state?: CanvasPublicationState,
): string {
  if (!state) return '未发布'
  if (state.visibility === 'ADMIN_OFFLINE') return '已下架'
  if (state.pendingId)
    return state.isPublic ? '旧版本公开 · 新版本审核中' : '待审核'
  if (state.reviewStatus === 'REJECTED')
    return state.isPublic ? '旧版本公开 · 新版本未通过' : '审核未通过'
  if (state.isPublic)
    return state.hasUnpublishedChanges ? '已公开 · 有未发布修改' : '已公开'
  return state.hasApprovedVersion ? '已撤回' : '未发布'
}
