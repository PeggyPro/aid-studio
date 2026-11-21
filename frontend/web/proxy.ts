import { NextResponse, type NextRequest } from 'next/server'
import {
  MOBILE_ONLY_PATH,
  isMobileUserAgent,
  shouldForceMobileOnlyPath
} from '~/utils/mobileOnlyNavigation'

/** SSR 阶段 UA 拦截：与原 Nuxt middleware/00.mobile-only.global.ts 的服务端分支一致，避免首屏闪跳 */
export function proxy(request: NextRequest) {
  const ua = request.headers.get('user-agent') || ''
  const isMobile = isMobileUserAgent(ua)
  const { pathname } = request.nextUrl

  if (isMobile && shouldForceMobileOnlyPath(pathname)) {
    return NextResponse.redirect(new URL(MOBILE_ONLY_PATH, request.url))
  }
  // 桌面 UA 的窄视口也可能通过客户端判断进入 /mobile；由 RouteGuard
  // 根据 UA 与视口共同处理离开逻辑，避免服务端和客户端互相重定向。
  return NextResponse.next()
}

export const config = {
  /** 跳过静态资源与 API 代理路径 */
  matcher: [
    '/((?!_next|url|api|tac|media|favicon|.*\\..*).*)'
  ]
}
