export const STUDIO_FREE_THEME_STORAGE_KEY = 'aid-studio-free-theme:v1'

export const STUDIO_FREE_THEME_BOOTSTRAP_SCRIPT = `(function(){try{var u=new URL(location.href),p=u.pathname.replace(/\\/+$/,""),s=p.endsWith("/create/studio")&&u.searchParams.get("studioMode")==="free"&&!u.searchParams.has("previewProjectId"),a=p.endsWith("/canvas/invite")||p.endsWith("/canvas/share");if(!s&&!a)return;var t="soft-mist";try{if(localStorage.getItem("aid-home-theme")==="paper")t="opal"}catch(e){}document.documentElement.setAttribute("data-studio-free-theme",t);document.documentElement.style.colorScheme=t==="opal"?"light":"dark"}catch(e){}})()`

export const STUDIO_FREE_THEMES = ['opal', 'soft-mist'] as const

export type StudioFreeTheme = (typeof STUDIO_FREE_THEMES)[number]

export function isStudioFreeTheme(value: string | null | undefined): value is StudioFreeTheme {
  return value === 'opal' || value === 'soft-mist'
}

export function resolveStudioFreeThemeAt(date: Date): StudioFreeTheme {
  const hour = date.getHours()
  return hour >= 6 && hour < 18 ? 'opal' : 'soft-mist'
}

export function oppositeStudioFreeTheme(theme: StudioFreeTheme): StudioFreeTheme {
  return theme === 'opal' ? 'soft-mist' : 'opal'
}

export function resolveStudioCanvasColorMode(
  studioMode: 'free' | 'flow',
  theme: StudioFreeTheme
): 'light' | 'dark' {
  return studioMode === 'free' && theme === 'opal' ? 'light' : 'dark'
}

export function millisecondsUntilNextStudioThemeBoundary(date: Date): number {
  const next = new Date(date)
  const hour = date.getHours()

  if (hour < 6) {
    next.setHours(6, 0, 0, 0)
  } else if (hour < 18) {
    next.setHours(18, 0, 0, 0)
  } else {
    next.setDate(next.getDate() + 1)
    next.setHours(6, 0, 0, 0)
  }

  return Math.max(next.getTime() - date.getTime(), 1)
}

export function readStudioFreeThemePreference(
  storage: Pick<Storage, 'getItem'> | null | undefined
): StudioFreeTheme | null {
  try {
    const value = storage?.getItem(STUDIO_FREE_THEME_STORAGE_KEY)
    return isStudioFreeTheme(value) ? value : null
  } catch {
    return null
  }
}

export function writeStudioFreeThemePreference(
  storage: Pick<Storage, 'setItem'> | null | undefined,
  theme: StudioFreeTheme
): void {
  try {
    storage?.setItem(STUDIO_FREE_THEME_STORAGE_KEY, theme)
  } catch {
    // 隐私模式或存储空间不可用时，仍保留当前页面内的手动主题。
  }
}

export function clearStudioFreeThemePreference(
  storage: Pick<Storage, 'removeItem'> | null | undefined
): void {
  try {
    storage?.removeItem(STUDIO_FREE_THEME_STORAGE_KEY)
  } catch {
    // 存储不可用时，Provider 仍会在当前页面恢复自动模式。
  }
}
