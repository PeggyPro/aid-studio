'use client'

import { usePathname } from 'next/navigation'
import { useEffect } from 'react'
import { create } from 'zustand'
import { useOnboardingModalPause } from '@/hooks/useOnboardingModalPause'
import { useUserStore } from '@/stores/user'
import { RechargeConsumeDrawer } from './RechargeConsumeDrawer'
import './recharge-modal.css'

const useConsumeRequest = create<{ token: string }>(() => ({ token: '' }))
const closeCreditConsume = () => useConsumeRequest.setState({ token: '' })

export function openCreditConsume() {
  const { token } = useUserStore.getState()
  if (token) useConsumeRequest.setState({ token })
}

/** 头像菜单关闭后，消耗明细由根壳接管。 */
export function CreditConsumeHost() {
  const pathname = usePathname()
  const token = useUserStore((s) => s.token)
  const requestToken = useConsumeRequest((s) => s.token)
  const open = Boolean(token && requestToken === token)
  const { setPaused } = useOnboardingModalPause()

  useEffect(() => closeCreditConsume(), [pathname, token])
  useEffect(() => {
    setPaused('credit-consume', open)
    return () => setPaused('credit-consume', false)
  }, [open, setPaused])

  return <RechargeConsumeDrawer key={token} open={open} onClose={closeCreditConsume} />
}
