'use client'

import './studio-surface-motion.css'
import './studio-free-control-tokens.css'
import './studio-matte-palette.css'

import { ConfigProvider, theme as antdTheme, type ThemeConfig } from 'antd'
import { registerAntdPageTheme } from '@/utils/antdPageTheme'
import { getV10Theme, setV10Theme, subscribeV10Theme } from '@/utils/v10ThemePreference'
import { canvasThemeFromSite, siteThemeFromCanvas } from '@/utils/studio/studioSiteTheme'
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode
} from 'react'
import './studio-free-themes.css'
import './studio-surface-materials.css'
import {
  millisecondsUntilNextStudioThemeBoundary,
  clearStudioFreeThemePreference,
  readStudioFreeThemePreference,
  resolveStudioFreeThemeAt,
  type StudioFreeTheme,
  writeStudioFreeThemePreference
} from '@/utils/studio/studioFreeTheme'

type StudioFreeThemeContextValue = {
  theme: StudioFreeTheme
  isAutomatic: boolean
  siteSynced: boolean
  selectTheme: (theme: StudioFreeTheme) => void
  toggleTheme: () => void
}

const defaultStudioFreeThemeContext: StudioFreeThemeContextValue = {
  theme: 'soft-mist',
  isAutomatic: true,
  siteSynced: false,
  selectTheme: () => undefined,
  toggleTheme: () => undefined
}

const StudioFreeThemeContext = createContext<StudioFreeThemeContextValue>(defaultStudioFreeThemeContext)

const useBrowserLayoutEffect = typeof window === 'undefined' ? useEffect : useLayoutEffect

function browserThemeStorage(): Storage | null {
  try {
    return window.localStorage
  } catch {
    return null
  }
}

function useStudioFreeThemeController(enabled: boolean, syncWithSiteTheme = false): StudioFreeThemeContextValue {
  const [theme, setTheme] = useState<StudioFreeTheme>('soft-mist')
  const [preference, setPreference] = useState<StudioFreeTheme | null>(null)
  const preferenceRef = useRef<StudioFreeTheme | null>(null)

  const syncAutomaticTheme = useCallback(() => {
    if (preferenceRef.current) return
    setTheme(resolveStudioFreeThemeAt(new Date()))
  }, [])

  useBrowserLayoutEffect(() => {
    if (!enabled) return
    if (syncWithSiteTheme) {
      const sync = () => setTheme(canvasThemeFromSite(getV10Theme()))
      sync()
      return subscribeV10Theme(sync)
    }
    const stored = readStudioFreeThemePreference(browserThemeStorage())
    preferenceRef.current = stored
    setPreference(stored)
    setTheme(stored ?? resolveStudioFreeThemeAt(new Date()))
  }, [enabled, syncWithSiteTheme])

  useEffect(() => {
    if (!enabled || syncWithSiteTheme || preference) return

    let boundaryTimer: number | null = null
    const scheduleBoundary = () => {
      if (boundaryTimer != null) window.clearTimeout(boundaryTimer)
      syncAutomaticTheme()
      boundaryTimer = window.setTimeout(scheduleBoundary, millisecondsUntilNextStudioThemeBoundary(new Date()) + 50)
    }
    const handleVisible = () => {
      if (document.visibilityState === 'visible') scheduleBoundary()
    }

    scheduleBoundary()
    window.addEventListener('focus', scheduleBoundary)
    document.addEventListener('visibilitychange', handleVisible)
    return () => {
      if (boundaryTimer != null) window.clearTimeout(boundaryTimer)
      window.removeEventListener('focus', scheduleBoundary)
      document.removeEventListener('visibilitychange', handleVisible)
    }
  }, [enabled, syncWithSiteTheme, preference, syncAutomaticTheme])

  const selectTheme = useCallback((nextTheme: StudioFreeTheme) => {
    if (syncWithSiteTheme) {
      setV10Theme(siteThemeFromCanvas(nextTheme))
      return
    }
    preferenceRef.current = nextTheme
    setPreference(nextTheme)
    setTheme(nextTheme)
    writeStudioFreeThemePreference(browserThemeStorage(), nextTheme)
  }, [syncWithSiteTheme])

  const toggleTheme = useCallback(() => {
    if (syncWithSiteTheme) {
      setV10Theme(getV10Theme() === 'paper' ? 'graphite' : 'paper')
      return
    }
    if (preference == null) {
      selectTheme('opal')
      return
    }
    if (preference === 'soft-mist') {
      preferenceRef.current = null
      setPreference(null)
      setTheme(resolveStudioFreeThemeAt(new Date()))
      clearStudioFreeThemePreference(browserThemeStorage())
      return
    }
    selectTheme('soft-mist')
  }, [syncWithSiteTheme, preference, selectTheme])

  return useMemo<StudioFreeThemeContextValue>(() => ({
    theme,
    isAutomatic: !syncWithSiteTheme && preference == null,
    siteSynced: syncWithSiteTheme,
    selectTheme,
    toggleTheme
  }), [syncWithSiteTheme, preference, selectTheme, theme, toggleTheme])
}

