/** Homepage compatibility exports use the shared persisted appearance. */
import { getV10Theme } from '~/utils/v10ThemePreference'

export const HOME_THEMES = [
  { id: 'graphite', label: '石墨', ready: true },
  { id: 'paper', label: '瓷白', ready: true }
] as const

export type HomeThemeId = (typeof HOME_THEMES)[number]['id']

export const HOME_THEME_STORAGE_KEY = 'aid-home-theme'

export const ACTIVE_HOME_THEME: HomeThemeId = 'graphite'

export function isHomeThemeReady(id: string): id is HomeThemeId {
  return HOME_THEMES.some((theme) => theme.id === id && theme.ready)
}

export const readHomeTheme = getV10Theme
