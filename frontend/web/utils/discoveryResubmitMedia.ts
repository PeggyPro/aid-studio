import type { UserDiscoveryItem } from '~/types/business-api'

export interface DiscoveryResubmitVideoState {
  mediaUrl: string
  mediaWidth: number
  mediaHeight: number
  previewVideoUrl: string | null
}

export interface DiscoveryCoverReplacementResult {
  coverUrl: string
  replaced: boolean
}

export function resolveDiscoveryCoverReplacement(
  previousCoverUrl: string,
  uploadedCoverUrl: string | null | undefined
): DiscoveryCoverReplacementResult {
  const replacement = String(uploadedCoverUrl || '').trim()
  return replacement
    ? { coverUrl: replacement, replaced: true }
    : { coverUrl: previousCoverUrl, replaced: false }
}

export async function attemptDiscoveryCoverReplacement(
  previousCoverUrl: string,
  upload: () => Promise<string | null>
): Promise<DiscoveryCoverReplacementResult & { error?: unknown }> {
  try {
    return resolveDiscoveryCoverReplacement(previousCoverUrl, await upload())
  } catch (error) {
    return { ...resolveDiscoveryCoverReplacement(previousCoverUrl, null), error }
  }
}

export function preserveDiscoveryResubmitVideo(
  item: Pick<
    UserDiscoveryItem,
    'mediaUrl' | 'mediaWidth' | 'mediaHeight' | 'previewVideoUrl'
  >
): DiscoveryResubmitVideoState {
  return {
    mediaUrl: String(item.mediaUrl || '').trim(),
    mediaWidth: Number(item.mediaWidth) || 0,
    mediaHeight: Number(item.mediaHeight) || 0,
    previewVideoUrl: item.previewVideoUrl ?? null
  }
}

/** 新主视频成功后旧预览不再与主视频一致，必须清空并交后端重新生成。 */
export function completeDiscoveryVideoReplacement(
  mediaUrl: string,
  dimensions: { mediaWidth: number; mediaHeight: number }
): DiscoveryResubmitVideoState {
  return {
    mediaUrl,
    mediaWidth: dimensions.mediaWidth,
    mediaHeight: dimensions.mediaHeight,
    previewVideoUrl: null
  }
}
