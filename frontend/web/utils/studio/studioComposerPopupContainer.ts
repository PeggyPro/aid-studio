/** 下方能完整容纳时向下，否则优先上方；两侧均不足时使用空间较大的一侧。 */
export type StudioComposerPopupPlacement = 'topLeft' | 'bottomLeft'
export const STUDIO_COMPOSER_POPUP_GAP = 8
export const STUDIO_COMPOSER_POPUP_MARGIN = 8

export function resolveStudioComposerPopupPlacement(
  anchor: { top: number; height: number },
  viewport: { top: number; height: number },
  popupHeight: number
): StudioComposerPopupPlacement {
  const { above, below } = studioComposerPopupSpace(anchor, viewport)
  if (popupHeight <= below) return 'bottomLeft'
  return popupHeight <= above || above >= below ? 'topLeft' : 'bottomLeft'
}

export function studioComposerPopupSpace(
  anchor: { top: number; height: number },
  viewport: { top: number; height: number }
) {
  return {
    above: Math.max(0, anchor.top - viewport.top - STUDIO_COMPOSER_POPUP_GAP - STUDIO_COMPOSER_POPUP_MARGIN),
    below: Math.max(0, viewport.top + viewport.height - anchor.top - anchor.height - STUDIO_COMPOSER_POPUP_GAP - STUDIO_COMPOSER_POPUP_MARGIN)
  }
}

/** 画布可能比浏览器可见区高；软键盘/浏览器缩放后取二者交集。 */
export function readStudioComposerPopupViewport(trigger: HTMLElement): { top: number; height: number } {
  const win = trigger.ownerDocument.defaultView!
  const visual = win.visualViewport
  const top = visual?.offsetTop ?? 0
  const bottom = top + (visual?.height ?? win.innerHeight)
  const canvas = trigger.closest<HTMLElement>('.studio-canvas')?.getBoundingClientRect()
  const visibleTop = Math.max(top, canvas?.top ?? top)
  return { top: visibleTop, height: Math.max(0, Math.min(bottom, canvas?.bottom ?? bottom) - visibleTop) }
}

export function readStudioComposerPopupPlacement(trigger: HTMLElement, popupHeight: number): StudioComposerPopupPlacement {
  return resolveStudioComposerPopupPlacement(trigger.getBoundingClientRect(), readStudioComposerPopupViewport(trigger), popupHeight)
}

export function getStudioComposerPopupContainer(trigger: HTMLElement): HTMLElement {
  const dock = trigger.closest<HTMLElement>('.studio-node-agent-dock')
  if (dock?.dataset.placement === 'center') {
    const host = dock.parentElement?.querySelector<HTMLElement>('[data-studio-node-composer-overlays]')
    if (host) return host
  }
  // Popovers must not be mounted inside the agent dock: the dock observes its own
  // size and repositions after every resize. A portaled popover inside that tree
  // therefore creates a measure -> move -> realign feedback loop and is also
  // clipped by the dock's scrolling boundary. The canvas is the nearest stable
  // positioned overlay host shared by every composer.
  return trigger.closest<HTMLElement>('[data-studio-free-theme-scope], .studio-canvas')
    // 首页输入框有装饰隔离层，浮层应挂在同主题的页面/浮动输入壳上，避开卡片层叠与滚动裁切。
    ?? trigger.closest<HTMLElement>('[data-home-theme]')
    ?? document.body
}
