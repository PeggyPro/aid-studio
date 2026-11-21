'use client'

import { useEffect } from 'react'

/** 首页输入区的跟手描边；媒体卡片不再逐帧计算倾斜与雾面光晕。 */
export function useHomePointerChrome() {
  useEffect(() => {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return undefined

    function onMove(event: PointerEvent) {
      const target = event.target instanceof Element ? event.target : null
      const spot = target?.closest('.home-spot')
      if (spot instanceof HTMLElement) {
        const rect = spot.getBoundingClientRect()
        spot.style.setProperty('--x', `${event.clientX - rect.left}px`)
        spot.style.setProperty('--y', `${event.clientY - rect.top}px`)
      }
    }

    document.addEventListener('pointermove', onMove)
    return () => {
      document.removeEventListener('pointermove', onMove)
    }
  }, [])
}
