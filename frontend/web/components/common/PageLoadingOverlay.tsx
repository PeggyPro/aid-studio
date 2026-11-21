'use client'

import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { useAuthPublicConfig } from '~/composables/useAuthPublicConfig'
import { getAidPortalRoot } from '~/utils/portalRoot'
import { AppLoadingIndicator } from './AppLoadingIndicator'
import './page-loading-overlay.css'

/** 复用跨流程导航的视觉，避免页面 transform 限制全屏遮罩。 */
export function PageLoadingOverlay({ label = '页面加载中…' }: { label?: string }) {
  const { siteName } = useAuthPublicConfig()
  const [mounted, setMounted] = useState(false)

  useEffect(() => {
    setMounted(true)
  }, [])

  if (!mounted) return null

  const portalRoot = getAidPortalRoot()
  if (!portalRoot) return null

  return createPortal(<RouteLoadingScreen label={label} brand={siteName} />, portalRoot)
}

/** 新版分支的所有路由共用同一加载视觉，不按来源或目标路径切回旧版。 */
export function RouteLoadingScreen({ label, brand }: { label: string; brand?: string }) {
  return (
    <div className="route-overlay aid-loading-screen" role="status" aria-live="polite" aria-label={label}>
      <AppLoadingIndicator label={label} brand={brand || 'AID'} />
    </div>
  )
}
