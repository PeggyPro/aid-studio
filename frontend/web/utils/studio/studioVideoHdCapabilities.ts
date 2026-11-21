import type { StudioVideoResolution } from '@/types/studio'

const RESOLUTION_CODES: Record<string, StudioVideoResolution> = {
  '720p': '720', '720': '720', '1080p': '1080', '1080': '1080', '2k': '2K', '4k': '4K'
}

/** Shared panel policy: declared constraints take precedence over legacy options. */
export function resolveStudioVideoHdCapabilities(capability: Record<string, unknown>, sourceHeight: number | null,
  legacyResolutions: readonly StudioVideoResolution[]) {
  const configured = capability.targetResolutionOptions ?? capability.sizeOptions
  const targets = configured == null ? [...legacyResolutions] : Array.isArray(configured)
    ? configured.filter((value): value is string => typeof value === 'string') : []
  const pairs = capability.upscalePairs as Record<string, unknown> | undefined
  const allowed = pairs == null ? targets : sourceHeight == null ? [] : pairs[`${sourceHeight}p`]
  const resolutions = Array.isArray(allowed) ? [...new Set(targets
    .filter(value => allowed.some(target => typeof target === 'string' && target.toLowerCase() === value.toLowerCase()))
    .map(value => RESOLUTION_CODES[value.toLowerCase()]).filter((value): value is StudioVideoResolution => Boolean(value)))] : []
  const supportsInterpolation = capability.supportsInterpolation !== false
  return { resolutions, requiresSourceMetadata: pairs != null, supportsInterpolation,
    supportsSlowMotion: supportsInterpolation && capability.supportsSlowMotion !== false }
}
