'use client'

import { useUserStore } from '@/stores/user'
import { usePathname, useRouter } from 'next/navigation'
import { useEffect } from 'react'
import { isAuthRequiredPath, isFreeCanvasLocation } from '@/utils/authRequiredPath'
import {
  MOBILE_ONLY_PATH,
  isMobileClientEnvironment,
  shouldForceMobileOnlyPath,
  shouldLeaveMobileOnlyPath
} from '@/utils/mobileOnlyNavigation'
import { isPublicProjectPreviewQuery } from '@/utils/publicProjectPreviewNavigation'

function isPublicProjectPreviewLocation(path: string, search: string): boolean {
  if (path !== '/create' && !path.startsWith('/create/')) return false
  return isPublicProjectPreviewQuery(new URLSearchParams(search))
}

/**
 * 客户端路由守卫：
 * - 移动端强制 /mobile，桌面访问 /mobile 回首页（与 proxy.ts / 原 Nuxt 00.mobile-only 一致）
 * - /works、/assets、/create*、/invite 未登录仅回首页，**不自动弹登录**
 *   （登录弹窗只由用户点击需登录功能触发，保证游客可逛案例广场等公开页）
 *
 * 说明：静态导出环境下 proxy 不生效，移动端拦截必须以本守卫为准。
 */
export function RouteGuard({ children }: { children: React.ReactNode }) {
  const pathname = usePathname()
  const router = useRouter()
  const token = useUserStore((s) => s.token)

  useEffect(() => {
    const mobile = isMobileClientEnvironment()
    if (mobile && shouldForceMobileOnlyPath(pathname)) {
      router.replace(MOBILE_ONLY_PATH)
      return
    }
    if (shouldLeaveMobileOnlyPath(pathname, mobile)) {
      router.replace('/')
      return
    }

    if (!isAuthRequiredPath(pathname) || isPublicProjectPreviewLocation(pathname, window.location.search) || isFreeCanvasLocation(pathname, window.location.search)) {
      return
    }

    const store = useUserStore.getState()
    if (!store.token) {
      store.hydrateFromStorage()
    }
    const nextToken = useUserStore.getState().token || localStorage.getItem('token') || ''
    if (!nextToken) {
      router.replace('/')
    }
  }, [pathname, router, token])

  return <>{children}</>
}
