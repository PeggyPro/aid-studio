'use client'

import { useCallback } from 'react'
import { openLoginModal } from '@/stores/loginModal'
import { HOME_GUEST_LOGIN_REDIRECT } from '~/utils/homeGuestAccess'

/** 首页游客入口：直接弹出登录窗，登录后默认留在首页。 */
export function useHomeGuestAccess() {
  const goToLogin = useCallback(() => {
    openLoginModal({ redirect: HOME_GUEST_LOGIN_REDIRECT })
  }, [])

  const requireLogin = useCallback((_actionName?: string) => {
    openLoginModal({ redirect: HOME_GUEST_LOGIN_REDIRECT })
  }, [])

  return { goToLogin, requireLogin }
}
