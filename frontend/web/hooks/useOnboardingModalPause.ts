import { create } from 'zustand'

/** 业务弹层打开时暂停引导引擎（充值、用户菜单等） */
const usePauseStore = create<{ reasons: string[] }>(() => ({ reasons: [] }))

function setPaused(reason: string, paused: boolean) {
  const { reasons } = usePauseStore.getState()
  const has = reasons.includes(reason)
  if (paused && !has) {
    usePauseStore.setState({ reasons: [...reasons, reason] })
  } else if (!paused && has) {
    usePauseStore.setState({ reasons: reasons.filter((r) => r !== reason) })
  }
}

export function useOnboardingModalPause() {
  const isPausedByModal = usePauseStore((s) => s.reasons.length > 0)
  return { isPausedByModal, setPaused }
}

/** 非组件上下文读取（引导引擎轮询用） */
export function isOnboardingPausedByModal(): boolean {
  return usePauseStore.getState().reasons.length > 0
}
