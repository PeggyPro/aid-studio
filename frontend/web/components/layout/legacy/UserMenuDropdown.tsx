'use client'

import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react'
import type { CSSProperties } from 'react'
import { createPortal } from 'react-dom'
import { Switch, Tooltip, message } from 'antd'
import {
  QuestionCircleOutlined,
  DollarCircleOutlined,
  WalletOutlined,
  HistoryOutlined,
  LogoutOutlined,
  BellOutlined,
  GiftOutlined
} from '@ant-design/icons'
import { useOnboardingEngine } from '~/composables/useOnboardingEngine'
import { useOnboardingStore } from '~/stores/onboarding'
import { useAuthPublicConfig } from '~/composables/useAuthPublicConfig'
import { useWechatNotifyPreference } from '~/composables/useWechatNotifyPreference'
import { useUserStore } from '~/stores/user'
import { assetUrl } from '~/utils/assetUrl'
import { useUnreadStudioNotifications } from '~/hooks/studio/useStudioNotifications'
import { NotificationBadge } from '~/components/common/NotificationBadge'
import groupAvtorMod from '~/assets/img/home/Group-avtor.svg'
import './UserMenuDropdown.css'
import { ThemePreference } from '~/components/common/ThemePreference'

const groupAvtorUrl = assetUrl(groupAvtorMod)

export interface UserMenuDropdownProps {
  open: boolean
  /** fixed 定位，由父级根据触发按钮计算 */
  floatingStyle?: Record<string, string>
  onFaq: () => void
  onBilling: () => void
  onRecharge: () => void
  onCreditConsume: () => void
  onNotifications: () => void
  onInvite: () => void
  onLogout: () => void
}

/** 原 defineExpose({ floatingRoot })：父级点击外部判定需要读浮层根节点 */
export interface UserMenuDropdownHandle {
  floatingRoot: HTMLElement | null
}