function componentThemeFrom(element: Element, theme: StudioFreeTheme, motion = true): ThemeConfig {
  const styles = getComputedStyle(element)
  const color = (name: string) => styles.getPropertyValue(name).trim()
  return {
    inherit: false,
    algorithm: theme === 'opal' ? antdTheme.defaultAlgorithm : antdTheme.darkAlgorithm,
    token: {
      motion,
      fontFamily: 'inherit',
      colorPrimary: color('--studio-accent'),
      colorInfo: color('--studio-accent'),
      colorLink: color('--studio-accent'),
      colorBgBase: color('--studio-bg'),
      colorBgLayout: color('--studio-bg'),
      colorBgContainer: color('--studio-surface'),
      colorBgElevated: color('--studio-theme-panel-solid'),
      colorBgSpotlight: color('--studio-surface-strong'),
      colorText: color('--studio-text'),
      colorTextSecondary: color('--studio-muted'),
      colorTextLightSolid: color('--studio-theme-inverse-text'),
      colorBorder: color('--studio-border'),
      colorBorderSecondary: color('--studio-border'),
      colorFillSecondary: color('--studio-theme-control-hover'),
      colorFillTertiary: color('--studio-theme-control'),
      controlItemBgHover: color('--studio-theme-control-hover'),
      controlItemBgActive: color('--studio-theme-control-active'),
      borderRadius: 9,
      borderRadiusLG: parseFloat(color('--studio-theme-radius'))
    },
    components: {
      Tooltip: { colorTextLightSolid: color('--studio-text'), ...(theme === 'soft-mist' ? { colorBgSpotlight: color('--studio-theme-popover') } : {}) },
      ...(theme === 'soft-mist' ? {
        Modal: { contentBg: color('--studio-theme-modal'), headerBg: 'transparent', footerBg: 'transparent' },
        Popover: { colorBgElevated: color('--studio-theme-popover') },
        Input: { activeBg: color('--studio-theme-field'), hoverBg: color('--studio-theme-field') },
        Button: { defaultBg: color('--studio-theme-control'), defaultBorderColor: 'transparent', defaultColor: color('--studio-theme-text-secondary') }
      } : {}),
      Message: { zIndexPopup: 20000 },
      Notification: { zIndexPopup: 20000 }
    }
  }
}

export function StudioFreeThemeProvider({
  enabled,
  syncWithSiteTheme = false,
  children
}: {
  enabled: boolean
  syncWithSiteTheme?: boolean
  children: ReactNode
}) {
  const value = useStudioFreeThemeController(enabled, syncWithSiteTheme)
  const { theme } = value
  const [componentTheme, setComponentTheme] = useState<ThemeConfig>()

  useBrowserLayoutEffect(() => {
    const root = document.documentElement
    if (!enabled) {
      root.removeAttribute('data-studio-free-theme')
      root.style.removeProperty('color-scheme')
      return
    }

    root.dataset.studioFreeTheme = theme
    root.style.colorScheme = theme === 'opal' ? 'light' : 'dark'
    // 从同一份 CSS 材料读取实际颜色。Antd 的派生算法不能接收 var(...)，
    // 且必须切断站点级深色组件覆盖，否则 Portal 内部仍会使用旧背景。
    setComponentTheme(componentThemeFrom(root, theme))
    return () => {
      root.removeAttribute('data-studio-free-theme')
      root.style.removeProperty('color-scheme')
    }
  }, [enabled, theme])

  useBrowserLayoutEffect(() => {
    if (!enabled || !componentTheme) return
    return registerAntdPageTheme(componentTheme)
  }, [enabled, componentTheme])

  return (
    <StudioFreeThemeContext.Provider value={value}>
      {enabled ? <ConfigProvider theme={componentTheme}>{children}</ConfigProvider> : children}
    </StudioFreeThemeContext.Provider>
  )
}

/**
 * 局部主题和浮层共用同一容器，不改写 html 或全局静态提示的主题。
 * 显式覆盖时固定主题，不运行自动切换或写入用户偏好。
 */
export function StudioFreeThemeScope({
  enabled = true,
  className,
  themeOverride,
  children
}: {
  enabled?: boolean
  className?: string
  themeOverride?: StudioFreeTheme
  children: ReactNode
}) {
  const value = useStudioFreeThemeController(enabled && !themeOverride)
  const theme = themeOverride ?? value.theme
  const scopedValue = useMemo(() => themeOverride
    ? { ...defaultStudioFreeThemeContext, theme: themeOverride, isAutomatic: false }
    : value, [themeOverride, value])
  const scopeRef = useRef<HTMLDivElement>(null)
  const getPopupContainer = useCallback(() => scopeRef.current ?? document.body, [])
  const [componentTheme, setComponentTheme] = useState<ThemeConfig>()

  useBrowserLayoutEffect(() => {
    const scope = scopeRef.current
    if (!enabled || !scope) return
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
    // Antd must skip entering motion when its reduced-motion CSS removes the animation.
    const syncTheme = () => setComponentTheme(componentThemeFrom(scope, theme, !reducedMotion.matches))
    syncTheme()
    reducedMotion.addEventListener('change', syncTheme)
    return () => reducedMotion.removeEventListener('change', syncTheme)
  }, [enabled, theme])

  return (
    <StudioFreeThemeContext.Provider value={scopedValue}>
      <ConfigProvider theme={enabled ? componentTheme : undefined} getPopupContainer={enabled ? getPopupContainer : undefined}>
        <div
          ref={scopeRef}
          className={className}
          data-studio-free-theme-scope={enabled ? theme : undefined}
          style={enabled ? { colorScheme: theme === 'opal' ? 'light' : 'dark' } : undefined}
          suppressHydrationWarning
        >
          {children}
        </div>
      </ConfigProvider>
    </StudioFreeThemeContext.Provider>
  )
}

export function useStudioFreeTheme(): StudioFreeThemeContextValue {
  return useContext(StudioFreeThemeContext)
}
