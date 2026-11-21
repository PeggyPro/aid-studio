import { create } from 'zustand'

/**
 * 首页首屏（Hero Banner 预加载）就绪信号。
 * 供侧栏邀请卡等「等首页内容出来后再出现」的浮层共用。
 */
const useHeroReadyStore = create<{ ready: boolean }>(() => ({ ready: false }))

export function markHomeHeroContentReady(ready: boolean) {
  useHeroReadyStore.setState({ ready })
}

export function useHomeHeroContentReady() {
  const homeHeroContentReady = useHeroReadyStore((s) => s.ready)
  return { homeHeroContentReady, markHomeHeroContentReady }
}
