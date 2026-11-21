'use client'

/* eslint-disable @next/next/no-img-element -- 用户头像来自运行时账户资料。 */

import {
  BgColorsOutlined,
  BellOutlined,
  CreditCardOutlined,
  FolderOpenOutlined,
  LogoutOutlined,
  SafetyCertificateOutlined,
  TeamOutlined,
  UserOutlined,
  WalletOutlined
} from '@ant-design/icons'
import { Modal } from 'antd'
import { useRouter } from 'next/navigation'
import { useState, type RefObject } from 'react'
import defaultAvatarRaw from '@/assets/img/home/Group-avtor.svg'
import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { useStudioFreeTheme } from '@/components/studio/theme/StudioFreeThemeProvider'
import type { CanvasProjectWorkspaceRuntime } from '@/hooks/studio/useCanvasProjectWorkspace'
import { openAccountSettings } from '@/stores/accountSettings'
import { useUserStore } from '@/stores/user'
import { assetUrl } from '@/utils/assetUrl'
import './studio-user-menu.css'
import { useStudioSkillWorkspace } from '@/components/studio/skills/StudioSkillWorkspaceProvider'
import { StudioIconGlyph } from '@/components/studio/theme/StudioIconGlyph'
import { useStudioNotifications, useUnreadStudioNotifications } from '@/hooks/studio/useStudioNotifications'
import { NotificationBadge } from '@/components/common/NotificationBadge'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'

function userDisplayName(user: ReturnType<typeof useUserStore.getState>['user']) {
  return user?.nickName?.trim() || user?.username?.trim() || user?.email?.trim() || (user?.id ? `用户 ${user.id}` : 'AID 用户')
}

function expiryText(value?: string | null) {
  if (!value) return '当前账户权益'
  const date = new Date(value.replace(/-/g, '/'))
  if (Number.isNaN(date.getTime())) return `有效期至 ${value}`
  return `有效期至 ${date.toLocaleDateString('zh-CN')}`
}

