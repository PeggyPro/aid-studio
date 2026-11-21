'use client'

import { useUnreadStudioNotifications } from '@/hooks/studio/useStudioNotifications'
import { NotificationBadge } from '@/components/common/NotificationBadge'

import Link from 'next/link'
import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
  type MouseEvent as ReactMouseEvent
} from 'react'
import AidHoverLogo from '~/components/atoms/AidHoverLogo'
import SidebarNavHoverIcon from '~/components/atoms/SidebarNavHoverIcon'
import OpenSourcePanel, { type FloatingPanelHandle } from '~/components/common/OpenSourcePanel'
import DiscussionGroupPanel from '~/components/common/DiscussionGroupPanel'
import InvitePromoPanel from '~/components/common/InvitePromoPanel'
import starlightCoinUrl from '~/assets/img/home/starlightCoin.svg'
import inviteIconUrl from '~/assets/img/home/pic-Invitation.svg'
import groupAvtorUrl from '~/assets/img/home/Group-avtor.svg'
import { assetUrl } from '~/utils/assetUrl'
import { toLayoutPx } from '~/utils/viewportZoom'
import {
  clearInvitePromoDismiss,
  dismissInvitePromo,
  isInvitePromoDismissed
} from '~/utils/invitePromoDismiss'
import { useUserStore } from '~/stores/user'
import { useAuthPublicConfig } from '~/composables/useAuthPublicConfig'
import { useHomeHeroContentReady } from '~/composables/useHomeHeroContentReady'
import { useOnboardingEngine } from '~/hooks/useOnboardingEngine'
import { formatCreditAmount } from '~/components/common/recharge/rechargeFormat'
import { useV10Theme } from '~/utils/v10ThemePreference'
import '~/components/home/theme/home-theme.css'
import './HomeNewSidebar.css'

/** 首页首屏就绪后再稍等片刻，避免与 Hero 同时抢注意力 */
const INVITE_PROMO_SHOW_DELAY_MS = 800
const compactCreditAmount = new Intl.NumberFormat('zh-CN', {
  notation: 'compact',
  maximumFractionDigits: 0
})

export interface HomeNewSidebarHandle {
  userMenuTriggerRef: HTMLElement | null
  openSourceTriggerRef: HTMLElement | null
  discussionGroupTriggerRef: HTMLElement | null
}

interface HomeNewSidebarProps {
  galleryActive?: boolean
  worksActive?: boolean
  assetsActive?: boolean
  inviteActive?: boolean
  skeleton?: boolean
  /** 首页壳：品牌与案例广场使用 Link；流程页使用 button */
  useRouterLinks?: boolean
  onBrand?: () => void
  onGallery?: () => void
  onWorks?: () => void
  onAssets?: () => void
  onTutorial?: () => void
  onOpenSource?: () => void
  onDiscussionGroup?: () => void
  onInvite?: () => void
  onRecharge?: () => void
  onLogin?: () => void
  onToggleUserMenu?: () => void
  onUserMenuTriggerChange?: (element: HTMLElement | null) => void
}

