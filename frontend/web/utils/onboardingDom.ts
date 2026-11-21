/** 将 data-onboarding 锚点转为选择器 */
export function onboardingSelector(anchor: string): string {
  return `[data-onboarding="${anchor}"]`
}

/** 等待 DOM 元素出现 */
export function waitForElement(
  selector: string,
  timeoutMs = 5000
): Promise<Element | null> {
  if (!(typeof window !== 'undefined')) return Promise.resolve(null)

  const existing = document.querySelector(selector)
  if (existing) return Promise.resolve(existing)

  return new Promise((resolve) => {
    const started = Date.now()
    const timer = window.setInterval(() => {
      const el = document.querySelector(selector)
      if (el) {
        window.clearInterval(timer)
        resolve(el)
        return
      }
      if (Date.now() - started >= timeoutMs) {
        window.clearInterval(timer)
        resolve(null)
      }
    }, 120)
  })
}

export function isElementVisible(el: Element | null): boolean {
  if (!el || !(el instanceof HTMLElement)) return false
  const rect = el.getBoundingClientRect()
  if (rect.width <= 0 && rect.height <= 0) return false
  const style = window.getComputedStyle(el)
  return style.visibility !== 'hidden' && style.display !== 'none' && style.opacity !== '0'
}

/** Spotlight 高亮前通知页面展开菜单等 */
export const ONBOARDING_PREPARE_ANCHOR_EVENT = 'onboarding-prepare-anchor'

export function dispatchOnboardingPrepareAnchor(anchor: string) {
  if (!(typeof window !== 'undefined')) return
  window.dispatchEvent(
    new CustomEvent(ONBOARDING_PREPARE_ANCHOR_EVENT, { detail: { anchor } })
  )
}

export function sleepMs(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}