export function StudioUserMenu({
  canvasRuntime,
  triggerRef,
  onOpenCollaboration
}: {
  canvasRuntime?: CanvasProjectWorkspaceRuntime | null
  triggerRef: RefObject<HTMLButtonElement | null>
  onOpenCollaboration: () => void
}) {
  const router = useRouter()
  const user = useUserStore((state) => state.user)
  const { theme, isAutomatic, toggleTheme } = useStudioFreeTheme()
  const [open, setOpen] = useState(false)
  const skills = useStudioSkillWorkspace()
  const notifications = useStudioNotifications()
  const unreadCount = useUnreadStudioNotifications()
  const avatarUrl = user?.avatar?.trim() || assetUrl(defaultAvatarRaw)
  const name = userDisplayName(user)
  const accountLabel = user?.email?.trim() || user?.username?.trim() || (user?.id ? `ID ${user.id}` : '已登录')
  const themeName = isAutomatic ? `自动 · ${theme === 'opal' ? '月白' : '柔雾'}` : theme === 'opal' ? '月白' : '柔雾'
  const onlineCount = canvasRuntime?.collaboration?.onlineUsers?.length || 0

  const closeThen = (action: () => void) => {
    setOpen(false)
    action()
  }

  const logout = () => {
    setOpen(false)
    Modal.confirm({
      className: 'create-flow-modal',
      title: '确认退出登录',
      content: '退出后需要重新登录，是否继续？',
      okText: '退出登录',
      cancelText: '取消',
      centered: true,
      onOk: () => {
        useUserStore.getState().logout()
        router.push('/')
      }
    })
  }

  const panel = (
    <section className="studio-user-menu" aria-label="账户菜单">
      <header className="studio-user-menu__profile">
        <span className="studio-user-menu__avatar studio-user-menu__avatar--large" aria-hidden="true">
          <img src={avatarUrl} alt="" />
        </span>
        <span className="studio-user-menu__identity">
          <strong title={name}>{name}</strong>
          <small title={accountLabel}>{accountLabel}</small>
        </span>
        <button
          type="button"
          className="studio-user-menu__account-shortcut"
          onClick={() => closeThen(() => openAccountSettings())}
        >
          账户设置
        </button>
      </header>

      <div className="studio-user-menu__benefits">
        <span>
          <small>账户权益</small>
          <strong>{user?.memberLevelName?.trim() || '免费用户'}</strong>
          <em>{expiryText(user?.memberExpireTime)}</em>
        </span>
        <span>
          <small>积分余额</small>
          <strong>{Number(user?.balance ?? 0).toLocaleString('zh-CN')}</strong>
          <button type="button" onClick={() => closeThen(() => window.dispatchEvent(new Event('open-recharge-modal')))}>充值</button>
        </span>
      </div>

      <nav className="studio-user-menu__list" aria-label="账户功能">
        <StudioCanvasTooltip title={!notifications.connected ? '连接画布服务后查看通知' : '查看官方通知与服务公告'}>
          <button type="button" disabled={!notifications.connected} onClick={() => closeThen(notifications.client.open)}>
            <span className="studio-user-menu__item-icon" aria-hidden><BellOutlined /></span><span className="studio-user-menu__item-copy"><strong>通知</strong><small>官方消息与服务公告</small></span>
            <NotificationBadge count={unreadCount} />
          </button>
        </StudioCanvasTooltip>
        {skills ? <button type="button" onClick={() => closeThen(skills.openManager)}><span className="studio-user-menu__item-icon" aria-hidden><StudioIconGlyph name="skill" /></span><span className="studio-user-menu__item-copy"><strong>我的 Skill</strong><small>文件、版本、封面与公开设置</small></span></button> : null}
        {canvasRuntime?.project ? (
          <button type="button" onClick={() => closeThen(onOpenCollaboration)}>
            <span className="studio-user-menu__item-icon" aria-hidden><TeamOutlined /></span><span className="studio-user-menu__item-copy"><strong>协作管理</strong><small>成员、邀请、分享与权限</small></span>
            {onlineCount ? <em>{onlineCount}</em> : null}
          </button>
        ) : null}
        <button type="button" onClick={() => closeThen(() => openAccountSettings())}>
          <span className="studio-user-menu__item-icon" aria-hidden><UserOutlined /></span><span className="studio-user-menu__item-copy"><strong>个人中心</strong><small>账号与安全设置</small></span>
        </button>
        <button type="button" onClick={() => closeThen(() => router.push('/works?tab=canvas&from=studio'))}>
          <span className="studio-user-menu__item-icon" aria-hidden><FolderOpenOutlined /></span><span className="studio-user-menu__item-copy"><strong>我的作品</strong><small>查看画布与创作项目</small></span>
        </button>
        <button type="button" onClick={() => closeThen(() => router.push('/billing'))}>
          <span className="studio-user-menu__item-icon" aria-hidden><CreditCardOutlined /></span><span className="studio-user-menu__item-copy"><strong>计费说明</strong><small>查看积分与计费规则</small></span>
        </button>
        <button type="button" onClick={toggleTheme}>
          <span className="studio-user-menu__item-icon" aria-hidden><BgColorsOutlined /></span><span className="studio-user-menu__item-copy"><strong>界面风格</strong><small>{themeName}</small></span>
          <i aria-hidden>{theme === 'opal' ? '明' : '暗'}</i>
        </button>
        <button type="button" onClick={() => closeThen(() => openAccountSettings('sessions'))}>
          <span className="studio-user-menu__item-icon" aria-hidden><SafetyCertificateOutlined /></span><span className="studio-user-menu__item-copy"><strong>登录设备</strong><small>管理登录会话</small></span>
        </button>
        <button type="button" onClick={() => closeThen(() => window.dispatchEvent(new Event('open-recharge-modal')))}>
          <span className="studio-user-menu__item-icon" aria-hidden><WalletOutlined /></span><span className="studio-user-menu__item-copy"><strong>积分充值</strong><small>补充创作积分</small></span>
        </button>
        <button type="button" className="studio-user-menu__logout" onClick={logout}>
          <span className="studio-user-menu__item-icon" aria-hidden><LogoutOutlined /></span><span className="studio-user-menu__item-copy"><strong>退出登录</strong></span>
        </button>
      </nav>
    </section>
  )

  return (
    <StudioCanvasPopover
      open={open}
      onOpenChange={setOpen}
      trigger="click"
      placement="bottomRight"
      arrow={false}
      content={panel}
      classNames={{ root: 'studio-user-menu-popover' }}
    >
      <button
        ref={triggerRef}
        type="button"
        className="studio-topbar__avatar-trigger"
        aria-label="打开账户菜单"
        aria-description={unreadCount > 0 ? `${unreadCount} 条未读通知` : undefined}
        aria-haspopup="dialog"
        aria-expanded={open}
      >
        <span className="studio-user-menu__avatar" aria-hidden="true"><img src={avatarUrl} alt="" /></span>
        <NotificationBadge count={unreadCount} dot />
      </button>
    </StudioCanvasPopover>
  )
}
