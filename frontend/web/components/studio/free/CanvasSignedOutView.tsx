'use client'

import { Dropdown } from 'antd'
import { DownOutlined, HomeOutlined, UserOutlined } from '@ant-design/icons'
import { useRouter } from 'next/navigation'
import AidHoverLogo from '@/components/atoms/AidHoverLogo'
import { openLoginModal } from '@/stores/loginModal'
import { StudioFreeThemeProvider } from '@/components/studio/theme/StudioFreeThemeProvider'
import './canvas-signed-out.css'

export function CanvasSignedOutView() {
  const router = useRouter()

  return <StudioFreeThemeProvider enabled syncWithSiteTheme>
    <main className="studio-workspace studio-workspace--free canvas-signed-out h-dvh w-full" aria-label="自由画布登出状态">
      <header className="studio-topbar">
        <div className="studio-topbar__left">
          <Dropdown trigger={['click']} placement="bottomLeft" menu={{
            className: 'studio-toolbar-more-menu studio-toolbar-more-menu--canvas',
            items: [{ key: 'home', label: '返回首页', icon: <HomeOutlined />, onClick: () => router.push('/') }]
          }}>
            <button type="button" className="studio-topbar__aid-trigger canvas-signed-out__home" aria-label="作品菜单">
              <AidHoverLogo className="studio-topbar__aid-logo" alt="AID" />
              <DownOutlined className="studio-topbar__project-chevron" aria-hidden />
            </button>
          </Dropdown>
          <span className="studio-topbar__project-divider" aria-hidden />
          <span className="canvas-signed-out__title">自由画布</span>
        </div>
        <div className="studio-topbar__right">
          <button type="button" className="canvas-signed-out__login" aria-label="登录账号" onClick={() => openLoginModal({ tab: 'password', redirect: null })}>
            <UserOutlined aria-hidden /> <span>登录</span>
          </button>
        </div>
      </header>
      <section className="canvas-signed-out__center" aria-live="polite">
        <p>登录后继续创作</p>
        <span>当前画布会话已结束。点击右上角登录账号，返回当前画布。</span>
      </section>
    </main>
  </StudioFreeThemeProvider>
}
