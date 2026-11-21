import { request } from '@/utils/request';
import { createSingleFlight } from '@/utils/singleFlight';

const runDiscoveryListRequest = createSingleFlight<string>();

export {
  DISCOVERY_MEDIA_TYPES,
  DISCOVERY_NAME_MAX_LENGTH,
  DISCOVERY_STATUS_VALUES,
  canRestoreDiscoveryContent,
  isDiscoveryNameLengthValid
} from './discoveryContract';
import type { DiscoveryMediaType, DiscoveryStatus } from './discoveryContract';
export type { DiscoveryMediaType, DiscoveryStatus } from './discoveryContract';
export type DiscoverySourceType = 'USER' | 'ADMIN';
export type DiscoveryReviewStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'REVOKED';
export type DiscoveryReviewAction = 'APPROVE' | 'REJECT' | 'REVOKE';

export interface DiscoveryListQuery {
  pageNum: number;
  pageSize: number;
  name?: string;
  mediaType?: DiscoveryMediaType;
  sourceType?: DiscoverySourceType;
  reviewStatus?: DiscoveryReviewStatus;
}

export interface DiscoveryContent {
  id: number;
  name: string;
  description: string;
  mediaType: DiscoveryMediaType;
  coverUrl: string;
  mediaUrl: string | null;
  previewVideoUrl?: string | null;
  mediaWidth: number;
  mediaHeight: number;
  aspectRatio?: string | null;
  ratioSource: 'declared' | null;
  sortOrder?: number | null;
  status?: DiscoveryStatus | null;
  sourceType: DiscoverySourceType;
  reviewStatus: DiscoveryReviewStatus;
  reviewReason?: string | null;
  reviewTime?: string | null;
  reviewBy?: string | null;
  publishTime?: string | null;
  userId?: number | string | null;
  submitterName?: string | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface DiscoveryReviewLog {
  action: string;
  beforeStatus: DiscoveryReviewStatus | null;
  afterStatus: DiscoveryReviewStatus | null;
  reason: string | null;
  operatorType: string | null;
  operatorName: string | null;
  createTime: string | null;
}

/** 仅管理端详情接口返回审核流水，列表与公开接口均不携带。 */
export interface DiscoveryDetail extends DiscoveryContent {
  promptText?: string | null;
  reviewLogs: DiscoveryReviewLog[] | null;
}

export interface DiscoverySavePayload {
  name: string;
  description: string;
  mediaType: DiscoveryMediaType;
  coverUrl: string;
  mediaUrl: string | null;
  previewVideoUrl?: string | null;
  mediaWidth: number;
  mediaHeight: number;
  promptText?: string | null;
  sortOrder?: number | null;
  status?: DiscoveryStatus | null;
}

export interface DiscoveryEditPayload extends DiscoverySavePayload {
  id: number;
}

export interface DiscoveryReviewPayload {
  id: number;
  action: DiscoveryReviewAction;
  reason?: string | null;
}

export function listDiscovery(data: DiscoveryListQuery) {
  const requestKey = JSON.stringify([
    data.pageNum,
    data.pageSize,
    data.name || '',
    data.mediaType || '',
    data.sourceType || '',
    data.reviewStatus || ''
  ]);
  return runDiscoveryListRequest(requestKey, () =>
    request<DiscoveryContent[]>({ url: '/aid/discovery/list', method: 'post', data })
  );
}

export function getDiscoveryDetail(id: number) {
  return request<DiscoveryDetail>({ url: '/aid/discovery/detail', method: 'post', data: { id } });
}

export function addDiscovery(data: DiscoverySavePayload) {
  return request({ url: '/aid/discovery/add', method: 'post', data });
}

export function editDiscovery(data: DiscoveryEditPayload) {
  return request({ url: '/aid/discovery/edit', method: 'post', data });
}

export function reviewDiscovery(data: DiscoveryReviewPayload) {
  return request({ url: '/aid/discovery/review', method: 'post', data });
}

export function removeDiscovery(id: number) {
  return request({ url: '/aid/discovery/remove', method: 'post', data: { id } });
}
