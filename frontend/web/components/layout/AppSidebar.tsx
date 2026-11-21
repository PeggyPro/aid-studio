'use client'

import { Modal, message } from 'antd'
import dynamic from 'next/dynamic'
import { usePathname, useRouter } from 'next/navigation'
import { useEffect, useRef, useState } from 'react'
import { openCreditConsume } from '~/components/common/recharge/CreditConsumeHost'
import UserMenuDropdown, { type FloatingPanelHandle } from '~/components/common/UserMenuDropdown'
import HomeNewSidebar, { type HomeNewSidebarHandle } from '~/components/layout/HomeNewSidebar'
import { useHomeGuestAccess } from '~/hooks/useHomeGuestAccess'
import { useOnboardingModalPause } from '~/hooks/useOnboardingModalPause'
import { useStudioNotifications } from '~/hooks/studio/useStudioNotifications'
import { openAccountSettings } from '~/stores/accountSettings'
import { openLoginModal } from '~/stores/loginModal'
import { useUserStore } from '~/stores/user'
import { retainFloatingPosition } from '~/utils/reactUpdateGuards'
import { toLayoutPx } from '~/utils/viewportZoom'

const RechargeModal = dynamic(() => import('~/components/common/RechargeModal'), { ssr: false })

export function AppSidebar({ flow = false, navigate }: {
  flow?: boolean
  navigate?: (href: string) => void
}) {
  const pathname = usePathname() ?? '/'
  const router = useRouter()
  const token = useUserStore((state) => state.token)
  const { goToLogin } = useHomeGuestAccess()
  const { setPaused } = useOnboardingModalPause()
  const notifications = useStudioNotifications()
  const sidebar = useRef<HomeNewSidebarHandle>(null)
  const dropdown = useRef<FloatingPanelHandle>(null)
  const [menu, setMenu] = useState<{ path: string; token: string; position: Record<string, string> } | null>(null)
  const [recharge, setRecharge] = useState(false)
  const [rechargeReady, setRechargeReady] = useState(false)
  const menuOpen = Boolean(menu && token && menu.token === token && menu.path === pathname)
  const gallery = pathname === '/'

  useEffect(() => {
    if (recharge) setRechargeReady(true)
  }, [recharge])

  useEffect(() => {
    setPaused('recharge', recharge)
    return () => setPaused('recharge', false)
  }, [recharge, setPaused])
  useEffect(() => {
    setPaused('user-menu', menuOpen)
    return () => setPaused('user-menu', false)
  }, [menuOpen, setPaused])

  useEffect(() => {
    function openRecharge() {
      if (useUserStore.getState().token) setRecharge(true)
      else if (flow) openLoginModal()
      else goToLogin()
    }
    window.addEventListener('open-recharge-modal', openRecharge)
    return () => window.removeEventListener('open-recharge-modal', openRecharge)
  }, [flow, goToLogin])

  function getMenuPosition() {
    const rect = sidebar.current?.userMenuTriggerRef?.getBoundingClientRect()
    return rect ? { left: toLayoutPx(rect.right + 10) + 'px', top: toLayoutPx(rect.bottom) + 'px' } : null
  }

  useEffect(() => {
    if (!menuOpen) return
    function closeOnOutside(event: PointerEvent) {
      if (!(event.target instanceof Node)) return
      if (sidebar.current?.userMenuTriggerRef?.contains(event.target) || dropdown.current?.floatingRoot?.contains(event.target)) return
      setMenu(null)
    }
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key !== 'Escape') return
      setMenu(null)
      sidebar.current?.userMenuTriggerRef?.focus()
    }
    function reposition() {
      const position = getMenuPosition()
      if (position) setMenu((current) => {
        if (!current) return current
        const next = retainFloatingPosition(current.position, position)
        return next === current.position ? current : { ...current, position: next }
      })
    }
    document.addEventListener('pointerdown', closeOnOutside)
    document.addEventListener('keydown', closeOnEscape)
    window.addEventListener('resize', reposition)
    window.addEventListener('scroll', reposition, true)
    return () => {
      document.removeEventListener('pointerdown', closeOnOutside)
      document.removeEventListener('keydown', closeOnEscape)
      window.removeEventListener('resize', reposition)
      window.removeEventListener('scroll', reposition, true)
    }
  }, [menuOpen])

  function go(href: string, requiresLogin = false) {
    setMenu(null)
    if (requiresLogin && !token) {
      if (flow) openLoginModal({ redirect: href })
      else goToLogin()
      return
    }
    if (navigate) navigate(href)
    else router.push(href)
  }

  function openGallery() {
    if (gallery) document.querySelector('.home-new-main')?.scrollTo({ top: 0, behavior: 'smooth' })
    else go('/')
  }

  function toggleMenu() {
    const position = getMenuPosition()
    setMenu(!menuOpen && position ? { path: pathname, token, position } : null)
  }

  function openRecharge() {
    setMenu(null)
    if (token) setRecharge(true)
    else if (flow) openLoginModal()
    else goToLogin()
  }

  function handleLogout() {
    setMenu(null)
    Modal.confirm({
      className: 'home-confirm-modal',
      wrapClassName: 'create-flow-modal home-confirm-wrap',
      title: '确认退出登录',
      content: '退出后需要重新登录，是否继续？',
      okText: '确定',
      cancelText: '取消',
      centered: true,
      onOk: () => {
        useUserStore.getState().logout()
        router.push('/')
      }
    })
  }

  return (
    <>
      <HomeNewSidebar
        ref={sidebar}
        useRouterLinks
        galleryActive={gallery}
        worksActive={pathname.startsWith('/works')}
        assetsActive={pathname.startsWith('/assets')}
        inviteActive={pathname.startsWith('/invite')}
        onGallery={openGallery}
        onWorks={() => go('/works', true)}
        onAssets={() => go('/assets', true)}
        onInvite={() => go('/invite', true)}
        onRecharge={openRecharge}
        onLogin={flow ? () => openLoginModal() : goToLogin}
        onToggleUserMenu={toggleMenu}
      />
      {rechargeReady ? <RechargeModal open={recharge} onOpenChange={setRecharge} onPaid={() => {
        void useUserStore.getState().fetchProfile()
        message.success('充值已到账')
      }} /> : null}
      <UserMenuDropdown
        ref={dropdown}
        open={menuOpen}
        floatingStyle={menu?.position}
        onMySubmissions={() => go('/works?tab=submission', true)}
        onFaq={() => go('/faq')}
        onBilling={() => go('/billing')}
        onRecharge={openRecharge}
        onCreditConsume={() => { setMenu(null); openCreditConsume() }}
        onNotifications={() => { setMenu(null); notifications.client.open() }}
        onAccountSettings={() => { setMenu(null); openAccountSettings() }}
        onLogout={handleLogout}
      />
    </>
  )
}
