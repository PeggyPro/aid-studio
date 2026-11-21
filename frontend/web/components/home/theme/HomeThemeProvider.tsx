'use client'

import { createContext, useContext, type ReactNode } from 'react'
import { ACTIVE_HOME_THEME, type HomeThemeId } from './homeTheme'

const HomeThemeContext = createContext<HomeThemeId>(ACTIVE_HOME_THEME)

export function HomeThemeProvider({
  theme,
  children
}: {
  theme: HomeThemeId
  children: ReactNode
}) {
  return <HomeThemeContext.Provider value={theme}>{children}</HomeThemeContext.Provider>
}

export function useHomeTheme() {
  return useContext(HomeThemeContext)
}