export const UserMenuDropdown = forwardRef<UserMenuDropdownHandle, UserMenuDropdownProps>(
  function UserMenuDropdown(
    { open, floatingStyle = {}, onFaq, onBilling, onRecharge, onCreditConsume, onNotifications, onInvite, onLogout },
    ref
  ) {
    const user = useUserStore((s) => s.user)
    const unreadCount = useUnreadStudioNotifications()
    const globalDismissed = useOnboardingStore((s) => s.globalDismissed)
    const onboardingEngine = useOnboardingEngine()
    const { invitePromotionEnabled, loadPublicConfig } = useAuthPublicConfig()
    const {
      showWechatNotifyRow,
      wechatNotifyChecked,
      wechatNotifySwitchDisabled,
      toggling: notifyToggling,
      loadPreference,
      setWechatNotifyEnabled
    } = useWechatNotifyPreference()

    useEffect(() => {
      if (open) {
        void loadPreference(true)
        void loadPublicConfig()
      }
    }, [open, loadPreference, loadPublicConfig])

    async function onWechatNotifyChange(checked: boolean | string | number) {
      const result = await setWechatNotifyEnabled(Boolean(checked))
      if (!result.ok && result.message) {
        message.error(result.message)
      }
    }

    async function onDismissGuidance() {
      await onboardingEngine.setGlobalDismissed(true)
      message.success('已关闭新手引导')
    }

    const userAvatarUrl = user?.avatar?.trim() || groupAvtorUrl

    const menuCardUserName = (() => {
      const u = user
      const name = u?.nickName?.trim()
      if (name) return name
      if (u?.email?.trim()) return u.email.trim()
      if (u?.id) return `ID${u.id}`
      return '用户'
    })()

    const menuCardUserSub = (() => {
      const u = user
      if (!u?.id) return ''
      if (u.username?.trim()) return `ID:${u.id}`
      return ''
    })()

    const menuCardUserTitle = (() => {
      const u = user
      if (!u) return ''
      const parts = [u.username, u.id ? `ID${u.id}` : '', u.email].filter(Boolean) as string[]
      return parts.join(' · ')
    })()

    const floatingRootRef = useRef<HTMLDivElement | null>(null)

    useImperativeHandle(ref, () => ({
      get floatingRoot() {
        return floatingRootRef.current
      }
    }))

    if (!open || typeof document === 'undefined') return null

    return createPortal(
      <div
        ref={floatingRootRef}
        className="user-menu-dropdown"
        style={floatingStyle as CSSProperties}
        role="menu"
        aria-label="用户菜单"
      >
        <div className="user-menu-dropdown__header">
          <div className="user-menu-dropdown__avatar" aria-hidden="true">
            <img src={userAvatarUrl} alt="" width={44} height={44} />
          </div>
          <div className="user-menu-dropdown__meta">
            <span className="user-menu-dropdown__name" title={menuCardUserTitle}>
              {menuCardUserName}
            </span>
            {menuCardUserSub ? (
              <span className="user-menu-dropdown__sub">{menuCardUserSub}</span>
            ) : null}
          </div>
        </div>
        {showWechatNotifyRow ? (
          <div className="user-menu-dropdown__wechat-notify">
            <Tooltip title={wechatNotifySwitchDisabled ? '请先绑定微信' : ''} placement="top">
              <div
                className={`user-menu-dropdown__wechat-notify-row${wechatNotifySwitchDisabled ? ' is-disabled' : ''}`}
              >
                <span className="user-menu-dropdown__wechat-notify-label">开启微信推送</span>
                <Switch
                  checked={wechatNotifyChecked}
                  disabled={wechatNotifySwitchDisabled || notifyToggling}
                  loading={notifyToggling}
                  className="user-menu-dropdown__switch"
                  onChange={(checked) => void onWechatNotifyChange(checked)}
                  onClick={(_checked, event) => event?.stopPropagation()}
                />
              </div>
            </Tooltip>
          </div>
        ) : null}
        <div className="user-menu-dropdown__list">
          <ThemePreference />
          <button
            type="button"
            className="user-menu-dropdown__item"
            role="menuitem"
            onClick={onRecharge}
          >
            <WalletOutlined aria-hidden />
            <span>积分充值</span>
          </button>
          {invitePromotionEnabled ? (
            <button
              type="button"
              className="user-menu-dropdown__item"
              role="menuitem"
              onClick={onInvite}
            >
              <GiftOutlined aria-hidden />
              <span>邀请有礼</span>
            </button>
          ) : null}
          <button type="button" className="user-menu-dropdown__item" role="menuitem" onClick={onFaq}>
            <QuestionCircleOutlined aria-hidden />
            <span>常见问题</span>
          </button>
          <button
            type="button"
            className="user-menu-dropdown__item"
            role="menuitem"
            onClick={onBilling}
          >
            <DollarCircleOutlined aria-hidden />
            <span>计费说明</span>
          </button>
          <button
            type="button"
            className="user-menu-dropdown__item"
            role="menuitem"
            onClick={onCreditConsume}
          >
            <HistoryOutlined aria-hidden />
            <span>消耗明细</span>
          </button>
          <button type="button" className="user-menu-dropdown__item" role="menuitem" onClick={onNotifications}>
            <BellOutlined />
            <span>消息通知</span>
            <NotificationBadge count={unreadCount} />
          </button>
          {/* 暂时隐藏：关闭引导提示入口 */}
          {false && !globalDismissed ? (
            <button
              type="button"
              className="user-menu-dropdown__item"
              role="menuitem"
              onClick={() => void onDismissGuidance()}
            >
              <BellOutlined aria-hidden />
              <span>不再提示引导</span>
            </button>
          ) : null}
          <button
            type="button"
            className="user-menu-dropdown__item user-menu-dropdown__item--danger"
            role="menuitem"
            onClick={onLogout}
          >
            <LogoutOutlined aria-hidden />
            <span>退出登录</span>
          </button>
        </div>
      </div>,
      document.body
    )
  }
)

export default UserMenuDropdown
