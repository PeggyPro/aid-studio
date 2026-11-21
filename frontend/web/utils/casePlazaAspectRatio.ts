const FALLBACK_RATIO = 16 / 9

const NAMED_RATIOS: Record<string, number> = {
  '16:9': 16 / 9,
  '9:16': 9 / 16,
  '1:1': 1,
  '4:3': 4 / 3,
  '3:4': 3 / 4
}

const STANDARD_RATIOS = Object.entries(NAMED_RATIOS)

export interface CasePlazaAspectRatioInput {
  sourceKind?: 'discovery' | 'project' | 'canvas'
  mediaWidth?: number | null
  mediaHeight?: number | null
  aspectRatio?: string | null
}

export interface ResolvedCasePlazaAspectRatio {
  ratio: number
  label: string
  source: 'measured' | 'aspectRatio' | 'fallback'
}

export function parseAspectRatioString(raw: string | null | undefined): number | null {
  const value = String(raw || '').trim().toLowerCase()
  if (!value || value === 'other') return null

  const namedRatio = NAMED_RATIOS[value]
  if (namedRatio != null) return namedRatio

  const match = /^(\d+(?:\.\d+)?)\s*[:/]\s*(\d+(?:\.\d+)?)$/.exec(value)
  if (!match) return null

  const width = Number(match[1])
  const height = Number(match[2])
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    return null
  }
  return width / height
}

function labelFromRatio(ratio: number): string {
  const standard = STANDARD_RATIOS.find(([, value]) => Math.abs(ratio - value) < 0.02)
  return standard?.[0] || 'other'
}

export function resolveCasePlazaAspectRatio(
  input: CasePlazaAspectRatioInput
): ResolvedCasePlazaAspectRatio {
  if (input.sourceKind === 'project') {
    const projectRatio = parseAspectRatioString(input.aspectRatio)
    if (projectRatio != null) {
      return {
        ratio: projectRatio,
        label: labelFromRatio(projectRatio),
        source: 'aspectRatio'
      }
    }
    return { ratio: FALLBACK_RATIO, label: '16:9', source: 'fallback' }
  }

  const width = Number(input.mediaWidth)
  const height = Number(input.mediaHeight)
  if (Number.isFinite(width) && Number.isFinite(height) && width > 0 && height > 0) {
    const ratio = width / height
    return { ratio, label: labelFromRatio(ratio), source: 'measured' }
  }

  const parsedRatio = parseAspectRatioString(input.aspectRatio)
  if (parsedRatio != null) {
    return {
      ratio: parsedRatio,
      label: labelFromRatio(parsedRatio),
      source: 'aspectRatio'
    }
  }

  return { ratio: FALLBACK_RATIO, label: '16:9', source: 'fallback' }
}
