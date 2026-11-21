import type {
  PublicProjectAccessReasonCode,
  PublicProjectDetailRow
} from '~/types/business-api'

const VIEW_REASON_TEXT: Record<string, string> = {
  PROJECT_UNAVAILABLE: '项目已下架',
  NO_APPROVED_SNAPSHOT: '暂无发布内容',
  PROJECT_CONTENT_SHARING_DISABLED: '未开放工程',
  RIGHTS_RESTRICTED: '内容暂不可用',
  PUBLISHED_SNAPSHOT_UNAVAILABLE: '发布内容不可用'
}

const COPY_REASON_TEXT: Record<string, string> = {
  PROJECT_UNAVAILABLE: '项目已下架',
  NO_APPROVED_SNAPSHOT: '暂无发布内容',
  PROJECT_CONTENT_NOT_VIEWABLE: '工程不可查看',
  PROJECT_COPY_DISABLED: '未开放复制',
  PROJECT_TYPE_UNSUPPORTED: '暂不支持复制',
  RIGHTS_RESTRICTED: '内容暂不可复制',
  PUBLISHED_SNAPSHOT_UNAVAILABLE: '发布内容不可用'
}

export function publicProjectAccessReasonText(
  reasonCode: PublicProjectAccessReasonCode | null | undefined,
  operation: 'view' | 'copy'
): string {
  const code = String(reasonCode || '').trim()
  if (!code) return operation === 'view' ? '暂不可查看' : '暂不可复制'
  return (operation === 'view' ? VIEW_REASON_TEXT : COPY_REASON_TEXT)[code]
    || (operation === 'view' ? '暂不可查看' : '暂不可复制')
}

export function resolvePublicProjectAccess(detail: PublicProjectDetailRow | null) {
  const canView = typeof detail?.canViewProjectContent === 'boolean'
    ? detail.canViewProjectContent
    : detail?.allowPreview === true
  const canCopy = typeof detail?.canCopyProject === 'boolean'
    ? detail.canCopyProject
    : canView && detail?.allowCopy === true && detail?.projectType === 'movie'
  return {
    canView,
    canCopy,
    viewReasonText: canView
      ? ''
      : publicProjectAccessReasonText(detail?.viewProjectContentReasonCode, 'view'),
    copyReasonText: canCopy
      ? ''
      : publicProjectAccessReasonText(detail?.copyProjectReasonCode, 'copy'),
    copyRequiresLogin: detail?.copyRequiresLogin !== false
  }
}
