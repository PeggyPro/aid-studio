'use client'

import { useLayoutEffect, useRef, useState, type RefObject } from 'react'
import type { TooltipRef } from 'antd/es/tooltip'
import { registerStudioComposerPopupAlign } from '@/utils/studio/studioComposerPopupAlign'
import {
  readStudioComposerPopupViewport,
  resolveStudioComposerPopupPlacement,
  studioComposerPopupSpace,
  type StudioComposerPopupPlacement
} from '@/utils/studio/studioComposerPopupContainer'

/** Measure the rendered panel, not the node or a guessed menu height. */
export function useStudioComposerPopupLayout(
  popoverRef: RefObject<TooltipRef | null>,
  mountedContent: HTMLElement | null,
  enabled: boolean
) {
  const [side, setSide] = useState<StudioComposerPopupPlacement>('bottomLeft')
  const sideRef = useRef(side)

  useLayoutEffect(() => {
    if (!enabled || !mountedContent) return
    const trigger = popoverRef.current?.nativeElement
    const popup = popoverRef.current?.popupElement ?? mountedContent.closest<HTMLElement>('.ant-popover')
    if (!trigger || !popup) return
    const content = popup.querySelector<HTMLElement>('.studio-composer-popup-fit')
    if (!content) return
    const win = trigger.ownerDocument.defaultView!
    let frame = 0
    const update = () => {
      const viewport = readStudioComposerPopupViewport(trigger)
      const anchor = trigger.getBoundingClientRect()
      // 内容层在展开时会缩放；用布局高度和定位壳的画布缩放比测量，
      // 不能把视觉缩放期间变矮的内容误认为更厚的浮层边框。
      const popupHeight = popup.getBoundingClientRect().height
      const viewportScale = popup.offsetHeight > 0 ? popupHeight / popup.offsetHeight : 1
      const chrome = Math.max(0, popupHeight - content.offsetHeight * viewportScale)
      // scrollHeight retains the unconstrained height after a previous small viewport.
      const height = content.scrollHeight * viewportScale + chrome
      const next = resolveStudioComposerPopupPlacement(anchor, viewport, height)
      const space = studioComposerPopupSpace(anchor, viewport)
      const maxHeight = Math.max(1, (next === 'topLeft' ? space.above : space.below) - chrome)
      content.style.maxHeight = `${maxHeight / (viewportScale || 1)}px`
      if (next !== sideRef.current) {
        sideRef.current = next
        setSide(next)
      } else {
        popoverRef.current?.forceAlign()
      }
    }
    const schedule = () => {
      if (frame) return
      frame = win.requestAnimationFrame(() => { frame = 0; update() })
    }
    const resize = new ResizeObserver(schedule)
    resize.observe(trigger)
    resize.observe(content)
    resize.observe(popup)
    const canvas = trigger.closest('.studio-canvas')
    if (canvas) resize.observe(canvas)
    // Async model lists can grow inside a capped panel without resizing its box.
    const mutation = new MutationObserver(schedule)
    mutation.observe(content, { childList: true, subtree: true, characterData: true })
    const unregister = registerStudioComposerPopupAlign(schedule)
    win.addEventListener('resize', schedule)
    win.addEventListener('scroll', schedule, true)
    win.visualViewport?.addEventListener('resize', schedule)
    win.visualViewport?.addEventListener('scroll', schedule)
    update()
    return () => {
      win.cancelAnimationFrame(frame)
      resize.disconnect()
      mutation.disconnect()
      unregister()
      win.removeEventListener('resize', schedule)
      win.removeEventListener('scroll', schedule, true)
      win.visualViewport?.removeEventListener('resize', schedule)
      win.visualViewport?.removeEventListener('scroll', schedule)
      content.style.maxHeight = ''
    }
  }, [mountedContent, enabled, popoverRef])

  useLayoutEffect(() => {
    if (enabled) popoverRef.current?.forceAlign()
  }, [enabled, side, popoverRef])

  return side
}
