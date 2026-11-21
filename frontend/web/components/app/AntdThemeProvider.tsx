'use client'

import { AID_ANTD_THEME } from '@/config/antdTheme'
import { App, ConfigProvider } from 'antd'
import { LoadingOutlined } from '@ant-design/icons'
import { type ReactNode, useLayoutEffect, useSyncExternalStore } from 'react'
import { usePathname } from 'next/navigation'
import { V10_ANTD_THEME, V10_LIGHT_ANTD_THEME, isV10AntdTheme } from '@/config/v10Theme'
import { resolveV10Surface, supportsV10Theme } from '@/utils/v10Surface'
import { getV10Theme, useV10Theme } from '@/utils/v10ThemePreference'
import { registerAntdPageTheme } from '@/utils/antdPageTheme'
import { getAntdPageTheme, getServerAntdPageTheme, subscribeAntdPageTheme } from '@/utils/antdPageTheme'
import { getAidPortalRoot } from '~/utils/portalRoot'

export const AID_ANTD_STATIC_HOLDER_CLASS = 'aid-antd-static-holder'

const V10_SPIN_CONFIG = { indicator: <LoadingOutlined spin className="flow-generation-spinner" /> }

/** 锚定浮层复用 Portal；无锚点的全局消息必须脱离业务 Portal 的 stacking context。 */
export function resolveAidPopupContainer(triggerNode?: HTMLElement): HTMLElement {
  if (!triggerNode) return document.body
  return getAidPortalRoot() ?? triggerNode?.parentElement ?? document.body
}

/** Static Modal/message/notification APIs render outside the React provider tree. */
export function renderAidAntdStaticHolder(children: ReactNode) {
  return <AidAntdStaticHolder>{children}</AidAntdStaticHolder>
}

function AidAntdStaticHolder({ children }: { children: ReactNode }) {
  const pageTheme = useSyncExternalStore(subscribeAntdPageTheme, getAntdPageTheme, getServerAntdPageTheme)
  return (
    <ConfigProvider
      theme={pageTheme ?? AID_ANTD_THEME}
      spin={isV10AntdTheme(pageTheme) ? V10_SPIN_CONFIG : undefined}
      wave={{ disabled: true }}
      getPopupContainer={resolveAidPopupContainer}
    >
      <App component={false}>
        <div className={AID_ANTD_STATIC_HOLDER_CLASS}>{children}</div>
      </App>
    </ConfigProvider>
  )
}

let staticThemeInstalled = false

/** Idempotent for React Strict Mode and Fast Refresh. */
export function installAidAntdStaticTheme(): void {
  if (staticThemeInstalled) return
  staticThemeInstalled = true
  ConfigProvider.config({
    theme: AID_ANTD_THEME,
    holderRender: renderAidAntdStaticHolder
  })
}

export function AntdThemeProvider({ children }: { children: ReactNode }) {
  const pathname = usePathname() ?? '/'
  const preference = useV10Theme()
  const surface = resolveV10Surface(pathname, preference)
  const enabled = supportsV10Theme(pathname)
  const pageTheme = enabled && preference === 'paper' ? V10_LIGHT_ANTD_THEME
    : enabled && surface !== 'original' ? V10_ANTD_THEME : AID_ANTD_THEME
  useLayoutEffect(() => {
    document.documentElement.dataset.aidUi = surface
    if (!enabled) {
      delete document.documentElement.dataset.v10Theme
      return
    }
    document.documentElement.dataset.v10Theme = getV10Theme()
    return registerAntdPageTheme(pageTheme)
  }, [surface, enabled, pageTheme])
  return (
    <ConfigProvider
      theme={pageTheme}
      spin={isV10AntdTheme(pageTheme) ? V10_SPIN_CONFIG : undefined}
      wave={{ disabled: true }}
      getPopupContainer={resolveAidPopupContainer}
    >
      <App component={false}>{children}</App>
    </ConfigProvider>
  )
}
