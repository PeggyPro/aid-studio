import { isImagePreviewUrl } from '@/components/studio/canvas/StudioMediaPreview'
import { resolveMediaPlaybackUrl } from '@/utils/mediaFetch'

export type StudioMentionPreview =
  | { kind: 'empty' }
  | { kind: 'image'; src: string }
  | { kind: 'video'; src: string }

/** 正文引用的预览必须能被对应标签解码。视频地址交给 video，图片地址才交给 img。 */
export function resolveStudioMentionPreview(
  mediaKind: string | null | undefined,
  previewUrl: string | null | undefined
): StudioMentionPreview {
  const url = String(previewUrl || '').trim()
  if (!url) return { kind: 'empty' }
  if (mediaKind === 'video' && !isImagePreviewUrl(url)) {
    const playback = resolveMediaPlaybackUrl(url)
    if (!playback) return { kind: 'empty' }
    return { kind: 'video', src: playback.includes('#') ? playback : `${playback}#t=0.1` }
  }
  return { kind: 'image', src: url }
}
