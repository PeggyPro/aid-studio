import type { DiscoveryReviewStatus } from '~/types/business-api'

export const DISCOVERY_REVIEW_STATUS_META: Record<
  DiscoveryReviewStatus,
  { text: string; color: string }
> = {
  PENDING: { text: '审核中', color: 'processing' },
  APPROVED: { text: '已通过', color: 'success' },
  REJECTED: { text: '已拒绝', color: 'error' },
  REVOKED: { text: '已撤销', color: 'default' }
}

/** 审核中 / 已拒绝 / 已撤销可编辑；审核通过不可再编辑。 */
export function canResubmitDiscovery(status: DiscoveryReviewStatus): boolean {
  return status === 'PENDING' || status === 'REJECTED' || status === 'REVOKED'
}
