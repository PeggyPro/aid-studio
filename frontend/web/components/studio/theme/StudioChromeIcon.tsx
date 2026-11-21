import type { ReactNode } from 'react'
import { useStudioUiStore } from '@/stores/studioUi'
import { StudioIconGlyph, type StudioIconName } from './StudioIconGlyph'

export type StudioChromeIconName = StudioIconName

/** Canvas glyphs share one family; other studio modes retain their existing icons. */
export function StudioChromeIcon({
  name,
  fallback,
  className,
  size = 16
}: {
  name: StudioChromeIconName
  fallback: ReactNode
  className?: string
  size?: number | string
}) {
  const mode = useStudioUiStore((state) => state.studioMode)
  if (mode !== 'free') return fallback
  return <StudioIconGlyph name={name} width={size} height={size} className={className} />
}
