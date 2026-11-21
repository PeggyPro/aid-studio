export const MAX_RESOURCE_BYTES = 100 * 1024;
export const MAX_RESOURCE_TOTAL_BYTES = 512 * 1024;
export const MAX_REFERENCE_PATH_LENGTH = 192;

const REFERENCE_PATH_PATTERN = /^references\/[a-z0-9][a-z0-9._/-]{0,180}$/;

/** Mirrors the Skill package DTO and storage path checks. File extensions are controlled by MIME type. */
export function isSafeReferencePath(value: string | undefined) {
  if (!value || value.length > MAX_REFERENCE_PATH_LENGTH || !REFERENCE_PATH_PATTERN.test(value)) return false;
  return value.split('/').every((segment) => segment !== '' && segment !== '.' && segment !== '..');
}

export function utf8ByteLength(value: string) {
  return new TextEncoder().encode(value).length;
}

export function validateResourceContentBytes(resources: Array<{ content?: string }>) {
  let totalBytes = 0;
  for (const [index, resource] of resources.entries()) {
    const size = utf8ByteLength(resource.content || '');
    if (size > MAX_RESOURCE_BYTES) return `资源 ${index + 1} 内容不能超过 100 KiB（UTF-8）`;
    totalBytes += size;
  }
  if (totalBytes > MAX_RESOURCE_TOTAL_BYTES) return '资源内容合计不能超过 512 KiB（UTF-8）';
  return undefined;
}
