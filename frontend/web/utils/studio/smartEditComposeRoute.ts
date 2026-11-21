import { diagnosticFetch } from '@/utils/errorDiagnostics'
import { resolveMediaPlaybackUrl } from '@/utils/mediaFetch'
import { hasMediaContentTypeHint } from '@/utils/mediaBlobGuard'
import type { SmartEditAudioClip, SmartEditTimeline, SmartEditTrack } from '@/types/studio'

export const SMART_EDIT_BROWSER_COMPOSE_LIMIT = 500 * 1024 * 1024
export function smartEditDecodedClips(timeline: SmartEditTimeline, hiddenTracks: SmartEditTrack[] = []): SmartEditAudioClip[] {
  return [
    ...(hiddenTracks.includes('video') ? [] : timeline.videoClips),
    ...(hiddenTracks.includes('overlay') ? [] : timeline.overlayClips),
    ...(hiddenTracks.includes('audio') ? [] : timeline.audioClips)
  ]
}
function knownBytes(value: number | null | undefined): number | null {
  return typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : null
}
/** Resolve legacy media size without downloading the asset into memory. */
export async function readSmartEditSourceSize(url: string, signal: AbortSignal): Promise<number | null> {
  const source = resolveMediaPlaybackUrl(url)
  if (!source) return null
  const probeSignal = () => AbortSignal.any([signal, AbortSignal.timeout(15000)])
  try {
    const headers = await diagnosticFetch(source, { method: 'HEAD', signal: probeSignal() })
    if (headers.ok && hasMediaContentTypeHint(headers.headers.get('content-type'))) {
      const size = knownBytes(Number(headers.headers.get('content-length')))
      if (size) return size
    }
  } catch {
    signal.throwIfAborted()
  }
  try {
    const response = await diagnosticFetch(source, { headers: { Range: 'bytes=0-0' }, signal: probeSignal() })
    const range = /^bytes 0-0\/(\d+)$/.exec(response.headers.get('content-range') || '')
    await response.body?.cancel()
    return response.status === 206 && hasMediaContentTypeHint(response.headers.get('content-type')) && range
      ? knownBytes(Number(range[1])) : null
  } catch {
    signal.throwIfAborted()
    return null
  }
}

export function routeSmartEditCompose(input: {
  timeline: SmartEditTimeline
  hiddenTracks?: SmartEditTrack[]
  fileSizeByResourceId: ReadonlyMap<string, number | null | undefined>
  fileSizeBySourceUrl?: ReadonlyMap<string, number | null | undefined>
}): { route: 'browser' } | { route: 'server'; reason: 'over-limit' | 'unknown-size' } {
  const seen = new Set<string>()
  let total = 0
  for (const clip of smartEditDecodedClips(input.timeline, input.hiddenTracks)) {
    const identity = clip.sourceResourceId || clip.sourceUrl
    if (seen.has(identity)) continue
    seen.add(identity)
    const bytes = (clip.sourceResourceId ? knownBytes(input.fileSizeByResourceId.get(clip.sourceResourceId)) : null)
      ?? knownBytes(input.fileSizeBySourceUrl?.get(clip.sourceUrl))
    if (bytes == null) return { route: 'server', reason: 'unknown-size' }
    total += bytes
    if (total > SMART_EDIT_BROWSER_COMPOSE_LIMIT) return { route: 'server', reason: 'over-limit' }
  }
  return { route: 'browser' }
}
