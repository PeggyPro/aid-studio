import type { StudioMediaKind, StudioNodeKind } from '@/types/studio'

export function resolveStudioNodeMediaKind(
  kind: StudioNodeKind,
  mediaKind: StudioMediaKind | undefined
): StudioMediaKind | undefined {
  return kind === 'video' ? 'video' : mediaKind
}

export function studioNodeMediaDownloadType(
  kind: StudioNodeKind,
  mediaKind: StudioMediaKind | undefined
): '图片' | '视频' | '音频' {
  const resolved = resolveStudioNodeMediaKind(kind, mediaKind)
  if (resolved === 'video') return '视频'
  if (resolved === 'audio') return '音频'
  return '图片'
}
