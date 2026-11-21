import type {
  UserDiscoveryResubmitRequest,
  UserDiscoverySubmitRequest
} from '~/types/business-api'

export type DiscoverySubmissionFields = Omit<
  UserDiscoverySubmitRequest,
  'mediaType' | 'previewVideoUrl' | 'aspectRatio'
>

const CANONICAL_ASPECT_RATIOS = [
  ['16:9', 16 / 9],
  ['9:16', 9 / 16],
  ['1:1', 1],
  ['4:3', 4 / 3],
  ['3:4', 3 / 4],
  ['21:9', 21 / 9]
] as const

const ASPECT_RATIO_TOLERANCE = 0.025

export interface DiscoverySubmissionMediaValidation {
  valid: boolean
  coverError: string
  videoError: string
  focus: 'cover' | 'video' | null
}

const MEDIA_REQUIRED_ERROR = '请至少上传封面或视频'

function hasText(value: string | null | undefined): boolean {
  return Boolean(String(value || '').trim())
}

export function validateDiscoverySubmissionMedia(
  fields: Pick<
    DiscoverySubmissionFields,
    'coverUrl' | 'mediaUrl' | 'mediaWidth' | 'mediaHeight'
  >
): DiscoverySubmissionMediaValidation {
  const hasCover = hasText(fields.coverUrl)
  const hasVideo = hasText(fields.mediaUrl)
  if (!hasCover && !hasVideo) {
    return {
      valid: false,
      coverError: MEDIA_REQUIRED_ERROR,
      videoError: MEDIA_REQUIRED_ERROR,
      focus: 'cover'
    }
  }
  if (hasVideo && (fields.mediaWidth <= 0 || fields.mediaHeight <= 0)) {
    return {
      valid: false,
      coverError: '',
      videoError: '请重新上传可读取尺寸的视频',
      focus: 'video'
    }
  }
  if (hasCover && (fields.mediaWidth <= 0 || fields.mediaHeight <= 0)) {
    return {
      valid: false,
      coverError: '请重新上传可读取尺寸的封面',
      videoError: '',
      focus: 'cover'
    }
  }
  return { valid: true, coverError: '', videoError: '', focus: null }
}

export function resolveDiscoverySubmissionMediaType(
  fields: Pick<DiscoverySubmissionFields, 'mediaUrl'>
): UserDiscoverySubmitRequest['mediaType'] {
  return hasText(fields.mediaUrl) ? 'video' : 'image'
}

function greatestCommonDivisor(left: number, right: number): number {
  let a = Math.abs(Math.round(left))
  let b = Math.abs(Math.round(right))
  while (b !== 0) {
    const remainder = a % b
    a = b
    b = remainder
  }
  return Math.max(a, 1)
}

/** 与服务端当前规则一致：常见画幅允许 2.5% 像素误差，其余返回最简整数比。 */
export function resolveDiscoverySubmissionAspectRatio(
  fields: Pick<DiscoverySubmissionFields, 'mediaWidth' | 'mediaHeight'>
): string {
  const width = Math.round(Number(fields.mediaWidth))
  const height = Math.round(Number(fields.mediaHeight))
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    throw new RangeError('媒体宽高必须为正数')
  }

  const actual = width / height
  const canonical = CANONICAL_ASPECT_RATIOS.find(([, expected]) =>
    Math.abs(actual - expected) / expected <= ASPECT_RATIO_TOLERANCE
  )
  if (canonical) return canonical[0]

  const divisor = greatestCommonDivisor(width, height)
  return `${width / divisor}:${height / divisor}`
}

export function buildNewDiscoverySubmission(
  fields: DiscoverySubmissionFields
): UserDiscoverySubmitRequest {
  return {
    mediaType: resolveDiscoverySubmissionMediaType(fields),
    ...fields,
    aspectRatio: resolveDiscoverySubmissionAspectRatio(fields)
  }
}

export function buildDiscoveryResubmission(
  id: number,
  fields: DiscoverySubmissionFields,
  previewVideoUrl: string | null
): UserDiscoveryResubmitRequest {
  const mediaType = resolveDiscoverySubmissionMediaType(fields)
  return {
    id,
    mediaType,
    ...fields,
    aspectRatio: resolveDiscoverySubmissionAspectRatio(fields),
    previewVideoUrl: mediaType === 'video' ? previewVideoUrl : null
  }
}
