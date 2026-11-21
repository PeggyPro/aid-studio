'use client'

import { useLayoutEffect, useRef, type RefObject } from 'react'
import { placeStudioContextMenu } from '@/utils/studio/studioContextMenuPlacement'

/** Measure only the visible surface; unopened submenus never reserve canvas space. */
export function useStudioContextMenuPlacement({
  boundsRef,
  client,
  anchorRef,
  enabled = true
}: {
  boundsRef: RefObject<HTMLElement | null>
  client?: { x: number; y: number }
  anchorRef?: RefObject<HTMLElement | null>
  enabled?: boolean
}) {
  const menuRef = useRef<HTMLDivElement>(null)
  const clientX = client?.x
  const clientY = client?.y

  useLayoutEffect(() => {
    const menu = menuRef.current
    const canvas = boundsRef.current
    if (!enabled || !menu || !canvas) return

    const align = () => {
      const parent = menu.offsetParent
      if (!(parent instanceof HTMLElement)) return
      const viewport = window.visualViewport
      const canvasRect = canvas.getBoundingClientRect()
      const bounds = {
        left: Math.max(canvasRect.left, viewport?.offsetLeft ?? 0) + 8,
        top: Math.max(canvasRect.top, viewport?.offsetTop ?? 0) + 8,
        right: Math.min(canvasRect.right, (viewport?.offsetLeft ?? 0) + (viewport?.width ?? document.documentElement.clientWidth)) - 8,
        bottom: Math.min(canvasRect.bottom, (viewport?.offsetTop ?? 0) + (viewport?.height ?? document.documentElement.clientHeight)) - 8
      }
      const parentRect = parent.getBoundingClientRect()
      const scaleX = parent.offsetWidth ? parentRect.width / parent.offsetWidth : 1
      const scaleY = parent.offsetHeight ? parentRect.height / parent.offsetHeight : 1
      const maxHeight = `${Math.max(0, bounds.bottom - bounds.top) / scaleY}px`
      menu.style.maxWidth = `${Math.max(0, bounds.right - bounds.left) / scaleX}px`
      menu.style.maxHeight = maxHeight
      menu.style.setProperty('--studio-context-menu-max-height', maxHeight)
      const rect = menu.getBoundingClientRect()
      const anchor = anchorRef?.current?.getBoundingClientRect()
        ?? { left: clientX ?? 0, right: clientX ?? 0, top: clientY ?? 0, bottom: clientY ?? 0 }
      const position = placeStudioContextMenu({ bounds, anchor, size: rect, submenu: Boolean(anchorRef) })
      menu.style.left = `${(position.x - parentRect.left) / scaleX - parent.clientLeft + parent.scrollLeft}px`
      menu.style.top = `${(position.y - parentRect.top) / scaleY - parent.clientTop + parent.scrollTop}px`
      menu.dataset.side = position.side
    }

    align()
    const observer = new ResizeObserver(align)
    observer.observe(canvas)
    observer.observe(menu)
    if (anchorRef?.current) observer.observe(anchorRef.current)
    window.addEventListener('resize', align)
    window.addEventListener('scroll', align, true)
    window.visualViewport?.addEventListener('resize', align)
    window.visualViewport?.addEventListener('scroll', align)
    return () => {
      observer.disconnect()
      window.removeEventListener('resize', align)
      window.removeEventListener('scroll', align, true)
      window.visualViewport?.removeEventListener('resize', align)
      window.visualViewport?.removeEventListener('scroll', align)
    }
  }, [boundsRef, anchorRef, clientX, clientY, enabled])

  return menuRef
}
