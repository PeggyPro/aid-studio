'use client'

import {
BellOutlined,
DollarCircleOutlined,
HistoryOutlined,
LogoutOutlined,
QuestionCircleOutlined,
SettingOutlined,
WalletOutlined
} from '@ant-design/icons'
import { Switch,Tooltip,message } from 'antd'
import {
forwardRef,
useEffect,
useImperativeHandle,
useRef,
useState,
type CSSProperties
} from 'react'
import { createPortal } from 'react-dom'
import groupAvtorUrl from '~/assets/img/home/Group-avtor.svg'
import submitIconRaw from '~/assets/img/home/icon-sumbit.svg'
import type { FloatingPanelHandle } from '~/components/common/OpenSourcePanel'
import { getAidPortalRoot } from '~/utils/portalRoot'
import { useAuthPublicConfig } from '~/composables/useAuthPublicConfig'
import { useOnboardingEngine } from '~/composables/useOnboardingEngine'
import { useWechatNotifyPreference } from '~/composables/useWechatNotifyPreference'
import { useOnboardingStore } from '~/stores/onboarding'
import { useUserStore } from '~/stores/user'
import { assetUrl } from '~/utils/assetUrl'
import { useUnreadStudioNotifications } from '~/hooks/studio/useStudioNotifications'
import { NotificationBadge } from './NotificationBadge'
import './UserMenuDropdown.css'
import { ThemePreference } from './ThemePreference'

export type { FloatingPanelHandle }

interface UserMenuDropdownProps {
  open: boolean
  /** fixed 定位，由父级根据触发按钮计算 */
  floatingStyle?: Record<string, string>
  onMySubmissions?: () => void
  onFaq?: () => void
  onBilling?: () => void
  onRecharge?: () => void
  onCreditConsume?: () => void
  onNotifications?: () => void
  onAccountSettings?: () => void
  onLogout?: () => void
}

const POP_DURATION_MS = 200

/** 对齐 Vue transition 的 nextFrame：两帧后再切换 class 才能触发过渡 */
function nextFrame(cb: () => void) {
  requestAnimationFrame(() => {
    requestAnimationFrame(cb)
  })
}