const HomeNewSidebar = forwardRef<HomeNewSidebarHandle, HomeNewSidebarProps>(
  function HomeNewSidebar(
    {
      galleryActive = false,
      worksActive = false,
      assetsActive = false,
      inviteActive = false,
      skeleton = false,
      useRouterLinks = false,
      onBrand,
      onGallery,
      onWorks,
      onAssets,
      onTutorial: _onTutorial,
      onOpenSource,
      onDiscussionGroup,
      onInvite,
      onRecharge,
      onLogin,
      onToggleUserMenu,
      onUserMenuTriggerChange
    },
    ref
  ) {
    const theme = useV10Theme()
    const token = useUserStore((s) => s.token)
    const user = useUserStore((s) => s.user)
    const {
      openSourceGiteeUrl,
      openSourceGitUrl,
      exchangeImageUrl,
      invitePromotionEnabled,
      inviteRebateRatioText,
      loadPublicConfig
    } = useAuthPublicConfig()

    const isLoggedIn = !!token
    const unreadCount = useUnreadStudioNotifications()
    const userId = String(user?.id ?? '').trim()
    const userAvatarUrl =
      (isLoggedIn ? user?.avatar?.trim() : '') || assetUrl(groupAvtorUrl)
    const balance = Number(user?.balance ?? 0)
    const displayPoints = formatCreditAmount(balance)
    const railPoints = Math.abs(balance) >= 1000 ? compactCreditAmount.format(balance) : displayPoints
    void _onTutorial

    const showOpenSourceNav = !skeleton && (!!openSourceGiteeUrl || !!openSourceGitUrl)
    const showDiscussionGroupNav = !skeleton && !!exchangeImageUrl

    const userMenuTriggerRef = useRef<HTMLButtonElement | null>(null)
    const setUserMenuTriggerRef = useCallback(
      (element: HTMLButtonElement | null) => {
        userMenuTriggerRef.current = element
        onUserMenuTriggerChange?.(element)
      },
      [onUserMenuTriggerChange]
    )
    const openSourceTriggerRef = useRef<HTMLButtonElement | null>(null)
    const openSourcePanelRef = useRef<FloatingPanelHandle | null>(null)
    const [showOpenSourcePanel, setShowOpenSourcePanel] = useState(false)
    const [openSourcePanelStyle, setOpenSourcePanelStyle] = useState<Record<string, string>>({})

    const discussionGroupTriggerRef = useRef<HTMLButtonElement | null>(null)
    const discussionGroupPanelRef = useRef<FloatingPanelHandle | null>(null)
    const [showDiscussionGroupPanel, setShowDiscussionGroupPanel] = useState(false)
    const [discussionGroupPanelStyle, setDiscussionGroupPanelStyle] = useState<
      Record<string, string>
    >({})

    const { homeHeroContentReady } = useHomeHeroContentReady()

    const { activeSession, paused, versionUpgradePrompt } = useOnboardingEngine()
    const [invitePromo, setInvitePromo] = useState<{ userId: string; open: boolean } | null>(null)
    const invitePromoForUser = invitePromo?.userId === userId ? invitePromo : null
    const canShowInvitePromo = Boolean(
      !skeleton && galleryActive && homeHeroContentReady && isLoggedIn && userId &&
      invitePromotionEnabled && !activeSession && !paused && !versionUpgradePrompt
    )

    useEffect(() => {
      if (!canShowInvitePromo || invitePromoForUser || isInvitePromoDismissed(userId)) return
      const timer = window.setTimeout(() => {
        setInvitePromo((current) => current?.userId === userId
          ? current
          : { userId, open: !isInvitePromoDismissed(userId) })
      }, INVITE_PROMO_SHOW_DELAY_MS)
      return () => window.clearTimeout(timer)
    }, [canShowInvitePromo, invitePromoForUser, userId])

    useImperativeHandle(
      ref,
      () => ({
        get userMenuTriggerRef() {
          return userMenuTriggerRef.current
        },
        get openSourceTriggerRef() {
          return openSourceTriggerRef.current
        },
        get discussionGroupTriggerRef() {
          return discussionGroupTriggerRef.current
        }
      }),
      []
    )

    function onBrandClick(event: ReactMouseEvent) {
      if (useRouterLinks) return
      event.preventDefault()
      onBrand?.()
    }

    function updateOpenSourcePanelPosition() {
      const trigger = openSourceTriggerRef.current
      if (!trigger) return
      const rect = trigger.getBoundingClientRect()
      setOpenSourcePanelStyle({
        left: `${toLayoutPx(rect.right + 12)}px`,
        top: `${toLayoutPx(rect.top)}px`
      })
    }

    function updateDiscussionGroupPanelPosition() {
      const trigger = discussionGroupTriggerRef.current
      if (!trigger) return
      const rect = trigger.getBoundingClientRect()
      setDiscussionGroupPanelStyle({
        left: `${toLayoutPx(rect.right + 12)}px`,
        top: `${toLayoutPx(rect.top)}px`
      })
    }

    function closeOpenSourcePanel() {
      setShowOpenSourcePanel(false)
    }

    function closeDiscussionGroupPanel() {
      setShowDiscussionGroupPanel(false)
    }

    function onInvitePromoClose() {
      if (userId) dismissInvitePromo(userId)
      setInvitePromo({ userId, open: false })
    }

    function onInvitePromoInvite() {
      onInvitePromoClose()
      onInvite?.()
    }

    function toggleOpenSourcePanel() {
      const next = !showOpenSourcePanel
      setShowOpenSourcePanel(next)
      if (next) {
        closeDiscussionGroupPanel()
        requestAnimationFrame(() => updateOpenSourcePanelPosition())
      }
      onOpenSource?.()
    }

    function toggleDiscussionGroupPanel() {
      const next = !showDiscussionGroupPanel
      setShowDiscussionGroupPanel(next)
      if (next) {
        closeOpenSourcePanel()
        requestAnimationFrame(() => updateDiscussionGroupPanelPosition())
      }
      onDiscussionGroup?.()
    }

    function handleDocumentClick(event: MouseEvent) {
      const target = event.target as Node | null
      if (!target) return

      if (showOpenSourcePanel) {
        if (!openSourceTriggerRef.current?.contains(target)) {
          const floating = openSourcePanelRef.current?.floatingRoot
          if (!floating?.contains(target)) closeOpenSourcePanel()
        }
      }

      if (showDiscussionGroupPanel) {
        if (!discussionGroupTriggerRef.current?.contains(target)) {
          const floating = discussionGroupPanelRef.current?.floatingRoot
          if (!floating?.contains(target)) closeDiscussionGroupPanel()
        }
      }
    }

    function updateFloatingPanelsPosition() {
      if (showOpenSourcePanel) updateOpenSourcePanelPosition()
      if (showDiscussionGroupPanel) updateDiscussionGroupPanelPosition()
    }

    // 全局监听读取最新一轮渲染的浮层状态。
    const latestRef = useRef({
      handleDocumentClick,
      updateFloatingPanelsPosition
    })
    useEffect(() => {
      latestRef.current = {
        handleDocumentClick,
        updateFloatingPanelsPosition
      }
    })

    useEffect(() => {
      if (skeleton) return
      void loadPublicConfig()
      const onDocumentClick = (event: MouseEvent) => latestRef.current.handleDocumentClick(event)
      const onReposition = () => latestRef.current.updateFloatingPanelsPosition()
      document.addEventListener('click', onDocumentClick)
      window.addEventListener('resize', onReposition)
      window.addEventListener('scroll', onReposition, true)

      return () => {
        document.removeEventListener('click', onDocumentClick)
        window.removeEventListener('resize', onReposition)
        window.removeEventListener('scroll', onReposition, true)
      }
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    useEffect(() => {
      if (!showOpenSourceNav) closeOpenSourcePanel()
    }, [showOpenSourceNav])

    useEffect(() => {
      if (!showDiscussionGroupNav) closeDiscussionGroupPanel()
    }, [showDiscussionGroupNav])

    // 对齐 Vue watch(isLoggedIn, (now, was))：登出瞬间清理邀请卡状态
    const wasLoggedInRef = useRef(isLoggedIn)
    useEffect(() => {
      const wasLoggedIn = wasLoggedInRef.current
      wasLoggedInRef.current = isLoggedIn
      if (wasLoggedIn && !isLoggedIn) {
        clearInvitePromoDismiss()
        setInvitePromo(null)
      }
    }, [isLoggedIn])

    const brandChildren = <AidHoverLogo className="home-new-logo" alt="AID" />

    return (
      <aside
        className={skeleton ? 'home-new-sidebar create-sidebar--skeleton' : 'home-new-sidebar'}
        data-home-theme={theme}
        aria-label={skeleton ? undefined : '主导航'}
        aria-hidden={skeleton ? true : undefined}
      >
        {skeleton ? (
          <>
            <div className="skeleton-sidebar-logo" />
            {Array.from({ length: 5 }, (_, i) => (
              <div key={i + 1} className="skeleton-sidebar-nav" />
            ))}
          </>
        ) : (
          <>
            {useRouterLinks ? (
              <Link href="/" prefetch={false} className="home-new-brand" aria-label="首页" onClick={onBrandClick}>
                {brandChildren}
              </Link>
            ) : (
              <button
                type="button"
                className="home-new-brand"
                aria-label="首页"
                onClick={onBrandClick}
              >
                {brandChildren}
              </button>
            )}

            <nav className="home-new-nav" aria-label="主要导航">
              <button
                type="button"
                className={galleryActive ? 'home-new-nav-item is-active' : 'home-new-nav-item'}
                onClick={() => onGallery?.()}
              >
                <SidebarNavHoverIcon type="gallery" className="home-new-nav-ico" />
                <span>案例广场</span>
              </button>
              <button
                type="button"
                className={worksActive ? 'home-new-nav-item is-active' : 'home-new-nav-item'}
                aria-current={worksActive ? 'page' : undefined}
                onClick={() => onWorks?.()}
              >
                <SidebarNavHoverIcon type="works" className="home-new-nav-ico" />
                <span>我的作品</span>
              </button>
              <button
                type="button"
                className={assetsActive ? 'home-new-nav-item is-active' : 'home-new-nav-item'}
                onClick={() => onAssets?.()}
              >
                <SidebarNavHoverIcon type="assets" className="home-new-nav-ico" />
                <span>资产库</span>
              </button>
            </nav>

            <div className="home-new-nav-divider" aria-hidden="true" />

            <nav className="home-new-nav home-new-nav--secondary" aria-label="帮助与资源">
              {showOpenSourceNav ? (
                <button
                  ref={openSourceTriggerRef}
                  type="button"
                  className={
                    showOpenSourcePanel ? 'home-new-nav-item is-active' : 'home-new-nav-item'
                  }
                  data-onboarding="nav-open-source"
                  aria-haspopup="dialog"
                  aria-expanded={showOpenSourcePanel}
                  onClick={toggleOpenSourcePanel}
                >
                  <SidebarNavHoverIcon type="open-source" className="home-new-nav-ico" />
                  <span>开源</span>
                </button>
              ) : null}
              {showDiscussionGroupNav ? (
                <button
                  ref={discussionGroupTriggerRef}
                  type="button"
                  className={
                    showDiscussionGroupPanel ? 'home-new-nav-item is-active' : 'home-new-nav-item'
                  }
                  data-onboarding="nav-discussion-group"
                  aria-haspopup="dialog"
                  aria-expanded={showDiscussionGroupPanel}
                  onClick={toggleDiscussionGroupPanel}
                >
                  <SidebarNavHoverIcon type="discussion" className="home-new-nav-ico" />
                  <span>交流群</span>
                </button>
              ) : null}
            </nav>
          </>
        )}

        <div className="home-new-sidebar-spacer" aria-hidden="true" />

        <div className="home-new-sidebar-bottom">
          {skeleton ? (
            <div className="skeleton-sidebar-footer" />
          ) : (
            <>
              {invitePromotionEnabled ? (
                <button
                  type="button"
                  className={`home-new-invite-gift${inviteActive ? ' is-active' : ''}`}
                  aria-current={inviteActive ? 'page' : undefined}
                  onClick={onInvitePromoInvite}
                >
                  <img src={assetUrl(inviteIconUrl)} alt="邀请有礼" width={60} height={46} />
                </button>
              ) : null}
              <div className={isLoggedIn ? 'home-new-user' : 'home-new-user home-new-user--guest'}>
                <div className="home-new-credit-card" role="group" aria-label="积分与充值">

                  <span className="home-new-credit-card__balance">
                    <img src={assetUrl(starlightCoinUrl)} alt="" className="home-new-points-ico" width={10} height={10} />
                    <span className="home-new-points-num">{isLoggedIn ? railPoints : '积分'}</span>
                  </span>
                  <button type="button" className="home-new-credit-card__recharge" onClick={onRecharge}
                    aria-label={isLoggedIn ? `积分充值，当前余额 ${displayPoints}` : '登录后充值积分'}>积分充值</button>
                </div>
                <button
                  ref={setUserMenuTriggerRef}
                  type="button"
                  className="home-new-user-btn"
                  aria-label={isLoggedIn ? '打开用户菜单' : '登录'}
                  aria-description={isLoggedIn && unreadCount > 0 ? `${unreadCount} 条未读通知` : undefined}
                  title={isLoggedIn ? undefined : '点击登录'}
                  onClick={() => (isLoggedIn ? onToggleUserMenu?.() : onLogin?.())}
                >
                  <span className="home-new-avatar-frame app-notification-anchor">
                    <img
                      src={userAvatarUrl}
                      alt=""
                      className="home-new-avatar-img"
                      width={40}
                      height={40}
                    />
                    <NotificationBadge count={unreadCount} dot />
                  </span>
                </button>
              </div>
            </>
          )}
        </div>

        {!skeleton ? (
          <>
            <OpenSourcePanel
              ref={openSourcePanelRef}
              open={showOpenSourcePanel}
              floatingStyle={openSourcePanelStyle}
              giteeUrl={openSourceGiteeUrl}
              gitUrl={openSourceGitUrl}
            />
            <DiscussionGroupPanel
              ref={discussionGroupPanelRef}
              open={showDiscussionGroupPanel}
              floatingStyle={discussionGroupPanelStyle}
              qrImageUrl={exchangeImageUrl}
            />
            {canShowInvitePromo && invitePromoForUser?.open ? (
              <InvitePromoPanel
                rebateRatioText={inviteRebateRatioText}
                onClose={onInvitePromoClose}
                onInvite={onInvitePromoInvite}
              />
            ) : null}
          </>
        ) : null}
      </aside>
    )
  }
)

export default HomeNewSidebar
