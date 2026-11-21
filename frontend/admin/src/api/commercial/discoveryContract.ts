export const DISCOVERY_MEDIA_TYPES = ['video', 'image'] as const;
export const DISCOVERY_STATUS_VALUES = ['0', '1'] as const;
export const DISCOVERY_NAME_MAX_LENGTH = 100;

export type DiscoveryMediaType = (typeof DISCOVERY_MEDIA_TYPES)[number];
export type DiscoveryStatus = (typeof DISCOVERY_STATUS_VALUES)[number];

export function isDiscoveryNameLengthValid(name: string) {
  return name.trim().length > 0 && name.length <= DISCOVERY_NAME_MAX_LENGTH;
}

/** 仅管理员创建且已撤销的内容允许后台用 APPROVE 恢复发布。 */
export function canRestoreDiscoveryContent(sourceType: string, reviewStatus: string) {
  return sourceType === 'ADMIN' && reviewStatus === 'REVOKED';
}