const UserMenuDropdown = forwardRef<FloatingPanelHandle, UserMenuDropdownProps>(
  function UserMenuDropdown(
    {
      open,
      floatingStyle = {},
      onMySubmissions,
      onFaq,
      onBilling,
      onRecharge,
      onCreditConsume,
      onNotifications,
      onAccountSettings,
      onLogout
    },
    ref
  ) {
    const user = useUserStore((s) => s.user)
    const unreadCount = useUnreadStudioNotifications()
    const globalDismissed = useOnboardingStore((s) => s.globalDismissed)
    const onboardingEngine = useOnboardingEngine()
    const { loadPublicConfig } = useAuthPublicConfig()
    const {
      showWechatNotifyRow,
      wechatNotifyChecked,
      wechatNotifySwitchDisabled,
      toggling: notifyToggling,
      loadPreference,
      setWechatNotifyEnabled
    } = useWechatNotifyPreference()

    const floatingRootRef = useRef<HTMLDivElement | null>(null)
    // rendered 控制 DOM 存在（含离场动画期间），transitionClass 复刻 Vue transition 各阶段 class
    const [rendered, setRendered] = useState(false)
    const [transitionClass, setTransitionClass] = useState('')
    const renderedRef = useRef(false)
    const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

    useImperativeHandle(
      ref,
      () => ({
        get floatingRoot() {
          return floatingRootRef.current
        }
      }),
      []
    )

    useEffect(() => {
      if (open) {
        void loadPreference(true)
        void loadPublicConfig()
      }
    }, [open, loadPreference, loadPublicConfig])

    useEffect(() => {
      if (timerRef.current) {
        clearTimeout(timerRef.current)
        timerRef.current = null
      }

      if (open) {
        renderedRef.current = true
        setRendered(true)
        setTransitionClass('user-menu-dropdown-pop-enter-from user-menu-dropdown-pop-enter-active')
        nextFrame(() => {
          setTransitionClass('user-menu-dropdown-pop-enter-active')
          timerRef.current = setTimeout(() => {
            timerRef.current = null
            setTransitionClass('')
          }, POP_DURATION_MS)
        })
        return
      }

      if (!renderedRef.current) return
      setTransitionClass('user-menu-dropdown-pop-leave-active user-menu-dropdown-pop-leave-to')
      timerRef.current = setTimeout(() => {
        timerRef.current = null
        renderedRef.current = false
        setRendered(false)
        setTransitionClass('')
      }, POP_DURATION_MS)
    }, [open])

    useEffect(() => {
      return () => {
        if (timerRef.current) clearTimeout(timerRef.current)
      }
    }, [])

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

    const avatar = user?.avatar?.trim()
    const userAvatarUrl = avatar || assetUrl(groupAvtorUrl)
    const submitIconUrl = assetUrl(submitIconRaw)

    const menuCardUserName = (() => {
      const name = user?.nickName?.trim()
      if (name) return name
      if (user?.email?.trim()) return user.email.trim()
      if (user?.id) return `ID${user.id}`
      return '用户'
    })()

    const menuCardUserSub = (() => {
      if (!user?.id) return ''
      if (user.username?.trim()) return `ID:${user.id}`
      return ''
    })()

    const menuCardUserTitle = (() => {
      if (!user) return ''
      const parts = [user.username, user.id ? `ID${user.id}` : '', user.email].filter(
        Boolean
      ) as string[]
      return parts.join(' · ')
    })()

    if (!rendered || typeof document === 'undefined') return null
    const portalRoot = getAidPortalRoot()
    if (!portalRoot) return null

    return createPortal(
      <div
        ref={floatingRootRef}
        className={transitionClass ? `user-menu-dropdown app-account-menu ${transitionClass}` : 'user-menu-dropdown app-account-menu'}
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
                className={
                  wechatNotifySwitchDisabled
                    ? 'user-menu-dropdown__wechat-notify-row is-disabled'
                    : 'user-menu-dropdown__wechat-notify-row'
                }
              >
                <span className="user-menu-dropdown__wechat-notify-label">开启微信推送</span>
                <Switch
                  checked={wechatNotifyChecked}
                  disabled={wechatNotifySwitchDisabled || notifyToggling}
                  loading={notifyToggling}
                  className="user-menu-dropdown__switch"
                  onChange={onWechatNotifyChange}
                  onClick={(_, event) => event.stopPropagation()}
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
            onClick={() => onMySubmissions?.()}
          >
            <img src={submitIconUrl} alt="" width={16} height={16} className="user-menu-dropdown__item-icon" />
            <span>我的投稿</span>
          </button>
          <button
            type="button"
            className="user-menu-dropdown__item"
            role="menuitem"
            onClick={() => onAccountSettings?.()}
          >
            <SettingOutlined aria-hidden />
            <span>账号设置</span>
          </button>
          <button
            type="button"
            className="user-menu-dropdown__item"
            role="menuitem"
            onClick={() => onRecharge?.()}
          >
            <WalletOutlined />
            <span>积分充值</span>
          </button>
          <button
            type="button"
            className="user-menu-dropdown__item"
            role="menuitem"
            onClick={() => onFaq?.()}
          >
            <QuestionCircleOutlined />
            <span>常见问题</span>
          </button>
          <button
            type="button"
            className="user-menu-dropdown__item"
            role="menuitem"
            onClick={() => onBilling?.()}
          >
            <DollarCircleOutlined />
            <span>计费说明</span>
          </button>
          <button
            type="button"
            className="user-menu-dropdown__item"
            role="menuitem"
            onClick={onCreditConsume}
          >
            <HistoryOutlined />
            <span>消耗明细</span>
          </button>
          {onNotifications && <button type="button" className="user-menu-dropdown__item" role="menuitem" onClick={onNotifications}>
            <BellOutlined />
            <span>消息通知</span>
            <NotificationBadge count={unreadCount} />
          </button>}
          {/* 暂时隐藏：关闭引导提示入口 */}
          {false && !globalDismissed ? (
            <button
              type="button"
              className="user-menu-dropdown__item"
              role="menuitem"
              onClick={onDismissGuidance}
            >
              <BellOutlined />
              <span>不再提示引导</span>
            </button>
          ) : null}
          <button
            type="button"
            className="user-menu-dropdown__item user-menu-dropdown__item--danger"
            role="menuitem"
            onClick={() => onLogout?.()}
          >
            <LogoutOutlined />
            <span>退出登录</span>
          </button>
        </div>
      </div>,
      portalRoot
    )
  }
)

export default UserMenuDropdown
