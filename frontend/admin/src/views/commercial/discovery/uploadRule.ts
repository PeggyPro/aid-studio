import type { UploadLimits } from '@/utils/uploadLimits';
import type { MediaKind } from './mediaMetadata';

export interface ResolvedDiscoveryUploadRule {
  configured: boolean;
  mode: 'unconfigured' | 'typed' | 'global' | 'unavailable';
  name: string;
  maxSizeMb?: number;
  extensions: string[];
}

export type DiscoveryUploadValidationError = 'mime' | 'extension' | 'size' | 'unavailable';

export function isDiscoveryUploadTypeDisabled(rule: ResolvedDiscoveryUploadRule) {
  return rule.configured && rule.extensions.length === 0;
}

interface UploadCandidate {
  name: string;
  size: number;
  type?: string;
}

const TYPE_SIGNATURES: Record<MediaKind, { name: string; aliases: string[] }> = {
  image: {
    name: '图片',
    aliases: ['图片', 'image']
  },
  video: {
    name: '视频',
    aliases: ['视频', 'video']
  }
};

function normalizeExtensions(extensions: string[]) {
  return Array.from(
    new Set(extensions.map((extension) => extension.trim().toLowerCase().replace(/^\./, '')).filter(Boolean))
  );
}

/**
 * 只有成功取得后端配置时才生成前端严格校验规则。
 * null 表示配置未读取，前端不猜测扩展名或大小，交由上传接口校验。
 */
export function resolveDiscoveryUploadRule(limits: UploadLimits | null, kind: MediaKind): ResolvedDiscoveryUploadRule {
  const signature = TYPE_SIGNATURES[kind];
  if (!limits) {
    return { configured: false, mode: 'unconfigured', name: signature.name, extensions: [] };
  }

  const typeLimits = limits.typeLimits || [];
  if (typeLimits.length > 0) {
    const matched = typeLimits.find((item) => {
      const name = item.name.trim().toLowerCase();
      return signature.aliases.some((alias) => name.includes(alias));
    });
    if (!matched) {
      return { configured: true, mode: 'unavailable', name: signature.name, extensions: [] };
    }
    return {
      configured: true,
      mode: 'typed',
      name: matched.name,
      maxSizeMb: matched.maxSizeMb > 0 ? matched.maxSizeMb : undefined,
      extensions: normalizeExtensions(matched.extensions || [])
    };
  }

  const globalExtensions = normalizeExtensions((limits.globalAllowedExtensions || '').split(','));
  return {
    configured: true,
    mode: 'global',
    name: signature.name,
    maxSizeMb: limits.globalMaxSizeMb > 0 ? limits.globalMaxSizeMb : undefined,
    extensions: globalExtensions
  };
}

function fileExtension(fileName: string) {
  const index = fileName.lastIndexOf('.');
  return index >= 0 ? fileName.slice(index + 1).toLowerCase() : '';
}

/** MIME 存在时校验媒体大类；配置成功后始终校验后端扩展名和大小。 */
export function validateDiscoveryUploadCandidate(
  rule: ResolvedDiscoveryUploadRule,
  kind: MediaKind,
  file: UploadCandidate
): DiscoveryUploadValidationError | null {
  if (file.type && !file.type.toLowerCase().startsWith(`${kind}/`)) return 'mime';
  if (!rule.configured) return null;
  if (rule.mode === 'unavailable') return 'unavailable';
  const extension = fileExtension(file.name);
  if (!extension || !rule.extensions.includes(extension)) return 'extension';
  if (rule.maxSizeMb && file.size / 1024 / 1024 > rule.maxSizeMb) return 'size';
  return null;
}
