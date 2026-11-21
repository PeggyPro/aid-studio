import { theme, type ThemeConfig } from 'antd'

/** V10 only replaces presentation. The existing routing, stores and task lifecycle stay authoritative. */
export const V10_THEME_OPTIONS = [
  { id: 'graphite', label: '深色', ready: true },
  { id: 'paper', label: '浅色', ready: true }
] as const

export const V10_ANTD_THEME: ThemeConfig = {
  cssVar: { key: 'aid-v10-graphite' },
  algorithm: theme.darkAlgorithm,
  token: {
    colorPrimary: '#f1f2f4', colorInfo: '#4ae7fd', colorLink: '#4ae7fd',
    colorSuccess: '#5fd38d', colorWarning: '#f2b94b', colorError: '#ff7a7a',
    colorBgBase: '#0f1012', colorBgLayout: '#0f1012', colorBgContainer: '#1d1e22',
    colorBgElevated: '#1d1e22', colorBgSpotlight: '#303137',
    colorText: '#f1f2f4', colorTextSecondary: '#a8abb3', colorTextTertiary: '#8b8e98',
    colorBorder: 'rgba(255,255,255,.06)', colorBorderSecondary: 'rgba(255,255,255,.06)',
    colorFillSecondary: '#26272c', colorFillTertiary: '#1d1e22', colorFillQuaternary: '#16171a',
    controlItemBgHover: '#26272c', controlItemBgActive: '#303137',
    controlOutline: 'rgba(255,255,255,.18)', borderRadius: 8, borderRadiusLG: 14,
    fontFamily: 'inherit',
    wireframe: false
  },
  components: {
    Button: { primaryColor: '#0f1012', primaryShadow: 'none', defaultShadow: 'none', defaultBg: '#26272c', defaultColor: '#f1f2f4', defaultBorderColor: 'transparent' },
    Modal: { contentBg: '#16171a', headerBg: '#16171a', footerBg: '#16171a', titleColor: '#f1f2f4' },
    Drawer: { colorBgElevated: '#16171a', colorBgContainer: '#16171a' },
    Select: { selectorBg: '#1d1e22', clearBg: '#1d1e22', optionActiveBg: '#26272c', optionSelectedBg: '#303137', optionSelectedColor: '#f1f2f4', multipleItemBg: '#303137', hoverBorderColor: 'transparent', activeBorderColor: 'rgba(255,255,255,.18)' },
    Input: { hoverBg: '#26272c', activeBg: '#1d1e22', hoverBorderColor: 'transparent', activeBorderColor: 'rgba(255,255,255,.18)', activeShadow: 'none' },
    InputNumber: { hoverBg: '#26272c', activeBg: '#1d1e22', hoverBorderColor: 'transparent', activeBorderColor: 'rgba(255,255,255,.18)', activeShadow: 'none' },
    Segmented: { itemSelectedBg: '#f1f2f4', itemSelectedColor: '#0f1012', trackBg: '#1d1e22' },
    Message: { contentBg: '#1d1e22', zIndexPopup: 20000 },
    Notification: { zIndexPopup: 20000 }
  }
}

/** v10-light/light.css: porcelain surfaces and a graphite primary action. */
export const V10_LIGHT_ANTD_THEME: ThemeConfig = {
  ...V10_ANTD_THEME,
  // Keep SSR graphite variables and active porcelain variables in distinct scopes.
  cssVar: { key: 'aid-v10-paper' },
  algorithm: theme.defaultAlgorithm,
  token: {
    ...V10_ANTD_THEME.token,
    colorPrimary: '#252b31', colorInfo: '#007589', colorLink: '#007589',
    colorSuccess: '#24734d', colorWarning: '#98620a', colorError: '#bc3e4c',
    colorBgBase: '#ffffff', colorBgLayout: '#f2f3f5', colorBgContainer: '#f1f4f6',
    colorBgElevated: '#ffffff', colorBgSpotlight: '#ffffff', colorBgMask: 'rgba(49,64,78,.28)',
    colorText: '#252b31', colorTextSecondary: '#515e6a', colorTextTertiary: '#596874',
    colorTextLightSolid: '#ffffff', colorBorder: 'rgba(37,52,65,.08)', colorBorderSecondary: 'rgba(37,52,65,.07)',
    colorFillSecondary: '#e8edf0', colorFillTertiary: '#f1f4f6', colorFillQuaternary: '#f2f3f5',
    controlItemBgHover: '#f1f4f6', controlItemBgActive: '#e3f2f5', controlOutline: 'rgba(0,126,148,.5)'
  },
  components: {
    ...V10_ANTD_THEME.components,
    Button: { primaryColor: '#ffffff', primaryShadow: 'none', defaultShadow: 'none', defaultBg: '#e8edf0', defaultColor: '#252b31', defaultBorderColor: 'transparent' },
    Modal: { contentBg: '#ffffff', headerBg: '#ffffff', footerBg: '#ffffff', titleColor: '#252b31' },
    Drawer: { colorBgElevated: '#ffffff', colorBgContainer: '#ffffff' },
    Select: { selectorBg: '#f1f4f6', clearBg: '#f1f4f6', optionActiveBg: '#f1f4f6', optionSelectedBg: '#e3f2f5', optionSelectedColor: '#007589', multipleItemBg: '#e8edf0', hoverBorderColor: 'transparent', activeBorderColor: 'rgba(0,126,148,.5)' },
    Input: { hoverBg: '#e8edf0', activeBg: '#f1f4f6', hoverBorderColor: 'transparent', activeBorderColor: 'rgba(0,126,148,.5)', activeShadow: 'none' },
    InputNumber: { hoverBg: '#e8edf0', activeBg: '#f1f4f6', hoverBorderColor: 'transparent', activeBorderColor: 'rgba(0,126,148,.5)', activeShadow: 'none' },
    Segmented: { itemSelectedBg: '#252b31', itemSelectedColor: '#ffffff', trackBg: '#e8edf0' },
    Message: { contentBg: '#ffffff', zIndexPopup: 20000 }
  }
}

export function isV10AntdTheme(value: ThemeConfig | undefined): boolean {
  return value === V10_ANTD_THEME || value === V10_LIGHT_ANTD_THEME
}
