/** 移动端内容首页及创作提示页（与 proxy.ts / RouteGuard 共用）。 */
export const MOBILE_ONLY_PATH = '/mobile'
export const MOBILE_DESKTOP_PATH = '/mobile/desktop'
export const VIRTUAL_CAMERA_PATH = '/studio/virtual-camera'

export function isMobileUserAgent(ua: string): boolean {
  return /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini|Mobile|Windows Phone/i.test(
    ua
  )
}

/** 客户端移动入口判定，供路由守卫和登录后欢迎引导共用。 */
export function isMobileClientEnvironment(): boolean {
  if (typeof window === 'undefined') return false
  // 窄窗口仍可使用桌面创作；页面宽度只影响布局，不决定设备入口。
  return isMobileUserAgent(navigator.userAgent || '')
}

/**
 * 是否应对该路径执行移动端拦截。
 * 手机只开放内容首页、电脑端创作提示和虚拟相机控制页。
 */
export function shouldForceMobileOnlyPath(pathname: string): boolean {
  if (pathname === MOBILE_ONLY_PATH || pathname === MOBILE_DESKTOP_PATH) return false
  if (pathname === VIRTUAL_CAMERA_PATH || pathname.startsWith(`${VIRTUAL_CAMERA_PATH}/`)) return false
  return true
}

/** 桌面端访问移动专属页面时回到原有桌面首页。 */
export function shouldLeaveMobileOnlyPath(pathname: string, isMobile: boolean): boolean {
  return !isMobile && (pathname === MOBILE_ONLY_PATH || pathname === MOBILE_DESKTOP_PATH)
}
