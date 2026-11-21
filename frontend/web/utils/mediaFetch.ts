import { diagnosticFetch } from '~/utils/errorDiagnostics'
import {
isRejectedMediaContentType,
isUsableMediaBlob
} from '~/utils/mediaBlobGuard'
import { inspectMediaUrl } from '~/utils/mediaUrlPolicy'

function isSameOriginUrl(url: string): boolean {
  if (typeof window === 'undefined') return true
  try {
    return new URL(url, window.location.href).origin === window.location.origin
  } catch {
    return false
  }
}

/** Use the media URL returned by the API for playback. */
export function resolveMediaPlaybackUrl(url: string): string {
  const source = String(url || '').trim()
  return inspectMediaUrl(source).allowed ? source : ''
}

export type StudioVideoFilmstripSource = {
  src: string
  crossOrigin?: 'anonymous'
}

/** Frame extraction needs permission to read pixels from cross-origin media. */
export function resolveStudioVideoFilmstripSource(url: string): StudioVideoFilmstripSource {
  const src = resolveMediaPlaybackUrl(url)
  if (!src || src.startsWith('blob:') || src.startsWith('data:') || isSameOriginUrl(src)) return { src }
  return { src, crossOrigin: 'anonymous' }
}

async function tryFetchMediaBlob(href: string, init?: RequestInit): Promise<Blob | null> {
  try {
    const res = await diagnosticFetch(href, init)
    if (!res.ok) return null
    const contentType = res.headers.get('content-type')
    if (isRejectedMediaContentType(contentType)) return null
    const blob = await res.blob()
    if (!(await isUsableMediaBlob(blob))) return null
    return blob
  } catch {
    init?.signal?.throwIfAborted()
    return null
  }
}

/** Read the asset directly, including cross-origin media that permits CORS. */
export async function fetchMediaBlob(url: string, signal?: AbortSignal): Promise<Blob | null> {
  signal?.throwIfAborted()
  const source = resolveMediaPlaybackUrl(url)
  return source ? tryFetchMediaBlob(source, { credentials: 'same-origin', signal }) : null
}

/** 供 WebAV MP4Clip / AudioClip 使用的可读流 */
export async function fetchMediaStream(url: string): Promise<ReadableStream<Uint8Array> | null> {
  const blob = await fetchMediaBlob(url)
  if (!blob) return null
  return blob.stream() as ReadableStream<Uint8Array>
}
