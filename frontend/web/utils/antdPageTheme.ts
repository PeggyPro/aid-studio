import type { ThemeConfig } from 'antd'

// 静态 message / Modal 不在页面 React 树中，显式桥接页面主题，离开页面即释放。
let active: { theme: ThemeConfig } | null = null
const listeners = new Set<() => void>()
const emit = () => listeners.forEach((listener) => listener())

export const getAntdPageTheme = () => active?.theme
export const getServerAntdPageTheme = () => undefined
export function subscribeAntdPageTheme(listener: () => void) {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

export function registerAntdPageTheme(theme: ThemeConfig) {
  const entry = { theme }
  active = entry
  emit()
  return () => {
    if (active !== entry) return
    active = null
    emit()
  }
}
