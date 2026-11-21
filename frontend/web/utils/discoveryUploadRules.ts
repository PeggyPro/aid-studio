import type { AuthUploadPublicConfig } from '~/types/business-api'

export type DiscoveryUploadKind = 'image' | 'video'

export interface VisibleUploadRule {
  accept: string
  text: string
  extensions: string[]
  /** 当前媒体类型是否开放上传。 */
  available: boolean
  /** 是否命中了后台对应的 typeLimit。 */
  configured: boolean
}

const TYPE_NAME_PATTERNS: Record<DiscoveryUploadKind, RegExp> = {
  image: /图片|图像|image|picture/i,
  video: /视频|video/i
}

function normalizeExtensions(extensions: unknown): string[] {
  if (!Array.isArray(extensions)) return []
  return [
    ...new Set(
      extensions
        .map((extension) => String(extension || '').trim().replace(/^\./, '').toLowerCase())
        .filter(Boolean)
    )
  ]
}

function normalizeGlobalExtensions(raw: unknown): string[] {
  return [
    ...new Set(
      String(raw || '')
        .split(/[,，\s]+/)
        .map((extension) => extension.trim().replace(/^\./, '').toLowerCase())
        .filter(Boolean)
    )
  ]
}

/**
 * 优先以后台 typeLimit.name 的业务语义匹配媒体类型；命中后完整采用该项配置。
 * 配置整体缺失时交由服务器校验；global-only 配置则完整采用全局扩展与大小。
 */
export function resolveVisibleUploadRule(
  config: AuthUploadPublicConfig | null | undefined,
  kind: DiscoveryUploadKind
): VisibleUploadRule {
  const typeLimits = Array.isArray(config?.typeLimits) ? config.typeLimits : []
  const typeLimit = typeLimits.find((item) =>
    TYPE_NAME_PATTERNS[kind].test(String(item.name || '').trim())
  )
  if (!typeLimit) {
    if (typeLimits.length > 0) {
      return {
        accept: `${kind}/*`,
        extensions: [],
        available: false,
        configured: true,
        text: kind === 'image' ? '系统当前未开放图片上传' : '系统当前未开放视频上传'
      }
    }
    if (config) {
      const extensions = normalizeGlobalExtensions(config.globalAllowedExtensions)
      const globalMax = Number(config.globalMaxSizeMb)
      const maxSize = Number.isFinite(globalMax) && globalMax > 0 ? globalMax : null
      const accept = extensions.length
        ? extensions.map((extension) => `.${extension}`).join(',')
        : `${kind}/*`
      const typeText = extensions.length
        ? extensions.map((extension) => extension.toUpperCase()).join('、')
        : kind === 'image'
          ? '图片格式'
          : '视频格式'
      return {
        accept,
        extensions,
        available: true,
        configured: true,
        text: `支持 ${typeText}${maxSize ? `，最大 ${maxSize}MB` : '，大小以服务器校验为准'}`
      }
    }
    return {
      accept: `${kind}/*`,
      extensions: [],
      available: true,
      configured: false,
      text: kind === 'image'
        ? '未读取到图片上传限制，格式与大小将由服务器校验'
        : '未读取到视频上传限制，格式与大小将由服务器校验'
    }
  }

  const extensions = normalizeExtensions(typeLimit.extensions)
  const configuredMax = Number(typeLimit?.maxSizeMb)
  const maxSize = Number.isFinite(configuredMax) && configuredMax > 0
    ? configuredMax
    : null
  if (!extensions.length) {
    return {
      accept: `${kind}/*`,
      extensions: [],
      available: false,
      configured: true,
      text: kind === 'image' ? '图片上传未配置可用格式' : '视频上传未配置可用格式'
    }
  }
  const accept = extensions.length
    ? extensions.map((extension) => `.${extension}`).join(',')
    : `${kind}/*`
  const typeText = extensions.length
    ? extensions.map((extension) => extension.toUpperCase()).join('、')
    : kind === 'image'
      ? '后台配置的图片格式'
      : '后台配置的视频格式'

  return {
    accept,
    extensions,
    available: true,
    configured: true,
    text: `支持 ${typeText}${maxSize ? `，最大 ${maxSize}MB` : '，大小以服务器校验为准'}`
  }
}

/** MIME 缺失或仅为通用二进制时，严格使用当前动态规则的 extensions 判断。 */
export function fileMatchesUploadKind(
  file: Pick<File, 'name' | 'type'>,
  kind: DiscoveryUploadKind,
  rule: VisibleUploadRule
): boolean {
  if (!rule.available) return false
  const mime = String(file.type || '').trim().toLowerCase()
  const hasSpecificMime = Boolean(mime && mime !== 'application/octet-stream')
  const extension = String(file.name || '').split('.').pop()?.toLowerCase() || ''
  if (!rule.configured) {
    return hasSpecificMime ? mime.startsWith(`${kind}/`) : true
  }
  if (!rule.extensions.length) {
    return hasSpecificMime ? mime.startsWith(`${kind}/`) : true
  }
  const extensionAllowed = rule.extensions.includes(extension)
  if (!extensionAllowed) return false
  return hasSpecificMime ? mime.startsWith(`${kind}/`) : true
}
