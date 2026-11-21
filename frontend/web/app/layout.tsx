import type { Metadata, Viewport } from 'next'
import { AntdRegistry } from '@ant-design/nextjs-registry'
import { AntdThemeProvider } from '@/components/app/AntdThemeProvider'
import { StudioNotificationProvider } from '@/hooks/studio/useStudioNotifications'
import { AppBootstrap } from '@/components/app/AppBootstrap'
import { AppShellOverlay } from '@/components/app/AppShellOverlay'
import { LoginModalHost } from '@/components/login/LoginModalHost'
import { RouteGuard } from '@/components/app/RouteGuard'
import { ViewportScaleEffect } from '@/components/app/ViewportScaleEffect'
import OnboardingProvider from '@/components/onboarding/OnboardingProvider'
import { STUDIO_FREE_THEME_BOOTSTRAP_SCRIPT } from '@/utils/studio/studioFreeTheme'
import { AccountSettingsHost } from '@/components/account/AccountSettingsHost'
import { V10_THEME_BOOTSTRAP_SCRIPT } from '@/utils/v10Surface'
import { CreditConsumeHost } from '@/components/common/recharge/CreditConsumeHost'

import './globals.css'
import 'antd/dist/reset.css'
/* 以下引入顺序与原 Nuxt 项目 nuxt.config.ts 的 css 数组一致，顺序即优先级，勿调整 */
import '@/assets/css/root-font-size.css'
import '@/assets/css/main.css'
import '@/assets/css/home-theme.css'
import '@/assets/css/home-legacy-page.css'
import '@/assets/css/home-new-page.css'
import '@/assets/css/home-new-sidebar.css'
import '@/assets/css/home-new-compact-viewport.css'
import '@/assets/css/create-flow-compact-viewport.css'
import '@/assets/css/compact-viewport-btn-radius.css'
import '@/assets/css/create-steps-ant-overrides.css'
import '@/assets/css/login-modal.css'
import '@/assets/css/auth-dark-field.css'
import '@/assets/css/app-confirm-modal.css'
import '@/assets/css/viewport-compact-scale-overrides.css'
import '@/assets/css/viewport-large-scale-overrides.css'
import '@/assets/css/viewport-wide-range.css'
import '@/assets/css/storyboard-step-shared.css'
import '@/assets/css/scp-step-shared.css'
import '@/assets/css/shimmer-image.css'
import '@/assets/css/video-play-btn.css'
import '@/assets/css/asset-card-cancel-hint.css'
import '@/assets/css/empty-image-icon.css'
import '@/assets/css/aid-portal-root.css'
import '@/assets/font/font.css'
import '@/assets/css/v10-ui.css'

const baiduSiteVerification = process.env.NEXT_PUBLIC_BAIDU_SITE_VERIFICATION?.trim()

export const metadata: Metadata = {
  title: {
    default: '视觉AID·AI漫剧工作流引导者',
    template: '%s - 视觉·AID'
  },
  description: '视觉AID是一站式AI漫剧、AI漫画与AI视频创作平台，支持从创意、剧本、角色场景设计、分镜生成、AI绘图、配音到成片输出，帮助个人创作者与内容团队高效完成作品生产',
  keywords: ['AI 视频', 'AI 创作', '剧本', '分镜', '视频生成'],
  robots: { index: true, follow: true },
  openGraph: {
    title: '视觉AID·AI漫剧工作流引导者',
    description: '视觉AID,AI漫剧,AI漫画,AI视频,AI短剧,AI电影,小说推文,AI绘画,AI分镜,AI配音,图生视频,文生视频,一站式AI创作平台',
    type: 'website',
    locale: 'zh_CN'
  },
  twitter: {
    card: 'summary',
    title: '视觉AID - AI漫剧工作流引导者',
    description: '视觉AID是一站式AI漫剧、AI漫画与AI视频创作平台，支持从创意、剧本、角色场景设计、分镜生成、AI绘图、配音到成片输出，帮助个人创作者与内容团队高效完成作品生产'
  },
  icons: {
    icon: [{ url: '/favicon.svg', type: 'image/svg+xml' }],
    apple: [{ url: '/favicon.svg' }]
  },
  ...(baiduSiteVerification
    ? {
        verification: {
          other: {
            'baidu-site-verification': baiduSiteVerification
          }
        }
      }
    : {})
}

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: STUDIO_FREE_THEME_BOOTSTRAP_SCRIPT }} />
        <script dangerouslySetInnerHTML={{ __html: V10_THEME_BOOTSTRAP_SCRIPT }} />
      </head>
      <body>
        <AntdRegistry>
          <AntdThemeProvider>
            <AppBootstrap />
            <ViewportScaleEffect />
            {/* 新手引导全局提供者（原 plugins/onboarding.client.ts + 引导组件挂载） */}
            <OnboardingProvider />
            <StudioNotificationProvider>
              <RouteGuard>{children}</RouteGuard>
            </StudioNotificationProvider>
            <LoginModalHost />
            <AccountSettingsHost />
            <CreditConsumeHost />
            {/* 应用根壳遮罩（原 app.vue）：全局 loading + 跨壳层路由遮罩，仅覆盖不卸载页面子树 */}
            <AppShellOverlay />
          </AntdThemeProvider>
        </AntdRegistry>
        <div id="aid-portal-root" />
      </body>
    </html>
  )
}
