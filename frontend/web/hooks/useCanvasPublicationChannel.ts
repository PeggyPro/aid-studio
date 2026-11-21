'use client'
import { useEffect, useState } from 'react'
import { canvasPublicationChannel } from '@/utils/canvas/canvasPublicationApi'

export function useCanvasPublicationChannel() {
  const [enabled, setEnabled] = useState<boolean | null>(null)
  useEffect(() => {
    let active = true,
      running = false
    async function refresh() {
      if (running || document.hidden) return
      running = true
      try {
        const value = await canvasPublicationChannel()
        if (active)
          setEnabled(
            value.items.some((item) => item.code === 'CANVAS' && item.enabled),
          )
      } catch {
        if (active) setEnabled(false)
      } finally {
        running = false
      }
    }
    void refresh()
    const timer = window.setInterval(() => void refresh(), 60000)
    window.addEventListener('focus', refresh)
    document.addEventListener('visibilitychange', refresh)
    return () => {
      active = false
      clearInterval(timer)
      window.removeEventListener('focus', refresh)
      document.removeEventListener('visibilitychange', refresh)
    }
  }, [])
  return enabled
}
