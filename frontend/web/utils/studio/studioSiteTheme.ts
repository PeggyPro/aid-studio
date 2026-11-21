import type { V10ThemeId } from '@/utils/v10ThemePreference'
import type { StudioFreeTheme } from './studioFreeTheme'

export function canvasThemeFromSite(theme: V10ThemeId): StudioFreeTheme {
  return theme === 'paper' ? 'opal' : 'soft-mist'
}

export function siteThemeFromCanvas(theme: StudioFreeTheme): V10ThemeId {
  return theme === 'opal' ? 'paper' : 'graphite'
}
